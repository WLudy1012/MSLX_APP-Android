# 第三方组件与许可

**正本在应用里，也随 APK 打包**（离线可查、不会与发版包脱节）：

- 文件：[`app/src/main/assets/legal/THIRD_PARTY_NOTICES.md`](app/src/main/assets/legal/THIRD_PARTY_NOTICES.md)
- 应用内：设置 → 关于 → 合规与许可 → 第三方组件与许可
- 内嵌 Java 运行时的来源与校验值另附一份说明：
  [`app/src/jreRuntime/assets/jre/NOTICE-jre17.txt`](app/src/jreRuntime/assets/jre/NOTICE-jre17.txt)

内容涵盖：上游 MSLX / MSLAPI 与本应用的许可关系（AGPL-3.0 + 第三方身份声明）、
Android OpenJDK 构建产物（Java 17 内嵌，Java 8 / 21 / 25 下载，GPL-2.0 with Classpath Exception）、
以及 AndroidX / Compose / Kotlin / Retrofit / OkHttp / Gson / SignalR / Coil / XZ /
Commons-Compress / Shizuku 等随包库的许可证。

本应用是**独立第三方应用**，与 MSLTeam 无隶属、代理或责任关系；
使用风险与开源义务的完整表述见 [`DISCLAIMER.txt`](DISCLAIMER.txt)（与首次开屏免责协议同一份文本）。

> 只维护这一份清单：新增/升级依赖时改 `libs.versions.toml` 与上面的正本即可，
> 本页只作入口，不重复列条目，避免两处漂移。
