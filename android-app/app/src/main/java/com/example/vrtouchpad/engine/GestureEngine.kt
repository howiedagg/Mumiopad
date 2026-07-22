// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/engine/GestureEngine.kt

package com.example.vrtouchpad.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// 💡 強型別 Enum 定義
enum class MouseButton { LEFT, RIGHT, MIDDLE }
enum class ClickAction { DOWN, UP, CLICK }
enum class SystemKey { BROWSER_BACK, BROWSER_FORWARD, VOLUME_UP, VOLUME_DOWN, BACKSPACE, ENTER }
enum class GestureType { DESKTOP, MULTITASK }
enum class GestureDirection { UP, DOWN, TAP }

sealed class TouchOutEvent {
    data class Move(val dx: Float, val dy: Float) : TouchOutEvent()
    data class Click(val button: MouseButton, val action: ClickAction) : TouchOutEvent()
    data class Scroll(val dy: Float) : TouchOutEvent()
    data class Zoom(val delta: Float) : TouchOutEvent() // 縮放事件（此處 delta 將傳送精準的整數步數）
    data class Gesture(val name: GestureType, val direction: GestureDirection) : TouchOutEvent()
    data class Keypress(val key: SystemKey) : TouchOutEvent()
}

enum class LocalFeedbackType {
    PRESS_LOCK,
    RELEASE_LOCK,
    TICK,
    ZOOM_TICK // 縮放專用刻度震動
}

