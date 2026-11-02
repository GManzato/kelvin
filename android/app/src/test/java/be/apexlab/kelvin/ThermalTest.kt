package be.apexlab.kelvin

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardware-free regression tests for [Thermal.analyze] and [Analysis].
 *
 * Raw frames are 80×60 sensor images ([FlirCamera.W] × [FlirCamera.H], row-major). Portrait results are
 * 60×80 ([PW] × [PH]). Pixels are placed in *sensor* coordinates and the expected *portrait* coordinates
 * are written out by hand (clockwise quarter turn: sensor (sx, sy) -> portrait (59 - sy, sx)), so a
 * wrong rotation fails here instead of being mirrored by a test helper.
 *
 * The reference temperatures below are a fixed fixture: they were produced once, offline, from the
 * Planck constants and ε = 1 / reflected = 20 °C. They pin the current conversion against regressions;
 * they are NOT an independent calibration of the camera.
 */
class ThermalTest {

    private var originalFahrenheit = false

    @Before
    fun forceCelsius() {
        originalFahrenheit = Units.fahrenheit
        Units.fahrenheit = false
    }

    @After
    fun restoreUnits() {
        Units.fahrenheit = originalFahrenheit
    }

    private class Sensor(val x: Int, val y: Int, val raw: Int)

    private fun frame(background: Int, vararg px: Sensor): IntArray {
        val f = IntArray(FlirCamera.W * FlirCamera.H) { background }
        for (p in px) f[p.y * FlirCamera.W + p.x] = p.raw
        return f
    }

    private fun idx(x: Int, y: Int) = y * PW + x

    /** ε = 1, so the camera's own reading is used as is; reflected temperature has no effect. */
    private val eps1 = ViewSettings(palette = Palette.Iron, emissivityPreset = Emissivity.Custom, customEmissivity = 1f)

    private fun custom(e: Float, reflected: Float = 20f) =
        ViewSettings(emissivityPreset = Emissivity.Custom, customEmissivity = e, reflectedC = reflected)

    // ---- raw count -> temperature -------------------------------------------------------------

    @Test
    fun rawCountsMapToReferenceTemperaturesAtUnitEmissivity() {
        // raw count -> °C (ε = 1, reflected 20 °C), see class comment for provenance.
        val reference = listOf(
            2000 to -15.3108f,
            2500 to 0.0662f,
            3000 to 13.0097f,
            3500 to 24.3611f,
            4000 to 34.5766f,
            5000 to 52.6181f,
            6000 to 68.4394f,
            8000 to 95.8358f,
            9000 to 108.0638f,
            10000 to 119.5728f,
        )
        // all on sensor row 0 -> portrait column 59, portrait row = sensor column
        val raw = frame(3500, *reference.mapIndexed { i, (r, _) -> Sensor(i, 0, r) }.toTypedArray())
        val a = Thermal.analyze(raw, eps1, isoPreview = false)

        reference.forEachIndexed { i, (r, c) ->
            assertEquals("raw $r", c, a.at(Pt(59.5f, i + 0.5f)).c, 0.01f)
        }
        // only the last count is beyond the usable range
        assertEquals(Reading.Sat.High, a.at(Pt(59.5f, 9.5f)).sat)
        for (i in 0 until 9) assertEquals("raw ${reference[i].first}", Reading.Sat.None, a.at(Pt(59.5f, i + 0.5f)).sat)
    }

    @Test
    fun uniformFrameKeepsAConsistentReadingAndColour() {
        val a = Thermal.analyze(frame(3500), eps1, isoPreview = false)
        assertEquals(a.autoMin, a.autoMax, 0f)
        assertTrue(a.pixels.all { it == a.pixels[0] })
        assertTrue(a.temps.all { kotlin.math.abs(it - 24.3611f) < 0.01f })
    }

    // ---- orientation -----------------------------------------------------------------------------

    @Test
    fun hotAndColdMarkersLandOnTheRotatedPortraitPixels() {
        val raw = frame(3500, Sensor(10, 5, 5000), Sensor(70, 50, 3000))
        val a = Thermal.analyze(raw, eps1, isoPreview = false)

        // sensor (10, 5) -> portrait (59 - 5, 10); sensor (70, 50) -> portrait (59 - 50, 70)
        assertEquals(Pt(54.5f, 10.5f), a.hot)
        assertEquals(Pt(9.5f, 70.5f), a.cold)
        assertEquals(52.618f, a.hotReading.c, 0.01f)
        assertEquals(13.010f, a.coldReading.c, 0.01f)
    }

