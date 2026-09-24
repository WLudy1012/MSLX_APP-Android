# MSLX.Plugin.ServerIcon —— 服务端图标（统一获取与缓存）

MSLX Daemon 插件：为实例统一提供图标端点，供 MSLX App / 面板的实例卡片展示 Minecraft
服务端图标（`server-icon.png`），免去各客户端自行跨域拉第三方 API 的麻烦。

**来源优先级**（服务端串行执行，结果带 24 小时磁盘缓存）：

1. 实例目录下的 `server-icon.png`（Minecraft 标准服务端图标，零网络成本）；
2. 插件磁盘缓存（< 24 小时，直接命中）；
3. 第三方状态 API：`mcsrvstat.us` → 失败回退 `mcstatus.io`；
   仅当 `server.properties` 中 **显式配置** 了 `server-ip` 且为**公网地址**（公网 IP / 域名，
   自动排除私网、回环、链路本地与内网域名）时才发起外部查询。

无任何可用来源时返回 `404 + message`（例如「需在 server.properties 中配置 server-ip」），
App 侧据此回退到本地占位图，不会反复无效请求。

## 安装

1. 运行 `pack.ps1`（需 .NET 10 SDK；`MSLX.SDK` 默认取 `../../MSLX-dev/MSLX.SDK`，
   可用 `-p:MSLX_SDK_DIR=<SDK 目录>` 覆盖）得到 `dist/MSLX.Plugin.ServerIcon.dll`；
2. 将 `MSLX.Plugin.ServerIcon.dll` 复制到 Daemon 数据目录的 `Plugins/` 子目录：
   - Windows：`%APPDATA%\MSLX\MSLXData\DaemonData\Plugins\`
   - macOS：`~/Library/Application Support/MSLX/MSLXData/DaemonData/Plugins/`
3. 重启 Daemon，或在面板插件管理中热加载；
4. 日志出现 `[ServerIcon] 服务端图标插件已挂载 /api/plugins/icon` 即成功。

## API（前缀 `/api/plugins/icon`）

除普通登录鉴权外，逐实例校验 `server:{id}` 资源权限（与 Daemon 内置实例接口同一口径，
admin / system-admin 直通）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/server/{id}` | 返回图标 PNG 字节流（`image/png`）；无可用图标时返回 `{code,message}` JSON |
| POST | `/server/{id}/refresh` | 清除该实例的第三方图标磁盘缓存，下次请求重新拉取 |

### 示例

```bash
curl -o icon.png "https://<daemon>:1027/api/plugins/icon/server/1" \
  -H "x-api-key: <API Key>"
```

## 数据位置

- 磁盘缓存：`<AppData>/PluginsData/mslx-icon/IconCache/<实例 id>.png`（TTL 24 小时）

## 安全边界

- 仅对**公网地址**发起外部查询，绝不把内网地址外发；
- 外部请求带固定 User-Agent，超时 10 秒，失败静默回退（不影响面板/App 其它功能）；
- 端点鉴权完全复用 Daemon 的 `AuthMiddleware` 与资源权限模型，不新增弱兜底路径。
