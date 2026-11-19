package com.screencastpro

import android.content.Context
import com.screencastpro.core.BitrateChoice
import com.screencastpro.core.Resolution

/** Preferencias de usuario. Solo se guardan en el propio teléfono (SharedPreferences privadas). */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("screencast_settings", Context.MODE_PRIVATE)

    var resolution: Resolution
        get() = Resolution.fromShortSide(prefs.getInt(KEY_RES, Resolution.P720.shortSide))
        set(v) { prefs.edit().putInt(KEY_RES, v.shortSide).apply() }

    var fps: Int
        get() = prefs.getInt(KEY_FPS, 30).takeIf { it in FPS_OPTIONS } ?: 30
        set(v) { prefs.edit().putInt(KEY_FPS, v).apply() }

    var bitrate: BitrateChoice
        get() = BitrateChoice.fromOrdinal(prefs.getInt(KEY_BITRATE, 0))
        set(v) { prefs.edit().putInt(KEY_BITRATE, v.ordinal).apply() }

    var lowLatency: Boolean
        get() = prefs.getBoolean(KEY_LOW_LATENCY, true)
        set(v) { prefs.edit().putBoolean(KEY_LOW_LATENCY, v).apply() }

    var batterySaver: Boolean
        get() = prefs.getBoolean(KEY_BATTERY, false)
        set(v) { prefs.edit().putBoolean(KEY_BATTERY, v).apply() }

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_RECONNECT, true)
        set(v) { prefs.edit().putBoolean(KEY_RECONNECT, v).apply() }

    var internalAudio: Boolean
        get() = prefs.getBoolean(KEY_AUDIO, true)
        set(v) { prefs.edit().putBoolean(KEY_AUDIO, v).apply() }

    companion object {
        val FPS_OPTIONS = listOf(24, 30, 60)
        private const val KEY_RES = "resolution"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_LOW_LATENCY = "low_latency"
        private const val KEY_BATTERY = "battery_saver"
        private const val KEY_RECONNECT = "auto_reconnect"
        private const val KEY_AUDIO = "internal_audio"
    }
}
