// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/engine/GestureEngine.kt

package com.example.vrtouchpad.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class MouseButton { LEFT, RIGHT, MIDDLE }
enum class ClickAction { DOWN, UP, CLICK }
enum class SystemKey { BROWSER_BACK, BROWSER_FORWARD, VOLUME_UP, VOLUME_DOWN, BACKSPACE, ENTER, LEFT, RIGHT }
enum class GestureType { DESKTOP, MULTITASK }
enum class GestureDirection { UP, DOWN, TAP }

sealed class TouchOutEvent {
    data class Move(val dx: Float, val dy: Float) : TouchOutEvent()
    data class Click(val button: MouseButton, val action: ClickAction) : TouchOutEvent()
    data class Scroll(val dy: Float) : TouchOutEvent()
    data class Zoom(val delta: Float) : TouchOutEvent()
    data class Gesture(val name: GestureType, val direction: GestureDirection) : TouchOutEvent()
    data class Keypress(val key: SystemKey) : TouchOutEvent()
}

enum class LocalFeedbackType {
    PRESS_LOCK,
    RELEASE_LOCK,
    TICK,
    ZOOM_TICK
}

data class GestureConfig(
    val longPressMs: Long = 350L,              // 長按觸發拖曳時間
    val multiTapWindowMs: Long = 120L,         // 多指點擊時間視窗
    val tapTimeoutMs: Long = 280L,             // 點擊判定超時
    val emitIntervalMs: Long = 10L,            // 事件發送間隔
    val movingRelayTimeoutMs: Long = 100L,     // 滑動中抬手的接力視窗 (800ms)
    val stationaryRelayTimeoutMs: Long = 0L,   // 靜止按著抬手的快釋放視窗 (0ms 瞬間釋放)
    val stationaryThresholdMs: Long = 150L     // 靜止防抖時間門檻 (150ms)
)

