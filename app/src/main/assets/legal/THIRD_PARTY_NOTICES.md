# 第三方组件与许可（THIRD PARTY NOTICES）

本文件是 MSLX 控制台（Android 端，包名 `com.mslx.console`）所用第三方组件与许可的**正本**，
随 APK 打包（`assets/legal/THIRD_PARTY_NOTICES.md`），应用内「设置 → 关于 → 合规与许可」
可直接离线查看；仓库根目录的 `THIRD_PARTY_NOTICES.md` 只是指向本文件的说明。

最后核对：2026-09（版本号以 `gradle/libs.versions.toml` 为准）。

## 一、本项目与上游项目

| 名称 | 角色 | 许可 |
| --- | --- | --- |
| MSLX_APP-Android（本应用） | MSLX 的第三方 Android 客户端 | AGPL-3.0 |
| [MSLX](https://github.com/MSLTeam/MSLX)（MSLTeam） | 被管理的守护程序（MSLX Daemon）与其桌面端 | AGPL-3.0 |
| MSLAPI（mslapi / mslxapi） | 服务端核心与版本元数据、下载地址、SHA-256 | 未声明开源许可，仅作为外部 HTTP 接口调用 |
| Minecraft / Mojang Studios | 被管理的服务端程序本身与其 EULA | 非开源；服务端运行须自行同意 Minecraft EULA |

本应用是**独立第三方应用**，并非 MSLX 官方或 MSLTeam 发布的产品，两者之间不存在责任、
关联、代理或隶属关系；完整措辞见首次启动的「第三方免责声明」与仓库根目录 `DISCLAIMER.txt`。

## 二、Java 运行时（本机开服随包/下载的 JRE）

| 运行时 | 来源构建 | 许可 |
| --- | --- | --- |
| Java 17（完整版 APK 内嵌） | [PojavLauncherTeam/android-openjdk-build-multiarch](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch) 发布标签 `jre17-ec28559`，产物 `jre17-arm64-20210825-release.tar.xz` / `jre17-x86_64-20210825-release.tar.xz` | OpenJDK：GPL-2.0 **with Classpath Exception**（SPDX 标识 `GPL-2.0-only with Classpath-exception-2.0`） |
| Java 21（应用内下载 / Release 附件） | FCL 下载站同源构建 `jre21-arm64-20260223-release.tar.xz`，经 CNB 镜像 → GitHub Release → 上游直链依次回退 | 同上（OpenJDK GPL-2.0 with Classpath Exception） |
| Java 25（应用内下载 / Release 附件） | FCL 下载站同源构建 `jre25-arm64-20260223-release.tar.xz`（OpenJDK 25.0.3），回退顺序同上 | 同上（OpenJDK GPL-2.0 with Classpath Exception） |
| Java 8（应用内下载 / Release 附件） | [ZalithLauncher 2](https://github.com/ZalithLauncher/ZalithLauncher2) 内置运行时 `runtimes/jre-8`（OpenJDK 8u442 的 Android/bionic 构建）：类库归档 `universal.tar.xz` + 平台二进制 `bin-arm64.tar.xz` / `bin-x86_64.tar.xz`，默认从 jsDelivr CDN（GitHub raw 兜底）拉取，项目自托管镜像可选 | 同上（OpenJDK GPL-2.0 with Classpath Exception） |

- 构建产物本身只是 OpenJDK 的 Android/bionic 交叉编译结果；本应用不修改其字节码，仅按
  `SHA-256` 校验后解压使用（校验值写死在 `LocalJreManager` 与 `fetch-jre-assets.ps1`）。
- Java 8 的类库以 pack200 压缩格式（`lib/*.jar.pack`，Java 9 起已废弃）分发，安装时由随 APK
  分发的 `libunpack200.so`（OpenJDK 8 的 unpack200 工具，见下表）在设备上还原为 `.jar`。
- 每台设备的运行时安装位置：`filesDir/mslx/runtime/<运行时标识>`（应用私有目录）。

## 三、随包分发的第三方库

| 组件（Gradle 坐标） | 版本 | 许可 |
| --- | --- | --- |
| AndroidX Core Ktx / Splashscreen / Lifecycle / Activity Compose / Compose UI & Material3 / Navigation / DataStore Preferences | 见 `libs.versions.toml` | Apache-2.0 |
| Compose Material Icons Core（图标） | BOM 2024.10.00 | 代码 Apache-2.0；图标资源遵循 Google Material Icons 的 CC-BY-4.0 |
| Kotlin 标准库（JetBrains） | 2.0.21 | Apache-2.0 |
| kotlinx-coroutines-android | 1.9.0 | Apache-2.0 |
| Retrofit + converter-gson（Square） | 2.11.0 | Apache-2.0 |
| OkHttp + logging-interceptor（Square） | 4.12.0 | Apache-2.0 |
| Gson（Google） | 2.10.1 | Apache-2.0 |
| SignalR Java Client（Microsoft，`com.microsoft.signalr:signalr`） | 8.0.8 | Apache-2.0 |
| Coil Compose（`io.coil-kt:coil-compose`） | 2.7.0 | Apache-2.0 |
| XZ for Java（`org.tukaani:xz`，解压内嵌 `.tar.xz` 运行时） | 1.10 | 0BSD（1.9 及更早为 Public Domain） |
| Apache Commons Compress（tar 归档处理） | 1.27.1 | Apache-2.0 |
| Shizuku 客户端 API / Provider（`dev.rikka.shizuku:api`、`:provider`） | 13.1.5 | Apache-2.0（按该许可第 6 条附加说明：**不得**复用 Shizuku 管理器自身的界面资源；本应用只调用 API） |
| `libunpack200.so`（`jniLibs/{arm64-v8a,x86_64}`，Java 8 类库的 pack200 解包器） | 8u442（取自 ZalithLauncher 2 的 `runtimes/jre-8` 归档，bionic 构建） | OpenJDK：GPL-2.0 **with Classpath Exception** |

## 四、自研组件

| 组件 | 说明 | 许可 |
| --- | --- | --- |
| `app/src/main/cpp/mslxvm.c`、`app/src/main/cpp/shim/mslxnotag.c`（后者由 `build-shim.ps1` 产出 `libmslxnotag-*.so`） | 进程内 JVM 桥接（`dlopen libjvm.so` + `JNI_CreateJavaVM`）、stdio 接管，以及关 Scudo 堆标签的 `LD_PRELOAD` 垫片 | AGPL-3.0（属本应用源码） |

## 五、获取完整许可证文本

- Apache-2.0：https://www.apache.org/licenses/LICENSE-2.0
- AGPL-3.0：https://www.gnu.org/licenses/agpl-3.0.html（本仓库 `LICENSE`）
- GPL-2.0 with Classpath Exception：https://openjdk.org/legal/gplv2+ce.html
- 0BSD：https://opensource.org/licenses/0BSD
- MIT：https://opensource.org/licenses/MIT

上述组件的许可证副本亦随各自发布产物提供；APK 中 `META-INF/` 目录内可找到多数 JVM 库
自带的 `LICENSE`/`NOTICE` 文件。若在分发版本时对第三方组件做了实质性修改，请以对应上游
仓库的许可声明为准复核。
