package com.example.vrtouchpad.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 將「跨過捲動啟動死區(scrollActivationSlopPx)前所累積、原本會被吃掉的位移」
 * 以遞減權重（ease-out）平滑分配到數個 tick 內注入，避免一次性補回造成跳動感。
 *
 * 與 GestureEngine 完全解耦：只透過建構子傳入的 emitDelta callback 溝通，
 * GestureEngine 不需要知道平滑演算法的實作細節，之後要調整補償手感
 * （tick 數、每 tick 間隔、權重曲線）只需要改這個檔案。
 */
class ScrollCatchupSmoother(
    private val scope: CoroutineScope,
    private val tickMs: Long = 10L,
    private val ticks: Int = 3,
    private val emitDelta: (Float) -> Unit,
) {
    private var job: Job? = null

    /**
     * 開始平滑注入補償位移。若前一次補償尚未完成會先取消，
     * 避免快速連續觸發雙指捲動時殘留的補償互相干擾。
     */
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

    /** 立即中止尚未完成的補償注入，不會補發剩餘量。 */
    fun cancel() {
        job?.cancel()
        job = null
    }
}