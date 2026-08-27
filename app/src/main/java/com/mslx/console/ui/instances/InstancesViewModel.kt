package com.mslx.console.ui.instances

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.model.InstanceSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InstancesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: String? = null,
    val error: String? = null,
    val instances: List<InstanceSummary> = emptyList(),
)

class InstancesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = getApplication<MSLXApplication>().container.instanceRepository

    private val _state = MutableStateFlow(InstancesUiState())
    val state = _state.asStateFlow()

    init {
        refresh(initial = true)
    }

    fun delete(instance: InstanceSummary, deleteFiles: Boolean, onDone: () -> Unit) {
        if (_state.value.deleting) return
        _state.update { it.copy(deleting = true, deleteError = null) }
        viewModelScope.launch {
            repository.deleteInstance(instance.id, deleteFiles).fold(
                onSuccess = {
                    _state.update { it.copy(deleting = false, deleteError = null) }
                    onDone()
                    refresh()
                },
                onFailure = { e ->
                    _state.update { it.copy(deleting = false, deleteError = e.message ?: "删除失败") }
                },
            )
        }
    }

    fun refresh(initial: Boolean = false) {
        if (initial) {
            _state.update { it.copy(loading = true, error = null) }
        } else {
            _state.update { it.copy(refreshing = true, error = null) }
        }
        viewModelScope.launch {
            repository.listInstances().fold(
                onSuccess = { list ->
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = null, instances = list)
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = e.message ?: "加载失败",
                        )
                    }
                },
            )
        }
    }
}
