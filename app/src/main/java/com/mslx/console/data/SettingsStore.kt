package com.mslx.console.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** 一个 Daemon 连接配置。 */
data class DaemonConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    /** 允许 http:// 明文连接（勾选并经警告确认后才会保留明文地址）。 */
    val allowHttp: Boolean = false,
)

enum class ThemeMode { DYNAMIC, SEED }

/** 默认主题色：硫磺史莱姆黄绿（与 ui.theme.DEFAULT_SEED_COLOR 保持一致）。 */
const val DEFAULT_SEED_COLOR = 0xFF9FA83A

/**
 * 1.7.2 及以前的默认主题色(青蓝 0xFF00838F)。
 * 老版本未写盘时按默认值兜底，因此读到该值且无迁移标记时视为「未自定义」，
 * 一次性升级到 [DEFAULT_SEED_COLOR]；迁移后用户再显式选择青蓝预设不受影响。
 */
private const val LEGACY_DEFAULT_SEED_COLOR = 0xFF00838F

/** 玻璃面板默认不透明度（与 ui.theme.DEFAULT_GLASS_ALPHA 保持一致）。 */
private const val DEFAULT_GLASS_ALPHA = 0.78f

/** 更新渠道：稳定版(默认) / 测试版(Beta) / Actions 调试构建。 */
enum class UpdateChannel { STABLE, BETA, ACTIONS }