class GestureEngine(
    private val scope: CoroutineScope,
    private val density: Float,
    private val config: GestureConfig = GestureConfig(),
    private val emit: (TouchOutEvent) -> Unit,
    private val onLocalFeedback: (LocalFeedbackType) -> Unit = {},
    private val onToggleKeyboard: () -> Unit = {},
    private val isKeyboardActive: () -> Boolean = { false },
    private val getScrollSpeed: () -> Float = { 1f }
) {
    private val slopPx = 8f * density                      // 8dp (點擊死區門檻)
    private val stillPx = 10f * density                    // 10dp (靜止防抖區門檻)
    private val dragStartSlopPx = 1.5f * density
    private val swipeActivationSlopPx = 18f * density
    private val scrollActivationSlopPx = 32f * density
    private val zoomActivationSlopPx = 25f * density
    private val zoomHapticDistancePx = 30f * density
    private val swipeThresholdPx = 48f * density
    private val hapticNotchDistancePx = 24f * density

    private var rawHapticAccumulator = 0f
    private var scrollStepAccumulator = 0f
    private var lastHapticTwoFingerDist = 0f

    private var handOffJob: Job? = null
    private var activePointerId: Long? = null

    // 防抖靜止判定變數
    private var lastSignificantMoveTime: Long = 0L         // 最後一次超過 10dp 位移的時間
    private var dragStillAnchorX: Float = 0f                // 靜止防抖基準點 X
    private var dragStillAnchorY: Float = 0f                // 靜止防抖基準點 Y

    private var twoFingerLongPressJob: Job? = null
    private var longPressJob: Job? = null

    private enum class Mode {
        IDLE, MOVE, PREDRAG_WAIT, DRAG, TWO_FINGER_WAIT,
        RIGHT_DRAG, SCROLL_VERTICAL, SWIPE_HORIZONTAL, ZOOM, THREE_FINGER, FOUR_FINGER
    }

    private data class Pointer(
        var x: Float,
        var y: Float,
        var startX: Float,
        var startY: Float
    )

    private val pointers = LinkedHashMap<Long, Pointer>()
    private var mode = Mode.IDLE

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
    private var accumulatedZoomSteps = 0

    private var startTwoFingerDist = 0f
    private var lastTwoFingerDist = 0f

    private var horizontalSwipeTriggered = false

    private var threeFingerStartX = 0f
    private var threeFingerStartY = 0f
    private var threeFingerSwiped = false

    private val catchupSmoother = ScrollCatchupSmoother(scope) { delta ->
        scrollStepAccumulator += delta * getScrollSpeed()
        triggerStepsFromAccumulator()
    }

    private fun triggerStepsFromAccumulator() {
        val hapticThreshold = hapticNotchDistancePx
        val steps = (scrollStepAccumulator / hapticThreshold).toInt()

        if (steps != 0) {
            val absSteps = abs(steps)
            repeat(absSteps) {
                onLocalFeedback(LocalFeedbackType.TICK)
            }
            emit(TouchOutEvent.Scroll(steps.toFloat()))
            scrollStepAccumulator -= steps * hapticThreshold
        }
    }

    private fun executeTwoFingerWaitSetup() {
        if (dragging) {
            if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                accumulatedDragDx = 0f
                accumulatedDragDy = 0f
            }
            emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.UP))
            dragging = false
            onLocalFeedback(LocalFeedbackType.RELEASE_LOCK)
        }

        twoFingerDownTime = System.currentTimeMillis()
        val timeSinceFirst = twoFingerDownTime - firstFingerDownTime
        rightClickCandidate = timeSinceFirst <= config.multiTapWindowMs

        mode = Mode.TWO_FINGER_WAIT
        transitionedFromMultiTouch = true

        pointers.values.forEach {
            it.startX = it.x
            it.startY = it.y
        }

        val p1 = pointers.values.elementAtOrNull(0)
        val p2 = pointers.values.elementAtOrNull(1)
        if (p1 != null && p2 != null) {
            startTwoFingerDist = distBetween(p1, p2)
            lastTwoFingerDist = startTwoFingerDist
            lastHapticTwoFingerDist = startTwoFingerDist
        }

        scrollStepAccumulator = 0f
        accumulatedZoomSteps = 0
        rawHapticAccumulator = 0f
        lastEmitTime = System.currentTimeMillis()

        startTwoFingerLongPressWatcher()
    }

    private fun startTwoFingerLongPressWatcher() {
        twoFingerLongPressJob?.cancel()
        twoFingerLongPressJob = scope.launch {
            delay(config.longPressMs)
            if (mode == Mode.TWO_FINGER_WAIT && pointers.size == 2) {
                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null && dist(p1) < stillPx && dist(p2) < stillPx) {
                    rightClickCandidate = false
                    mode = Mode.DRAG
                    dragging = true

                    val secondFingerId = pointers.keys.elementAtOrNull(1) ?: pointers.keys.last()
                    firstFingerId = secondFingerId
                    activePointerId = secondFingerId

                    val activePointer = pointers[secondFingerId]
                    if (activePointer != null) {
                        activePointer.startX = activePointer.x
                        activePointer.startY = activePointer.y
                        dragStillAnchorX = activePointer.x
                        dragStillAnchorY = activePointer.y
                    }
                    lastSignificantMoveTime = System.currentTimeMillis()

                    emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.DOWN))
                    onLocalFeedback(LocalFeedbackType.PRESS_LOCK)
                }
            }
        }
    }

    fun onDown(id: Long, x: Float, y: Float) {
        pointers[id] = Pointer(x, y, x, y)
        val now = System.currentTimeMillis()

        handOffJob?.cancel()

        when (pointers.size) {
            1 -> {
                firstFingerId = id
                activePointerId = id
                mode = Mode.MOVE
                firstFingerDownTime = now

                startLongPressWatcher(id)
                transitionedFromMultiTouch = false

                accumulatedDx = 0f
                accumulatedDy = 0f
                lastEmitTime = now
            }
            2 -> {
                longPressJob?.cancel()
                twoFingerDownTime = now

                // 💡 關鍵修復：只有在真正的左鍵拖曳 (dragging == true) 狀態下，雙指按下才接管「拖曳接力」
                if (dragging) {
                    activePointerId = id
                    val activeP = pointers[id]
                    if (activeP != null) {
                        activeP.startX = activeP.x
                        activeP.startY = activeP.y
                        dragStillAnchorX = activeP.x
                        dragStillAnchorY = activeP.y
                    }
                    accumulatedDx = 0f
                    accumulatedDy = 0f
                    accumulatedDragDx = 0f
                    accumulatedDragDy = 0f
                    lastEmitTime = now
                    lastSignificantMoveTime = now
                } else {
                    // 💡 普通狀態下雙指按下，一律正常進入 TWO_FINGER_WAIT，釋放所有雙指手勢！
                    executeTwoFingerWaitSetup()
                }
            }
            3 -> {
                longPressJob?.cancel()
                twoFingerLongPressJob?.cancel()

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
                    threeFingerStartX = (p1.x + p2.x + p3.x) / 3f
                    threeFingerStartY = (p1.y + p2.y + p3.y) / 3f
                }
                threeFingerSwiped = false
            }
            4 -> {
                longPressJob?.cancel()
                twoFingerLongPressJob?.cancel()

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
            Mode.MOVE -> if (id == activePointerId || id == firstFingerId) {
                if (dist(p) >= slopPx) longPressJob?.cancel()

                accumulatedDx += dx
                accumulatedDy += dy

                if (now - lastEmitTime >= config.emitIntervalMs) {
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
                activePointerId = id
                dragStillAnchorX = p.x
                dragStillAnchorY = p.y
                lastSignificantMoveTime = now

                emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.DOWN))

                accumulatedDragDx = p.x - p.startX
                accumulatedDragDy = p.y - p.startY
                if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                    emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                    accumulatedDragDx = 0f
                    accumulatedDragDy = 0f
                }
                lastEmitTime = now
            }

            Mode.DRAG -> if (id == activePointerId || (activePointerId == null && id == firstFingerId)) {
                val distFromAnchor = sqrt(
                    (p.x - dragStillAnchorX) * (p.x - dragStillAnchorX) +
                            (p.y - dragStillAnchorY) * (p.y - dragStillAnchorY)
                )

                if (distFromAnchor >= stillPx) {
                    lastSignificantMoveTime = now
                    dragStillAnchorX = p.x
                    dragStillAnchorY = p.y
                }

                accumulatedDragDx += dx
                accumulatedDragDy += dy

                if (now - lastEmitTime >= config.emitIntervalMs) {
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
                    rightClickCandidate = false
                    twoFingerLongPressJob?.cancel()
                }

                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null) {
                    val currentAvgX = (p1.x + p2.x) / 2
                    val currentAvgY = (p1.y + p2.y) / 2
                    val startAvgX = (p1.startX + p2.startX) / 2
                    val startAvgY = (p1.startY + p2.startY) / 2

                    val deltaX = currentAvgX - startAvgX
                    val deltaY = currentAvgY - startAvgY

                    val currentDist = distBetween(p1, p2)
                    val deltaDist = currentDist - startTwoFingerDist

                    val v1x = p1.x - p1.startX
                    val v1y = p1.y - p1.startY
                    val v2x = p2.x - p2.startX
                    val v2y = p2.y - p2.startY

                    val dot = (v1x * v2x) + (v1y * v2y)

                    val isMovingInSameDirection = dot > 0 &&
                            ((v1x * v1x + v1y * v1y) > slopPx * slopPx || (v2x * v2x + v2y * v2y) > slopPx * slopPx)

                    val bothMoved = (v1x * v1x + v1y * v1y) > slopPx * slopPx &&
                            (v2x * v2x + v2y * v2y) > slopPx * slopPx

                    if (abs(deltaDist) >= zoomActivationSlopPx && !isMovingInSameDirection && bothMoved) {
                        rightClickCandidate = false
                        twoFingerLongPressJob?.cancel()
                        mode = Mode.ZOOM
                        lastTwoFingerDist = currentDist
                        lastHapticTwoFingerDist = currentDist
                        triggerZoomHaptics(deltaDist)
                    } else if (abs(deltaY) >= scrollActivationSlopPx && abs(deltaY) > abs(deltaX)) {
                        rightClickCandidate = false
                        twoFingerLongPressJob?.cancel()
                        mode = Mode.SCROLL_VERTICAL
                        catchupSmoother.start(deltaY)
                        lastScrollY = currentAvgY
                    } else if (abs(deltaX) >= swipeActivationSlopPx && abs(deltaX) > abs(deltaY)) {
                        rightClickCandidate = false
                        twoFingerLongPressJob?.cancel()
                        mode = Mode.SWIPE_HORIZONTAL
                        horizontalSwipeTriggered = false
                    }
                }
            }

            Mode.RIGHT_DRAG -> {
                if (id == firstFingerId) {
                    accumulatedDragDx += dx
                    accumulatedDragDy += dy

                    if (now - lastEmitTime >= config.emitIntervalMs) {
                        if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                            emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                            accumulatedDragDx = 0f
                            accumulatedDragDy = 0f
                        }
                        lastEmitTime = now
                    }
                }
            }

            Mode.SCROLL_VERTICAL -> {
                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null) {
                    val currentAvgY = (p1.y + p2.y) / 2
                    val rawDeltaY = currentAvgY - lastScrollY

                    scrollStepAccumulator += rawDeltaY * getScrollSpeed()
                    triggerStepsFromAccumulator()

                    lastScrollY = currentAvgY
                }
            }

            Mode.ZOOM -> {
                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null) {
                    val currentDist = distBetween(p1, p2)
                    lastTwoFingerDist = currentDist

                    val hapticDiff = currentDist - lastHapticTwoFingerDist
                    if (abs(hapticDiff) >= zoomHapticDistancePx) {
                        val ticksCount = (abs(hapticDiff) / zoomHapticDistancePx).toInt()
                        val sign = if (hapticDiff > 0) 1 else -1

                        repeat(ticksCount) {
                            onLocalFeedback(LocalFeedbackType.ZOOM_TICK)
                        }

                        accumulatedZoomSteps += sign * ticksCount
                        lastHapticTwoFingerDist += sign * ticksCount * zoomHapticDistancePx
                    }

                    if (now - lastEmitTime >= config.emitIntervalMs) {
                        if (accumulatedZoomSteps != 0) {
                            emit(TouchOutEvent.Zoom(accumulatedZoomSteps.toFloat()))
                            accumulatedZoomSteps = 0
                        }
                        lastEmitTime = now
                    }
                }
            }

            Mode.SWIPE_HORIZONTAL -> {
                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null && !horizontalSwipeTriggered) {
                    val currentAvgX = (p1.x + p2.x) / 2
                    val startAvgX = (p1.startX + p2.startX) / 2
                    val deltaX = currentAvgX - startAvgX

                    if (abs(deltaX) >= swipeActivationSlopPx) {
                        if (deltaX > 0) {
                            emit(TouchOutEvent.Keypress(SystemKey.RIGHT))
                        } else {
                            emit(TouchOutEvent.Keypress(SystemKey.LEFT))
                        }
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
                        val currentAvgX = (p1.x + p2.x + p3.x) / 3f
                        val currentAvgY = (p1.y + p2.y + p3.y) / 3f

                        val deltaX = currentAvgX - threeFingerStartX
                        val deltaY = currentAvgY - threeFingerStartY

                        if (abs(deltaY) >= swipeThresholdPx && abs(deltaY) > abs(deltaX)) {
                            threeFingerSwiped = true
                            onLocalFeedback(LocalFeedbackType.TICK)
                            if (deltaY >= 0) {
                                emit(TouchOutEvent.Gesture(GestureType.DESKTOP, GestureDirection.DOWN))
                            } else {
                                emit(TouchOutEvent.Gesture(GestureType.DESKTOP, GestureDirection.UP))
                            }
                        } else if (abs(deltaX) >= swipeThresholdPx && abs(deltaX) > abs(deltaY)) {
                            threeFingerSwiped = true
                            onLocalFeedback(LocalFeedbackType.TICK)
                            onToggleKeyboard()
                        }
                    }
                }
            }

            Mode.FOUR_FINGER, Mode.IDLE -> Unit
        }
    }

    fun onUp(id: Long) {
        val p = pointers[id] ?: return
        val now = System.currentTimeMillis()
        val oldMode = mode

        pointers.remove(id)

        when (oldMode) {
            Mode.MOVE -> if (id == firstFingerId || id == activePointerId) {
                longPressJob?.cancel()
                if (accumulatedDx != 0f || accumulatedDy != 0f) {
                    emit(TouchOutEvent.Move(accumulatedDx, accumulatedDy))
                    accumulatedDx = 0f
                    accumulatedDy = 0f
                }

                val remainingId = pointers.keys.firstOrNull()
                if (remainingId != null) {
                    activePointerId = remainingId
                    firstFingerId = remainingId
                    pointers[remainingId]?.let {
                        it.startX = it.x
                        it.startY = it.y
                    }
                } else {
                    if (dist(p) < slopPx && !transitionedFromMultiTouch) {
                        emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.CLICK))
                    }
                    mode = Mode.IDLE
                    activePointerId = null
                }
            }

            Mode.PREDRAG_WAIT -> if (id == firstFingerId) {
                emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.CLICK))
                mode = Mode.IDLE
                onLocalFeedback(LocalFeedbackType.RELEASE_LOCK)
            }

            Mode.DRAG -> {
                if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                    emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                    accumulatedDragDx = 0f
                    accumulatedDragDy = 0f
                }

                val remainingId = pointers.keys.firstOrNull()
                if (remainingId != null) {
                    activePointerId = remainingId
                    firstFingerId = remainingId
                    pointers[remainingId]?.let {
                        it.startX = it.x
                        it.startY = it.y
                        dragStillAnchorX = it.x
                        dragStillAnchorY = it.y
                    }
                    lastSignificantMoveTime = now
                } else {
                    val idleDuration = now - lastSignificantMoveTime
                    val isStationary = idleDuration >= config.stationaryThresholdMs

                    val relayTimeout = if (!isStationary) {
                        config.movingRelayTimeoutMs     // 滑動中抬手：800ms
                    } else {
                        config.stationaryRelayTimeoutMs // 靜止抬手：0ms 瞬間釋放
                    }

                    handOffJob?.cancel()
                    handOffJob = scope.launch {
                        if (relayTimeout > 0) {
                            delay(relayTimeout)
                        }
                        if (pointers.isEmpty()) {
                            emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.UP))
                            dragging = false
                            mode = Mode.IDLE
                            activePointerId = null
                            onLocalFeedback(LocalFeedbackType.RELEASE_LOCK)
                        }
                    }
                }
            }

            Mode.TWO_FINGER_WAIT -> {
                twoFingerLongPressJob?.cancel()
                val tapDuration = now - twoFingerDownTime
                val p2 = pointers[id]
                val movedDist = if (p2 != null) dist(p2) else 0f

                if (tapDuration <= config.tapTimeoutMs && movedDist < slopPx) {
                    if (rightClickCandidate) {
                        emit(TouchOutEvent.Click(MouseButton.RIGHT, ClickAction.CLICK))
                    } else if (id != firstFingerId) {
                        emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.CLICK))
                    }
                }
            }

            Mode.RIGHT_DRAG -> {
                if (id == firstFingerId) {
                    if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                        emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                        accumulatedDragDx = 0f
                        accumulatedDragDy = 0f
                    }
                    emit(TouchOutEvent.Click(MouseButton.RIGHT, ClickAction.UP))
                    mode = Mode.IDLE
                    onLocalFeedback(LocalFeedbackType.RELEASE_LOCK)
                }
            }

            Mode.SCROLL_VERTICAL -> {
                catchupSmoother.cancel()
            }

            Mode.ZOOM -> {
                if (accumulatedZoomSteps != 0) {
                    emit(TouchOutEvent.Zoom(accumulatedZoomSteps.toFloat()))
                    accumulatedZoomSteps = 0
                }
                mode = Mode.IDLE
            }

            Mode.SWIPE_HORIZONTAL -> { }

            Mode.THREE_FINGER -> {
                if (!threeFingerSwiped && dist(p) < slopPx * 2f) {
                    emit(TouchOutEvent.Gesture(GestureType.MULTITASK, GestureDirection.TAP))
                }
                mode = Mode.IDLE
            }

            Mode.FOUR_FINGER -> {
                if (dist(p) < slopPx * 3f) {
                    onLocalFeedback(LocalFeedbackType.TICK)
                    emit(TouchOutEvent.Click(MouseButton.MIDDLE, ClickAction.CLICK))
                }
                mode = Mode.IDLE
            }

            Mode.IDLE -> Unit
        }

        // 💡 確保剩餘 1 隻手指時能流暢繼承主控權
        if (pointers.isEmpty() && mode != Mode.DRAG) {
            mode = Mode.IDLE
            firstFingerId = null
            activePointerId = null
            dragging = false
            transitionedFromMultiTouch = false
            horizontalSwipeTriggered = false
            threeFingerSwiped = false
        } else if (pointers.size == 1 && mode != Mode.DRAG) {
            val remainingId = pointers.keys.first()
            val remainingPointer = pointers[remainingId]
            if (remainingPointer != null) {
                firstFingerId = remainingId
                activePointerId = remainingId
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
        twoFingerLongPressJob?.cancel()
        handOffJob?.cancel()
        catchupSmoother.cancel()
        pointers.clear()

        activePointerId = null
        lastSignificantMoveTime = 0L
        dragStillAnchorX = 0f
        dragStillAnchorY = 0f

        mode = Mode.IDLE
        dragging = false
        firstFingerId = null
        transitionedFromMultiTouch = false
        accumulatedDx = 0f
        accumulatedDy = 0f
        accumulatedDragDx = 0f
        accumulatedDragDy = 0f
        scrollStepAccumulator = 0f
        accumulatedZoomSteps = 0
        startTwoFingerDist = 0f
        lastTwoFingerDist = 0f
        lastHapticTwoFingerDist = 0f
        rawHapticAccumulator = 0f
        horizontalSwipeTriggered = false
        threeFingerStartX = 0f
        threeFingerStartY = 0f
        threeFingerSwiped = false
    }

    private fun startLongPressWatcher(id: Long) {
        longPressJob = scope.launch {
            delay(config.longPressMs)
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

    private fun distBetween(p1: Pointer, p2: Pointer): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun triggerZoomHaptics(rawDeltaDist: Float) {
        val currentDist = lastTwoFingerDist
        val hapticDiff = currentDist - lastHapticTwoFingerDist
        if (abs(hapticDiff) >= zoomHapticDistancePx) {
            val ticksCount = (abs(hapticDiff) / zoomHapticDistancePx).toInt()
            val sign = if (hapticDiff > 0) 1 else -1

            repeat(ticksCount) {
                onLocalFeedback(LocalFeedbackType.ZOOM_TICK)
            }

            accumulatedZoomSteps += sign * ticksCount
            lastHapticTwoFingerDist += sign * ticksCount * zoomHapticDistancePx
        }
    }
}