# MSLX_APP-Android(MSLX 控制台 / Android 端)

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
[![Android CI](https://github.com/WLudy1012/MSLX_APP-Android/actions/workflows/android.yml/badge.svg)](https://github.com/WLudy1012/MSLX_APP-Android/actions/workflows/android.yml)

MSLX 守护程序(MSLX Daemon)的第三方手机安卓端控制台。基于 **Kotlin + Jetpack Compose** 构建，
复刻了 MSLX Desktop 版"连接守护程序 → 管理实例"的核心能力,并针对手机触屏重新设计了操作流程。

> 仓库地址:https://github.com/WLudy1012/MSLX_APP-Android。Android applicationId / 包名仍为 `com.mslx.console`,未随仓库改名而修改。

## ✨ 功能

- **连接守护程序**:输入 Daemon 地址(BaseURL)+ API Key,勾选"记住连接信息"后下次启动自动连接。
- **实例列表**:展示所有实例的名称、运行状态(未启动/启动中/运行中/停止中/重启中)、在线人数,支持下拉刷新。
- **实例控制**:启动、停止、重启、强制结束、备份。
- **实例删除**:删除前需输入实例名二次确认,可选"同时删除磁盘上的服务端数据文件"。
- **实时控制台**:深色终端风格,实时接收服务器日志,支持发送命令、自动滚动、清空日志、一键回到最新。
- **资源概览**:实例主页顶部显示 Daemon 的 CPU、内存占用与当前连接协议(HTTP / WS、HTTPS / WSS)。
- **EULA 引导**:服务器因未签署 EULA 而无法启动时,自动弹窗引导"同意并启动"。
- **用户中心**:查看当前用户信息,可一键复制 API Key(系统账户显示为 System)。

## 📁 目录结构

```
app/src/main/java/com/mslx/console/
├── data/                    # 数据层
│   ├── model/Models.kt      # 与 Daemon 交互的数据模型
│   ├── remote/MslxApi.kt    # Retrofit REST 接口
│   ├── remote/ApiClient.kt  # OkHttp + Retrofit 构建器(x-api-key 认证)
│   ├── remote/ConsoleHubClient.kt  # SignalR 控制台客户端
│   ├── ConnectionStore.kt   # DataStore 持久化地址/密钥
│   ├── InstanceRepository.kt# 实例数据仓库
│   └── AppContainer.kt      # 手动依赖注入容器
└── ui/                      # 界面层
    ├── connect/             # 连接页
    ├── instances/           # 实例列表页
    ├── console/             # 控制台页
    ├── navigation/          # 导航图
    └── theme/               # 主题
```

## 🔧 技术栈

| 依赖 | 版本 |
| --- | --- |
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.2 |
| Gradle | 8.9 |
| Jetpack Compose (BOM) | 2024.10.00 |
| Retrofit / OkHttp | 2.11.0 / 4.12.0 |
| SignalR Java Client | 8.0.8 |
| DataStore Preferences | 1.1.1 |

## 🛠 编译

1. 用 **Android Studio**(建议 Ladybug 或更新版本)打开本目录 `MSLX_APP-Android`。
2. 等待 Gradle 同步完成(首次会下载依赖,需要联网)。
3. `Build → Build APK(s)`,或在连接设备后点击 `Run`。

> 要求:JDK 17(Android Studio 自带即可)、Android SDK Platform 35。

## 📱 使用

1. 在电脑上启动 **MSLX Daemon**(默认监听 `http://192.168.1.100:1027`),并在其 Web 面板/配置中找到 **API Key**。
2. 手机与电脑连入**同一局域网**。
3. 打开本 App,在"连接守护程序"页填写:
   - **Daemon 地址**:电脑的局域网 IP,例如 `http://192.168.1.100:1027`
     (Windows 可用 `ipconfig`、Linux/macOS 可用 `ip addr` 查询;若用 Docker 部署,填映射后的端口)。
   - **API Key**:守护程序的 API Key。
4. 点击"连接",进入实例列表;点任意实例进入控制台,即可查看日志、发送命令、启停实例。

## ⚠️ 注意事项

- 请确保守护程序所在主机的防火墙放行了对应端口(默认 1027)。
- 本 App 允许 `http://` 明文连接(内网场景);若你的 Daemon 已启用 HTTPS,可在
  `AndroidManifest.xml` 中将 `android:usesCleartextTraffic` 改为 `false` 以增强安全性。
- HTTPS / WSS 自签证书:App 对守护进程的 REST 与 SignalR(WebSocket)连接均信任自签证书(仅用于连接你自己的守护进程);
  若控制台连接报"WebSocket 协商失败",请确认 Daemon 已启用 WebSocket,并检查 HTTPS / 反向代理的 WS 升级配置。
- 路径预设限制:新建实例 / 整合包的"Daemon 绝对路径"目前仅提供常用路径预设(/home/user/下载/、/home/user/、/opt/servers/),
  也可手动输入任意绝对路径;文件管理仅支持对已存在的实例 id 进行浏览 / 编辑。
- API 契约与 Desktop 版一致:`x-api-key` 请求头认证、`GET /api/instance/list`、
  `POST /api/instance/action`、SignalR `/api/hubs/instanceControlHub`。

## 📜 开源协议

本项目基于 [GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE) 开源，
与上游 [MSLX](https://github.com/MSLTeam/MSLX) 保持一致的 AGPL-3.0 协议。

Copyright (C) 2026 WLudy1012

## 🤖 持续集成 (GitHub Actions)

- **android.yml**：`main` 分支 push / PR 时自动构建 debug 与 release(未签名)APK，并上传 debug APK 工件。
- **release.yml**：推送 `v*` 标签时，使用仓库 Secrets 恢复签名密钥，构建签名 release APK 并自动附加到对应 Release。

### release.yml 所需 Secrets

在仓库 `Settings → Secrets and variables → Actions` 配置：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | 对 `.jks` 密钥库执行 `base64` 编码后的内容 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

> Windows 生成 `KEYSTORE_BASE64`：
> `[Convert]::ToBase64String([IO.File]::ReadAllBytes('keystore\mslx-release.jks'))`
