using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using MSLX.SDK;

namespace MSLX.Plugin.Pairing;

/// <summary>
/// MSLX Daemon 插件：手机扫码配对。
/// 在 Daemon 控制台/桌面端生成一次性二维码，手机 App 扫码后自动换取**独立受限**的 API Key，
/// 免去手输长 Key；每台设备可单独撤销、可过期，全部操作写审计日志。
/// </summary>
public sealed class PairingPlugin : IPlugin
{
    public string Id => "mslx-pair";

    public string Name => "扫码配对（设备接入）";

    public string Description => "生成一次性配对二维码，手机扫码即可安全接入 Daemon；配对用户独立可撤销、可过期。";

    public string Version => "1.0.0";

    public string MinSDKVersion => "1.5.10.2";

    public string Developer => "MSLX-Android";

    public string AuthorUrl => "https://github.com/WLudy1012/MSLX_APP-Android";

    public string PluginUrl => "https://github.com/WLudy1012/MSLX_APP-Android";

    private PairingService? _service;
    private bool _initialized;

    public void OnLoad()
    {
        _service ??= new PairingService();
        if (_initialized) return;
        _initialized = true;
        _service.Initialize();
    }

    public void OnUnload()
    {
        _service?.Shutdown();
        _initialized = false;
    }

    public void OnRegisterServices(IServiceCollection services)
    {
        // 本插件不注册额外服务：配置/用户/日志全部走 MSLX.SDK 静态桥
    }

    public void OnPluginInitialize(IServiceProvider serviceProvider)
    {
        // 无额外初始化
    }

    public void OnRegisterEndpoints(IEndpointRouteBuilder endpoints)
    {
        _service ??= new PairingService();
        PairingEndpoints.Map(endpoints, _service);
    }
}
