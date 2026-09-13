package com.mslx.console.data.localengine

import com.google.gson.GsonBuilder
import com.mslx.console.data.AppLogger
import java.io.File

/** 实例元数据（`instance.json`）：与 daemon 的 ServerInfo 字段对应，便于日后接入控制台/迁移。 */
data class LocalInstanceMeta(
    val name: String = "",
    val directory: String = "",
    val core: String = "",
    val coreVersion: String = "",
    val coreBuild: String = "",
    /** 核心文件固定名（同 daemon 约定：server.jar）。 */
    val serverJar: String = ServerFiles.SERVER_JAR_NAME,
    /** 主类（从核心 jar 的 MANIFEST 解析，bundler 壳会换成内层主类）。 */
    val mainClass: String = "",
    val runtimeId: String = LocalJreManager.RUNTIME_ID,
    val javaMajor: Int = LocalJreManager.JAVA_MAJOR,
    val abi: String = "",
    val minMemMb: Int = 1024,
    val maxMemMb: Int = 2048,
    val jvmArgs: String = "",
    val useSerialGc: Boolean = true,
    val keepAlive: Boolean = true,
    val serverPort: Int = 25565,
    val motd: String = "",
    val maxPlayers: Int = 20,
    val onlineMode: Boolean = true,
    val difficulty: String = "easy",
    val gamemode: String = "survival",
    val levelName: String = "world",
    val createdAt: String = "",
    val updatedAt: String = "",
)

/**
 * 实例目录的**文件补全**：仿照 MSLX Daemon 的创建流程，
 * 下载完核心后把服务端运行所需的其余文件一次补齐（eula、server.properties、
 * 玩家名单 json、运行期目录、实例元数据），使实例目录开箱即可启动。
 *
 * 上游对照：daemon 端 `Servers/<id>/` + `DeployCoreAsync` 落地核心、
 * `AgreeEULA` 写 `eula.txt`（`#By changing the setting below to TRUE ...`），
 * 这里保持一致的文件名与 eula 内容格式。
 */
object ServerFiles {

    const val SERVER_JAR_NAME = "server.jar"
    const val EULA_NAME = "eula.txt"
    const val PROPERTIES_NAME = "server.properties"
    const val META_NAME = "instance.json"
    const val DEFAULT_SERVER_NAME = "本地服务器"

    /** 需要预建的运行期目录（按核心类型补充 plugins/mods）。 */
    private val BASE_DIRS = listOf("logs", "cache")
    private val PLUGIN_CORES = listOf("paper", "spigot", "bukkit", "purpur", "folia", "pufferfish", "leaves", "mohist", "magma", "cat", "arclight")
    private val MOD_CORES = listOf("fabric", "forge", "neoforge", "quilt", "vanilla-forge")

    /** 服务端首次启动前必须存在的空名单文件（vanilla 生成，提前补齐避免启动期写权限/顺序问题）。 */
    private val EMPTY_JSON_FILES = listOf(
        "ops.json",
        "whitelist.json",
        "banned-players.json",
        "banned-ips.json",
    )

    /** 核心名是否属于插件服务端（决定是否预建 plugins/）。 */
    fun isPluginCore(core: String): Boolean {
        val c = core.lowercase()
        return PLUGIN_CORES.any { c.contains(it) }
    }

    fun isModCore(core: String): Boolean {
        val c = core.lowercase()
        return MOD_CORES.any { c.contains(it) }
    }

    /**
     * 补全实例目录：[dir] 为核心所在实例目录，[meta] 为实例参数。
     * 已存在的文件一律不覆盖（保护用户改动），只补缺失项。
     */
    fun complete(dir: File, meta: LocalInstanceMeta): Result<LocalInstanceMeta> = runCatching {
        dir.mkdirs()
        val now = LocalStorage.nowIso()
        val written = mutableListOf<String>()

        // 1. 运行期目录
        (BASE_DIRS +
            (if (isPluginCore(meta.core)) listOf("plugins") else emptyList()) +
            (if (isModCore(meta.core)) listOf("mods") else emptyList())).forEach { name ->
            val d = File(dir, name)
            if (!d.exists() && d.mkdirs()) written += "$name/"
        }

        // 2. eula.txt（内容格式与 daemon AgreeEULA 一致）
        val eula = File(dir, EULA_NAME)
        if (!eula.exists()) {
            eula.writeText(
                "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n" +
                    "#$now\n" +
                    "eula=true\n",
            )
            written += EULA_NAME
        }

        // 3. server.properties（缺失才写，带常见键的默认值；其余键由服务端首次启动补全）
        val properties = File(dir, PROPERTIES_NAME)
        if (!properties.exists()) {
            properties.writeText(renderServerProperties(meta, now))
            written += PROPERTIES_NAME
        }

        // 4. 空名单/封禁名单
        EMPTY_JSON_FILES.forEach { name ->
            val f = File(dir, name)
            if (!f.exists()) {
                f.writeText("[]\n")
                written += name
            }
        }

        // 5. 实例元数据
        val finalMeta = meta.copy(
            directory = dir.name,
            serverJar = SERVER_JAR_NAME,
            createdAt = meta.createdAt.ifBlank { now },
            updatedAt = now,
        )
        File(dir, META_NAME).writeText(
            GsonBuilder().setPrettyPrinting().create().toJson(finalMeta) + "\n",
        )
        written += META_NAME

        AppLogger.i("ServerFiles", "实例 ${dir.name} 已补全文件：${written.joinToString(", ")}")
        finalMeta
    }

