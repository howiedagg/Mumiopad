package com.example.vrtouchpad.ui.components

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun InvisibleKeyboardInput(
    active: Boolean,
    onSendText: (String) -> Unit,
    onSendKey: (String) -> Unit,
    onKeyboardDismissed: () -> Unit
) {
    if (!active) return

    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current

    // 追蹤鍵盤在本次開啟週期中，是否至少有成功顯示過
    var hasKeyboardBeenShown by remember { mutableStateOf(false) }

    // 使用 Android 底層 OnGlobalLayoutListener 監聽物理可見螢幕高度變化
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val screenHeight = view.rootView.height
            val keypadHeight = screenHeight - rect.bottom

            val isKeyboardOpen = keypadHeight > screenHeight * 0.15

            if (isKeyboardOpen) {
                hasKeyboardBeenShown = true
            } else if (hasKeyboardBeenShown) {
                onKeyboardDismissed()
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    LaunchedEffect(active) {
        if (active) {
            focusRequester.requestFocus()
        }
    }

    // 【修改】：將預設緩衝區極大化至 1000 個空白字元，提供充足的退格空間並徹底隱形
    val anchorText = remember { " ".repeat(1000) }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = anchorText, selection = TextRange(anchorText.length)))
    }

    Box(
        modifier = Modifier
            .size(1.dp)
            .background(Color.Transparent)
    ) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val oldText = textFieldValue.text
                val newText = newValue.text

                when {
                    // 1. 退格刪除判定
                    newText.length < oldText.length -> {
                        val diff = oldText.length - newText.length
                        repeat(diff) {
                            onSendKey("BACKSPACE")
                        }

                        // 【修改】：按住退格時不再頻繁重置。只有當 1000 字元被刪到剩下不到 100 字元時，
                        // 才強制重置。這能完美避免輸入法在連續刪除時因 Buffer 變更而中斷。
                        if (newText.length < 100) {
                            textFieldValue = TextFieldValue(text = anchorText, selection = TextRange(anchorText.length))
                        } else {
                            textFieldValue = newValue
                        }
                    }

                    // 2. 字元送出判定：組字完畢
                    newValue.composition == null -> {
                        if (newText.length > oldText.length) {
                            val committed = newText.substring(oldText.length)
                            if (committed.contains("\n")) {
                                onSendKey("ENTER")
                            } else {
                                onSendText(committed)
                            }
                        }
                        // 【修改】：每當有新字元成功送出時，才順便在背景悄悄補滿 1000 字元緩衝區
                        textFieldValue = TextFieldValue(text = anchorText, selection = TextRange(anchorText.length))
                    }

                    // 3. 組字中
                    else -> {
                        textFieldValue = newValue
                    }
                }
            },
            modifier = Modifier.focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false,
                imeAction = ImeAction.Default
            ),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent)
        )
    }
}