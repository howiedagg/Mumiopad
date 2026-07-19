package com.example.vrtouchpad.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("mumiopad_settings", Context.MODE_PRIVATE)

    var mouseSpeed: Float
        get() = prefs.getFloat("mouse_speed", 1.0f)
        set(value) = prefs.edit().putFloat("mouse_speed", value).apply()

    var scrollSpeed: Float
        get() = prefs.getFloat("scroll_speed", 1.0f)
        set(value) = prefs.edit().putFloat("scroll_speed", value).apply()

    var reverseScroll: Boolean
        get() = prefs.getBoolean("reverse_scroll", true)
        set(value) = prefs.edit().putBoolean("reverse_scroll", value).apply()

    var connectionMode: String
        get() = prefs.getString("connection_mode", "WIFI") ?: "WIFI"
        set(value) = prefs.edit().putString("connection_mode", value).apply()
}