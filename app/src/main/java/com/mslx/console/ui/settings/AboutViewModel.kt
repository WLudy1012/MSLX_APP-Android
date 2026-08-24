package com.mslx.console.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.data.remote.ApiClient
import com.mslx.console.data.remote.GitHubContributor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AboutUiState(
    val loading: Boolean = true,
    /** 最近一次 release 的 tag（如 v1.2.13）。 */
    val releaseTag: String = "",
    /** 最近一次 release 的更新说明。 */
    val releaseNotes: String = "",
    /** GitHub 贡献者列表。 */
    val contributors: List<GitHubContributor> = emptyList(),
    val error: String? = null,
)

/** 关于页数据源：从 GitHub 拉取最近 release 说明与贡献者。 */
class AboutViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AboutUiState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (_state.value.loading && _state.value.releaseTag.isNotBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val api = ApiClient.buildGitHubReleaseApi()
                val release = api.latestRelease()
                val contributors = api.contributors()
                _state.update {
                    AboutUiState(
                        loading = false,
                        releaseTag = release.tagName.orEmpty(),
                        releaseNotes = release.body.orEmpty(),
                        contributors = contributors,
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(loading = false, error = e.message ?: "加载失败")
                }
            }
        }
    }
}
