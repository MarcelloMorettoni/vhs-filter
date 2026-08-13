package com.retro.vhs.data

import android.content.Context
import android.content.SharedPreferences

enum class OutputQuality(val label: String, val width: Int, val height: Int, val bitRate: Int) {
    TAPE("Tape · 640×480", 640, 480, 4_500_000),
    HIGH("High · 1280×960", 1280, 960, 14_000_000)
}

/** Small persisted preferences bag; read and written on the main thread only. */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vhs_settings", Context.MODE_PRIVATE)

    var presetId: String
        get() = prefs.getString(KEY_PRESET, "vhs_sp") ?: "vhs_sp"
        set(value) = prefs.edit().putString(KEY_PRESET, value).apply()

    var osdEnabled: Boolean
        get() = prefs.getBoolean(KEY_OSD, true)
        set(value) = prefs.edit().putBoolean(KEY_OSD, value).apply()

    var eraDate: Boolean
        get() = prefs.getBoolean(KEY_ERA_DATE, true)
        set(value) = prefs.edit().putBoolean(KEY_ERA_DATE, value).apply()

    var vhsAudio: Boolean
        get() = prefs.getBoolean(KEY_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO, value).apply()

    var recordAudio: Boolean
        get() = prefs.getBoolean(KEY_MIC, true)
        set(value) = prefs.edit().putBoolean(KEY_MIC, value).apply()

    var letterbox: Boolean
        get() = prefs.getBoolean(KEY_LETTERBOX, true)
        set(value) = prefs.edit().putBoolean(KEY_LETTERBOX, value).apply()

    /** Dropout streaks are the most divisive artefact, so they are switchable. */
    var dropouts: Boolean
        get() = prefs.getBoolean(KEY_DROPOUTS, true)
        set(value) = prefs.edit().putBoolean(KEY_DROPOUTS, value).apply()

    /** Extra degrees applied on top of the automatic camera rotation: 0, 90, 180, 270. */
    var rotationOffset: Int
        get() = prefs.getInt(KEY_ROTATION, 0)
        set(value) = prefs.edit().putInt(KEY_ROTATION, ((value % 360) + 360) % 360).apply()

    var quality: OutputQuality
        get() = runCatching {
            OutputQuality.valueOf(prefs.getString(KEY_QUALITY, null) ?: OutputQuality.TAPE.name)
        }.getOrDefault(OutputQuality.TAPE)
        set(value) = prefs.edit().putString(KEY_QUALITY, value.name).apply()

    private companion object {
        const val KEY_PRESET = "preset"
        const val KEY_OSD = "osd"
        const val KEY_ERA_DATE = "era_date"
        const val KEY_AUDIO = "vhs_audio"
        const val KEY_MIC = "mic"
        const val KEY_LETTERBOX = "letterbox"
        const val KEY_QUALITY = "quality"
        const val KEY_ROTATION = "rotation_offset"
        const val KEY_DROPOUTS = "dropouts"
    }
}
