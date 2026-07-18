package com.example.vrtouchpad.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

sealed class TouchOutEvent {
    data class Move(val dx: Float, val dy: Float) : TouchOutEvent()
    data class Click(val button: String, val action: String) : TouchOutEvent()
    data class Scroll(val dy: Float) : TouchOutEvent()
    data class Gesture(val name: String, val direction: String) : TouchOutEvent()
    data class Keypress(val key: String) : TouchOutEvent()
}

enum class LocalFeedbackType {
    PRESS_LOCK,
    RELEASE_LOCK,
    TICK
}

class GestureEngine(
    private val scope: CoroutineScope,
    private val density: Float,
    private val longPressMs: Long = 200,
    private val emit: (TouchOutEvent) -> Unit,
    private val onLocalFeedback: (LocalFeedbackType) -> Unit = {},
    private val onToggleKeyboard: () -> Unit = {}
) {
    private val slopPx = 8f * density
    private val stillPx = 10f * density
    private val dragStartSlopPx = 1.5f * density // 優化：極小的啟動門檻，僅用於過濾物理雜訊，實現零延遲拖曳
    private val scrollActivationSlopPx = 32f * density
    private val swipeThresholdPx = 48f * density

    private val emitIntervalMs = 10L
    private val multiTapWindowMs = 120L
    private val tapTimeoutMs = 280L

    // 觸覺反饋與防暴衝相關參數
    private val scrollDampeningLimitPx = 35f * density
    private val hapticNotchDistancePx = 24f * density // 可調：越小震動越密集
    private var rawHapticAccumulator = 0f

    private enum class Mode {
        IDLE, MOVE, PREDRAG_WAIT, DRAG, TWO_FINGER_WAIT,
        SCROLL_VERTICAL, SWIPE_HORIZONTAL, THREE_FINGER, FOUR_FINGER
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

    private var horizontalSwipeTriggered = false

    private var threeFingerStartY = 0f
    private var threeFingerSwiped = false

    // 兩指捲動仍保留 catchupSmoother，因為捲動本身需要平滑過渡
    private val catchupSmoother = ScrollCatchupSmoother(scope) { delta ->
        accumulatedScrollDy += delta
    }

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

                if (dragging) {
                    // 已移除非同步的 dragCatchupSmootherX/Y 清理呼叫
                    if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                        emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                        accumulatedDragDx = 0f
                        accumulatedDragDy = 0f
                    }
                    emit(TouchOutEvent.Click("left", "up"))
                    dragging = false
                    onLocalFeedback(LocalFeedbackType.RELEASE_LOCK)
                }

                twoFingerDownTime = System.currentTimeMillis()
                val timeSinceFirst = twoFingerDownTime - firstFingerDownTime
                rightClickCandidate = timeSinceFirst <= multiTapWindowMs

                mode = Mode.TWO_FINGER_WAIT
                transitionedFromMultiTouch = true

                pointers.values.forEach {
                    it.startX = it.x
                    it.startY = it.y
                }

                accumulatedScrollDy = 0f
                rawHapticAccumulator = 0f
                lastEmitTime = System.currentTimeMillis()
            }
            3 -> {
                mode = Mode.THREE_FINGER
                transitionedFromMultiTouch = true

                pointers.values.forEach {
                    it.startX = it.x
                    it.startY = it.y
                }

                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                val p3 = pointers.values.elementAtOrNull(2)
                if (p1 != null && p2 != null && p3 != null) {
                    threeFingerStartY = (p1.y + p2.y + p3.y) / 3f
                }
                threeFingerSwiped = false
            }
            4 -> {
                mode = Mode.FOUR_FINGER
                transitionedFromMultiTouch = true
                pointers.values.forEach {
                    it.startX = it.x
                    it.startY = it.y
                }
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

            Mode.PREDRAG_WAIT -> if (id == firstFingerId && dist(p) >= dragStartSlopPx) {
                dragging = true
                mode = Mode.DRAG
                emit(TouchOutEvent.Click("left", "down"))

                // 優化：直接同步將這段極微小的啟動距離加入累積量，並立即送出，免除任何非同步延遲
                accumulatedDragDx = p.x - p.startX
                accumulatedDragDy = p.y - p.startY
                if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                    emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                    accumulatedDragDx = 0f
                    accumulatedDragDy = 0f
                }
                lastEmitTime = now
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
                    val currentAvgX = (p1.x + p2.x) / 2
                    val currentAvgY = (p1.y + p2.y) / 2
                    val startAvgX = (p1.startX + p2.startX) / 2
                    val startAvgY = (p1.startY + p2.startY) / 2

                    val deltaX = currentAvgX - startAvgX
                    val deltaY = currentAvgY - startAvgY

                    if (max(abs(deltaX), abs(deltaY)) >= scrollActivationSlopPx) {
                        rightClickCandidate = false
                        if (abs(deltaY) > abs(deltaX)) {
                            mode = Mode.SCROLL_VERTICAL
                            catchupSmoother.start(deltaY)
                            triggerRawHaptics(deltaY)
                            lastScrollY = currentAvgY
                        } else {
                            mode = Mode.SWIPE_HORIZONTAL
                            horizontalSwipeTriggered = false
                        }
                    }
                }
            }

            Mode.SCROLL_VERTICAL -> {
                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null) {
                    val currentAvgY = (p1.y + p2.y) / 2
                    val rawDeltaY = currentAvgY - lastScrollY
                    accumulatedScrollDy += rawDeltaY

                    triggerRawHaptics(rawDeltaY)

                    if (now - lastEmitTime >= emitIntervalMs) {
                        if (abs(accumulatedScrollDy) > 0.1f) {
                            val limitedScrollDy = applyScrollDampening(accumulatedScrollDy)
                            emit(TouchOutEvent.Scroll(limitedScrollDy))
                            accumulatedScrollDy = 0f
                        }
                        lastEmitTime = now
                    }
                    lastScrollY = currentAvgY
                }
            }

            Mode.SWIPE_HORIZONTAL -> {
                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null && !horizontalSwipeTriggered) {
                    val currentAvgX = (p1.x + p2.x) / 2
                    val startAvgX = (p1.startX + p2.startX) / 2
                    val deltaX = currentAvgX - startAvgX

                    if (deltaX >= swipeThresholdPx) {
                        emit(TouchOutEvent.Keypress("BROWSER_BACK"))
                        horizontalSwipeTriggered = true
                        onLocalFeedback(LocalFeedbackType.TICK)
                    } else if (deltaX <= -swipeThresholdPx) {
                        emit(TouchOutEvent.Keypress("BROWSER_FORWARD"))
                        horizontalSwipeTriggered = true
                        onLocalFeedback(LocalFeedbackType.TICK)
                    }
                }
            }

            Mode.THREE_FINGER -> {
                if (!threeFingerSwiped) {
                    val p1 = pointers.values.elementAtOrNull(0)
                    val p2 = pointers.values.elementAtOrNull(1)
                    val p3 = pointers.values.elementAtOrNull(2)
                    if (p1 != null && p2 != null && p3 != null) {
                        val currentAvgY = (p1.y + p2.y + p3.y) / 3f
                        val deltaY = currentAvgY - threeFingerStartY

                        if (deltaY >= swipeThresholdPx) {
                            threeFingerSwiped = true
                            emit(TouchOutEvent.Gesture("desktop", "down"))
                        } else if (deltaY <= -swipeThresholdPx) {
                            threeFingerSwiped = true
                            emit(TouchOutEvent.Gesture("desktop", "up"))
                        }
                    }
                }
            }

            Mode.FOUR_FINGER, Mode.IDLE -> Unit
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
                onLocalFeedback(LocalFeedbackType.RELEASE_LOCK)
            }

            Mode.DRAG -> if (id == firstFingerId) {
                // 已移除了 dragCatchupSmoother.cancel() 呼叫
                if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                    emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                }
                emit(TouchOutEvent.Click("left", "up"))
                dragging = false
                mode = Mode.IDLE
                onLocalFeedback(LocalFeedbackType.RELEASE_LOCK)
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

            Mode.SCROLL_VERTICAL -> {
                catchupSmoother.cancel()
                if (abs(accumulatedScrollDy) > 0.1f) {
                    val limitedScrollDy = applyScrollDampening(accumulatedScrollDy)
                    emit(TouchOutEvent.Scroll(limitedScrollDy))
                }
            }

            Mode.SWIPE_HORIZONTAL -> { }

            Mode.THREE_FINGER -> {
                if (!threeFingerSwiped && dist(p) < slopPx * 2f) {
                    emit(TouchOutEvent.Gesture("multitask", "tap"))
                }
                mode = Mode.IDLE
            }

            Mode.FOUR_FINGER -> {
                if (dist(p) < slopPx * 3f) {
                    onLocalFeedback(LocalFeedbackType.TICK)
                    onToggleKeyboard()
                }
                mode = Mode.IDLE
            }

            Mode.IDLE -> Unit
        }

        pointers.remove(id)

        if (pointers.isEmpty()) {
            mode = Mode.IDLE
            firstFingerId = null
            dragging = false
            transitionedFromMultiTouch = false
            horizontalSwipeTriggered = false
            threeFingerSwiped = false
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
        catchupSmoother.cancel()
        // 已移除了 dragCatchupSmoother 的重設呼叫
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
        rawHapticAccumulator = 0f
        horizontalSwipeTriggered = false
        threeFingerStartY = 0f
        threeFingerSwiped = false
    }

    private fun startLongPressWatcher(id: Long) {
        longPressJob = scope.launch {
            delay(longPressMs)
            val p = pointers[id] ?: return@launch
            if (pointers.size == 1 && dist(p) < stillPx) {
                mode = Mode.PREDRAG_WAIT
                onLocalFeedback(LocalFeedbackType.PRESS_LOCK)
            }
        }
    }

    private fun dist(p: Pointer): Float {
        val dx = p.x - p.startX
        val dy = p.y - p.startY
        return sqrt(dx * dx + dy * dy)
    }

    private fun applyScrollDampening(dy: Float): Float {
        val absDy = abs(dy)
        if (absDy <= scrollDampeningLimitPx) return dy

        val extra = absDy - scrollDampeningLimitPx
        val dampenedAbs = scrollDampeningLimitPx + (extra * 0.25f)
        return if (dy > 0) dampenedAbs else -dampenedAbs
    }
    private fun triggerRawHaptics(rawDeltaY: Float) {
        rawHapticAccumulator += abs(rawDeltaY)
        while (rawHapticAccumulator >= hapticNotchDistancePx) {
            onLocalFeedback(LocalFeedbackType.TICK)
            rawHapticAccumulator -= hapticNotchDistancePx
        }
    }
}