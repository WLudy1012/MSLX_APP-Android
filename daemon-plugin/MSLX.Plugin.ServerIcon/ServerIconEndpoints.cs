using System.Text.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace MSLX.Plugin.ServerIcon;

/// <summary>
/// 图标端点（全部挂在 <c>/api/plugins/icon</c> 前缀下，自动受 Daemon 认证管道保护）：
/// <list type="bullet">
/// <item>GET /server/{id} —— 返回实例图标 PNG 字节流；无可用图标时返回 JSON（code/message），App 侧回退占位图。</item>
/// <item>POST /server/{id}/refresh —— 清除该实例的第三方图标磁盘缓存（下次请求重新拉取）。</item>
/// </list>
/// 鉴权口径与 Daemon 内置实例接口一致：登录用户 + <c>server:{id}</c> 资源权限。
/// </summary>
public static class ServerIconEndpoints
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public static void Map(IEndpointRouteBuilder endpoints, ServerIconService service)
    {
        var group = endpoints.MapGroup("/api/plugins/icon");

        group.MapGet("/server/{id:long}", async (long id, HttpContext ctx) =>
        {
            if (!HasInstancePermission(ctx, id)) return Forbidden();
            var result = await service.GetIconAsync((uint)id).ConfigureAwait(false);
            return result.Bytes == null
                ? Fail(result.Code, result.Message)
                : Results.File(result.Bytes, result.ContentType);
        });

        group.MapPost("/server/{id:long}/refresh", (long id, HttpContext ctx) =>
        {
            if (!HasInstancePermission(ctx, id)) return Forbidden();
            service.InvalidateCache((uint)id);
            return Ok("缓存已清除，下次请求将重新拉取");
        });
    }

    /// <summary>与 Daemon 内置实例接口同一套资源权限判定（admin/system-admin 直通）。</summary>
    private static bool HasInstancePermission(HttpContext ctx, long instanceId)
    {
        var userId = ctx.User?.FindFirst("UserId")?.Value ?? string.Empty;
        return global::MSLX.SDK.MSLX.Config.Users.HasResourcePermission(userId, "server", (int)instanceId);
    }

    private static IResult Forbidden() =>
        Results.Json(new { code = 403, message = "没有该实例的访问权限" }, JsonOptions, statusCode: 403);

    private static IResult Fail(int code, string message) =>
        Results.Json(new { code, message }, JsonOptions, statusCode: code);

    private static IResult Ok(string message) =>
        Results.Json(new { code = 200, message }, JsonOptions);
}
