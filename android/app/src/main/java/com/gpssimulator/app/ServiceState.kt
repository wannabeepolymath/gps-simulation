package com.gpssimulator.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Direction { Forward, Reverse }

sealed class RunState {
    data object Idle : RunState()
    data class Running(
        val fileName: String,
        val elapsedSeconds: Long,
        val totalSeconds: Long,
        /** Currently at one of the endpoints (start when reverse, end when forward). */
        val holdingLastPoint: Boolean,
        val paused: Boolean = false,
        val direction: Direction = Direction.Forward,
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