    /** 读取实例元数据（无则返回 null）。 */
    fun readMeta(dir: File): LocalInstanceMeta? = runCatching {
        val f = File(dir, META_NAME)
        if (!f.isFile) return null
        com.google.gson.Gson().fromJson(f.readText(), LocalInstanceMeta::class.java)
    }.getOrNull()

    /** 供 UI 展示的实例目录清单（文件名 → 是否就绪）。 */
    fun listInstanceFiles(dir: File): List<Pair<String, Boolean>> {
        val expected = buildList {
            add(SERVER_JAR_NAME)
            add(PROPERTIES_NAME)
            add(EULA_NAME)
            add(META_NAME)
            addAll(EMPTY_JSON_FILES)
            add("logs/")
            if (isPluginCore(readMeta(dir)?.core.orEmpty())) add("plugins/")
            if (isModCore(readMeta(dir)?.core.orEmpty())) add("mods/")
        }
        return expected.map { name -> name to File(dir, name.trimEnd('/')).exists() }
    }

    /** 生成 server.properties：vanilla 常见键 + 用户配置值，格式与服务端自身写出的保持一致。 */
    private fun renderServerProperties(meta: LocalInstanceMeta, timestamp: String): String {
        val motd = meta.motd.ifBlank { meta.name.ifBlank { DEFAULT_SERVER_NAME } }
        return buildString {
            appendLine("#Minecraft server properties")
            appendLine("#$timestamp")
            appendLine("#由 MSLX-Android 本机开服补全，键值含义见 https://minecraft.wiki/w/Server.properties")
            appendLine("enable-jmx-monitoring=false")
            appendLine("rcon.port=25575")
            appendLine("level-seed=")
            appendLine("gamemode=${meta.gamemode}")
            appendLine("enable-command-block=false")
            appendLine("enable-query=false")
            appendLine("generator-settings={}")
            appendLine("enforce-secure-profile=true")
            appendLine("level-name=${meta.levelName}")
            appendLine("motd=$motd")
            appendLine("no-chat-reports=false")
            appendLine("pvp=true")
            appendLine("generate-structures=true")
            appendLine("max-chained-neighbor-updates=1000000")
            appendLine("difficulty=${meta.difficulty}")
            appendLine("network-compression-threshold=256")
            appendLine("max-tick-time=60000")
            appendLine("require-resource-pack=false")
            appendLine("use-native-transport=true")
            appendLine("enable-status=true")
            appendLine("online-mode=${meta.onlineMode}")
            appendLine("enable-pvp=true")
            appendLine("broadcast-rcon-to-ops=true")
            appendLine("view-distance=10")
            appendLine("max-build-height=320")
            appendLine("server-ip=")
            appendLine("allow-nether=true")
            appendLine("server-port=${meta.serverPort}")
            appendLine("enable-rcon=false")
            appendLine("sync-chunk-writes=true")
            appendLine("op-permission-level=4")
            appendLine("prevent-proxy-connections=false")
            appendLine("hide-online-players=false")
            appendLine("resource-pack=")
            appendLine("entity-broadcast-range-percentage=100")
            appendLine("simulation-distance=10")
            appendLine("rcon.password=")
            appendLine("player-idle-timeout=0")
            appendLine("force-gamemode=false")
            appendLine("rate-limit=0")
            appendLine("hardcore=false")
            appendLine("white-list=false")
            appendLine("broadcast-console-to-ops=true")
            appendLine("spawn-npcs=true")
            appendLine("spawn-animals=true")
            appendLine("log-ips=true")
            appendLine("function-permission-level=2")
            appendLine("initial-disabled-packs=")
            appendLine("level-type=minecraft\\:normal")
            appendLine("spawn-monsters=true")
            appendLine("enforce-whitelist=false")
            appendLine("spawn-protection=16")
            appendLine("resource-pack-sha1=")
            appendLine("max-world-size=29999984")
            appendLine("max-players=${meta.maxPlayers}")
        }
    }
}
