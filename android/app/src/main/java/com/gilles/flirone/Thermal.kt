package com.gilles.flirone

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How sensor images map to the portrait screen (clockwise degrees). Measured on the phone. */
object Orientation {
    const val THERMAL_DEGREES = 90
    const val VISIBLE_DEGREES = 90

    /**
     * The thermal field of view is the central 74 % of the visible image (edge correlation on a
     * capture, ±1 % parallax at ~1 m). Cropping the visible frame to it puts both images in the
     * same coordinates, so spots and markers stay valid in the Visible view.
     */
    const val VISIBLE_CROP = 0.74f

    /**
     * Parallax: the lenses are ~14 mm apart, so the thermal view shifts by about 0.0112 / distance (m)
     * of the visible frame height (1.1 % at 1 m, matching the ~1 % measured on a capture).
     */
    const val PARALLAX_M = 0.0112f
}

/** Portrait thermal image size after rotation. */
const val PW = 60
const val PH = 80

enum class Palette(val label: String, private val stops: LongArray) {
    Iron("Iron", longArrayOf(0x05000C, 0x1E0A5A, 0x5C0E8C, 0xA51E7A, 0xD7383F, 0xEF6A1A, 0xF9A20F, 0xFDD545, 0xFFF8E0)),
    Rainbow("Rainbow", longArrayOf(0x0B0A3A, 0x1A3BC4, 0x0396DD, 0x11BFA0, 0x5FD03A, 0xE3DC1E, 0xF5961A, 0xE8301E, 0xFFF2F0)),
    WhiteHot("White-hot", longArrayOf(0x000000, 0xFFFFFF)),
    BlackHot("Black-hot", longArrayOf(0xFFFFFF, 0x000000)),
    Arctic("Arctic", longArrayOf(0x020A24, 0x082C70, 0x1660B8, 0x2FA0DC, 0x9CDCF2, 0xE9C25A, 0xF6A83A, 0xFFE9B0)),
    Lava("Lava", longArrayOf(0x050508, 0x0C2236, 0x134A5C, 0x3D3A5E, 0x8E2A44, 0xD8402C, 0xF49A32, 0xFFE7A8)),
    Contrast("Contrast", longArrayOf(0x000000, 0x2A0A8A, 0x0064F0, 0x00C8C8, 0x00E050, 0xF0F000, 0xFF7A00, 0xFF0050, 0xFFFFFF));

    /** Gradient stops as ARGB ints (for the chips and the scale bar). */
    val colors: IntArray = IntArray(stops.size) { (0xFF000000 or stops[it]).toInt() }

