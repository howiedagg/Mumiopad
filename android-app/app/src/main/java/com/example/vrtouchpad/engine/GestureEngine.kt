// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/engine/GestureEngine.kt

package com.example.vrtouchpad.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// 💡 強型別 Enum 定義，杜絕字串硬編碼
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

class GestureEngine(
    private val scope: CoroutineScope,
    private val density: Float,
    private val longPressMs: Long = 350, // 全局統一長按鎖定時間：350ms
    private val emit: (TouchOutEvent) -> Unit,
    private val onLocalFeedback: (LocalFeedbackType) -> Unit = {},
    private val onToggleKeyboard: () -> Unit = {},
    private val isKeyboardActive: () -> Boolean = { false },
    private val getScrollSpeed: () -> Float = { 1f }
) {
    private val slopPx = 8f * density                       // 8dp (全局點擊死區門檻)
    private val stillPx = 10f * density                     // 10dp (全局長按靜止防抖區)
    private val dragStartSlopPx = 1.5f * density
    private val swipeActivationSlopPx = 18f * density      // 18dp (影片快進門檻)
    private val scrollActivationSlopPx = 32f * density     // 32dp (網頁滾動門檻)
    private val zoomActivationSlopPx = 25f * density
    private val zoomHapticDistancePx = 30f * density
    private val swipeThresholdPx = 48f * density

    private val emitIntervalMs = 10L
    private val multiTapWindowMs = 120L
    private val tapTimeoutMs = 280L

    private val scrollDampeningLimitPx = 35f * density
    private val hapticNotchDistancePx = 24f * density
    private var rawHapticAccumulator = 0f

    private var scrollStepAccumulator = 0f
    private var lastHapticTwoFingerDist = 0f

    // 雙指接力（Hand-off）緩衝變數
    private var handOffJob: Job? = null
    private var isHandOffPending = false
    private var handOffPrimaryId: Long? = null
    private var handOffSecondaryId: Long? = null

    // 雙指長按右鍵拖曳計時器
    private var twoFingerLongPressJob: Job? = null

    private enum class Mode {
        IDLE, MOVE, PREDRAG_WAIT, DRAG, TWO_FINGER_WAIT,
        RIGHT_DRAG, // 雙指長按右鍵拖曳模式
        SCROLL_VERTICAL, SWIPE_HORIZONTAL, ZOOM, THREE_FINGER, FOUR_FINGER
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
        rightClickCandidate = timeSinceFirst <= multiTapWindowMs

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

        // 啟動二指長按計時器（350ms 鎖定右鍵拖曳）
        startTwoFingerLongPressWatcher()
    }

    private fun startTwoFingerLongPressWatcher() {
        twoFingerLongPressJob?.cancel()
        twoFingerLongPressJob = scope.launch {
            delay(longPressMs) // 350ms
            if (mode == Mode.TWO_FINGER_WAIT && pointers.size == 2) {
                val p1 = pointers.values.elementAtOrNull(0)
                val p2 = pointers.values.elementAtOrNull(1)
                if (p1 != null && p2 != null && dist(p1) < stillPx && dist(p2) < stillPx) {
                    rightClickCandidate = false

                    // 💡 關鍵修復：改為 Mode.DRAG (左鍵拖曳)，並發送 MouseButton.LEFT！
                    mode = Mode.DRAG
                    dragging = true

                    val secondFingerId = pointers.keys.elementAtOrNull(1) ?: pointers.keys.last()
                    firstFingerId = secondFingerId

                    val activePointer = pointers[secondFingerId]
                    if (activePointer != null) {
                        activePointer.startX = activePointer.x
                        activePointer.startY = activePointer.y
                    }

                    emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.DOWN))
                    onLocalFeedback(LocalFeedbackType.PRESS_LOCK)
                }
            }
        }
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
                twoFingerDownTime = System.currentTimeMillis()

                val p1 = pointers[firstFingerId]
                val isFirstFingerMoving = p1 != null && dist(p1) >= slopPx

                // 💡 拖曳中或第一指滑動中 ➔ 啟動交疊接力觀望
                if (dragging || isFirstFingerMoving) {
                    isHandOffPending = true
                    handOffPrimaryId = firstFingerId
                    handOffSecondaryId = id

                    pointers.values.forEach {
                        it.startX = it.x
                        it.startY = it.y
                    }

                    // 拖曳給予 800ms 交疊容錯，普通滑動給予 180ms
                    val currentTimeout = if (dragging) 800L else 180L

                    handOffJob = scope.launch {
                        delay(currentTimeout)
                        if (isHandOffPending) {
                            isHandOffPending = false
                            executeTwoFingerWaitSetup()
                        }
                    }
                } else {
                    executeTwoFingerWaitSetup()
                }
            }
            3 -> {
                longPressJob?.cancel()
                twoFingerLongPressJob?.cancel()
                handOffJob?.cancel()
                isHandOffPending = false

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
                handOffJob?.cancel()
                isHandOffPending = false

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

        if (isHandOffPending) {
            val p2 = pointers[handOffSecondaryId]
            if (p2 != null) {
                val dist2 = sqrt((p2.x - p2.startX) * (p2.x - p2.startX) + (p2.y - p2.startY) * (p2.y - p2.startY))

                if (dist2 >= slopPx * 1.2f) {
                    handOffJob?.cancel()
                    isHandOffPending = false
                    executeTwoFingerWaitSetup()
                    return
                }
            }

            if (id == handOffPrimaryId) {
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
            return
        }

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

                    if (now - lastEmitTime >= emitIntervalMs) {
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

                    if (now - lastEmitTime >= emitIntervalMs) {
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

        if (isHandOffPending) {
            handOffJob?.cancel()
            isHandOffPending = false

            if (id == handOffPrimaryId) {
                pointers.remove(id)
                val newPrimaryId = handOffSecondaryId ?: pointers.keys.firstOrNull()
                if (newPrimaryId != null) {
                    firstFingerId = newPrimaryId
                    val newPointer = pointers[newPrimaryId]
                    if (newPointer != null) {
                        newPointer.startX = newPointer.x
                        newPointer.startY = newPointer.y
                    }

                    mode = if (dragging) Mode.DRAG else Mode.MOVE
                    transitionedFromMultiTouch = true
                    lastEmitTime = System.currentTimeMillis()
                } else {
                    mode = Mode.IDLE
                }
                return
            } else if (id == handOffSecondaryId) {
                val p2 = pointers[id]
                val movedDist = if (p2 != null) dist(p2) else 0f
                val tapDuration = System.currentTimeMillis() - twoFingerDownTime

                if (tapDuration <= tapTimeoutMs && movedDist < slopPx && !dragging) {
                    emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.CLICK))
                    onLocalFeedback(LocalFeedbackType.TICK)
                }

                pointers.remove(id)
                mode = if (dragging) Mode.DRAG else Mode.MOVE
                return
            }
        }

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
                    emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.CLICK))
                }
                mode = Mode.IDLE
            }

            Mode.PREDRAG_WAIT -> if (id == firstFingerId) {
                emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.CLICK))
                mode = Mode.IDLE
                onLocalFeedback(LocalFeedbackType.RELEASE_LOCK)
            }

            Mode.DRAG -> if (id == firstFingerId) {
                if (accumulatedDragDx != 0f || accumulatedDragDy != 0f) {
                    emit(TouchOutEvent.Move(accumulatedDragDx, accumulatedDragDy))
                    accumulatedDragDx = 0f
                    accumulatedDragDy = 0f
                }
                // 💡 關鍵：手指離開螢幕，0ms 秒發 Left Up！放開視窗乾脆俐落！
                emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.UP))
                dragging = false
                mode = Mode.IDLE
                onLocalFeedback(LocalFeedbackType.RELEASE_LOCK)
            }

            Mode.TWO_FINGER_WAIT -> {
                twoFingerLongPressJob?.cancel()
                val tapDuration = System.currentTimeMillis() - twoFingerDownTime
                val p2 = pointers[id]
                val movedDist = if (p2 != null) dist(p2) else 0f

                if (tapDuration <= tapTimeoutMs && movedDist < slopPx) {
                    if (rightClickCandidate) {
                        emit(TouchOutEvent.Click(MouseButton.RIGHT, ClickAction.CLICK))
                    } else if (id != firstFingerId) {
                        emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.CLICK))
                    }
                }
            }

            Mode.RIGHT_DRAG -> {
                if (id != firstFingerId) {
                    pointers.remove(id)
                    return
                } else {
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
        twoFingerLongPressJob?.cancel()
        handOffJob?.cancel()
        catchupSmoother.cancel()
        pointers.clear()

        isHandOffPending = false
        handOffPrimaryId = null
        handOffSecondaryId = null

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