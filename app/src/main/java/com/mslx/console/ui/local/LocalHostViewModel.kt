package com.mslx.console.ui.local

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.data.AppLogger
import com.mslx.console.data.localengine.LocalServerProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LocalHostUiState(
    val javaPath: String = "",
    val jarPath: String = "",
    val minMem: Int = 1024,
    val maxMem: Int = 2048,
    val running: Boolean = false,
    val logs: List<String> = emptyList(),
    val message: String? = null,
)

/** 本机开服（P0 实验）ViewModel：配置 java 与 server.jar 路径，启停一个本机服务端。 */
class LocalHostViewModel(application: Application) : AndroidViewModel(application) {

    private val appDir = application.getExternalFilesDir(null) ?: application.filesDir

    private val _state = MutableStateFlow(
        LocalHostUiState(
            javaPath = File(appDir, "jre/bin/java").absolutePath,
            jarPath = File(appDir, "server.jar").absolutePath,
        ),
    )
    val state = _state.asStateFlow()

    private var server: LocalServerProcess? = null

    fun update(transform: (LocalHostUiState) -> LocalHostUiState) = _state.update(transform)

    fun start() {
        val s = _state.value
        if (s.running) return
        val engine = LocalServerProcess(
            javaBin = File(s.javaPath),
            serverJar = File(s.jarPath),
            workDir = File(appDir, "worlds/local"),
            minMemM = s.minMem,
            maxMemM = s.maxMem,
        )
        server = engine
        viewModelScope.launch {
            // 日志流（全局只启动一次收集）
            engine.logs.collect { line ->
                _state.update {
                    it.copy(logs = (it.logs + line).takeLast(500))
                }
            }
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { engine.start() }
            _state.update {
                it.copy(running = ok, message = if (ok) null else "启动失败：请检查 Java 与 server.jar 路径")
            }
            AppLogger.i("LocalHost", "本地引擎启动结果 ok=$ok")
        }
    }

    fun stop() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { server?.stop() }
            _state.update { it.copy(running = false) }
        }
    }

    override fun onCleared() {
        // 实验页关闭不杀服务端，保持运行；如需停止由用户操作
        super.onCleared()
    }
}
