package com.wludy.rolithax.launcher.data.model

enum class PropType { STRING, NUMBER, BOOLEAN, SELECT }

data class PropOption(val label: String, val value: String)

data class PropSchema(
    val key: String,
    val label: String,
    val desc: String = "",
    val type: PropType,
    val options: List<PropOption> = emptyList(),
    val group: String,
)

/** server.properties 已知字段元数据(移植自网页版 serverPropertiesMeta)。 */
val SERVER_PROPERTIES_SCHEMA: List<PropSchema> = listOf(
    // ============ 基础设置 ============
    PropSchema("motd", "服务器标语", "显示在多人游戏列表中的服务器介绍信息。(支持中文 & 颜色代码)", PropType.STRING, group = "基础设置"),
    PropSchema("server-port", "服务器端口", "默认为 25565。一台机器运行多个服务器时必须修改。", PropType.NUMBER, group = "基础设置"),
    PropSchema("max-players", "最大玩家数", "服务器同时允许在线的最大玩家数量。", PropType.NUMBER, group = "基础设置"),
    PropSchema("online-mode", "正版验证", "开启后验证正版账号。离线/登录插件请关闭。", PropType.BOOLEAN, group = "基础设置"),
    PropSchema("white-list", "启用白名单", "开启后仅白名单内玩家可进入。", PropType.BOOLEAN, group = "基础设置"),
    PropSchema("enforce-whitelist", "强制白名单", "开启后不在白名单的玩家即使在线也会被踢出。", PropType.BOOLEAN, group = "基础设置"),
    PropSchema("level-name", "存档文件夹名称", "世界存档文件夹名称(默认 world)。", PropType.STRING, group = "基础设置"),
    PropSchema("server-name", "服务器名称", "服务器内部命名标识。", PropType.STRING, group = "基础设置"),

    // ============ 游戏规则 ============
    PropSchema("gamemode", "默认游戏模式", "新玩家进入时的默认模式。", PropType.SELECT,
        listOf(PropOption("生存 (Survival)", "survival"), PropOption("创造 (Creative)", "creative"), PropOption("冒险 (Adventure)", "adventure"), PropOption("旁观 (Spectator)", "spectator")),
        group = "游戏规则"),
    PropSchema("force-gamemode", "强制游戏模式", "玩家每次加入都被重置为默认模式。", PropType.BOOLEAN, group = "游戏规则"),
    PropSchema("difficulty", "难度", "世界的游戏难度。", PropType.SELECT,
        listOf(PropOption("和平", "peaceful"), PropOption("简单", "easy"), PropOption("普通", "normal"), PropOption("困难", "hard")),
        group = "游戏规则"),
    PropSchema("hardcore", "极限模式", "玩家死亡将被封禁。", PropType.BOOLEAN, group = "游戏规则"),
    PropSchema("allow-flight", "允许飞行", "允许生存模式玩家飞行。", PropType.BOOLEAN, group = "游戏规则"),
    PropSchema("allow-nether", "允许下界", "是否允许进入下界。", PropType.BOOLEAN, group = "游戏规则"),
    PropSchema("enable-command-block", "启用命令方块", "允许使用命令方块。", PropType.BOOLEAN, group = "游戏规则"),
    PropSchema("pvp", "玩家间伤害 (PVP)", "开启后玩家可互相攻击。", PropType.BOOLEAN, group = "游戏规则"),
    PropSchema("spawn-animals", "生成动物", "是否自然生成被动生物。", PropType.BOOLEAN, group = "游戏规则"),
    PropSchema("spawn-monsters", "生成怪物", "是否自然生成敌对生物。", PropType.BOOLEAN, group = "游戏规则"),
    PropSchema("spawn-npcs", "生成 NPC", "是否在村庄生成村民。", PropType.BOOLEAN, group = "游戏规则"),
    PropSchema("spawn-protection", "出生点保护半径", "出生点周围禁非 OP 破坏的格数，0 禁用。", PropType.NUMBER, group = "游戏规则"),
    PropSchema("player-idle-timeout", "挂机踢出时间", "玩家闲置多少分钟后被踢，0 不限制。", PropType.NUMBER, group = "游戏规则"),

    // ============ 世界生成 ============
    PropSchema("level-seed", "世界种子", "生成世界用种子，留空随机。", PropType.STRING, group = "世界生成"),
    PropSchema("level-type", "世界类型", "如 minecraft:normal / flat / amplified。", PropType.STRING, group = "世界生成"),
    PropSchema("generate-structures", "生成结构", "是否生成村庄、地牢等结构。", PropType.BOOLEAN, group = "世界生成"),
    PropSchema("generator-settings", "生成器设置", "自定义超平坦或特定生成器的 JSON。", PropType.STRING, group = "世界生成"),
    PropSchema("max-world-size", "世界边界半径", "世界边界最大半径。", PropType.NUMBER, group = "世界生成"),
    PropSchema("simulation-distance", "模拟距离", "服务器运算实体/作物的区块半径(3-32)。", PropType.NUMBER, group = "世界生成"),
    PropSchema("view-distance", "视距", "客户端可见区块半径，过大增加资源消耗。", PropType.NUMBER, group = "世界生成"),
    PropSchema("entity-broadcast-range-percentage", "实体广播范围百分比", "客户端看到实体距离系数(100 默认)。", PropType.NUMBER, group = "世界生成"),

    // ============ 性能与网络 ============
    PropSchema("max-tick-time", "最大刻耗时 (Watchdog)", "一刻最长毫秒数，超时强关，-1 禁用。", PropType.NUMBER, group = "性能与网络"),
    PropSchema("network-compression-threshold", "网络压缩阈值", "数据包超此字节数压缩，-1 禁用。", PropType.NUMBER, group = "性能与网络"),
    PropSchema("rate-limit", "数据包限制", "发送过快被踢，0 禁用。", PropType.NUMBER, group = "性能与网络"),
    PropSchema("use-native-transport", "使用原生传输优化", "Linux 下启用 Epoll 优化。", PropType.BOOLEAN, group = "性能与网络"),
    PropSchema("prevent-proxy-connections", "防止代理连接", "尝试阻止 VPN/代理连接。", PropType.BOOLEAN, group = "性能与网络"),
    PropSchema("sync-chunk-writes", "同步区块写入", "开启更安全但可能掉帧。", PropType.BOOLEAN, group = "性能与网络"),
    PropSchema("region-file-compression", "区块文件压缩格式", "保存区块的压缩算法。", PropType.SELECT,
        listOf(PropOption("Deflate (默认)", "deflate"), PropOption("LZ4 (更快)", "lz4"), PropOption("不压缩 (None)", "none")),
        group = "性能与网络"),
    PropSchema("max-chained-neighbor-updates", "最大连锁更新数", "限制红石/方块连锁更新防止崩服。", PropType.NUMBER, group = "性能与网络"),
    PropSchema("log-ips", "控制台记录 IP", "日志中显示玩家 IP。", PropType.BOOLEAN, group = "性能与网络"),
    PropSchema("hide-online-players", "隐藏在线玩家列表", "列表不显示具体玩家名单。", PropType.BOOLEAN, group = "性能与网络"),
    PropSchema("enable-status", "启用状态查询", "允许外部查询服务器状态。", PropType.BOOLEAN, group = "性能与网络"),
    PropSchema("accepts-transfers", "接受服务器传送", "允许从其他服务器无缝传送。", PropType.BOOLEAN, group = "性能与网络"),
    PropSchema("pause-when-empty-seconds", "空载暂停时间", "无玩家多少秒后暂停，-1 不暂停。", PropType.NUMBER, group = "性能与网络"),
    PropSchema("chat-spam-threshold-seconds", "聊天刷屏触发时间", "聊天过快被踢阈值，0 禁用。", PropType.NUMBER, group = "性能与网络"),
    PropSchema("command-spam-threshold-seconds", "指令刷屏触发时间", "指令过快被踢阈值，0 禁用。", PropType.NUMBER, group = "性能与网络"),

    // ============ 安全与权限 ============
    PropSchema("op-permission-level", "OP 权限等级", "OP 默认权限级别。", PropType.SELECT,
        listOf(PropOption("1 - 无视出生点保护", "1"), PropOption("2 - 使用单机指令", "2"), PropOption("3 - 多人管理", "3"), PropOption("4 - 系统管理", "4")),
        group = "安全与权限"),
    PropSchema("function-permission-level", "函数权限等级", "数据包函数默认权限等级。", PropType.NUMBER, group = "安全与权限"),
    PropSchema("enforce-secure-profile", "强制安全配置 (签名)", "强制官方签名公钥，离线服建议关闭。", PropType.BOOLEAN, group = "安全与权限"),
    PropSchema("enable-code-of-conduct", "启用行为准则提示", "向玩家展示行为准则链接。", PropType.BOOLEAN, group = "安全与权限"),
    PropSchema("bug-report-link", "Bug 反馈链接", "玩家遇到错误时显示的反馈网址。", PropType.STRING, group = "安全与权限"),

    // ============ 资源包与数据包 ============
    PropSchema("resource-pack", "资源包下载地址", "资源包直链 URL。", PropType.STRING, group = "资源包与数据包"),
    PropSchema("require-resource-pack", "强制资源包", "拒绝下载资源包的玩家被踢。", PropType.BOOLEAN, group = "资源包与数据包"),
    PropSchema("resource-pack-sha1", "资源包 SHA1", "资源包 SHA-1 校验码。", PropType.STRING, group = "资源包与数据包"),
    PropSchema("resource-pack-id", "资源包唯一标识 (UUID)", "现代版本客户端区分/清理缓存。", PropType.STRING, group = "资源包与数据包"),
    PropSchema("resource-pack-prompt", "资源包提示语", "下载资源包时显示的自定义消息。", PropType.STRING, group = "资源包与数据包"),
    PropSchema("initial-enabled-packs", "初始启用数据包", "世界生成时默认启用的数据包(逗号分隔)。", PropType.STRING, group = "资源包与数据包"),
    PropSchema("initial-disabled-packs", "初始禁用数据包", "世界生成时默认禁用的数据包。", PropType.STRING, group = "资源包与数据包"),

    // ============ 远程管理 ============
    PropSchema("enable-rcon", "启用 RCON", "开启远程控制台协议。", PropType.BOOLEAN, group = "远程管理"),
    PropSchema("rcon.port", "RCON 端口", "RCON 监听端口(默认 25575)。", PropType.NUMBER, group = "远程管理"),
    PropSchema("rcon.password", "RCON 密码", "连接 RCON 的密码，请设复杂。", PropType.STRING, group = "远程管理"),
    PropSchema("broadcast-rcon-to-ops", "向 OP 广播 RCON", "RCON 执行指令时通知在线 OP。", PropType.BOOLEAN, group = "远程管理"),
    PropSchema("enable-query", "启用 Query", "开启 GameSpy4 协议获取详情。", PropType.BOOLEAN, group = "远程管理"),
    PropSchema("query.port", "Query 端口", "Query 监听端口(默认 25565)。", PropType.NUMBER, group = "远程管理"),
    PropSchema("broadcast-console-to-ops", "向 OP 广播控制台", "控制台输出发给在线 OP。", PropType.BOOLEAN, group = "远程管理"),
    PropSchema("enable-jmx-monitoring", "启用 JMX 监控", "开启 Java JMX 性能监控。", PropType.BOOLEAN, group = "远程管理"),

    // ============ 官方管理后台 ============
    PropSchema("management-server-enabled", "启用管理后台", "启用官方管理服务器接口(不懂勿开)。", PropType.BOOLEAN, group = "官方管理后台"),
    PropSchema("management-server-port", "管理后台端口", "管理接口监听端口。", PropType.NUMBER, group = "官方管理后台"),
    PropSchema("management-server-host", "管理后台主机", "管理接口绑定主机名/IP。", PropType.STRING, group = "官方管理后台"),
    PropSchema("management-server-allowed-origins", "管理后台允许源", "允许访问的 Origin 列表。", PropType.STRING, group = "官方管理后台"),
    PropSchema("management-server-secret", "管理后台密钥", "访问官方管理接口的令牌，请设复杂。", PropType.STRING, group = "官方管理后台"),
    PropSchema("management-server-tls-enabled", "启用管理后台 TLS 加密", "为管理接口启用 HTTPS/TLS。", PropType.BOOLEAN, group = "官方管理后台"),
    PropSchema("management-server-tls-keystore", "管理后台密钥库路径", "TLS 密钥库(.jks)路径。", PropType.STRING, group = "官方管理后台"),
    PropSchema("management-server-tls-keystore-password", "管理后台密钥库密码", "读取 TLS 密钥库的密码。", PropType.STRING, group = "官方管理后台"),

    // ============ 杂项与调试 ============
    PropSchema("server-ip", "服务器绑定 IP", "绑定的本地网卡 IP，留空监听所有。", PropType.STRING, group = "杂项与调试"),
    PropSchema("debug", "调试模式", "开启后控制台输出更多调试信息。", PropType.BOOLEAN, group = "杂项与调试"),
    PropSchema("text-filtering-config", "文本过滤配置", "文本过滤服务 API 配置。", PropType.STRING, group = "杂项与调试"),
    PropSchema("status-heartbeat-interval", "状态心跳间隔", "向客户端发送状态心跳的间隔。", PropType.NUMBER, group = "杂项与调试"),
    PropSchema("text-filtering-version", "文本过滤服务版本", "文本过滤系统协议/API 版本号。", PropType.STRING, group = "杂项与调试"),
)
