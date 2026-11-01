package com.gilles.flirone

import android.content.Context

/**
 * Persists the user's choices (palette, tools, emissivity, alarms, Settings screen). The alarm's
 * on/off state and a locked range are session-only on purpose: the app starts with a plain live view.
 */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): ViewSettings {
        val d = ViewSettings()
        fun <E : Enum<E>> enum(key: String, def: E, values: Array<E>) = values.firstOrNull { it.name == p.getString(key, null) } ?: def
        return d.copy(
            palette = enum("palette", d.palette, Palette.entries.toTypedArray()),
            view = enum("view", d.view, ViewMode.entries.toTypedArray()),
            tool = enum("tool", d.tool, Tool.entries.toTypedArray()),
            emissivityPreset = enum("emissivityPreset", d.emissivityPreset, Emissivity.entries.toTypedArray()),
            customEmissivity = p.getFloat("customEmissivity", d.customEmissivity),
            isoMode = enum("isoMode", d.isoMode, IsoMode.entries.toTypedArray()),
            isoLo = p.getFloat("isoLo", d.isoLo),
            isoHi = p.getFloat("isoHi", d.isoHi),
            sound = p.getBoolean("sound", d.sound),
            vibrate = p.getBoolean("vibrate", d.vibrate),
            pip = p.getBoolean("pip", d.pip),
            blend = p.getFloat("blend", d.blend),
            reflectedC = p.getFloat("reflectedC", d.reflectedC),
            fahrenheit = p.getBoolean("fahrenheit", d.fahrenheit),
            distanceM = p.getFloat("distanceM", d.distanceM),
            thermalFlip = p.getBoolean("thermalFlip", d.thermalFlip),
            visibleFlip = p.getBoolean("visibleFlip", d.visibleFlip),
            powerSaving = p.getBoolean("powerSaving", d.powerSaving),
            calibrationSound = p.getBoolean("calibrationSound", d.calibrationSound),
        )
    }

    fun save(s: ViewSettings) {
        p.edit()
            .putString("palette", s.palette.name)
            .putString("view", s.view.name)
            .putString("tool", s.tool.name)
            .putString("emissivityPreset", s.emissivityPreset.name)
            .putFloat("customEmissivity", s.customEmissivity)
            .putString("isoMode", s.isoMode.name)
            .putFloat("isoLo", s.isoLo)
            .putFloat("isoHi", s.isoHi)
            .putBoolean("sound", s.sound)
            .putBoolean("vibrate", s.vibrate)
            .putBoolean("pip", s.pip)
            .putFloat("blend", s.blend)
            .putFloat("reflectedC", s.reflectedC)
            .putBoolean("fahrenheit", s.fahrenheit)
            .putFloat("distanceM", s.distanceM)
            .putBoolean("thermalFlip", s.thermalFlip)
            .putBoolean("visibleFlip", s.visibleFlip)
            .putBoolean("powerSaving", s.powerSaving)
            .putBoolean("calibrationSound", s.calibrationSound)
            .apply()
    }
}
