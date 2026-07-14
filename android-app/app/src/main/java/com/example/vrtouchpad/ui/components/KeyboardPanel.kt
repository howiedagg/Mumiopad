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

    // 【核心優化】：使用 Android 底層 OnGlobalLayoutListener 監聽物理可見螢幕高度變化
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            // 取得當前 App 視窗實體可見區域（會排除鍵盤佔用部分）
            view.getWindowVisibleDisplayFrame(rect)
            val screenHeight = view.rootView.height
            val keypadHeight = screenHeight - rect.bottom

            // 如果被遮擋的高度大於螢幕總高度的 15%，即判定鍵盤正開啟
            val isKeyboardOpen = keypadHeight > screenHeight * 0.15

            if (isKeyboardOpen) {
                hasKeyboardBeenShown = true
            } else if (hasKeyboardBeenShown) {
                // 曾經開啟過，且目前被遮擋高度歸零 -> 觸發狀態回跳
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

    // 建立一個長度為 20 的底線緩衝區，游標鎖定在最右側
    val anchorText = "____________________"
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

                        if (newText.length < 5) {
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