    @Test
    fun thermalFlipTurnsTheImageByHalfATurn() {
        val raw = frame(3500, Sensor(10, 5, 5000), Sensor(70, 50, 3000))
        val plain = Thermal.analyze(raw, eps1, isoPreview = false)
        val flipped = Thermal.analyze(raw, eps1.copy(thermalFlip = true), isoPreview = false)

        // (x, y) -> (59 - x, 79 - y)
        assertEquals(Pt(5.5f, 69.5f), flipped.hot)
        assertEquals(Pt(50.5f, 9.5f), flipped.cold)
        // same pixels, just moved: every plain pixel equals the flipped pixel at the mirrored position
        for (y in 0 until PH) for (x in 0 until PW) {
            assertEquals(plain.temps[idx(x, y)], flipped.temps[idx(PW - 1 - x, PH - 1 - y)], 0f)
        }
    }

    // ---- visible region and scales ------------------------------------------------------------------

    @Test
    fun hiddenPixelsStayMeasurableButDoNotDriveTheAutoScale() {
        // Portrait: visible rows y < 40. Hidden: a hot, a cold and an over-range pixel in rows >= 40.
        val raw = frame(
            3500,
            Sensor(10, 5, 5000),   // visible hot   -> portrait (54, 10)
            Sensor(20, 30, 3000),  // visible cold  -> portrait (29, 20)
            Sensor(60, 10, 8000),  // hidden hot    -> portrait (49, 60)
            Sensor(70, 50, 2000),  // hidden cold   -> portrait (9, 70)
            Sensor(45, 40, 10500), // hidden >120   -> portrait (19, 45)
        )
        val roi = Thermal.analyze(raw, eps1, isoPreview = false, visible = intArrayOf(0, 0, PW, 40))

        assertEquals(Pt(54.5f, 10.5f), roi.hot)
        assertEquals(Pt(29.5f, 20.5f), roi.cold)
        assertEquals(52.618f, roi.autoMax, 0.01f)
        assertEquals(13.010f, roi.autoMin, 0.01f)
        assertFalse(roi.anySatHi)
        assertFalse(roi.anySatLo)

        // hidden pixels are still read correctly and keep their own saturation state
        assertEquals(95.836f, roi.at(Pt(49.5f, 60.5f)).c, 0.01f)
        assertEquals(-15.311f, roi.at(Pt(9.5f, 70.5f)).c, 0.01f)
        assertEquals(Reading.Sat.High, roi.at(Pt(19.5f, 45.5f)).sat)
        assertEquals(1, roi.satMask()[idx(19, 45)].toInt())
        // they are drawn clamped to the ends of the visible-only scale
        assertEquals(Palette.Iron.lut[255], roi.pixels[idx(54, 10)])
        assertEquals(Palette.Iron.lut[255], roi.pixels[idx(49, 60)])
        assertEquals(Palette.Iron.lut[0], roi.pixels[idx(29, 20)])
        assertEquals(Palette.Iron.lut[0], roi.pixels[idx(9, 70)])

        // the same frame with everything visible is dominated by the over-range pixel
        val all = Thermal.analyze(raw, eps1, isoPreview = false)
        assertEquals(Pt(19.5f, 45.5f), all.hot)
        assertEquals(Reading.Sat.High, all.hotReading.sat)
        assertEquals(Pt(9.5f, 70.5f), all.cold)
        assertTrue(all.anySatHi)
    }

