using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace MSLX.Plugin.Pairing;

/// <summary>
/// 配对插件 API（全部挂在 /api 前缀下，自动经过 Daemon 的 AuthMiddleware）：
/// - POST /api/plugins/pair/codes           生成一次性配对码（admin）
/// - POST /api/plugins/pair/redeem          兑换配对码（AllowAnonymous + 插件内部强校验：签名/时效/一次性/IP 限速）
/// - GET  /api/plugins/pair/devices         已配对设备列表（admin，脱敏）
/// - POST /api/plugins/pair/devices/{id}/revoke  撤销设备（admin，删除配对用户使 Key 立即失效）
/// </summary>
public static class PairingEndpoints
{
    private static readonly JsonSerializerOptions ResponseJson = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    private static readonly JsonSerializerOptions RequestJson = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public static void Map(IEndpointRouteBuilder endpoints, PairingService service)
    {
        var group = endpoints.MapGroup("/api/plugins/pair");

        group.MapPost("/codes", async (HttpContext ctx) =>
        {
            var request = await ReadBodyAsync<CreateCodeRequest>(ctx) ?? new CreateCodeRequest();
            var createdBy = ctx.User?.FindFirst("UserId")?.Value
                ?? ctx.User?.Identity?.Name
                ?? "unknown";
            var baseUrl = $"{ctx.Request.Scheme}://{ctx.Request.Host}";
            var (code, message, data) = service.CreateCode(request, createdBy, baseUrl);
            await WriteAsync(ctx, code, message, data);
        }).RequireAuthorization(new AuthorizeAttribute { Roles = "admin" });

        group.MapPost("/redeem", async (HttpContext ctx) =>
        {
            var request = await ReadBodyAsync<RedeemRequest>(ctx);
            if (request == null)
            {
                await WriteAsync(ctx, 400, "请求体不合法");
                return;
            }
            var clientIp = ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown";
            var (code, message, data) = service.Redeem(request, clientIp);
            await WriteAsync(ctx, code, message, data);
        }).AllowAnonymous();

        group.MapGet("/devices", async (HttpContext ctx) =>
        {
            var (code, message, data) = service.ListDevices();
            await WriteAsync(ctx, code, message, data);
        }).RequireAuthorization(new AuthorizeAttribute { Roles = "admin" });

        group.MapPost("/devices/{deviceId}/revoke", async (HttpContext ctx) =>
        {
            var deviceId = (string?)ctx.Request.RouteValues["deviceId"] ?? string.Empty;
            var (code, message, data) = service.Revoke(deviceId);
            await WriteAsync(ctx, code, message, data);
        }).RequireAuthorization(new AuthorizeAttribute { Roles = "admin" });
    }

    private static async Task<T?> ReadBodyAsync<T>(HttpContext ctx) where T : class
    {
        try
        {
            using var reader = new StreamReader(ctx.Request.Body);
            var json = await reader.ReadToEndAsync();
            if (string.IsNullOrWhiteSpace(json)) return null;
            return JsonSerializer.Deserialize<T>(json, RequestJson);
        }
        catch
        {
            return null;
        }
    }

    private static async Task WriteAsync(HttpContext ctx, int code, string message, object? data = null)
    {
        ctx.Response.StatusCode = code is >= 200 and < 600 ? code : 500;
        ctx.Response.ContentType = "application/json; charset=utf-8";
        var body = JsonSerializer.Serialize(new { code, message, data }, ResponseJson);
        await ctx.Response.WriteAsync(body);
    }
}