/** 应用全局设置(主题 + 多 Daemon + 更新渠道 + 引导状态 + 本机开服默认值)。 */
data class AppSettings(
    val daemons: List<DaemonConfig> = emptyList(),
    val activeDaemonId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SEED,
    val seedColor: Long = DEFAULT_SEED_COLOR,
    /** 毛玻璃面板不透明度（0.25-1.0，1.0 为不透明）。 */
    val glassAlpha: Float = DEFAULT_GLASS_ALPHA,
    /** 浅色模式自定义背景图路径（filesDir/theme 下）；空串表示未设置。 */
    val lightBackgroundPath: String = "",
    /** 深色模式自定义背景图路径；空串表示未设置。 */
    val darkBackgroundPath: String = "",
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val onboarded: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    // 本机开服默认参数（本地页可临时覆盖）
    val localMinMemMb: Int = 1024,
    val localMaxMemMb: Int = 2048,
    val localJvmArgs: String = "",
    val localKeepAlive: Boolean = true,
    val localUseSerialGc: Boolean = true,
    /** 本机开服增强模式：Shizuku 可用时以 shell 权限 exec 真正的 java 子进程（多实例/可重启/跨版本）。 */
    val localUseShizuku: Boolean = false,
    /** Daemon 配置解析失败（原始 JSON 已备份且已记日志），设置页据此给出提示。 */
    val daemonDecodeFailed: Boolean = false,
) {
    val activeDaemon: DaemonConfig?
        get() = daemons.firstOrNull { it.id == activeDaemonId }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private val gson = Gson()
    private val mutex = Mutex()

    /** 损坏配置的异步备份专用作用域（读写 SettingsStore 自身不持有协程作用域）。 */
    private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 备份只需一次：进程内首次发现解析失败时触发，避免每次 DataStore 发射都重复写盘。 */
    @Volatile
    private var corruptBackupScheduled = false

    private object Keys {
        val DAEMONS = stringPreferencesKey("daemons")
        /** 解析失败的 daemons 原始 JSON 备份（独立 key，不参与业务流程）。 */
        val DAEMONS_BACKUP = stringPreferencesKey("daemons_backup_corrupt")
        val ACTIVE_DAEMON = stringPreferencesKey("active_daemon")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SEED_COLOR = longPreferencesKey("seed_color")
        /** 主题色迁移标记：置位后老默认青蓝不再被重写为品牌新默认色。 */
        val SEED_COLOR_MIGRATED = booleanPreferencesKey("seed_color_migrated")
        val GLASS_ALPHA = floatPreferencesKey("glass_alpha")
        val BACKGROUND_LIGHT = stringPreferencesKey("background_light")
        val BACKGROUND_DARK = stringPreferencesKey("background_dark")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")
        val LOCAL_MIN_MEM = intPreferencesKey("local_min_mem")
        val LOCAL_MAX_MEM = intPreferencesKey("local_max_mem")
        val LOCAL_JVM_ARGS = stringPreferencesKey("local_jvm_args")
        val LOCAL_KEEP_ALIVE = booleanPreferencesKey("local_keep_alive")
        val LOCAL_USE_SERIAL_GC = booleanPreferencesKey("local_use_serial_gc")
        val LOCAL_USE_SHIZUKU = booleanPreferencesKey("local_use_shizuku")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val rawDaemons = prefs[Keys.DAEMONS]
        val decoded = rawDaemons?.let(::decodeDaemons)
        if (decoded?.failed == true) backupCorruptDaemons(rawDaemons)
        AppSettings(
            daemons = decoded?.daemons ?: emptyList(),
            activeDaemonId = prefs[Keys.ACTIVE_DAEMON]?.takeIf { it.isNotBlank() },
            themeMode = if (prefs[Keys.THEME_MODE] == "dynamic") ThemeMode.DYNAMIC else ThemeMode.SEED,
            // 老版本默认色(青蓝)在迁移前视为「未自定义」，统一升级为硫磺史莱姆品牌色。
            seedColor = prefs[Keys.SEED_COLOR]?.takeIf {
                it != LEGACY_DEFAULT_SEED_COLOR || prefs[Keys.SEED_COLOR_MIGRATED] == true
            } ?: DEFAULT_SEED_COLOR,
            glassAlpha = prefs[Keys.GLASS_ALPHA] ?: DEFAULT_GLASS_ALPHA,
            lightBackgroundPath = prefs[Keys.BACKGROUND_LIGHT].orEmpty(),
            darkBackgroundPath = prefs[Keys.BACKGROUND_DARK].orEmpty(),
            updateChannel = when (prefs[Keys.UPDATE_CHANNEL]) {
                "beta" -> UpdateChannel.BETA
                "actions" -> UpdateChannel.ACTIONS
                else -> UpdateChannel.STABLE
            },
            onboarded = prefs[Keys.ONBOARDED] ?: false,
            disclaimerAccepted = prefs[Keys.DISCLAIMER_ACCEPTED] ?: false,
            localMinMemMb = prefs[Keys.LOCAL_MIN_MEM] ?: 1024,
            localMaxMemMb = prefs[Keys.LOCAL_MAX_MEM] ?: 2048,
            localJvmArgs = prefs[Keys.LOCAL_JVM_ARGS].orEmpty(),
            localKeepAlive = prefs[Keys.LOCAL_KEEP_ALIVE] ?: true,
            localUseSerialGc = prefs[Keys.LOCAL_USE_SERIAL_GC] ?: true,
            localUseShizuku = prefs[Keys.LOCAL_USE_SHIZUKU] ?: false,
            daemonDecodeFailed = decoded?.failed == true,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) = mutex.withLock {
        val next = transform(settingsFlow.first())
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DAEMONS] = encodeDaemons(next.daemons)
            prefs[Keys.ACTIVE_DAEMON] = next.activeDaemonId ?: ""
            prefs[Keys.THEME_MODE] = if (next.themeMode == ThemeMode.SEED) "seed" else "dynamic"
            prefs[Keys.SEED_COLOR] = next.seedColor
            prefs[Keys.SEED_COLOR_MIGRATED] = true
            prefs[Keys.GLASS_ALPHA] = next.glassAlpha
            prefs[Keys.BACKGROUND_LIGHT] = next.lightBackgroundPath
            prefs[Keys.BACKGROUND_DARK] = next.darkBackgroundPath
            prefs[Keys.ONBOARDED] = next.onboarded
            prefs[Keys.DISCLAIMER_ACCEPTED] = next.disclaimerAccepted
            prefs[Keys.UPDATE_CHANNEL] = when (next.updateChannel) {
                UpdateChannel.BETA -> "beta"
                UpdateChannel.ACTIONS -> "actions"
                UpdateChannel.STABLE -> "stable"
            }
            prefs[Keys.LOCAL_MIN_MEM] = next.localMinMemMb
            prefs[Keys.LOCAL_MAX_MEM] = next.localMaxMemMb
            prefs[Keys.LOCAL_JVM_ARGS] = next.localJvmArgs
            prefs[Keys.LOCAL_KEEP_ALIVE] = next.localKeepAlive
            prefs[Keys.LOCAL_USE_SERIAL_GC] = next.localUseSerialGc
            prefs[Keys.LOCAL_USE_SHIZUKU] = next.localUseShizuku
        }
    }

    /**
     * 新增或更新一个 Daemon。
     * 去主连接：只在尚无默认项（首次添加、或默认项已被删）时把该 Daemon 补为默认，
     * 编辑已有 Daemon 不会抢走默认位。
     */
    suspend fun upsertDaemon(config: DaemonConfig) = update { s ->
        val exists = s.daemons.any { it.id == config.id }
        val daemons = if (exists) {
            s.daemons.map { if (it.id == config.id) config else it }
        } else {
            s.daemons + config
        }
        val active = s.activeDaemonId?.takeIf { id -> daemons.any { it.id == id } } ?: config.id
        s.copy(daemons = daemons, activeDaemonId = active)
    }

    suspend fun removeDaemon(id: String) = update { s ->
        val daemons = s.daemons.filter { it.id != id }
        val active = if (s.activeDaemonId == id) daemons.firstOrNull()?.id else s.activeDaemonId
        s.copy(daemons = daemons, activeDaemonId = active)
    }

    suspend fun setActiveDaemon(id: String) = update { it.copy(activeDaemonId = id) }

    suspend fun setTheme(mode: ThemeMode, seedColor: Long) =
        update { it.copy(themeMode = mode, seedColor = seedColor) }

    /** 保存毛玻璃面板不透明度。 */
    suspend fun setGlassAlpha(alpha: Float) = update { it.copy(glassAlpha = alpha) }

    /** 保存自定义背景图路径（[dark] 选择深浅模式槽位）。 */
    suspend fun setThemeBackground(dark: Boolean, path: String) = update {
        if (dark) it.copy(darkBackgroundPath = path) else it.copy(lightBackgroundPath = path)
    }

    /** 恢复毛玻璃默认：清空背景图并回到默认不透明度。 */
    suspend fun resetGlassAppearance() = update {
        it.copy(
            glassAlpha = DEFAULT_GLASS_ALPHA,
            lightBackgroundPath = "",
            darkBackgroundPath = "",
        )
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) =
        update { it.copy(updateChannel = channel) }

    /** 保存本机开服的默认性能参数。 */
    suspend fun setLocalServer(
        minMemMb: Int,
        maxMemMb: Int,
        jvmArgs: String,
        keepAlive: Boolean,
        useSerialGc: Boolean,
    ) = update {
        it.copy(
            localMinMemMb = minMemMb,
            localMaxMemMb = maxMemMb,
            localJvmArgs = jvmArgs,
            localKeepAlive = keepAlive,
            localUseSerialGc = useSerialGc,
        )
    }

    suspend fun markOnboarded() = update { it.copy(onboarded = true) }

    /** 开关本机开服增强模式（Shizuku exec）。 */
    suspend fun setLocalUseShizuku(enabled: Boolean) = update { it.copy(localUseShizuku = enabled) }

    /** 用户已同意第三方免责声明。 */
    suspend fun acceptDisclaimer() = update { it.copy(disclaimerAccepted = true) }

    private fun encodeDaemons(daemons: List<DaemonConfig>): String =
        gson.toJson(
            daemons.map {
                // 加密失败（fail-closed）：清空 apiKey，禁止明文落盘
                it.copy(apiKey = CryptoManager.encrypt(it.apiKey) ?: "")
            },
        )

    private data class DecodedDaemons(val daemons: List<DaemonConfig>, val failed: Boolean)

    /**
     * 解析 daemons JSON；失败标记 [DecodedDaemons.failed]（不再静默返回空列表），
     * 由 [settingsFlow] 负责备份原始 JSON、记日志并在设置页给出提示。
     */
    private fun decodeDaemons(json: String): DecodedDaemons {
        if (json.isBlank()) return DecodedDaemons(emptyList(), failed = false)
        val parsed = runCatching {
            gson.fromJson<List<DaemonConfig>>(json, object : TypeToken<List<DaemonConfig>>() {}.type)
        }.getOrNull() ?: return DecodedDaemons(emptyList(), failed = true)
        return DecodedDaemons(
            parsed.map {
                // 解密失败（如备份恢复后 Keystore 密钥丢失）时清空 apiKey，避免把密文当明文
                it.copy(apiKey = CryptoManager.decrypt(it.apiKey).orEmpty())
            },
            failed = false,
        )
    }

    /** 解析失败：备份原始 JSON + 记错误日志（只执行一次），供设置页提示与后续排查。 */
    private fun backupCorruptDaemons(rawJson: String) {
        if (corruptBackupScheduled) return
        corruptBackupScheduled = true
        AppLogger.e(
            "Settings",
            "Daemon 配置解析失败：原始数据（${rawJson.length} 字符）已备份到 ${Keys.DAEMONS_BACKUP.name}，请在设置页重新添加连接",
        )
        backupScope.launch {
            runCatching {
                context.settingsDataStore.edit { prefs -> prefs[Keys.DAEMONS_BACKUP] = rawJson }
            }.onFailure { AppLogger.w("Settings", "备份损坏的 Daemon 配置失败", it) }
        }
    }
}
