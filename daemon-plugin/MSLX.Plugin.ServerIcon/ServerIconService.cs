using System.Collections.Concurrent;
using System.Text.Json;
using MSLX.SDK.Models;

namespace MSLX.Plugin.ServerIcon;

/// <summary>
/// 服务端图标核心服务：给出实例图标（PNG 字节），来源优先级：
/// 1. 实例目录下的 <c>server-icon.png</c>（Minecraft 标准服务端图标，零网络成本）；
/// 2. 插件磁盘缓存（TTL 24 小时，避免反复打第三方 API）；
/// 3. 第三方状态 API（mcsrvstat.us → mcstatus.io 回退），仅对 server.properties
///    中显式配置且为公网地址的 server-ip 发起；
/// 无可用来源时返回 404 + 可执行提示（App 侧显示占位图）。
///
/// 单实例并发合并：同一实例的并发请求共享一次拉取（SemaphoreSlim 串行化），
/// 避免面板与 App 同时刷新时对第三方 API 发起重复请求。
/// </summary>
public sealed class ServerIconService
{
    public const string PluginId = "mslx-icon";

    private const string IconFileName = "server-icon.png";
    private const string DefaultPropertiesFile = "server.properties";
    private const int DefaultServerPort = 25565;

    private static readonly TimeSpan CacheTtl = TimeSpan.FromHours(24);
    private static readonly TimeSpan HttpTimeout = TimeSpan.FromSeconds(10);

    private readonly ConcurrentDictionary<uint, SemaphoreSlim> _instanceLocks = new();

    private static SDK.Interfaces.IMSLXLogger Log => global::MSLX.SDK.MSLX.Logger;
    private static string CacheDir =>
        Path.Combine(global::MSLX.SDK.MSLX.Config.GetPluginConfig(PluginId).GetDataPath(), "IconCache");

    public sealed record IconResult(int Code, string Message, byte[]? Bytes, string ContentType);

    public async Task<IconResult> GetIconAsync(uint instanceId)
    {
        var server = global::MSLX.SDK.MSLX.Config.Servers.GetServer(instanceId);
        if (server == null) return new IconResult(404, "实例不存在", null, string.Empty);

        // 1) 实例目录下的 server-icon.png
        var localBytes = TryReadFile(Path.Combine(server.Base, IconFileName));
        if (localBytes != null) return new IconResult(200, "ok", localBytes, "image/png");

        var gate = _instanceLocks.GetOrAdd(instanceId, _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            // 2) 磁盘缓存（并发等待期间可能已被其它请求写入）
            var cachePath = Path.Combine(CacheDir, $"{instanceId}.png");
            var cached = TryReadFreshCache(cachePath);
            if (cached != null) return new IconResult(200, "ok", cached, "image/png");

            // 3) 解析 server.properties 中的公网地址
            var address = ResolvePublicAddress(server);
            if (address == null)
            {
                return new IconResult(
                    404,
                    "该实例没有可查询的公网地址：需在 server.properties 中把 server-ip 配置为公网 IP/域名",
                    null,
                    string.Empty);
            }

            // 4) 第三方状态 API（mcsrvstat.us → mcstatus.io）
            var icon = await FetchFromThirdPartyAsync(address).ConfigureAwait(false);
            if (icon == null)
            {
                return new IconResult(404, $"第三方状态 API 未返回图标（{address}）", null, string.Empty);
            }

            TryWriteCache(cachePath, icon);
            Log.Info($"[ServerIcon] 已为实例 {instanceId} 拉取图标（{address}, {icon.Length} 字节）");
            return new IconResult(200, "ok", icon, "image/png");
        }
        finally
        {
            gate.Release();
        }
    }

    /// <summary>失效某实例的磁盘缓存（供外部在图标变更后主动刷新）。</summary>
    public void InvalidateCache(uint instanceId)
    {
        try
        {
            var cachePath = Path.Combine(CacheDir, $"{instanceId}.png");
            if (File.Exists(cachePath)) File.Delete(cachePath);
        }
        catch (Exception ex)
        {
            Log.Warn($"[ServerIcon] 清理缓存失败（{instanceId}）：{ex.Message}");
        }
    }

    // ---------------- 地址解析 ----------------

