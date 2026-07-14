package com.example.vrtouchpad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.example.vrtouchpad.engine.GestureEngine
import com.example.vrtouchpad.engine.TouchOutEvent
import kotlinx.coroutines.CoroutineScope

@Composable
fun Touchpad(
    modifier: Modifier,
    enabled: Boolean,
    mouseSpeed: Float,
    scrollSpeed: Float,
    reverseScroll: Boolean, // 【新增】
    scope: CoroutineScope,
    onOutEvent: (TouchOutEvent) -> Unit,
) {
    val density = LocalDensity.current.density
    val currentMouseSpeed by rememberUpdatedState(mouseSpeed)
    val currentScrollSpeed by rememberUpdatedState(scrollSpeed)
    val currentReverseScroll by rememberUpdatedState(reverseScroll) // 【新增】

    val currentOnOutEvent by rememberUpdatedState(onOutEvent)

    val engine = remember(scope, density) {
        GestureEngine(
            scope = scope,
            density = density,
            emit = { event ->
                // 【效能修正】：移除高頻熱路徑上的 Log.d。
                // 每次移動最高可達 ~100Hz，字串模板組裝 + JNI logcat 呼叫
                // 會在 UI thread 上造成可感知的微卡頓，僅在需要除錯時再手動打開。
                val scaled = when (event) {
                    is TouchOutEvent.Move -> TouchOutEvent.Move(
                        event.dx * currentMouseSpeed, event.dy * currentMouseSpeed,
                    )
                    is TouchOutEvent.Scroll -> {
                        // 【核心邏輯】：如果開啟反向，就將方向乘上 -1
                        val directionMultiplier = if (currentReverseScroll) -1f else 1f
                        TouchOutEvent.Scroll(event.dy * currentScrollSpeed * directionMultiplier)
                    }
                    else -> event
                }
                currentOnOutEvent(scaled)
            },
        )
    }

    Box(
        modifier
            .background(Color(0xFF1E1E1E))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            val id = change.id.value
                            val pos = change.position

                            val isPressed = change.pressed
                            val wasPressed = change.previousPressed

                            when {
                                isPressed && !wasPressed -> {
                                    engine.onDown(id, pos.x, pos.y)
                                }
                                !isPressed && wasPressed -> {
                                    engine.onUp(id)
                                }
                                isPressed && wasPressed -> {
                                    if (pos != change.previousPosition) {
                                        engine.onMove(id, pos.x, pos.y)
                                    }
                                }
                            }
                            change.consume()
                        }
                    }
                }
            },
    ) {
        Text(
            if (enabled) "觸控板區域" else "尚未連線",
            color = Color.Gray,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}