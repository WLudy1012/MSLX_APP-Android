using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using MSLX.SDK.Models;
using Newtonsoft.Json.Linq;

namespace MSLX.Plugin.Pairing;

/// <summary>
/// 扫码配对核心服务：
/// - 配对码：一次性、TTL 2 分钟、仅内存保存，HMAC-SHA256 签名防篡改；
/// - 兑换：按 IP 失败计数 + 临时锁定，成功即消费配对码；
/// - 设备：兑换后创建**独立受限用户**（随设备记录可撤销、可过期），API Key 只存前缀；
/// - 审计：所有关键操作写 Daemon 日志（密钥绝不落日志）。
/// </summary>
public sealed class PairingService
{
    public const string PluginId = "mslx-pair";
    public const string PayloadPrefix = "mslxp1:";

    private const int CodeTtlSeconds = 120;
    private const int MaxCodePerMinutePerUser = 10;
    private const int MaxRedeemAttemptsPerMinutePerIp = 10;
    private const int MaxRedeemFailures = 5;
    private static readonly TimeSpan FailureWindow = TimeSpan.FromMinutes(10);
    private static readonly TimeSpan LockDuration = TimeSpan.FromMinutes(15);
    private const int DefaultDeviceTtlDays = 30;
    private const int MaxDeviceTtlDays = 365;
    private const string CrockfordAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private sealed class PairingCodeEntry
    {
        public string Url = string.Empty;
        public long ExpiresAt;
        public string Scope = "full";
        public List<string> Resources = new();
        public int DeviceTtlDays = DefaultDeviceTtlDays;
        public string CreatedBy = string.Empty;
    }

    private sealed class IpState
    {
        public int Failures;
        public DateTime FailureWindowStart = DateTime.UtcNow;
        public DateTime? LockedUntil;
        public readonly Queue<DateTime> Attempts = new();
    }

    private readonly ConcurrentDictionary<string, PairingCodeEntry> _codes = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, IpState> _ipStates = new();
    private readonly ConcurrentDictionary<string, Queue<DateTime>> _codeIssued = new();
    private readonly object _storeLock = new();

    private string _installSecret = string.Empty;
    private Timer? _sweeper;

    private static SDK.Interfaces.IPluginConfigBridge Config =>
        global::MSLX.SDK.MSLX.Config.GetPluginConfig(PluginId);

    private static SDK.Interfaces.IMSLXLogger Log => global::MSLX.SDK.MSLX.Logger;

    /// <summary>插件加载后调用：准备安装密钥并启动过期清理定时器。</summary>
    public void Initialize()
    {
        lock (_storeLock)
        {
            var existing = Config.ReadConfigKey("installSecret")?.ToString();
            if (string.IsNullOrWhiteSpace(existing))
            {
                _installSecret = Base64Url(RandomNumberGenerator.GetBytes(32));
                Config.WriteConfigKey("installSecret", _installSecret);
                Log.Info($"[Pairing] 已生成本插件安装密钥（用于配对载荷签名）");
            }
            else
            {
                _installSecret = existing;
            }
        }
        _sweeper = new Timer(_ => SafeSweep(), null, TimeSpan.FromMinutes(5), TimeSpan.FromMinutes(10));
        Log.Info("[Pairing] 扫码配对插件已就绪");
    }

    public void Shutdown()
    {
        _sweeper?.Dispose();
        _sweeper = null;
    }

    // ---------------- 配对码 ----------------