    @Test
    fun fixedScaleClampsToThePaletteEndsAndKeepsTheMeasuredExtremes() {
        val raw = frame(
            3500,
            Sensor(60, 10, 8000),  // 95.8 °C -> portrait (49, 60)
            Sensor(70, 50, 2000),  // -15.3 °C -> portrait (9, 70)
            Sensor(20, 30, 4000),  // 34.6 °C -> portrait (29, 20)
        )
        val a = Thermal.analyze(raw, eps1.copy(locked = true, lockLo = 20f, lockHi = 60f), isoPreview = false)

        assertEquals(20f, a.rangeMin, 0f)
        assertEquals(60f, a.rangeMax, 0f)
        // the measured extremes are reported, not the fixed limits
        assertEquals(95.836f, a.autoMax, 0.01f)
        assertEquals(-15.311f, a.autoMin, 0.01f)
        assertEquals(Palette.Iron.lut[255], a.pixels[idx(49, 60)])
        assertEquals(Palette.Iron.lut[0], a.pixels[idx(9, 70)])
        // (34.58 - 20) / 40 of the way up: step 93 of 255
        assertEquals(Palette.Iron.lut[93], a.pixels[idx(29, 20)])
    }


    // ---- saturation and emissivity ----------------------------------------------------------------------

    @Test
    fun saturationIgnoresEmissivityCorrectionPushingAboveRange() {
        // The camera reads 108 °C (in range); ε = 0.3 corrects that to well over 120 °C.
        val a = Thermal.analyze(frame(3500, Sensor(0, 0, 9000)), custom(0.3f), isoPreview = false)
        val r = a.at(Pt(59.5f, 0.5f))

        assertTrue("corrected ${r.c}", r.c > 120f)
        assertEquals(Reading.Sat.None, r.sat)
        assertFalse(a.anySatHi)
        assertEquals(0, a.satMask()[idx(59, 0)].toInt())
    }

    @Test
    fun saturationSticksWhenCorrectionBringsTheValueBackBelowRange() {
        // The camera reads 119.6 °C (over its 119.5 limit); hot surroundings (150 °C) and ε = 0.8
        // correct that to about 111 °C. The reading stays flagged as over range.
        val a = Thermal.analyze(frame(3500, Sensor(0, 0, 10000)), custom(0.8f, reflected = 150f), isoPreview = false)
        val r = a.at(Pt(59.5f, 0.5f))

        assertTrue("corrected ${r.c}", r.c < Thermal.RANGE_HI)
        assertEquals(Reading.Sat.High, r.sat)
        assertTrue(a.anySatHi)
        assertEquals(1, a.satMask()[idx(59, 0)].toInt())
        assertEquals("> 120", r.text)
    }

    @Test
    fun saturationLowSticksWhenCorrectionBringsTheValueAboveTheLowLimit() {
        // The camera reads -19.5 °C or less; -20 °C surroundings and ε = 0.8 correct it to about -19.4 °C.
        val a = Thermal.analyze(frame(3500, Sensor(0, 0, 1880)), custom(0.8f, reflected = -20f), isoPreview = false)
        val r = a.at(Pt(59.5f, 0.5f))

        assertTrue("corrected ${r.c}", r.c > Thermal.RANGE_LO)
        assertEquals(Reading.Sat.Low, r.sat)
        assertTrue(a.anySatLo)
        assertEquals(2, a.satMask()[idx(59, 0)].toInt())
    }

    @Test
    fun reflectedTemperatureCannotChangeAPerfectEmitter() {
        val raw = frame(3500, Sensor(0, 0, 4000))
        val base = Thermal.analyze(raw, eps1.copy(reflectedC = 20f), isoPreview = false).at(Pt(59.5f, 0.5f)).c
        for (refl in listOf(-10f, 0f, 60f)) {
            val c = Thermal.analyze(raw, eps1.copy(reflectedC = refl), isoPreview = false).at(Pt(59.5f, 0.5f)).c
            assertEquals("reflected $refl", base, c, 0.001f)
        }
    }

    @Test
    fun anObjectAtTheReflectedTemperatureReadsThatTemperatureForAnyEmissivity() {
        // raw 3301 reads 19.9994 °C at ε = 1. With surroundings at that temperature the object and
        // the reflection are indistinguishable, so no emissivity can move the result.
        val raw = frame(3500, Sensor(0, 0, 3301))
        for (e in listOf(0.95f, 0.6f, 0.3f)) {
            val c = Thermal.analyze(raw, custom(e, reflected = 19.9994f), isoPreview = false).at(Pt(59.5f, 0.5f)).c
            assertEquals("ε $e", 19.9994f, c, 0.02f)
        }
    }

