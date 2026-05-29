package com.gpssimulator.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class RunState {
    data object Idle : RunState()
    data class Running(
        val fileName: String,
        val elapsedSeconds: Long,
        val totalSeconds: Long,
        val holdingLastPoint: Boolean,
    ) : RunState()
    data class Failed(val message: String) : RunState()
}

object ServiceState {
    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    fun set(state: RunState) {
        _state.value = state
    }
}
