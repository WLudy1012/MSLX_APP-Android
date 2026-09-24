# MSLX.Plugin.Pairing —— 扫码配对（设备接入）

MSLX Daemon 插件：在已授权的客户端（App / 桌面端 / curl）生成**一次性配对二维码**，
手机 App 扫码后自动换取一枚**独立受限**的 API Key，免去手输长 Key。

每台配对设备对应一个独立的 Daemon 用户：

- **可撤销**：撤销设备即删除对应用户，其 API Key 立即失效；
- **可过期**：设备记录带有效期（默认 30 天，生成时可选 1–365 天），过期自动清理；
- **可审计**：生成/兑换/撤销/过期全部写入 Daemon 运行日志（API Key 与配对码绝不落日志）；
- **不存明文**：服务端只保存 API Key 前缀用于展示识别，完整 Key 仅返回给扫码设备一次。

## 安装

1. 运行 `pack.ps1`（需 .NET 10 SDK；`MSLX.SDK` 默认取 `../../MSLX-dev/MSLX.SDK`，
   可用 `-p:MSLX_SDK_DIR=<SDK 目录>` 覆盖）得到 `dist/MSLX.Plugin.Pairing.dll`；
2. 将 `MSLX.Plugin.Pairing.dll` 复制到 Daemon 数据目录的 `Plugins/` 子目录：
   - Windows：`%APPDATA%\MSLX\MSLXData\DaemonData\Plugins\`
   - macOS：`~/Library/Application Support/MSLX/MSLXData/DaemonData/Plugins/`
   （macOS App Bundle 运行时会使用 Bundle 内的数据目录）
3. 重启 Daemon，或在面板插件管理中热加载；
4. 日志出现 `[Pairing] 扫码配对插件已就绪` 即成功。

也可把 dll 放到任意可公网下载的地址，通过 Daemon 的插件安装 API（`POST /api/plugins/install`，admin）
以 URL 方式安装。

## API（全部以 `/api/plugins/pair` 为前缀）

所有端点都经过 Daemon 的鉴权管道：除 `redeem` 外均要求 **admin** 角色的 `x-api-key` / `x-user-token`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/codes` | 生成一次性配对码（TTL 120s，仅内存保存）。可带 `scope=full\|limited`、`resources[]`、`deviceTtlDays`、`publicUrl`（覆盖自动识别的对外地址） |
| POST | `/redeem` | 兑换配对码（匿名端点，插件内部强校验：HMAC 签名 / 时效 / 一次性 / IP 限速与失败锁定）。请求：`payload`（二维码原文）、`deviceName`、`deviceFingerprint` |
| GET | `/devices` | 已配对设备列表（脱敏：Key 前缀、指纹前缀、状态、有效期） |
| POST | `/devices/{deviceId}/revoke` | 撤销设备（删除配对用户，Key 立即失效） |

### 生成配对码示例

```bash
curl -X POST "https://<daemon>:1027/api/plugins/pair/codes" \
  -H "x-api-key: <你的管理员 API Key>" \
  -H "Content-Type: application/json" \
  -d '{"scope":"full","deviceTtlDays":30,"publicUrl":"https://192.168.1.10:1027"}'
```

响应 `data.payload` 即二维码内容（`mslxp1:` 前缀 + Base64Url(JSON)），用任意二维码工具渲染后由手机扫描。

### 二维码载荷结构

```json
{"v":1,"u":"https://192.168.1.10:1027","c":"AB12CD34","e":1712345678,
 "s":"HMAC-SHA256(installSecret, \"url|code|exp\") 的 Base64Url"}
```

- `installSecret` 为插件首次运行生成的 32 字节随机密钥，保存在插件配置目录；
- 兑换时服务端以服务端时间校验时效、以常量时间比较验证签名、`TryRemove` 原子消费配对码杜绝重放；
- 同一设备指纹重复配对时，旧记录自动撤销（`replaced`）。

## 数据位置

- 插件配置：`<AppData>/PluginsData/mslx-pair/Config.json`（安装密钥 + 设备记录）
- 配对设备对应的用户：Daemon `UserList.json` 中用户名形如 `pair_dxxxxxxxxxx`

## 安全边界

- 配对码 120 秒过期、单次使用、仅内存保存，进程重启即失效；
- 同 IP 每分钟最多 10 次兑换尝试，连续失败 5 次临时锁定 15 分钟；
- `redeem` 是唯一匿名端点，其余端点强制 admin 鉴权，与 Daemon 现有 `AuthMiddleware`/`JwtUtils` 体系一致；
- 受限设备请在生成配对码时使用 `scope=limited` + `resources=["server:1", ...]`，仅授予必要实例。