    @Test
    fun lowerEmissivityPushesReadingsAwayFromTheReflectedTemperature() {
        val hotRaw = frame(3500, Sensor(0, 0, 4000))   // 34.6 °C at ε = 1, above the 20 °C surroundings
        val coolRaw = frame(3500, Sensor(0, 0, 3000))  // 13.0 °C at ε = 1, below them
        fun hot(e: Float, refl: Float = 20f) = Thermal.analyze(hotRaw, custom(e, refl), isoPreview = false).at(Pt(59.5f, 0.5f)).c
        fun cool(e: Float) = Thermal.analyze(coolRaw, custom(e), isoPreview = false).at(Pt(59.5f, 0.5f)).c

        assertEquals(35.292f, hot(0.95f), 0.02f)
        assertEquals(37.934f, hot(0.8f), 0.02f)
        assertEquals(62.808f, hot(0.3f), 0.05f)
        assertEquals(12.627f, cool(0.95f), 0.02f)
        // the reflected temperature matters once ε < 1: colder surroundings -> hotter object, and vice versa
        assertEquals(36.108f, hot(0.95f, refl = 0f), 0.02f)
        assertEquals(34.284f, hot(0.95f, refl = 40f), 0.02f)
    }

    // ---- alarms (isotherm) -----------------------------------------------------------------------------------

    /** Sensor row 0 = 13.0 °C (80 px), row 1 = 34.6 °C (80 px), row 2 = 52.6 °C (80 px), 24.4 °C elsewhere (4560 px). */
    private val bandFrame: IntArray = frame(3500).also { f ->
        for (x in 0 until FlirCamera.W) { f[x] = 3000; f[FlirCamera.W + x] = 4000; f[2 * FlirCamera.W + x] = 5000 }
    }
    private val bandSettings = eps1
    private val analysis0 get() = Thermal.analyze(bandFrame, bandSettings, isoPreview = false)
    // exact readings of each band, so thresholds can sit precisely on a pixel value
    private val t13 get() = analysis0.at(Pt(59.5f, 0.5f)).c // sensor row 0 -> portrait column 59
    private val t24 get() = analysis0.at(Pt(30.5f, 40.5f)).c
    private val t34 get() = analysis0.at(Pt(58.5f, 0.5f)).c // sensor row 1 -> portrait column 58
    private val t52 get() = analysis0.at(Pt(57.5f, 0.5f)).c // sensor row 2 -> portrait column 57

    private fun matchPct(mode: IsoMode, lo: Float, hi: Float = 0f): Float =
        Thermal.analyze(bandFrame, bandSettings.copy(isoMode = mode, isoLo = lo, isoHi = hi), isoPreview = false).isoMatchPct

    @Test
    fun aboveAndBelowThresholdsIncludeThePixelsExactlyOnThem() {
        // Above: 34.6 and 52.6 rows = 160 / 4800 px
        assertEquals(3.3333f, matchPct(IsoMode.Above, t34), 0.001f)
        assertEquals(1.6667f, matchPct(IsoMode.Above, Math.nextUp(t34)), 0.001f)
        assertEquals(1.6667f, matchPct(IsoMode.Above, t52), 0.001f)
        // Below: 13.0 row + background = 4640 / 4800 px
        assertEquals(96.6667f, matchPct(IsoMode.Below, t24), 0.001f)
        assertEquals(1.6667f, matchPct(IsoMode.Below, Math.nextDown(t24)), 0.001f)
    }

    @Test
    fun betweenIncludesBothEndsAndNothingJustOutside() {
        val hi = t52
        val lo = t34
        assertEquals(3.3333f, matchPct(IsoMode.Between, lo, hi), 0.001f)
        assertEquals(1.6667f, matchPct(IsoMode.Between, Math.nextUp(lo), hi), 0.001f)
        assertEquals(1.6667f, matchPct(IsoMode.Between, lo, Math.nextDown(hi)), 0.001f)
        assertEquals(0f, matchPct(IsoMode.Between, Math.nextUp(lo), Math.nextDown(hi)), 0f)
    }

