package com.wludy.rolithax.launcher.data

import android.content.Context

class InstancePinStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("instance_pins", Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    fun setPinned(key: String, pinned: Boolean) {
        val next = load().toMutableSet().apply {
            if (pinned) add(key) else remove(key)
        }
        prefs.edit().putStringSet(KEY, next).apply()
    }

    private companion object { const val KEY = "keys" }
}