    /** 256-entry ARGB lookup table. */
    val lut: IntArray = IntArray(256) { i ->
        val pos = i / 255.0 * (stops.size - 1)
        val k = minOf(pos.toInt(), stops.size - 2)
        val f = pos - k
        val a = stops[k]
        val b = stops[k + 1]
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - f) + ((b shr shift) and 0xFF) * f).roundToInt()
        (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

enum class ViewMode { Thermal, Visible, Blend, Edges }
enum class Tool { Spot, Box, Delta }
enum class IsoMode { Above, Below, Between }

enum class Emissivity(val label: String, val sub: String, val value: Float?) {
    Matte("Matte", "Skin, paint, wood, tape, fabric", 0.95f),
    SemiMatte("Semi-matte", "Concrete, brick, plastic", 0.80f),
    SemiGlossy("Semi-glossy", "Glazed tile, varnish, oxidised metal", 0.60f),
    Glossy("Glossy", "Polished metal. Readings are unreliable", 0.30f),
    Custom("Custom", "Set your own value", null),
}

/** A point on the portrait thermal image, in sensor pixels (0..PW, 0..PH). */
data class Pt(val x: Float, val y: Float)

/** Everything the user controls on the live view. */
data class ViewSettings(
    val palette: Palette = Palette.Iron,
    val view: ViewMode = ViewMode.Thermal,
    val tool: Tool = Tool.Spot,
    val emissivityPreset: Emissivity = Emissivity.Matte,
    val customEmissivity: Float = 0.70f,
    val locked: Boolean = false,
    val lockLo: Float = 18f,
    val lockHi: Float = 64f,
    val spotA: Pt = Pt(PW / 2f, PH / 2f),
    val spotB: Pt = Pt(PW / 2f, PH * 0.2f),
    val alarmOn: Boolean = false,
    val isoMode: IsoMode = IsoMode.Above,
    val isoLo: Float = 40f,
    val isoHi: Float = 50f,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val pip: Boolean = true,
    /** Blend view: share of the thermal image over the visible one (0…1). */
    val blend: Float = 0.6f,
    // ---- Settings screen ----
    val reflectedC: Float = Thermal.T_REFLECTED.toFloat(),
    val fahrenheit: Boolean = false,
    /** Thermal ↔ visible alignment: subject distance (parallax) and 180° flips. */
    val distanceM: Float = 0.6f,
    val thermalFlip: Boolean = false,
    val visibleFlip: Boolean = false,
    val powerSaving: Boolean = true,
    val calibrationSound: Boolean = false,
) {
    val emissivity: Float get() = emissivityPreset.value ?: customEmissivity
}

/** Temperature formatting in the user's unit; values are stored and computed in °C. */
object Units {
    var fahrenheit by mutableStateOf(false)
    val label: String get() = if (fahrenheit) "°F" else "°C"
    fun conv(c: Float): Float = if (fahrenheit) c * 1.8f + 32f else c
    fun delta(c: Float): Float = if (fahrenheit) c * 1.8f else c
    fun fromDisplay(v: Float): Float = if (fahrenheit) (v - 32f) / 1.8f else v
    /** Number only, one decimal. */
    fun t(c: Float): String = fmt1(conv(c))
    /** Number and unit. */
    fun tu(c: Float): String = t(c) + " " + label
    /** Whole degrees (thresholds, limits). */
    fun whole(c: Float): String = conv(c).roundToInt().toString().replace("-", "−")
}

/** A measured value; [sat] marks a reading beyond the camera's −20…120 °C range. */
data class Reading(val c: Float, val sat: Sat = Sat.None) {
    enum class Sat { None, High, Low }

    val text: String
        get() = when (sat) {
            Sat.High -> "> " + Units.whole(Thermal.RANGE_HI)
            Sat.Low -> "< " + Units.whole(Thermal.RANGE_LO)
            Sat.None -> Units.t(c)
        }
}

fun fmt1(v: Float): String = String.format(java.util.Locale.US, "%.1f", v).replace("-", "−")

class Analysis(
    /** Emissivity-corrected temperatures, portrait, row-major PW×PH. */
    val temps: FloatArray,
    private val satHiMask: BooleanArray,
    private val satLoMask: BooleanArray,
    /** ARGB pixels to draw, portrait PW×PH (palette, or greyscale + isotherm while alarms are shown). */
    val pixels: IntArray,
    val hot: Pt, val hotReading: Reading,
    val cold: Pt, val coldReading: Reading,
    val autoMin: Float, val autoMax: Float,
    val rangeMin: Float, val rangeMax: Float,
    val anySatHi: Boolean, val anySatLo: Boolean,
    val isoMatchPct: Float,
) {
    /** Per-pixel saturation: 0 = in range, 1 = above 120 °C, 2 = below −20 °C (stored with captures). */
    fun satMask(): ByteArray = ByteArray(temps.size) { i -> if (satHiMask[i]) 1 else if (satLoMask[i]) 2 else 0 }

    fun at(p: Pt): Reading {
        val i = index(p)
        return Reading(temps[i], if (satHiMask[i]) Reading.Sat.High else if (satLoMask[i]) Reading.Sat.Low else Reading.Sat.None)
    }

    /** Box tool: a region centred on [p] (min, max, avg). */
    fun box(p: Pt): Triple<Reading, Reading, Reading> {
        val (x0, y0, x1, y1) = boxRect(p)
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var sum = 0.0; var n = 0
        var mnSat = Reading.Sat.None; var mxSat = Reading.Sat.None
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * PW + x
            val t = temps[i]
            if (t < mn) { mn = t; mnSat = if (satLoMask[i]) Reading.Sat.Low else Reading.Sat.None }
            if (t > mx) { mx = t; mxSat = if (satHiMask[i]) Reading.Sat.High else Reading.Sat.None }
            sum += t; n++
        }
        return Triple(Reading(mn, mnSat), Reading(mx, mxSat), Reading((sum / n).toFloat()))
    }

    companion object {
        const val BOX_W = 20
        const val BOX_H = 30

        fun index(p: Pt): Int {
            val x = p.x.toInt().coerceIn(0, PW - 1)
            val y = p.y.toInt().coerceIn(0, PH - 1)
            return y * PW + x
        }

        fun boxRect(p: Pt): IntArray {
            val x0 = (p.x.roundToInt() - BOX_W / 2).coerceIn(0, PW - BOX_W)
            val y0 = (p.y.roundToInt() - BOX_H / 2).coerceIn(0, PH - BOX_H)
            return intArrayOf(x0, y0, x0 + BOX_W, y0 + BOX_H)
        }
    }
}