    /// <summary>生成一次性配对码并返回二维码载荷。scope=full 创建 admin 级配对用户；limited 创建受限 user。</summary>
    public (int Code, string Message, object? Data) CreateCode(
        CreateCodeRequest request,
        string createdBy,
        string requestBaseUrl)
    {
        if (!CheckAndTrackCodeIssue(createdBy))
        {
            return (429, "生成过于频繁，请稍后再试", null);
        }

        var url = NormalizeUrl(request.PublicUrl, requestBaseUrl);
        if (string.IsNullOrWhiteSpace(url))
        {
            return (400, "无法确定 Daemon 对外地址，请显式传入 publicUrl", null);
        }

        var scope = string.Equals(request.Scope, "limited", StringComparison.OrdinalIgnoreCase)
            ? "limited"
            : "full";
        var ttlDays = Math.Clamp(request.DeviceTtlDays ?? DefaultDeviceTtlDays, 1, MaxDeviceTtlDays);
        var expiresAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + CodeTtlSeconds;
        var code = GenerateCode();

        _codes[code] = new PairingCodeEntry
        {
            Url = url,
            ExpiresAt = expiresAt,
            Scope = scope,
            Resources = scope == "limited" ? (request.Resources ?? new List<string>()) : new List<string>(),
            DeviceTtlDays = ttlDays,
            CreatedBy = createdBy,
        };

        var payload = new PairingPayload
        {
            Url = url,
            Code = code,
            ExpiresAt = expiresAt,
        };
        payload.Signature = Sign(payload.Url, payload.Code, payload.ExpiresAt);
        var text = PayloadPrefix + Base64Url(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(payload, JsonOptions)));

