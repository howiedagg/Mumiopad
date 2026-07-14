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
    private val stillPx = 4f * density
    private val threeFingerSwipePx = 24f * density

    // 手勢時間窗參數
    private val multiTapWindowMs = 250L // 兩指落下的時間差，必須小於此值才算「同時」
    private val tapTimeoutMs = 280L     // 手指從落下到抬起的時間，必須小於此值才算「點按」

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

    // 紀錄時間以判斷「同時」與「點按」
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
                firstFingerDownTime = System.currentTimeMillis() // 紀錄第一指落下的時間

                startLongPressWatcher(id)
                transitionedFromMultiTouch = false

                accumulatedDx = 0f
                accumulatedDy = 0f
                lastEmitTime = System.currentTimeMillis()
            }
            2 -> {
                longPressJob?.cancel()
                if (dragging) return

                twoFingerDownTime = System.currentTimeMillis()

                // 嚴格判定「同時」
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
        val lastX = p.x
        val lastY = p.y
        p.x = x
        p.y = y

        when (mode) {
            Mode.MOVE -> if (id == firstFingerId) {
                if (dist(p) >= slopPx) longPressJob?.cancel()

                accumulatedDx += x - lastX
                accumulatedDy += y - lastY

                val now = System.currentTimeMillis()
                if (now - lastEmitTime >= 12) {
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
                accumulatedDragDx += x - lastX
                accumulatedDragDy += y - lastY

                val now = System.currentTimeMillis()
                if (now - lastEmitTime >= 12) {
                    if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                        emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                        accumulatedDragDx = 0f
                        accumulatedDragDy = 0f
                    }
                    lastEmitTime = now
                }
            }

            Mode.TWO_FINGER_WAIT -> {
                if (dist(p) >= slopPx) {
                    rightClickCandidate = false // 移動過大，取消右鍵資格
                }

                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null) {
                    val currentAvgY = (p1.y + p2.y) / 2
                    val startAvgY = (p1.startY + p2.startY) / 2

                    if (abs(currentAvgY - startAvgY) >= slopPx) {
                        mode = Mode.SCROLL
                        rightClickCandidate = false // 進入滾動模式，不觸發任何點擊
                        emit(TouchOutEvent.Scroll(currentAvgY - lastScrollY))
                    }
                    lastScrollY = currentAvgY
                }
            }

            Mode.SCROLL -> {
                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null) {
                    val currentAvgY = (p1.y + p2.y) / 2
                    accumulatedScrollDy += currentAvgY - lastScrollY

                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime >= 16) {
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

        // 1. 先處理當前狀態的釋放事件
        when (oldMode) {
            Mode.MOVE -> if (id == firstFingerId) {
                longPressJob?.cancel()
                if (accumulatedDx != 0f || accumulatedDy != 0f) {
                    emit(TouchOutEvent.Move(accumulatedDx, accumulatedDy))
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
                        // 情況 A：兩指同時放下、快速同時抬起 ──► 觸發右鍵
                        emit(TouchOutEvent.Click("right", "click"))
                    } else if (id != firstFingerId) {
                        // 情況 B：一指原本就按住（firstFingerId），另一指快速單擊（id 抬起且時間短） ──► 觸發左鍵
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

        // 2. 自指針列表中移除已放開的手指
        pointers.remove(id)

        // 3. 多指無縫降階過渡邏輯
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