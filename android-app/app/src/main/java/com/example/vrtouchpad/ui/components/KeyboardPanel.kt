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

    var hasKeyboardBeenShown by remember { mutableStateOf(false) }
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
                    newText.length < oldText.length -> {
                        val diff = oldText.length - newText.length
                        repeat(diff) {
                            onSendKey("BACKSPACE")
                        }
                        if (newText.length < 100) {
                            textFieldValue = TextFieldValue(text = anchorText, selection = TextRange(anchorText.length))
                        } else {
                            textFieldValue = newValue
                        }
                    }
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