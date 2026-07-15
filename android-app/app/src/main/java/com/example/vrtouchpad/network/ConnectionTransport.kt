package com.example.vrtouchpad.network

import com.example.vrtouchpad.engine.TouchOutEvent

interface ConnectionTransport {
    fun sendEvent(event: TouchOutEvent)
    fun sendText(value: String)
    fun sendKeypress(key: String)
    fun sendUnpairRequest()
    fun close()
}