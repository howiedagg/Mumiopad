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
    private val scrollActivationSlopPx = 32f * density
    private val swipeThresholdPx = 48f * density

    private val emitIntervalMs = 10L
    private val multiTapWindowMs = 120L
    private val tapTimeoutMs = 280L

    // 觸覺反饋與防暴衝相關參數
    private val scrollDampeningLimitPx = 35f * density

    // 【重新設計】：震動改成只看「兩指的原始物理位移」，完全不經過
    // accumulatedScrollDy／applyScrollDampening／emitIntervalMs 節流／scrollSpeed 倍率
    // 這條送出鏈路。真實滑鼠滾輪的觸感是機械結構直接對應手指轉動的物理角度，
    // 跟畫面實際捲動了多少格是兩件事；這裡刻意仿照這個原則：手指移動固定物理距離
    // 就震一次，在 onMove 當下立刻觸發，不等批次送出，也不受 dampening 影響而變慢或失真。
    // 代價是：震動格數不再保證跟 PC 端捲動格數逐格對應（快速滑動時 PC 端因為
    // dampening 少捲一點），但這才是像真滾輪的體感——手指動多少震多少，恆定不變。
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

    private val catchupSmoother = ScrollCatchupSmoother(scope) { delta ->
        accumulatedScrollDy += delta
    }

    // 【新增】：單指長按拖曳跨過 stillPx(~32px)死區時，這段死區期間手指
    // 已經走過、但原本會被直接丟棄的距離，比照兩指捲動已有的死區補償做法，
    // 重用同一個通用平滑元件把它補回去，避免「手指已經在動、游標卻完全
    // 沒反應」的明顯延遲感。x、y 分開兩個實例，各自累加進對應的 accumulator。
    private val dragCatchupSmootherX = ScrollCatchupSmoother(scope) { delta ->
        accumulatedDragDx += delta
    }
    private val dragCatchupSmootherY = ScrollCatchupSmoother(scope) { delta ->
        accumulatedDragDy += delta
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
                    // 【新增】：拖曳死區補償是個短暫的批次注入(~20ms 內跑完)，
                    // 正常情況下這裡不會遇到還在跑的狀況，但保險起見比照
                    // onUp 的處理方式一併取消，避免它在拖曳已經結束送出
                    // left-up 之後，還殘留著把值繼續加進 accumulatedDragDx/Dy。
                    dragCatchupSmootherX.cancel()
                    dragCatchupSmootherY.cancel()

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
                // 【設計說明】：rawHapticAccumulator 在這裡歸零是安全的——它現在只是
                // 「這次手勢裡手指移動了多少物理距離」的計數器，跟 PC 端無關，
                // 每次新的兩指手勢從零開始重新累積是正確行為，不會有基準點錯位的問題。
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

            Mode.PREDRAG_WAIT -> if (id == firstFingerId && dist(p) >= stillPx) {
                dragging = true
                mode = Mode.DRAG
                emit(TouchOutEvent.Click("left", "down"))
                accumulatedDragDx = 0f
                accumulatedDragDy = 0f

                // 【新增】：p.startX/Y 是長按判定成立那一刻的手指位置(死區起點)，
                // p.x/p.y 是跨過死區門檻當下的位置，兩者差值就是死區期間手指
                // 實際走過、但還沒送出去的距離。用 catchup smoother 平滑分批
                // 補回去，而不是像原本那樣直接丟掉、讓游標卡住不動。
                dragCatchupSmootherX.start(p.x - p.startX)
                dragCatchupSmootherY.start(p.y - p.startY)
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
                            // 【修正】：死區期間(TWO_FINGER_WAIT)走過的 deltaY，
                            // catchupSmoother 已經把它補償進畫面捲動，但震動累加器
                            // 在死區期間完全沒被餵過任何值(triggerRawHaptics 只在
                            // SCROLL_VERTICAL 的 onMove 裡呼叫)。這裡補上這一次，
                            // 讓死區走過的物理距離也算進震動判斷，才不會發生
                            // 「第一次移動畫面有動、但震動累加器從 0 重新算」的情況。
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

                    // 【重新設計】：震動用尚未經過 dampening／節流的原始物理位移即時判斷，
                    // 在 onMove 當下就觸發，不等 10ms 批次送出，跟畫面實際捲動速度脫鉤。
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
                // 【新增】：跟兩指捲動的 catchupSmoother.cancel() 做法一致，
                // 放開手指時把死區補償的批次注入中止，不補發剩餘量。
                dragCatchupSmootherX.cancel()
                dragCatchupSmootherY.cancel()

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
                // 震動已在 onMove 當下用原始物理位移即時處理過，這裡不用再補觸發。
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
        dragCatchupSmootherX.cancel() // 【新增】
        dragCatchupSmootherY.cancel() // 【新增】
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

    /**
     * 純粹依「兩指的原始物理位移距離」判斷震動，完全不理會 dampening、scrollSpeed、
     * 或 emitIntervalMs 節流——這幾個都是「畫面/PC 端要收到多少」的考量，
     * 跟「手指移動了多少物理距離該震一下」是兩件事，混在一起判斷正是先前
     * 「有時滑到沒振、有時沒滑到卻振」的根源。
     *
     * 效果類似真實滑鼠滾輪：手指（滾輪）轉動固定物理角度就是一格觸感，
     * 不會因為系統當下的加速度曲線或延遲節流而改變震動的節奏。
     */
    private fun triggerRawHaptics(rawDeltaY: Float) {
        rawHapticAccumulator += abs(rawDeltaY)
        while (rawHapticAccumulator >= hapticNotchDistancePx) {
            onLocalFeedback(LocalFeedbackType.TICK)
            rawHapticAccumulator -= hapticNotchDistancePx
        }
    }
}