package com.example.vrtouchpad.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

sealed class TouchOutEvent {
    data class Move(val dx: Float, val dy: Float) : TouchOutEvent()
    data class Click(val button: String, val action: String) : TouchOutEvent()
    data class Scroll(val dy: Float) : TouchOutEvent()
    data class Gesture(val name: String, val direction: String) : TouchOutEvent()
}

class GestureEngine(
    private val scope: CoroutineScope,
    density: Float,
    private val longPressMs: Long = 200,
    private val emit: (TouchOutEvent) -> Unit,
) {
    private val slopPx = 8f * density

    // 【修正】：4dp 太容易被單純的手震觸發（使用者刻意按住不動時，
    // 觸控螢幕回報的座標仍會有 1~3px 級別的自然抖動）。
    // 放寬到 10dp，降低「單指按住等待第二指」時被誤判成拖曳意圖的機率。
    private val stillPx = 10f * density

    // 【新增】：進入「二指滾動」模式專用的門檻，跟 slopPx 脫鉤。
    // 「按住第一指、點擊第二指=左鍵」這個手勢，第二指落下瞬間常有
    // 1~2mm 的自然滑動，用原本給單指點擊/拖曳判斷用的 8dp 太容易誤觸滾動，
    // 一旦誤判成滾動，點擊事件會被整個吃掉且無感（因為滾動量太小看不出來）。
    // 拉高到 24dp，需要更明顯、更像刻意滾動的位移才會觸發。
    private val scrollActivationSlopPx = 32f * density

    private val threeFingerSwipePx = 24f * density

    // 採樣時間窗口 (限制最高 ~100Hz 打包發送，完全避免 Wi-Fi 卡頓與干涉抖動)
    private val emitIntervalMs = 10L
    private val multiTapWindowMs = 120L
    private val tapTimeoutMs = 280L

    private enum class Mode {
        IDLE, MOVE, PREDRAG_WAIT, DRAG, TWO_FINGER_WAIT, SCROLL, THREE_FINGER
    }

    private data class Pointer(
        var x: Float,
        var y: Float,
        var startX: Float,
        var startY: Float
    )

    private val pointers = LinkedHashMap<Long, Pointer>()
    private var mode = Mode.IDLE

    private var longPressJob: Job? = null
    private var firstFingerId: Long? = null
    private var lastScrollY = 0f
    private var rightClickCandidate = false
    private var threeFingerStartX = 0f
    private var dragging = false

    private var firstFingerDownTime = 0L
    private var twoFingerDownTime = 0L

    private var transitionedFromMultiTouch = false

    private var lastEmitTime = 0L
    private var accumulatedDx = 0f
    private var accumulatedDy = 0f
    private var accumulatedDragDx = 0f
    private var accumulatedDragDy = 0f
    private var accumulatedScrollDy = 0f

    fun onDown(id: Long, x: Float, y: Float) {
        pointers[id] = Pointer(x, y, x, y)

        when (pointers.size) {
            1 -> {
                firstFingerId = id
                mode = Mode.MOVE
                firstFingerDownTime = System.currentTimeMillis()

                startLongPressWatcher(id)
                transitionedFromMultiTouch = false

                accumulatedDx = 0f
                accumulatedDy = 0f
                lastEmitTime = System.currentTimeMillis()
            }
            2 -> {
                longPressJob?.cancel()

                // 若手震已誤觸發拖曳，先收尾放開左鍵，再正常往下走二指判斷流程，
                // 而不是直接放棄整個手勢。
                if (dragging) {
                    if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                        emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                        accumulatedDragDx = 0f
                        accumulatedDragDy = 0f
                    }
                    emit(TouchOutEvent.Click("left", "up"))
                    dragging = false
                }

                twoFingerDownTime = System.currentTimeMillis()
                val timeSinceFirst = twoFingerDownTime - firstFingerDownTime
                rightClickCandidate = timeSinceFirst <= multiTapWindowMs

                mode = Mode.TWO_FINGER_WAIT
                transitionedFromMultiTouch = true

                accumulatedScrollDy = 0f
                lastEmitTime = System.currentTimeMillis()

                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null) {
                    lastScrollY = (p1.y + p2.y) / 2
                }
            }
            3 -> {
                mode = Mode.THREE_FINGER
                transitionedFromMultiTouch = true
                threeFingerStartX = x
            }
        }
    }

    fun onMove(id: Long, x: Float, y: Float) {
        val p = pointers[id] ?: return
        val dx = x - p.x
        val dy = y - p.y
        p.x = x
        p.y = y

        val now = System.currentTimeMillis()

        when (mode) {
            Mode.MOVE -> if (id == firstFingerId) {
                if (dist(p) >= slopPx) longPressJob?.cancel()

                accumulatedDx += dx
                accumulatedDy += dy

                if (now - lastEmitTime >= emitIntervalMs) {
                    if (accumulatedDx != 0f || accumulatedDy != 0f) {
                        emit(TouchOutEvent.Move(accumulatedDx, accumulatedDy))
                        accumulatedDx = 0f
                        accumulatedDy = 0f
                    }
                    lastEmitTime = now
                }
            }

            Mode.PREDRAG_WAIT -> if (id == firstFingerId && dist(p) >= stillPx) {
                dragging = true
                mode = Mode.DRAG
                emit(TouchOutEvent.Click("left", "down"))
                accumulatedDragDx = 0f
                accumulatedDragDy = 0f
            }

            Mode.DRAG -> if (id == firstFingerId) {
                accumulatedDragDx += dx
                accumulatedDragDy += dy

                if (now - lastEmitTime >= emitIntervalMs) {
                    if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                        emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                        accumulatedDragDx = 0f
                        accumulatedDragDy = 0f
                    }
                    lastEmitTime = now
                }
            }

            Mode.TWO_FINGER_WAIT -> {
                if (dist(p) >= slopPx) rightClickCandidate = false

                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null) {
                    val currentAvgY = (p1.y + p2.y) / 2
                    val startAvgY = (p1.startY + p2.startY) / 2

                    // 【修正】：改用專用的 scrollActivationSlopPx，
                    // 需要更明顯的位移才判定為「使用者要滾動」，
                    // 避免二指點擊時手指落下的自然小滑動被誤判成滾動意圖。
                    if (abs(currentAvgY - startAvgY) >= scrollActivationSlopPx) {
                        mode = Mode.SCROLL
                        rightClickCandidate = false
                        // 【修正】：不把跨過門檻前累積的位移當成第一筆滾動量送出，
                        // 避免因為門檻變大，滾動剛啟動時畫面明顯跳一下。
                        // lastScrollY 在下面統一更新為 baseline，下一次 onMove 才開始算真正的差值。
                    }
                    lastScrollY = currentAvgY
                }
            }

            Mode.SCROLL -> {
                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null) {
                    val currentAvgY = (p1.y + p2.y) / 2
                    accumulatedScrollDy += (currentAvgY - lastScrollY)

                    if (now - lastEmitTime >= emitIntervalMs) {
                        if (abs(accumulatedScrollDy) > 0.1f) {
                            emit(TouchOutEvent.Scroll(accumulatedScrollDy))
                            accumulatedScrollDy = 0f
                        }
                        lastEmitTime = now
                    }
                    lastScrollY = currentAvgY
                }
            }

            Mode.THREE_FINGER, Mode.IDLE -> Unit
        }
    }

    fun onUp(id: Long) {
        val p = pointers[id] ?: return
        val oldMode = mode

        when (oldMode) {
            Mode.MOVE -> if (id == firstFingerId) {
                longPressJob?.cancel()
                if (accumulatedDx != 0f || accumulatedDy != 0f) {
                    emit(TouchOutEvent.Move(accumulatedDx, accumulatedDy))
                    accumulatedDx = 0f
                    accumulatedDy = 0f
                }
                if (dist(p) < slopPx && !transitionedFromMultiTouch) {
                    emit(TouchOutEvent.Click("left", "click"))
                }
                mode = Mode.IDLE
            }

            Mode.PREDRAG_WAIT -> if (id == firstFingerId) {
                emit(TouchOutEvent.Click("left", "click"))
                mode = Mode.IDLE
            }

            Mode.DRAG -> if (id == firstFingerId) {
                if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                    emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                }
                emit(TouchOutEvent.Click("left", "up"))
                dragging = false
                mode = Mode.IDLE
            }

            Mode.TWO_FINGER_WAIT -> {
                val tapDuration = System.currentTimeMillis() - twoFingerDownTime
                if (tapDuration <= tapTimeoutMs) {
                    if (rightClickCandidate) {
                        emit(TouchOutEvent.Click("right", "click"))
                    } else if (id != firstFingerId) {
                        emit(TouchOutEvent.Click("left", "click"))
                    }
                }
            }

            Mode.SCROLL -> {
                if (abs(accumulatedScrollDy) > 0.1f) {
                    emit(TouchOutEvent.Scroll(accumulatedScrollDy))
                }
            }

            Mode.THREE_FINGER -> {
                val dx = p.x - threeFingerStartX
                if (abs(dx) > threeFingerSwipePx) {
                    emit(TouchOutEvent.Gesture("switch_desktop", if (dx > 0) "right" else "left"))
                }
            }

            Mode.IDLE -> Unit
        }

        pointers.remove(id)

        if (pointers.isEmpty()) {
            mode = Mode.IDLE
            firstFingerId = null
            dragging = false
            transitionedFromMultiTouch = false
        } else if (pointers.size == 1) {
            val remainingId = pointers.keys.first()
            val remainingPointer = pointers[remainingId]
            if (remainingPointer != null) {
                firstFingerId = remainingId
                remainingPointer.startX = remainingPointer.x
                remainingPointer.startY = remainingPointer.y

                mode = Mode.MOVE
                transitionedFromMultiTouch = true

                accumulatedDx = 0f
                accumulatedDy = 0f
                lastEmitTime = System.currentTimeMillis()
            }
        }
    }

    fun reset() {
        longPressJob?.cancel()
        pointers.clear()
        mode = Mode.IDLE
        dragging = false
        firstFingerId = null
        transitionedFromMultiTouch = false
        accumulatedDx = 0f
        accumulatedDy = 0f
        accumulatedDragDx = 0f
        accumulatedDragDy = 0f
        accumulatedScrollDy = 0f
    }

    private fun startLongPressWatcher(id: Long) {
        longPressJob = scope.launch {
            delay(longPressMs)
            val p = pointers[id] ?: return@launch
            if (pointers.size == 1 && dist(p) < stillPx) {
                mode = Mode.PREDRAG_WAIT
            }
        }
    }

    private fun dist(p: Pointer): Float {
        val dx = p.x - p.startX
        val dy = p.y - p.startY
        return sqrt(dx * dx + dy * dy)
    }
}