    private static string? ResolvePublicAddress(McServerInfo.ServerInfo server)
    {
        var relative = string.IsNullOrWhiteSpace(server.ServerPropertiesPath)
            ? DefaultPropertiesFile
            : server.ServerPropertiesPath;
        var path = Path.IsPathRooted(relative) ? relative : Path.Combine(server.Base, relative);
        if (!File.Exists(path)) return null;

        string? host = null;
        var port = DefaultServerPort;
        foreach (var raw in File.ReadLines(path))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line.StartsWith('#')) continue;
            var split = line.IndexOf('=');
            if (split <= 0) continue;
            var key = line[..split].Trim();
            var value = line[(split + 1)..].Trim();
            if (key == "server-ip") host = value;
            else if (key == "server-port" && int.TryParse(value, out var parsed) && parsed is > 0 and <= 65535) port = parsed;
        }

        if (string.IsNullOrWhiteSpace(host) || !IsPublicHost(host)) return null;
        return port == DefaultServerPort ? host : $"{host}:{port}";
    }

    /// <summary>仅公网地址允许外发查询：排除私网/回环/链路本地/组播 IPv4 与内网域名。</summary>
    private static bool IsPublicHost(string host)
    {
        if (host.Contains(':'))
        {
            // IPv6：只放行非本地回环链路地址的全局地址
            if (host.Equals("::1", StringComparison.OrdinalIgnoreCase)) return false;
            if (host.StartsWith("fe80", StringComparison.OrdinalIgnoreCase)) return false;
            if (host.StartsWith("fc", StringComparison.OrdinalIgnoreCase) ||
                host.StartsWith("fd", StringComparison.OrdinalIgnoreCase)) return false; // ULA
            return true;
        }

        if (host.Any(char.IsLetter))
        {
            var lower = host.ToLowerInvariant();
            if (lower == "localhost" || lower.EndsWith(".localhost")) return false;
            foreach (var suffix in new[] { ".local", ".lan", ".home", ".internal", ".intranet", ".localdomain" })
            {
                if (lower.EndsWith(suffix)) return false;
            }
            return true;
        }

        if (!System.Net.IPAddress.TryParse(host, out var ip)) return false;
        var bytes = ip.GetAddressBytes();
        if (bytes.Length != 4) return true;
        return !(
            bytes[0] == 10 ||
            bytes[0] == 127 ||
            (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) ||
            (bytes[0] == 192 && bytes[1] == 168) ||
            (bytes[0] == 169 && bytes[1] == 254) ||
            bytes[0] == 0 ||
            bytes[0] >= 224
        );
    }

    // ---------------- 第三方 API ----------------

    private async Task<byte[]?> FetchFromThirdPartyAsync(string address)
    {
        var fromMcsrvstat = await TryQueryAsync(
                $"https://api.mcsrvstat.us/3/{Uri.EscapeDataString(address)}", "icon")
            .ConfigureAwait(false);
        if (fromMcsrvstat != null) return fromMcsrvstat;

        return await TryQueryAsync(
                $"https://api.mcstatus.io/v2/status/java/{Uri.EscapeDataString(address)}", "icon")
            .ConfigureAwait(false);
    }

    private static async Task<byte[]?> TryQueryAsync(string url, string iconProperty)
    {
        try
        {
            var response = await global::MSLX.SDK.MSLX.Http.GetAsync(
                url,
                null,
                new Dictionary<string, string> { ["User-Agent"] = "MSLX-Daemon/ServerIcon-Plugin" },
                HttpTimeout).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode || string.IsNullOrWhiteSpace(response.Content)) return null;

            using var document = JsonDocument.Parse(response.Content);
            var root = document.RootElement;
            if (!root.TryGetProperty("online", out var online) || online.ValueKind != JsonValueKind.True) return null;
            if (!root.TryGetProperty(iconProperty, out var iconElement) || iconElement.ValueKind != JsonValueKind.String) return null;

            return DecodeDataUri(iconElement.GetString());
        }
        catch (Exception ex)
        {
            Log.Warn($"[ServerIcon] 第三方查询失败：{url}（{ex.Message}）");
            return null;
        }
    }

    /// <summary>把 <c>data:image/png;base64,xxxx</c> 形式的图标解码为 PNG 字节。</summary>
    private static byte[]? DecodeDataUri(string? dataUri)
    {
        if (string.IsNullOrWhiteSpace(dataUri)) return null;
        var marker = dataUri.IndexOf("base64,", StringComparison.OrdinalIgnoreCase);
        if (marker < 0) return null;
        try
        {
            return Convert.FromBase64String(dataUri[(marker + "base64,".Length)..]);
        }
        catch
        {
            return null;
        }
    }

    // ---------------- 缓存读写 ----------------

    private static byte[]? TryReadFile(string path)
    {
        try
        {
            return File.Exists(path) ? File.ReadAllBytes(path) : null;
        }
        catch (Exception ex)
        {
            Log.Warn($"[ServerIcon] 读取图标文件失败：{path}（{ex.Message}）");
            return null;
        }
    }

    private static byte[]? TryReadFreshCache(string cachePath)
    {
        try
        {
            if (!File.Exists(cachePath)) return null;
            if (DateTime.UtcNow - File.GetLastWriteTimeUtc(cachePath) > CacheTtl) return null;
            return File.ReadAllBytes(cachePath);
        }
        catch
        {
            return null;
        }
    }

    private static void TryWriteCache(string cachePath, byte[] bytes)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(cachePath)!);
            File.WriteAllBytes(cachePath, bytes);
        }
        catch (Exception ex)
        {
            Log.Warn($"[ServerIcon] 写入图标缓存失败：{cachePath}（{ex.Message}）");
        }
    }
}
