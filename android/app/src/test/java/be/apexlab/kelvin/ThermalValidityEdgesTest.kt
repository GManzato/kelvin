package be.apexlab.kelvin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalValidityEdgesTest {
    private val settings = ViewSettings(emissivityPreset = Emissivity.Custom, customEmissivity = 0.3f)

    private fun mixed(): IntArray = IntArray(PW * PH) { 2500 }.also {
        it[59 * 80] = 3301       // portrait (0,0), approximately 20 C
        it[58 * 80 + 1] = 4000   // portrait (1,1), approximately 62.8 C
    }

    @Test
    fun mixedFrameExtremaAndBoxAverageExcludeImpossiblePixels() {
        val a = Thermal.analyze(mixed(), settings, false)
        assertEquals(2, a.validCount)
        assertEquals(Pt(1.5f, 1.5f), a.hot)
        assertEquals(Pt(0.5f, 0.5f), a.cold)
        assertEquals(20f, a.autoMin, 0.02f)
        assertEquals(62.808f, a.autoMax, 0.02f)
        val (min, max, avg) = a.box(Pt(10f, 15f))
        assertEquals(20f, min.c, 0.02f)
        assertEquals(62.808f, max.c, 0.02f)
        assertEquals(41.404f, avg.c, 0.02f)
        assertFalse(a.at(Pt(10f, 10f)).valid)
        assertEquals(Thermal.INVALID_COLOR, a.pixels[10 * PW + 10])
    }

    @Test
    fun allInvalidFrameAndBoxHaveNoExtremaOrNumericScale() {
        val a = Thermal.analyze(IntArray(PW * PH) { 2500 }, settings.copy(locked = true), true)
        assertEquals(0, a.validCount)
        assertFalse(a.hasValidReadings)
        assertNull(a.hot)
        assertNull(a.cold)
        assertFalse(a.hotReading.valid)
        assertFalse(a.coldReading.valid)
        assertTrue(a.autoMin.isNaN() && a.autoMax.isNaN())
        assertTrue(a.rangeMin.isNaN() && a.rangeMax.isNaN())
        assertTrue(a.pixels.all { it == Thermal.INVALID_COLOR })
        val (min, max, avg) = a.box(Pt(0f, 0f))
        assertFalse(min.valid || max.valid || avg.valid)
    }

    @Test
    fun invalidSettingsCannotProduceMeasurementsOrAlarmMatches() {
        val invalidSettings = listOf(
            settings.copy(customEmissivity = 0f),
            settings.copy(customEmissivity = -0.1f),
            settings.copy(customEmissivity = 1.1f),
            settings.copy(customEmissivity = Float.NaN),
            settings.copy(customEmissivity = Float.POSITIVE_INFINITY),
            settings.copy(reflectedC = Float.NaN),
            settings.copy(reflectedC = Float.POSITIVE_INFINITY),
            settings.copy(reflectedC = -273.15f),
        )
        val raw = IntArray(PW * PH) { 4000 }
        for (s in invalidSettings) {
            val a = Thermal.analyze(raw, s.copy(alarmOn = true, isoMode = IsoMode.Below, isoLo = 300f), false)
            assertEquals("settings $s", 0, a.validCount)
            assertEquals(0f, a.isoMatchPct, 0f)
            assertTrue(a.temps.all { it.isNaN() })
            assertNull(a.hot)
        }
    }

    @Test
    fun invalidPixelsNeverCountAsMatchingAnyAlarmMode() {
        for (mode in IsoMode.entries) {
            val s = settings.copy(alarmOn = true, isoMode = mode, isoLo = if (mode == IsoMode.Below) 300f else -100f, isoHi = 300f)
            val a = Thermal.analyze(mixed(), s, false)
            assertEquals(mode.name, 2 * 100f / (PW * PH), a.isoMatchPct, 0.00001f)
        }
    }

    @Test
    fun nonfiniteAlarmThresholdsCannotActivateTheAlarm() {
        val raw = IntArray(PW * PH) { 4000 }
        for (mode in IsoMode.entries) {
            val a = Thermal.analyze(raw, settings.copy(alarmOn = true, isoMode = mode, isoLo = Float.NEGATIVE_INFINITY), false)
            assertEquals(mode.name, 0f, a.isoMatchPct, 0f)
        }
        val between = Thermal.analyze(raw, settings.copy(alarmOn = true, isoMode = IsoMode.Between, isoLo = 0f, isoHi = Float.NaN), false)
        assertEquals(0f, between.isoMatchPct, 0f)
    }

    @Test
    fun visibleRegionWithoutValidPixelsHasNoMarkersButKeepsWholeFrameAlarmSemantics() {
        val raw = IntArray(PW * PH) { 2500 }.also { it[79] = 4000 } // portrait (59,79), outside ROI
        val a = Thermal.analyze(raw, settings.copy(isoMode = IsoMode.Below, isoLo = 300f), false, intArrayOf(0, 0, 10, 10))
        assertEquals(0, a.validCount)
        assertNull(a.hot)
        assertNull(a.cold)
        assertTrue(a.rangeMin.isNaN() && a.rangeMax.isNaN())
        assertEquals(100f / (PW * PH), a.isoMatchPct, 0.00001f)
    }

    @Test
    fun invalidLockedBoundsFallBackToTheMeasuredScale() {
        val a = Thermal.analyze(mixed(), settings.copy(locked = true, lockLo = Float.NaN), false)
        assertEquals(a.autoMin, a.rangeMin, 0f)
        assertEquals(a.autoMax, a.rangeMax, 0f)
        assertTrue(a.rangeMin.isFinite() && a.rangeMax.isFinite())
    }

    @Test
    fun invalidConversionIsDistinctFromAValidOverOrUnderRangeReading() {
        val high = Thermal.analyze(IntArray(PW * PH) { 10000 }, settings.copy(customEmissivity = 0.8f, reflectedC = 150f), false)
        assertTrue(high.hotReading.valid)
        assertEquals(Reading.Sat.High, high.hotReading.sat)
        val low = Thermal.analyze(IntArray(PW * PH) { 1880 }, settings.copy(customEmissivity = 1f), false)
        assertTrue(low.coldReading.valid)
        assertEquals(Reading.Sat.Low, low.coldReading.sat)
        val invalid = Thermal.analyze(IntArray(PW * PH) { 0 }, settings.copy(customEmissivity = 1f), false)
        assertFalse(invalid.at(Pt(0f, 0f)).valid)
        assertFalse(invalid.anySatHi || invalid.anySatLo)
        assertFalse(Reading(Float.NaN, Reading.Sat.High).valid)
    }

    @Test
    fun finiteOnlySummaryPreservesZeroAndIgnoresNanAndInfinity() {
        val s = TemperatureStats(floatArrayOf(Float.NaN, 0f, -5f, Float.POSITIVE_INFINITY, 10f))
        assertEquals(3, s.validCount)
        assertEquals(-5f, s.min, 0f)
        assertEquals(10f, s.max, 0f)
        assertEquals(5f / 3, s.avg, 0.00001f)
        assertEquals(2, s.coldest)
        assertEquals(4, s.hottest)
        val absent = TemperatureStats(floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY))
        assertEquals(0, absent.validCount)
        assertEquals(-1, absent.coldest)
        assertEquals(-1, absent.hottest)
        assertTrue(absent.min.isNaN() && absent.max.isNaN() && absent.avg.isNaN())
    }
}