class GestureEngine(
    private val scope: CoroutineScope,
    private val density: Float,
    private val longPressMs: Long = 200,
    private val emit: (TouchOutEvent) -> Unit,
    private val onLocalFeedback: (LocalFeedbackType) -> Unit = {},
    private val onToggleKeyboard: () -> Unit = {},
    private val isKeyboardActive: () -> Boolean = { false }, // 判斷鍵盤狀態的回呼
    private val getScrollSpeed: () -> Float = { 1f } // 💡 動態獲取當前滾動速度的 Lambda
) {
    private val slopPx = 8f * density
    private val stillPx = 10f * density
    private val dragStartSlopPx = 1.5f * density
    private val scrollActivationSlopPx = 32f * density
    private val zoomActivationSlopPx = 25f * density // 捏合啟動門檻
    private val zoomHapticDistancePx = 30f * density // 縮放專用刻度間隔（30dp）
    private val swipeThresholdPx = 48f * density

    private val emitIntervalMs = 10L
    private val multiTapWindowMs = 120L
    private val tapTimeoutMs = 280L

    // 💡 新增：雙指接力（Hand-off）相關變數與參數
    private val handOffTimeoutMs = 200L
    private var handOffJob: Job? = null
    private var isHandOffPending = false
    private var handOffPrimaryId: Long? = null
    private var handOffSecondaryId: Long? = null

    // 觸覺反饋相關參數
    private val hapticNotchDistancePx = 24f * density // 可調：越小震動越密集

    // 💡 滾動步數累加器
    private var scrollStepAccumulator = 0f

    private var lastHapticTwoFingerDist = 0f // 紀錄上一次觸發震動時的「絕對距離」

    private enum class Mode {
        IDLE, MOVE, PREDRAG_WAIT, DRAG, TWO_FINGER_WAIT,
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
    private var accumulatedZoomSteps = 0 // 儲存準備傳送給 PC 的精準整數縮放步數

    private var startTwoFingerDist = 0f // 兩指初始距離
    private var lastTwoFingerDist = 0f  // 兩指前一次距離

    private var horizontalSwipeTriggered = false

    // 三指 X 軸起點，用來進行三指水平手勢判斷
    private var threeFingerStartX = 0f
    private var threeFingerStartY = 0f
    private var threeFingerSwiped = false

    // 💡 當位移達到門檻時，同步觸發手機震動與向外發送精準整數步數
    private fun triggerStepsFromAccumulator() {
        val hapticThreshold = hapticNotchDistancePx // 24dp * density
        val steps = (scrollStepAccumulator / hapticThreshold).toInt()

        if (steps != 0) {
            // 1. 觸發手機震動
            val absSteps = abs(steps)
            repeat(absSteps) {
                onLocalFeedback(LocalFeedbackType.TICK)
            }

            // 2. 向外發送精準的整數步數 (例如 +1f 或 -1f)
            emit(TouchOutEvent.Scroll(steps.toFloat()))

            // 3. 扣除已消耗的步數位移
            scrollStepAccumulator -= steps * hapticThreshold
        }
    }

    // 💡 新增：當確認不是接力，而是真正的雙指手勢時呼叫
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
        lastEmitTime = System.currentTimeMillis()
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

                // 💡 關鍵：若原本處於移動或拖曳狀態，啟動 150ms 的雙指接力觀望
                if (mode == Mode.MOVE || mode == Mode.DRAG || dragging) {
                    isHandOffPending = true
                    handOffPrimaryId = firstFingerId
                    handOffSecondaryId = id

                    pointers.values.forEach {
                        it.startX = it.x
                        it.startY = it.y
                    }

                    handOffJob = scope.launch {
                        delay(handOffTimeoutMs)
                        if (isHandOffPending) {
                            // 150ms 過去了第一指還沒抬起，確定是雙指手勢
                            isHandOffPending = false
                            executeTwoFingerWaitSetup()
                        }
                    }
                } else {
                    executeTwoFingerWaitSetup()
                }
            }
            3 -> {
                // 3 指落下時取消接力觀望
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
                    // 同步記錄三指的 X 與 Y 平均起點
                    threeFingerStartX = (p1.x + p2.x + p3.x) / 3f
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

        // 💡 關鍵修正：處於接力觀望期時
        if (isHandOffPending) {
            if (dragging) {
                // 情況一：正在【拖曳視窗中】！
                // 允許第一指繼續拉動視窗，不檢測滾動死區，直到第一指抬起（完成接力）
                if (id == handOffPrimaryId) {
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
                return
            } else {
                // 情況二：普通滑動／準備雙指滾動
                val p1 = pointers[handOffPrimaryId]
                val p2 = pointers[handOffSecondaryId]

                if (p1 != null && p2 != null) {
                    val dist1 = sqrt((p1.x - p1.startX) * (p1.x - p1.startX) + (p1.y - p1.startY) * (p1.y - p1.startY))
                    val dist2 = sqrt((p2.x - p2.startX) * (p2.x - p2.startX) + (p2.y - p2.startY) * (p2.y - p2.startY))

                    // 只有在非拖曳時，滑動才秒切雙指滾動模式
                    if (dist1 >= slopPx * 1.2f || dist2 >= slopPx * 1.2f) {
                        handOffJob?.cancel()
                        isHandOffPending = false
                        executeTwoFingerWaitSetup()
                        return
                    }
                }
                return
            }
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
                        mode = Mode.ZOOM
                        lastTwoFingerDist = currentDist
                        lastHapticTwoFingerDist = currentDist
                        triggerZoomHaptics(deltaDist)
                    } else if (max(abs(deltaX), abs(deltaY)) >= scrollActivationSlopPx) {
                        rightClickCandidate = false
                        if (abs(deltaY) > abs(deltaX)) {
                            mode = Mode.SCROLL_VERTICAL

                            // 💡 跨過死區時，立刻同步觸發第 1 次滾動與震動（下滑送 1f，上滑送 -1f）
                            onLocalFeedback(LocalFeedbackType.TICK)
                            emit(TouchOutEvent.Scroll(if (deltaY > 0) 1f else -1f))

                            // 💡 重置累加器為 0，從當前手指位置重新開始累積
                            scrollStepAccumulator = 0f
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

                    if (deltaX >= swipeThresholdPx) {
                        emit(TouchOutEvent.Keypress(SystemKey.BROWSER_BACK))
                        horizontalSwipeTriggered = true
                        onLocalFeedback(LocalFeedbackType.TICK)
                    } else if (deltaX <= -swipeThresholdPx) {
                        emit(TouchOutEvent.Keypress(SystemKey.BROWSER_FORWARD))
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

        // 💡 關鍵：若在觀望期內手指抬起（接力成功）
        if (isHandOffPending) {
            handOffJob?.cancel()
            isHandOffPending = false

            if (id == handOffPrimaryId) {
                // 第一指抬起：接力成功！主控權移交給第二指
                pointers.remove(id)
                val newPrimaryId = handOffSecondaryId ?: pointers.keys.firstOrNull()
                if (newPrimaryId != null) {
                    firstFingerId = newPrimaryId
                    val newPointer = pointers[newPrimaryId]
                    if (newPointer != null) {
                        // 重置第二指起點，避免座標跳躍（飛針）
                        newPointer.startX = newPointer.x
                        newPointer.startY = newPointer.y
                    }

                    // 若原本在拖曳，維持 DRAG 模式（不發送 Left Up）；否則維繫 MOVE 模式
                    mode = if (dragging) Mode.DRAG else Mode.MOVE
                    transitionedFromMultiTouch = true
                    lastEmitTime = System.currentTimeMillis()
                } else {
                    mode = Mode.IDLE
                }
                return
            } else if (id == handOffSecondaryId) {
                // 第二指只是快速點一下就抬起，恢復單指狀態
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
                }
                emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.UP))
                dragging = false
                mode = Mode.IDLE
                onLocalFeedback(LocalFeedbackType.RELEASE_LOCK)
            }

            Mode.TWO_FINGER_WAIT -> {
                val tapDuration = System.currentTimeMillis() - twoFingerDownTime
                if (tapDuration <= tapTimeoutMs) {
                    if (rightClickCandidate) {
                        emit(TouchOutEvent.Click(MouseButton.RIGHT, ClickAction.CLICK))
                    } else if (id != firstFingerId) {
                        emit(TouchOutEvent.Click(MouseButton.LEFT, ClickAction.CLICK))
                    }
                }
            }

            Mode.SCROLL_VERTICAL -> {
                // 💡 釋放時不需要發送殘留的微小位移，因為未滿一格不觸發滾動，符合物理滾輪體驗
            }

            Mode.ZOOM -> { // 釋放手指時送出剩餘縮放步數
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
                // 四指點擊（tap）改成「滑鼠中鍵點擊」，不再切換鍵盤
                if (dist(p) < slopPx * 3f) {
                    onLocalFeedback(LocalFeedbackType.TICK)
                    emit(TouchOutEvent.Click(MouseButton.MIDDLE, ClickAction.CLICK)) // 發送中鍵點擊事件給 PC
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
        handOffJob?.cancel() // 💡 清除接力計時器
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

    private fun distBetween(p1: Pointer, p2: Pointer): Float { // 計算雙指物理間距
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun triggerZoomHaptics(rawDeltaDist: Float) { // 格線絕對對齊震動演算法
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