        Log.Info($"[Pairing] 已生成配对码（scope={scope}, 有效期 {CodeTtlSeconds}s, 操作者 {createdBy}）");
        return (200, "配对码已生成", new
        {
            code,
            expiresAt = Iso(expiresAt),
            expiresInSeconds = CodeTtlSeconds,
            scope,
            deviceTtlDays = ttlDays,
            payload = text,
            url,
        });
    }

    // ---------------- 兑换 ----------------

    /// <summary>兑换配对码：校验签名/时效/一次性，成功后创建受限用户并返回 API Key。</summary>
    public (int Code, string Message, object? Data) Redeem(RedeemRequest request, string clientIp)
    {
        if (!CheckRedeemRate(clientIp, out var retrySeconds))
        {
            return (429, $"尝试过于频繁或已临时锁定，请 {Math.Max(1, retrySeconds / 60)} 分钟后再试", null);
        }

        var deviceName = Sanitize(request.DeviceName, 64, "未命名设备");
        var fingerprint = Sanitize(request.DeviceFingerprint, 128, string.Empty);

        // 1) 解析载荷
        if (!TryDecodePayload(request.Payload, out var payload, out var parseError))
        {
            RegisterFailure(clientIp);
            Log.Warn($"[Pairing] 兑换失败（{clientIp}）：{parseError}");
            return (400, parseError, null);
        }

        // 2) 验签（常量时间比较）
        var expected = Sign(payload.Url, payload.Code, payload.ExpiresAt);
        if (!FixedTimeEquals(expected, payload.Signature))
        {
            RegisterFailure(clientIp);
            Log.Warn($"[Pairing] 兑换失败（{clientIp}）：载荷签名不合法");
            return (400, "配对载荷签名不合法", null);
        }

        // 3) 时效（以服务端时间为准）
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (payload.ExpiresAt < now - 5)
        {
            RegisterFailure(clientIp);
            return (410, "配对码已过期，请在服务端重新生成", null);
        }

        // 4) 消费一次性配对码（TryRemove 原子消费，杜绝重放）
        if (!_codes.TryRemove(payload.Code, out var entry))
        {
            RegisterFailure(clientIp);
            Log.Warn($"[Pairing] 兑换失败（{clientIp}）：配对码不存在或已被使用");
            return (410, "配对码不存在或已被使用", null);
        }
        if (entry.ExpiresAt < now - 5)
        {
            RegisterFailure(clientIp);
            return (410, "配对码已过期，请在服务端重新生成", null);
        }
        if (!string.Equals(entry.Url, payload.Url, StringComparison.OrdinalIgnoreCase))
        {
            RegisterFailure(clientIp);
            Log.Warn($"[Pairing] 兑换失败（{clientIp}）：地址与签发记录不一致");
            return (400, "配对载荷与签发记录不一致", null);
        }

        // 5) 供给受限用户
        try
        {
            var deviceId = "d" + RandomString(10);
            var username = $"pair_{deviceId}";
            var apiKey = Base64Url(RandomNumberGenerator.GetBytes(24));
            var role = entry.Scope == "full" ? "admin" : "user";
            var expiresAt = now + (long)entry.DeviceTtlDays * 86400;

            var user = new UserInfo
            {
                Username = username,
                Name = $"配对设备 {deviceName}",
                PasswordHash = Base64Url(RandomNumberGenerator.GetBytes(32)),
                Role = role,
                ApiKey = apiKey,
                Resources = entry.Resources,
                Avatar = string.Empty,
            };

            ReplaceSameFingerprint(fingerprint, clientIp);

            if (!global::MSLX.SDK.MSLX.Config.Users.CreateUser(user))
            {
                Log.Error($"[Pairing] 创建配对用户失败：{username}");
                return (500, "服务端创建配对用户失败", null);
            }

            var record = new DeviceRecord
            {
                DeviceId = deviceId,
                Name = deviceName,
                Fingerprint = fingerprint,
                UserId = user.Id,
                Username = username,
                Role = role,
                Resources = entry.Resources,
                ApiKeyPrefix = apiKey.Length > 8 ? apiKey[..8] : apiKey,
                CreatedAt = now,
                ExpiresAt = expiresAt,
                CreatedIp = clientIp,
            };
            UpsertDevice(record);

            ClearFailures(clientIp);
            Log.Info($"[Pairing] 设备配对成功：{deviceName}（{deviceId}, role={role}, 过期 {Iso(expiresAt)}）");

            return (200, "配对成功", new
            {
                daemonUrl = entry.Url,
                apiKey,
                deviceId,
                userId = user.Id,
                role,
                resources = entry.Resources,
                expiresAt = Iso(expiresAt),
            });
        }
        catch (Exception ex)
        {
            Log.Error($"[Pairing] 兑换处理异常：{ex.Message}", ex);
            return (500, "服务端处理异常", null);
        }
    }

    // ---------------- 设备管理（管理员） ----------------

    public (int Code, string Message, object? Data) ListDevices()
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var devices = ReadDevices()
            .Select(d => new
            {
                d.DeviceId,
                d.Name,
                fingerprintPrefix = d.Fingerprint.Length > 12 ? d.Fingerprint[..12] + "…" : d.Fingerprint,
                d.Role,
                d.Resources,
                apiKeyPrefix = d.ApiKeyPrefix + "…",
                createdAt = Iso(d.CreatedAt),
                expiresAt = Iso(d.ExpiresAt),
                status = d.Revoked ? (d.RevokeReason ?? "revoked") : (d.ExpiresAt < now ? "expired" : "active"),
            })
            .OrderByDescending(d => d.createdAt)
            .ToList();
        return (200, "ok", devices);
    }

    /// <summary>撤销设备：删除对应配对用户（其 API Key 即时失效）并记录撤销原因。</summary>
    public (int Code, string Message, object? Data) Revoke(string deviceId)
    {
        var devices = ReadDevices();
        var target = devices.FirstOrDefault(d => d.DeviceId == deviceId);
        if (target == null)
        {
            return (404, "未找到该设备记录", null);
        }
        if (!target.Revoked)
        {
            global::MSLX.SDK.MSLX.Config.Users.DeleteUser(target.UserId);
            target.Revoked = true;
            target.RevokeReason = "revoked";
            WriteDevices(devices);
            Log.Info($"[Pairing] 已撤销配对设备：{target.Name}（{target.DeviceId}）");
        }
        return (200, "已撤销", null);
    }

    // ---------------- 内部 ----------------

    private void ReplaceSameFingerprint(string fingerprint, string clientIp)
    {
        if (string.IsNullOrWhiteSpace(fingerprint)) return;
        var devices = ReadDevices();
        var changed = false;
        foreach (var d in devices.Where(d => !d.Revoked && d.Fingerprint == fingerprint))
        {
            global::MSLX.SDK.MSLX.Config.Users.DeleteUser(d.UserId);
            d.Revoked = true;
            d.RevokeReason = "replaced";
            changed = true;
            Log.Info($"[Pairing] 同指纹设备重新配对，旧记录已撤销：{d.DeviceId}");
        }
        if (changed) WriteDevices(devices);
    }

    private void SafeSweep()
    {
        try
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            foreach (var key in _codes.Where(kv => kv.Value.ExpiresAt < now - 60).Select(kv => kv.Key).ToList())
            {
                _codes.TryRemove(key, out _);
            }

            var devices = ReadDevices();
            var changed = false;
            foreach (var d in devices.Where(d => !d.Revoked && d.ExpiresAt < now))
            {
                global::MSLX.SDK.MSLX.Config.Users.DeleteUser(d.UserId);
                d.Revoked = true;
                d.RevokeReason = "expired";
                changed = true;
                Log.Info($"[Pairing] 配对设备已过期并移除：{d.DeviceId}");
            }
            if (changed) WriteDevices(devices);

            var stale = DateTime.UtcNow - FailureWindow - LockDuration;
            foreach (var key in _ipStates.Where(kv => kv.Value.LockedUntil == null && kv.Value.FailureWindowStart < stale)
                         .Select(kv => kv.Key).ToList())
            {
                _ipStates.TryRemove(key, out _);
            }
        }
        catch (Exception ex)
        {
            Log.Error($"[Pairing] 过期清理异常：{ex.Message}", ex);
        }
    }

    private bool CheckAndTrackCodeIssue(string createdBy)
    {
        var now = DateTime.UtcNow;
        var queue = _codeIssued.GetOrAdd(createdBy, _ => new Queue<DateTime>());
        lock (queue)
        {
            while (queue.Count > 0 && (now - queue.Peek()) > TimeSpan.FromMinutes(1)) queue.Dequeue();
            if (queue.Count >= MaxCodePerMinutePerUser) return false;
            queue.Enqueue(now);
            return true;
        }
    }

    private bool CheckRedeemRate(string clientIp, out int retrySeconds)
    {
        retrySeconds = 0;
        var state = _ipStates.GetOrAdd(clientIp, _ => new IpState());
        lock (state)
        {
            if (state.LockedUntil.HasValue)
            {
                if (state.LockedUntil.Value > DateTime.UtcNow)
                {
                    retrySeconds = (int)(state.LockedUntil.Value - DateTime.UtcNow).TotalSeconds;
                    return false;
                }
                state.LockedUntil = null;
                state.Failures = 0;
            }
            var now = DateTime.UtcNow;
            while (state.Attempts.Count > 0 && (now - state.Attempts.Peek()) > TimeSpan.FromMinutes(1)) state.Attempts.Dequeue();
            if (state.Attempts.Count >= MaxRedeemAttemptsPerMinutePerIp)
            {
                retrySeconds = 60;
                return false;
            }
            state.Attempts.Enqueue(now);
            return true;
        }
    }

    private void RegisterFailure(string clientIp)
    {
        var state = _ipStates.GetOrAdd(clientIp, _ => new IpState());
        lock (state)
        {
            var now = DateTime.UtcNow;
            if ((now - state.FailureWindowStart) > FailureWindow)
            {
                state.FailureWindowStart = now;
                state.Failures = 0;
            }
            state.Failures++;
            if (state.Failures >= MaxRedeemFailures)
            {
                state.LockedUntil = now + LockDuration;
                state.Failures = 0;
                Log.Warn($"[Pairing] IP {clientIp} 连续失败达到上限，已临时锁定 {LockDuration.TotalMinutes} 分钟");
            }
        }
    }

    private void ClearFailures(string clientIp)
    {
        if (_ipStates.TryGetValue(clientIp, out var state))
        {
            lock (state)
            {
                state.Failures = 0;
                state.LockedUntil = null;
            }
        }
    }

    private List<DeviceRecord> ReadDevices()
    {
        lock (_storeLock)
        {
            var token = Config.ReadConfigKey("devices") as JArray ?? new JArray();
            return token.ToObject<List<DeviceRecord>>() ?? new List<DeviceRecord>();
        }
    }

    private void WriteDevices(List<DeviceRecord> devices)
    {
        lock (_storeLock)
        {
            Config.WriteConfigKey("devices", JArray.FromObject(devices));
        }
    }

    private void UpsertDevice(DeviceRecord record)
    {
        var devices = ReadDevices();
        devices.RemoveAll(d => d.DeviceId == record.DeviceId);
        devices.Add(record);
        WriteDevices(devices);
    }

    private bool TryDecodePayload(string? text, out PairingPayload payload, out string error)
    {
        payload = new PairingPayload();
        error = string.Empty;
        if (string.IsNullOrWhiteSpace(text) || !text.StartsWith(PayloadPrefix, StringComparison.Ordinal))
        {
            error = "无法识别的配对二维码";
            return false;
        }
        try
        {
            var json = Encoding.UTF8.GetString(FromBase64Url(text[PayloadPrefix.Length..]));
            var parsed = JsonSerializer.Deserialize<PairingPayload>(json, JsonOptions);
            if (parsed == null || parsed.Version != 1 ||
                string.IsNullOrWhiteSpace(parsed.Url) ||
                string.IsNullOrWhiteSpace(parsed.Code) ||
                string.IsNullOrWhiteSpace(parsed.Signature))
            {
                error = "配对二维码内容不完整";
                return false;
            }
            payload = parsed;
            return true;
        }
        catch
        {
            error = "配对二维码解析失败";
            return false;
        }
    }

    private string Sign(string url, string code, long expiresAt)
    {
        using var hmac = new HMACSHA256(FromBase64Url(_installSecret));
        var data = Encoding.UTF8.GetBytes($"{url.ToLowerInvariant()}|{code.ToUpperInvariant()}|{expiresAt}");
        return Base64Url(hmac.ComputeHash(data));
    }

    private static bool FixedTimeEquals(string a, string b)
    {
        var ba = Encoding.UTF8.GetBytes(a);
        var bb = Encoding.UTF8.GetBytes(b);
        return ba.Length == bb.Length && CryptographicOperations.FixedTimeEquals(ba, bb);
    }

    private static string GenerateCode()
    {
        var sb = new StringBuilder(8);
        for (var i = 0; i < 8; i++)
        {
            sb.Append(CrockfordAlphabet[RandomNumberGenerator.GetInt32(CrockfordAlphabet.Length)]);
        }
        return sb.ToString();
    }

    private static string RandomString(int length)
    {
        var sb = new StringBuilder(length);
        for (var i = 0; i < length; i++)
        {
            sb.Append(CrockfordAlphabet[RandomNumberGenerator.GetInt32(CrockfordAlphabet.Length)].ToString().ToLowerInvariant());
        }
        return sb.ToString();
    }

    private static string NormalizeUrl(string? explicitUrl, string fallback)
    {
        var url = string.IsNullOrWhiteSpace(explicitUrl) ? fallback : explicitUrl!;
        url = url.Trim().TrimEnd('/');
        if (url.Length == 0) return string.Empty;
        if (!url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
            !url.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            url = "https://" + url;
        }
        return url;
    }

    private static string Sanitize(string? value, int maxLength, string fallback)
    {
        var text = (value ?? string.Empty).Trim();
        text = new string(text.Where(c => !char.IsControl(c)).ToArray());
        if (text.Length == 0) return fallback;
        return text.Length > maxLength ? text[..maxLength] : text;
    }

    private static string Iso(long unixSeconds) =>
        DateTimeOffset.FromUnixTimeSeconds(unixSeconds).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss");

    private static string Base64Url(byte[] data) =>
        Convert.ToBase64String(data).Replace('+', '-').Replace('/', '_').TrimEnd('=');

    private static byte[] FromBase64Url(string text)
    {
        var s = text.Replace('-', '+').Replace('_', '/');
        s = (s.Length % 4) switch
        {
            2 => s + "==",
            3 => s + "=",
            _ => s,
        };
        return Convert.FromBase64String(s);
    }
}
