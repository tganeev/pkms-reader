// File: test-app/src/main/java/org/readium/r2/testapp/reader/ReadingTimer.kt

package org.readium.r2.testapp.reader

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReadingTimer(
    private val scope: CoroutineScope,
    initialTime: Long = 0,
) {
    private val _elapsedSeconds = MutableStateFlow(initialTime)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private var timerJob: Job? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return

        isRunning = true
        timerJob = scope.launch {
            while (isActive && isRunning) {
                delay(1000) // Обновляем каждую секунду
                _elapsedSeconds.value++
            }
        }
    }

    fun pause() {
        if (!isRunning) return

        isRunning = false
        timerJob?.cancel()
        timerJob = null
    }

    fun reset() {
        pause()
        _elapsedSeconds.value = 0
    }

    fun setTime(seconds: Long) {
        _elapsedSeconds.value = seconds
    }

    fun formatTime(): String {
        val hours = _elapsedSeconds.value / 3600
        val minutes = (_elapsedSeconds.value % 3600) / 60
        val seconds = _elapsedSeconds.value % 60

        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            else -> String.format("%d:%02d", minutes, seconds)
        }
    }
}