    @Test
    fun isothermColoursOnlyAppearWhileAlarmOrPreviewIsActive() {
        val s = bandSettings.copy(isoMode = IsoMode.Above, isoLo = t34)
        val off = Thermal.analyze(bandFrame, s, isoPreview = false)
        val preview = Thermal.analyze(bandFrame, s, isoPreview = true)
        val alarm = Thermal.analyze(bandFrame, s.copy(alarmOn = true), isoPreview = false)

        val matchedA = idx(58, 0)  // 34.6 °C, on the threshold
        val matchedB = idx(57, 0)  // 52.6 °C
        val cold = idx(59, 0)      // 13.0 °C, not matched
        val bg = idx(30, 40)       // 24.4 °C, not matched

        // alarm and preview both off: plain palette, only the match percentage is reported
        assertEquals(Palette.Iron.lut[255], off.pixels[matchedB])
        assertEquals(Palette.Iron.lut[0], off.pixels[cold])
        assertEquals(3.3333f, off.isoMatchPct, 0.001f)

        // preview on, alarm off: matches share one highlight colour, the rest turns greyscale
        assertEquals(preview.pixels[matchedA], preview.pixels[matchedB])
        assertNotEquals(off.pixels[matchedB], preview.pixels[matchedB])
        assertEquals(0xFF000000.toInt(), preview.pixels[cold])
        for (i in listOf(cold, bg)) {
            val p = preview.pixels[i]
            assertEquals(((p shr 16) and 0xFF), ((p shr 8) and 0xFF))
            assertEquals(((p shr 8) and 0xFF), (p and 0xFF))
        }
        assertTrue("background should be lit grey, not black", (preview.pixels[bg] and 0xFF) in 1..254)
        assertEquals(3.3333f, preview.isoMatchPct, 0.001f)

        // alarm on draws exactly what the preview draws
        assertArrayEquals(preview.pixels, alarm.pixels)
    }

    // ---- box tool ------------------------------------------------------------------------------------------------

    @Test
    fun boxNearTheTopLeftCornerClampsToTheImageAndIgnoresPixelsJustOutside() {
        val raw = frame(
            3500,
            Sensor(0, 59, 3000),  // portrait (0, 0): first pixel of the box (13.0 °C)
            Sensor(29, 40, 5000), // portrait (19, 29): last pixel of the box (52.6 °C)
            Sensor(0, 39, 8000),  // portrait (20, 0): just right of the box
            Sensor(30, 59, 2000), // portrait (0, 30): just below the box
        )
        val a = Thermal.analyze(raw, eps1, isoPreview = false)
        val (min, max, avg) = a.box(Pt(2f, 3f))

        assertArrayEquals(intArrayOf(0, 0, 20, 30), Analysis.boxRect(Pt(2f, 3f)))
        assertEquals(13.010f, min.c, 0.01f)
        assertEquals(52.618f, max.c, 0.01f)
        // 598 background pixels (24.361 °C) + 13.010 + 52.618, over 600 pixels
        assertEquals(24.389f, avg.c, 0.005f)
    }

    @Test
    fun boxAtTheBottomRightCornerCoversTheLastBoxWidthAndHeightInclusively() {
        val raw = frame(
            3500,
            Sensor(79, 0, 5000),  // portrait (59, 79): last pixel
            Sensor(50, 19, 2500), // portrait (40, 50): first pixel of the box
            Sensor(79, 20, 8000), // portrait (39, 79): just left of the box
            Sensor(49, 19, 2000), // portrait (40, 49): just above the box
        )
        val a = Thermal.analyze(raw, eps1, isoPreview = false)
        val (min, max, avg) = a.box(Pt(PW.toFloat(), PH.toFloat()))

        assertArrayEquals(intArrayOf(40, 50, 60, 80), Analysis.boxRect(Pt(PW.toFloat(), PH.toFloat())))
        assertEquals(0.066f, min.c, 0.01f)
        assertEquals(52.618f, max.c, 0.01f)
        assertEquals(24.368f, avg.c, 0.005f)
    }

    @Test
    fun boxExtremesCarryTheirOwnSaturationButTheAverageNeverDoes() {
        val raw = frame(3500, Sensor(5, 54, 10500)) // over range -> portrait (5, 5)
        val (min, max, avg) = Thermal.analyze(raw, eps1, isoPreview = false).box(Pt(10f, 15f))

        assertEquals(Reading.Sat.None, min.sat)
        assertEquals(Reading.Sat.High, max.sat)
        assertEquals("> 120", max.text)
        assertEquals(Reading.Sat.None, avg.sat)
    }
}