/** Raw sensor counts -> temperature, false colour, measurements. */
object Thermal {
    // Planck constants from this unit's CameraFiles.zip (calib.rsc, .calib.extRBF.ds120C_weRB_apNOA_fiNOF_le:
    // built-in lens, −20…120 °C range).
    private const val R = 17595.15
    private const val B = 1435.0
    private const val F = 1.0
    private const val J0 = 2591.348
    private const val J1 = 80.0

    const val T_REFLECTED = 20.0
    const val RANGE_LO = -20f
    const val RANGE_HI = 120f
    private const val SAT_LO = -19.5
    private const val SAT_HI = 119.5

    /** Signal reflected by the surface from surroundings at [reflectedC]. */
    private fun sigRefl(reflectedC: Double) = J1 * R / (exp(B / (reflectedC + 273.15)) - F) + J0

    private fun celsius(sig: Double, emissivity: Double, sigRefl: Double): Double {
        val sigObj = (sig - (1 - emissivity) * sigRefl) / emissivity
        val arg = J1 * R / (sigObj - J0) + F
        return if (arg <= 1.0) RANGE_HI.toDouble() + 100 else B / ln(arg) - 273.15
    }

    /** Rotates the 80×60 sensor image to portrait 60×80 ([Orientation.THERMAL_DEGREES]). */
    private fun portraitIndex(px: Int, py: Int): Int = when (Orientation.THERMAL_DEGREES) {
        90 -> (FlirCamera.H - 1 - px) * FlirCamera.W + py // clockwise: portrait (x,y) <- sensor (y, H-1-x)
        270 -> px * FlirCamera.W + (FlirCamera.W - 1 - py)
        else -> error("unsupported rotation")
    }

    /**
     * [visible] = x0, y0, x1, y1 (exclusive) of the part of the portrait image the user can see:
     * hot/cold markers, the auto range and the out-of-range chips only consider that region.
     */
    fun analyze(raw: IntArray, s: ViewSettings, isoPreview: Boolean, visible: IntArray = intArrayOf(0, 0, PW, PH)): Analysis {
        val n = PW * PH
        val temps = FloatArray(n)
        val satHi = BooleanArray(n)
        val satLo = BooleanArray(n)
        val e = s.emissivity.toDouble()
        val refl = sigRefl(s.reflectedC.toDouble())
        var hotI = -1; var coldI = -1
        var anyHi = false; var anyLo = false
        for (py in 0 until PH) for (px in 0 until PW) {
            val i = py * PW + px
            val src = if (s.thermalFlip) portraitIndex(PW - 1 - px, PH - 1 - py) else portraitIndex(px, py)
            val sig = raw[src] * 4.0
            // saturation is decided on the camera's own reading (ε = 1), then the ε correction applies
            val t1 = celsius(sig, 1.0, refl)
            satHi[i] = t1 >= SAT_HI
            satLo[i] = t1 <= SAT_LO
            temps[i] = celsius(sig, e, refl).toFloat()
            if (px < visible[0] || px >= visible[2] || py < visible[1] || py >= visible[3]) continue
            anyHi = anyHi || satHi[i]
            anyLo = anyLo || satLo[i]
            if (hotI < 0 || temps[i] > temps[hotI]) hotI = i
            if (coldI < 0 || temps[i] < temps[coldI]) coldI = i
        }
        if (hotI < 0) { hotI = 0; coldI = 0 }
        val autoMin = temps[coldI]
        val autoMax = temps[hotI]
        val lo = if (s.locked) s.lockLo else autoMin
        val hi = if (s.locked) s.lockHi else autoMax
        val span = maxOf(hi - lo, 0.5f)

        val pixels = IntArray(n)
        var matches = 0
        val iso = s.alarmOn || isoPreview
        for (i in 0 until n) {
            val v = ((temps[i] - lo) / span * 255f).roundToInt().coerceIn(0, 255)
            val match = when (s.isoMode) {
                IsoMode.Above -> temps[i] >= s.isoLo
                IsoMode.Below -> temps[i] <= s.isoLo
                IsoMode.Between -> temps[i] in s.isoLo..s.isoHi
            }
            if (match) matches++
            pixels[i] = when {
                iso && match -> ISOTHERM
                iso -> (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                else -> s.palette.lut[v]
            }
        }
        fun pt(i: Int) = Pt(i % PW + 0.5f, i / PW + 0.5f)
        fun reading(i: Int) = Reading(temps[i], if (satHi[i]) Reading.Sat.High else if (satLo[i]) Reading.Sat.Low else Reading.Sat.None)
        return Analysis(
            temps, satHi, satLo, pixels,
            pt(hotI), reading(hotI), pt(coldI), reading(coldI),
            autoMin, autoMax, lo, hi, anyHi, anyLo,
            matches * 100f / n,
        )
    }

    private const val ISOTHERM = 0xFFD4FF3A.toInt()
}
