# MSLX_APP-Android（MSLX 控制台 / Android 端）

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
[![Android CI](https://github.com/WLudy1012/MSLX_APP-Android/actions/workflows/android.yml/badge.svg)](https://github.com/WLudy1012/MSLX_APP-Android/actions/workflows/android.yml)

MSLX 守护程序（MSLX Daemon）的第三方手机安卓端控制台。基于 **Kotlin + Jetpack Compose** 构建，
复刻了 MSLX Desktop 版「连接守护程序 → 管理实例」的核心能力，并针对手机触屏重新设计了操作流程。

> 仓库地址：https://github.com/WLudy1012/MSLX_APP-Android
> Android applicationId / 包名保持 `com.mslx.console`，未随仓库改名而修改。
> 本应用为**第三方项目**，与 MSLTeam 无任何隶属/关联关系，使用前请阅读[免责声明](#免责声明)。

## ✨ 功能

- **多 Daemon 管理**：保存多个守护进程连接（地址 + API Key），随时切换；API Key 使用 Android Keystore AES-GCM 加密落盘，不随备份恢复。
- **实例列表**：展示所有实例的名称、运行状态、在线人数，支持下拉刷新与删除（二次确认 + 可选删除磁盘数据文件）。
- **实例控制**：启动、停止、重启、强制结束、备份；未签署 EULA 时自动弹窗引导「同意并启动」。
- **实时控制台**：深色终端风格，实时接收服务器日志并支持 **ANSI 原彩显示**；发送命令、自动滚动、清空日志、一键回到最新。
- **新建实例**：分步向导（基本信息 / 服务端核心 / Java 环境 / 资源配置 / MCDR / 确认），支持 Java 版与基岩版、在线核心库、远程下载、本地上传、整合包。
- **实例设置**：通用设置、文件管理（浏览/编辑/上传）、插件与模组管理、server.properties 编辑、Java 环境选择。
- **本机开服**：不依赖 Daemon / Termux，直接在手机上跑 Minecraft Java 版服务端（进程内 JVM，可选 Shizuku 增强为真 java 子进程），多 Java 运行时、常驻通知保活、控制台交互。详见[本机开服](#-本机开服在手机上开服)。
- **用户中心**：查看/编辑当前用户信息、一键复制 API Key；管理员可进行用户管理（创建/编辑/删除）。
- **主页仪表盘**：Daemon CPU / 内存负载监视（SignalR 实时推送）、系统信息、实例概览、开服/关服通知。
- **软件自动更新**：启动检测 + 设置页手动检查；支持**稳定版 / 测试版（Beta）双更新渠道**与 **Actions 调试构建渠道**（应用内下载安装 CI 最新 debug APK）；强制更新版本不可跳过。
- **全应用运行日志**：设置页可查看 / 复制 / 导出 / 清空日志（1MB 轮转 + 敏感信息脱敏）；崩溃重启后自动弹窗提示提交 GitHub Issue。
- **外观与关于**：主题颜色（动态取色 / 预设色）、「关于」页展示版本号、最近 Release 更新说明与贡献者。

## 📁 目录结构

```
app/src/main/java/com/mslx/console/
├── MSLXApplication.kt        # Application（日志初始化 + 依赖容器）
├── data/                     # 数据层
│   ├── model/                # 与 Daemon 交互的数据模型
│   ├── remote/               # Retrofit REST + SignalR Hub 客户端
│   ├── InstanceRepository.kt # 实例数据仓库（REST + SignalR 封装）
│   ├── UpdateRepository.kt   # 自动更新（版本后缀解析 + 渠道过滤）
│   ├── SettingsStore.kt      # DataStore 持久化（多 Daemon/主题/更新渠道）
│   ├── CryptoManager.kt      # AndroidKeyStore AES-GCM 加密
│   ├── AppLogger.kt          # 全应用日志（1MB 轮转 + 脱敏 + 崩溃标记）
│   ├── localengine/          # 本机开服引擎（JRE 管理/双引擎/实例文件）
│   └── AppContainer.kt       # 手动依赖注入容器
└── ui/                       # 界面层
    ├── connect/              # 连接守护程序
    ├── home/                 # 主页（负载仪表盘）
    ├── instances/            # 实例列表
    ├── console/              # 控制台（ANSI 原彩）
    ├── create/               # 新建实例分步向导
    ├── settings/             # 设置 / 外观 / 关于 / 运行日志 / 实例设置 / 文件管理
    ├── user/                 # 用户中心
    ├── update/               # 更新弹窗 / 免责协议 / 崩溃报告
    ├── navigation/           # 导航图
    └── theme/                # 主题
```

## 🔧 技术栈

| 依赖 | 版本 |
| --- | --- |
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.2 |
| Gradle | 8.9 |
| JDK | 17 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |
| Jetpack Compose (BOM) | 2024.10.00 |
| Navigation Compose | 2.8.2 |
| Retrofit / OkHttp | 2.11.0 / 4.12.0 |
| SignalR Java Client | 8.0.8 |
| DataStore Preferences | 1.1.1 |
| Coil Compose | 2.7.0 |
| XZ / Commons-Compress（解包 JRE） | 1.10 / 1.27.1 |
| Shizuku（本机开服增强） | 13.1.5 |
| NDK（进程内 JVM 桥接） | 28.2.13676358 |

## 🛠 编译

1. 用 **Android Studio**（Ladybug 或更新版本）打开本目录。
2. 等待 Gradle 同步完成（首次会下载依赖，需要联网）。
3. `Build → Build APK(s)`，或在连接设备后点击 `Run`。

> 要求：JDK 17、Android SDK Platform 35。命令行构建：`./gradlew assembleDebug` / `./gradlew assembleRelease`。

## 📱 使用

1. 在电脑上启动 **MSLX Daemon**，并在其 Web 面板/配置中找到 **API Key**。
2. 手机与电脑连入**同一局域网**。
3. 打开本 App，在「连接守护程序」页填写：
   - **Daemon 地址**：电脑的局域网 IP，例如 `http://192.168.1.100:1027`
     （Windows 可用 `ipconfig`、Linux/macOS 可用 `ip addr` 查询；Docker 部署填映射后的端口）。
   - **API Key**：守护程序的 API Key。
4. 点击「连接」进入实例列表；点任意实例进入控制台，即可查看日志、发送命令、启停实例。

## 📱 本机开服（在手机上开服）

新建实例时选「本机」目标，服务端就跑在手机自身的私有目录（`files/mslx/servers/<实例>`）里，
无需 Daemon、无需 Termux/proot。入口：「设置 → 本机开服」与「设置 → 本地开服设置」。

- **两种引擎**：
  - **基线（进程内 JVM）**：NDK `dlopen libjvm.so` + `JNI_CreateJavaVM`，无需任何授权；
    受 Android 限制，一个进程只能创建一个 JVM，停止后需重启 App 才能再起。
  - **增强模式（Shizuku / ADB）**：以 shell 权限 exec 真正的 `java` 子进程，可多实例并发、
    可原地重启、可跨 Java 版本。未授权时启动会自动回退到基线引擎。
- **Java 运行时**（设置页可装多个并逐实例选择）：

  | 运行时 | 来源 | 分发方式 |
  | --- | --- | --- |
  | Java 17 | PojavLauncher `jre17-ec28559`（Android/bionic） | **内嵌在完整版 APK**，可离线安装 |
  | Java 21 | FCL 下载站 `jre21-arm64-20260223`（Android/bionic） | 应用内下载，SHA-256 固定 |
  | Java 8 | — | **暂无 Android 构建**（上游只有 iOS/macOS 产物），UI 标为不可用 |

  下载源按「CNB 镜像 → GitHub Release → 上游直链」依次回退；精简版 APK 不内嵌运行时，
  首次开服需联网下载约 36MB。
- **版本匹配守卫**：1.17–1.20.4 用 Java 17，1.20.5+ 用 Java 21；运行时低于核心要求时启动会被
  直接拦下并给出可执行的提示（而不是刷一堆看不懂的 JVM 报错）。
- **保活**：前台服务 + 常驻通知，退到后台/划掉最近任务仍继续运行（通知栏可停）。

开发者相关：`fetch-jre-assets.ps1` 拉内嵌的 jre17（`-Jre21` 取仅作 Release 附件的 jre21）；
`build-shim.ps1` 重编 `LD_PRELOAD` 垫片（关 Scudo 堆打标签，否则旧 OpenJDK 在 Android 12+ 必
SIGABRT）；两者的细节注释在 `app/src/main/cpp/shim/mslxnotag.c` 与 `LocalJreManager.kt`。

## ⚠️ 安全与注意事项

- **默认强制 HTTPS**：未带协议的地址自动补 `https://`，明文 `http://` 默认自动升级为 `https://`；
  仅当你在连接页勾选「允许 HTTP」并确认警告后，才保留明文连接（仅限可信内网，明文传输 API Key 有被窃听/篡改风险）。
- **自签证书**：App 对守护进程的 REST 与 SignalR（WebSocket）连接信任自签证书（仅用于连接你自己的守护进程）；
  官方 MSLAPI / GitHub API 仍走系统信任链。若控制台报「WebSocket 协商失败」，请确认 Daemon 已启用 WebSocket，并检查 HTTPS/反向代理的 WS 升级配置。
- **API Key 加密存储**：使用 Android Keystore AES-GCM 加密，备份规则排除密钥相关数据，避免恢复后密文无法解密。
- **路径预设限制**：新建实例 / 整合包的「Daemon 绝对路径」提供常用路径预设，也可手动输入任意绝对路径；
  留空时默认创建在 Daemon 数据目录的 `Server` 文件夹下。
- **防火墙**：请确保守护程序所在主机的防火墙放行了对应端口（默认 1027）。

## 🔄 更新与版本后缀约定

版本号规则（1.3 起）：**正式版 `x.x`**（如 `1.3`）、**Beta 版 `x.x.x`**（如 `1.3.1`）、**Actions 版 `x.x.x.x`**（如 `1.3.0.12`）。

- **稳定版**：tag 无后缀（如 `v1.3`）。
- **测试版**：tag 带 `-Beta` 后缀（如 `v1.3.1-Beta`）。
- **强制更新版**：tag 带 `-Force` 后缀，客户端检测到后弹窗禁跳过。
- 设置页「更新渠道」可选 **稳定版**（默认）/ **测试版** / **Actions 调试构建**：
  - 稳定渠道只接收正式版；测试渠道同时接收稳定版 + Beta 版。
  - **Actions 调试构建**：直接从 GitHub Actions 拉取最新 main 分支调试 APK。该渠道**不稳定**，切换时会弹出警告。
- **所有渠道均应用内下载并安装**（下载进度条 → 系统安装器），不再跳转浏览器。
- **CNB 镜像（cnb.cool）为首选更新源**：应用优先从 `https://cnb.cool/WLudy/MSLX_APP-Android` 下载更新 APK，
  失败时自动回退 GitHub Releases（大陆网络环境下载更快、更稳）。
- Release 说明规则：正式版包含自上一正式版以来的全部更新（含中间 Beta）；Beta 版包含自上一 Beta 以来的全部更新（含中间 Actions 构建）。

## 📜 开源协议

本项目基于 [GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE) 开源，
与上游 [MSLX](https://github.com/MSLTeam/MSLX) 保持一致的 AGPL-3.0 协议。

Copyright (C) 2026 WLudy1012

## 免责声明

本应用是第三方项目，与 MSLTeam 不存在任何责任、关联、代理或隶属关系。使用本应用管理服务器实例
可能涉及数据修改、文件读写、进程启停等操作，由此产生的一切后果由使用者自行承担。
完整声明见 [DISCLAIMER.txt](DISCLAIMER.txt)，首次启动时需阅读并同意。

## 🤖 持续集成（GitHub Actions / CNB）

- **android.yml**：`main` 分支 push / PR 时自动构建 debug 与 release（未签名）APK，并上传 debug APK 工件；
  main push 时还会用正式签名构建 debug APK（可直接覆盖安装正式版），并发布到 `dev` Release 供「Actions 调试构建」渠道拉取。
- **CNB 云构建（.cnb.yml）**：镜像仓库 https://cnb.cool/WLudy/MSLX_APP-Android 上，main push 自动构建 debug APK；
  `v*` tag 推送时从密钥仓库恢复签名、构建 release APK 并发布 CNB Release（应用首选更新源）。
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
