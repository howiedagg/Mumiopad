package com.example.vrtouchpad.network

import com.example.vrtouchpad.engine.SystemKey
import com.example.vrtouchpad.engine.TouchOutEvent
import kotlinx.coroutines.flow.StateFlow

interface ConnectionClient {
    val connState: StateFlow<ConnState>
    fun sendEvent(event: TouchOutEvent)
    fun sendText(value: String)
    fun sendKeypress(key: SystemKey)
    fun close()
}