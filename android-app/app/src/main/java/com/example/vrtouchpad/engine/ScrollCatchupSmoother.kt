package com.example.vrtouchpad.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScrollCatchupSmoother(
    private val scope: CoroutineScope,
    private val tickMs: Long = 10L,
    private val ticks: Int = 3,
    private val emitDelta: (Float) -> Unit,
) {
    private var job: Job? = null

    fun start(totalDelta: Float) {
        job?.cancel()
        if (totalDelta == 0f) return

        job = scope.launch {
            val weights = List(ticks) { i -> (ticks - i).toFloat() } // 遞減權重：前面補多、後面補少
            val weightSum = weights.sum()
            for (w in weights) {
                emitDelta(totalDelta * (w / weightSum))
                delay(tickMs)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}