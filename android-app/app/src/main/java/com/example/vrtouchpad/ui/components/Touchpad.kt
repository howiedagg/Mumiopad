// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/ui/components/Touchpad.kt

package com.example.vrtouchpad.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.example.vrtouchpad.engine.GestureEngine
import com.example.vrtouchpad.engine.LocalFeedbackType
import com.example.vrtouchpad.engine.TouchOutEvent
import kotlinx.coroutines.CoroutineScope

@Composable
fun Touchpad(
    modifier: Modifier,
    enabled: Boolean,
    mouseSpeed: Float,
    scrollSpeed: Float,
    reverseScroll: Boolean,
    scope: CoroutineScope,
    onOutEvent: (TouchOutEvent) -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val density = LocalDensity.current.density
    val view = LocalView.current

    val currentMouseSpeed by rememberUpdatedState(mouseSpeed)
    val currentScrollSpeed by rememberUpdatedState(scrollSpeed)
    val currentReverseScroll by rememberUpdatedState(reverseScroll)

    val currentOnOutEvent by rememberUpdatedState(onOutEvent)
    val currentOnToggleKeyboard by rememberUpdatedState(onToggleKeyboard)

    val engine = remember(scope, density) {
        GestureEngine(
            scope = scope,
            density = density,
            emit = { event ->
                val scaled = when (event) {
                    is TouchOutEvent.Move -> TouchOutEvent.Move(
                        event.dx * currentMouseSpeed, event.dy * currentMouseSpeed,
                    )
                    is TouchOutEvent.Scroll -> {
                        val directionMultiplier = if (currentReverseScroll) -1f else 1f
                        TouchOutEvent.Scroll(event.dy * currentScrollSpeed * directionMultiplier)
                    }
                    else -> event
                }
                currentOnOutEvent(scaled)
            },
            onLocalFeedback = { type ->
                val constant = when (type) {
                    LocalFeedbackType.PRESS_LOCK -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            HapticFeedbackConstants.KEYBOARD_PRESS
                        } else {
                            HapticFeedbackConstants.LONG_PRESS
                        }
                    }
                    LocalFeedbackType.RELEASE_LOCK -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            HapticFeedbackConstants.KEYBOARD_RELEASE
                        } else {
                            HapticFeedbackConstants.VIRTUAL_KEY_RELEASE
                        }
                    }
                    LocalFeedbackType.TICK -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                        } else {
                            HapticFeedbackConstants.VIRTUAL_KEY
                        }
                    }
                    LocalFeedbackType.ZOOM_TICK -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            HapticFeedbackConstants.SEGMENT_TICK // 高階單次刻度，用於縮放微調
                        } else {
                            HapticFeedbackConstants.KEYBOARD_PRESS
                        }
                    }
                }
                view.performHapticFeedback(constant)
            },
            onToggleKeyboard = { currentOnToggleKeyboard() }
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