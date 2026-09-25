package com.mslx.console.ui.connect

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.DaemonConfig
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.model.PairCodeData
import com.mslx.console.data.model.PairCodeRequest
import com.mslx.console.data.model.PairRedeemRequest
import com.mslx.console.data.remote.ApiClient
import com.mslx.console.data.remote.PairingPayloadCodec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.UUID

data class ConnectUiState(
    val editingId: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val allowHttp: Boolean = false,
    val loading: Boolean = false,
    val autoChecking: Boolean = false,
    val error: String? = null,
    /** 扫码配对兑换中。 */
    val pairing: Boolean = false,
    /** 正在生成配对二维码。 */
    val generating: Boolean = false,
    /** 生成成功的配对码（非空时界面弹二维码窗）。 */
    val pairCode: PairCodeData? = null,
)

class ConnectViewModel(
    application: Application,
    private val autoConnect: Boolean = true,
    private val editingDaemonId: String? = null,
) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container
    /**
     * 连通性测试用的一次性仓储：绝不复用注册表里的实例仓储，
     * 否则编辑某台 Daemon 时会把未保存的地址写进正在使用的连接（去主连接后的隔离）。
     */
    private val probeRepository = InstanceRepository()
    private val store = container.settingsStore

    private val _state = MutableStateFlow(ConnectUiState())
    val state = _state.asStateFlow()

    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val connected = _connected.asSharedFlow()

    /** 启动自动连接失败事件（携带错误信息），由界面回退主页并弹窗提示。 */
    private val _autoConnectFailed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val autoConnectFailed = _autoConnectFailed.asSharedFlow()

    init {
        if (autoConnect || editingDaemonId != null) {
            viewModelScope.launch {
                val settings = runCatching { store.settingsFlow.first() }
                    .onFailure { com.mslx.console.data.AppLogger.w("Connect", "读取设置失败", it) }
                    .getOrNull()
                val target = settings?.daemons?.firstOrNull { it.id == editingDaemonId }
                    ?: settings?.activeDaemon
                if (target != null) {
                    _state.update {
                        it.copy(
                            editingId = target.id,
                            name = target.name,
                            baseUrl = target.baseUrl,
                            apiKey = target.apiKey,
                            allowHttp = target.allowHttp,
                        )
                    }
                    // 已有激活的 Daemon → 自动连接
                    if (autoConnect) doConnect(target, auto = true)
                }
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }
    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrl = value, error = null) }
    fun onApiKeyChange(value: String) = _state.update { it.copy(apiKey = value, error = null) }
    fun onAllowHttpChange(value: Boolean) = _state.update { it.copy(allowHttp = value, error = null) }

    fun connect() {
        val s = _state.value
        val baseUrl = normalizeBaseUrl(s.baseUrl, s.allowHttp)
        val apiKey = s.apiKey.trim()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            _state.update { it.copy(error = "请填写完整的 Daemon 地址和 API Key。") }
            return
        }
        val name = s.name.trim().ifBlank { baseUrl }
        val config = DaemonConfig(
            id = s.editingId ?: UUID.randomUUID().toString(),
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            allowHttp = s.allowHttp,
        )
        doConnect(config)
    }

    /**
     * 规范化 Daemon 地址：默认强制 HTTPS（http 自动升级）；用户勾选"允许 HTTP"后保留明文地址。
     */
    private fun normalizeBaseUrl(input: String, allowHttp: Boolean): String =
        ApiClient.normalizeDaemonUrl(input, allowHttp)

    private fun doConnect(config: DaemonConfig, auto: Boolean = false) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, autoChecking = auto, error = null) }
        viewModelScope.launch {
            val settings = runCatching { store.settingsFlow.first() }
                .onFailure { com.mslx.console.data.AppLogger.w("Connect", "读取设置失败", it) }
                .getOrNull()
            val duplicate = settings?.daemons?.any {
                it.id != config.id &&
                    ApiClient.normalizeDaemonUrl(it.baseUrl, it.allowHttp).equals(
                        ApiClient.normalizeDaemonUrl(config.baseUrl, config.allowHttp),
                        ignoreCase = true,
                    ) &&
                    it.apiKey == config.apiKey
            } == true
            if (duplicate) {
                _state.update { it.copy(loading = false, autoChecking = false, error = "同一 API Key 已连接此 Daemon，不能重复添加。") }
                return@launch
            }
            // 地址已由 normalize 规范化（默认升级 https，勾选允许 HTTP 后保留明文）
            val result = runCatching {
                probeRepository.configure(config.baseUrl, config.apiKey, config.allowHttp)
                probeRepository.verify()
            }
            if (result.isSuccess) {
                // 持久化失败不应阻断本次连接（验证通过后由注册表下次 sync 建立正式连接）
                runCatching { store.upsertDaemon(config) }
                    .onFailure { com.mslx.console.data.AppLogger.w("Connect", "保存 Daemon 配置失败", it) }
                // 立刻把新配置同步进注册表，使主页/二级页无需等 5 秒心跳即可拿到可用连接
                runCatching { container.daemonRegistry.sync(store.settingsFlow.first()) }
                    .onFailure { com.mslx.console.data.AppLogger.w("Connect", "同步 Daemon 连接失败", it) }
                _state.update { it.copy(loading = false, autoChecking = false) }
                _connected.tryEmit(Unit)
            } else {
                val message = "连接失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                _state.update {
                    it.copy(loading = false, autoChecking = false, error = message)
                }
                // 启动自动连接失败：通知界面回退主页并弹窗，而不是停留在连接页卡住
                if (auto) {
                    _autoConnectFailed.tryEmit(message)
                }
            }
        }
    }

    // ---------------- 扫码配对（MSLX Android 扩展插件，沿用兼容接口） ----------------

    /**
     * 处理扫码结果：解析 `mslxp1:` 载荷 → 调用插件 redeem 端点兑换一次性受限 API Key →
     * 自动填入表单并立即校验连接。失败时给出可执行的提示，而不是只抛异常文案。
     */
    fun onScanResult(contents: String?) {
        if (contents.isNullOrBlank()) return // 用户取消扫码，不提示
        val payload = PairingPayloadCodec.decode(contents)
        if (payload == null) {
            _state.update { it.copy(error = "无法识别的二维码：请扫描 Daemon 端「扫码配对」生成的配对二维码。") }
            return
        }
        val url = payload.url?.trim().orEmpty()
        if (payload.version != 1 || url.isBlank() || payload.code.isNullOrBlank()) {
            _state.update { it.copy(error = "配对二维码内容不完整，请在服务端重新生成。") }
            return
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            _state.update { it.copy(error = "配对二维码中的 Daemon 地址无效，请在服务端重新生成。") }
            return
        }
        if (payload.expiresAt > 0 && payload.expiresAt * 1000L < System.currentTimeMillis()) {
            _state.update { it.copy(error = "配对二维码已过期，请在服务端重新生成。") }
            return
        }
        if (_state.value.pairing || _state.value.loading) return
        _state.update { it.copy(pairing = true, error = null) }

        viewModelScope.launch {
            val app = getApplication<Application>()
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android 设备" }
            // ANDROID_ID 无需权限；同一 App 在同一设备上稳定，供服务端识别同机重复配对并替换旧凭据
            val fingerprint = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let { "android-$it" }
            // 兑换用未认证客户端：redeem 是插件唯一的匿名端点，内部做签名/时效/一次性/IP 限速校验
            val api = ApiClient.build(url, "")
            runCatching { api.pairRedeem(PairRedeemRequest(contents, deviceName, fingerprint)) }
                .onSuccess { resp ->
                    val data = resp.data
                    if (resp.code != 200 || data?.apiKey.isNullOrBlank()) {
                        _state.update {
                            it.copy(pairing = false, error = "扫码配对失败：${resp.message ?: "服务端未返回有效凭据"}")
                        }
                        return@onSuccess
                    }
                    val daemonUrl = data.daemonUrl?.takeIf { it.isNotBlank() } ?: url
                    val isHttp = daemonUrl.startsWith("http://", ignoreCase = true)
                    _state.update {
                        it.copy(
                            name = it.name.trim().ifBlank { deviceName },
                            baseUrl = daemonUrl,
                            apiKey = data.apiKey,
                            allowHttp = isHttp,
                            pairing = false,
                            error = null,
                        )
                    }
                    com.mslx.console.data.AppLogger.i("Connect", "扫码配对成功 role=${data.role} 凭据到期 ${data.expiresAt}")
                    if (isHttp) {
                        // 明文地址不静默自动连接：与手填流程一致，让用户知情后再点"连接"
                        _state.update {
                            it.copy(error = "配对成功，但该 Daemon 使用 HTTP 明文地址；已勾选「允许 HTTP 连接」，请确认风险后点击连接。")
                        }
                    } else {
                        connect()
                    }
                }
                .onFailure { e ->
                    val detail = when ((e as? HttpException)?.code()) {
                        404 -> "服务端未安装 MSLX Android 扩展插件，或插件版本过旧。"
                        else -> ApiClient.errorMessageFrom(e) ?: e.message ?: "网络异常"
                    }
                    com.mslx.console.data.AppLogger.w("Connect", "扫码配对失败", e)
                    _state.update { it.copy(pairing = false, error = "扫码配对失败：$detail") }
                }
        }
    }

    /** 生成一次性配对二维码（使用当前表单里的管理员凭据，供另一台设备扫码接入）。 */
    fun createPairCode() {
        val s = _state.value
        val baseUrl = normalizeBaseUrl(s.baseUrl, s.allowHttp)
        val apiKey = s.apiKey.trim()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            _state.update { it.copy(error = "请先填写 Daemon 地址与 API Key，再生成配对二维码。") }
            return
        }
        if (s.generating) return
        _state.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            runCatching { ApiClient.build(baseUrl, apiKey).pairCreateCode(PairCodeRequest()) }
                .onSuccess { resp ->
                    val data = resp.data
                    if (resp.code != 200 || data?.payload.isNullOrBlank()) {
                        _state.update {
                            it.copy(generating = false, error = "生成配对码失败：${resp.message ?: "服务端未返回配对码"}")
                        }
                    } else {
                        _state.update { it.copy(generating = false, pairCode = data) }
                    }
                }
                .onFailure { e ->
                    val detail = when ((e as? HttpException)?.code()) {
                        404 -> "服务端未安装 MSLX Android 扩展插件，或插件版本过旧。"
                        403 -> "当前 API Key 权限不足：生成配对码需要管理员权限。"
                        else -> ApiClient.errorMessageFrom(e) ?: e.message ?: "网络异常"
                    }
                    _state.update { it.copy(generating = false, error = "生成配对码失败：$detail") }
                }
        }
    }

    /** 关闭配对二维码弹窗。 */
    fun dismissPairCode() = _state.update { it.copy(pairCode = null) }
}
