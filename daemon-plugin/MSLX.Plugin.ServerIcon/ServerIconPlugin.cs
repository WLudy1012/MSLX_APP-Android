using Microsoft.AspNetCore.Routing;
using MSLX.SDK;

namespace MSLX.Plugin.ServerIcon;

/// <summary>
/// MSLX 服务端图标插件入口。
/// 无状态服务：OnLoad 与 OnRegisterEndpoints 均可能被调用多次（热加载 + 启动流程），
/// 必须保持幂等，因此仅做字段级初始化、不启动额外定时器。
/// </summary>
public sealed class ServerIconPlugin : IPlugin
{
    public string Id => ServerIconService.PluginId;

    public string Name => "MSLX 服务端图标";

    public string Description =>
        "为实例统一提供图标（/api/plugins/icon/server/{id}）：本地 server-icon.png 优先，" +
        "其次第三方状态 API（mcsrvstat.us/mcstatus.io，仅公网地址）并做 24 小时磁盘缓存。";

    public string Version => "1.0.0";

    public string MinSDKVersion => "1.5.10.2";

    public string Developer => "MSLX App";

    public string AuthorUrl => "https://github.com/WLudy1012";

    public string PluginUrl => "https://github.com/WLudy1012/MSLX_APP-Android";

    private ServerIconService? _service;

    public void OnLoad() => _service ??= new ServerIconService();

    public void OnRegisterEndpoints(IEndpointRouteBuilder endpoints)
    {
        ServerIconEndpoints.Map(endpoints, _service ??= new ServerIconService());
        global::MSLX.SDK.MSLX.Logger.Info("[ServerIcon] 服务端图标插件已挂载 /api/plugins/icon");
    }
}
