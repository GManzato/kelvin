package com.gilles.flirone

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Unit-aware display behaviour. [Units] is a process-wide singleton, so state is restored after each test. */
class UnitsTest {

    private var originalFahrenheit = false

    @Before
    fun saveGlobals() {
        originalFahrenheit = Units.fahrenheit
        Units.fahrenheit = false
    }

    @After
    fun restoreGlobals() {
        Units.fahrenheit = originalFahrenheit
    }

    @Test
    fun absoluteTemperaturesGetTheOffsetButDifferencesDoNot() {
        Units.fahrenheit = true

        assertEquals(32f, Units.conv(0f), 0.001f)
        assertEquals(212f, Units.conv(100f), 0.001f)
        assertEquals(-40f, Units.conv(-40f), 0.001f)
        // a 10 °C span is an 18 °F span, and zero difference stays zero
        assertEquals(18f, Units.delta(10f), 0.001f)
        assertEquals(0f, Units.delta(0f), 0f)
    }

    @Test
    fun celsiusModePassesValuesThroughUnchanged() {
        assertEquals(36.6f, Units.conv(36.6f), 0f)
        assertEquals(5f, Units.delta(5f), 0f)
        assertEquals(36.6f, Units.fromDisplay(36.6f), 0f)
        assertEquals("°C", Units.label)
    }

    @Test
    fun displayValuesConvertBackToCelsiusForThresholds() {
        Units.fahrenheit = true

        assertEquals(100f, Units.fromDisplay(212f), 0.001f)
        assertEquals(0f, Units.fromDisplay(32f), 0.001f)
        assertEquals(-20f, Units.fromDisplay(-4f), 0.001f)
        for (c in listOf(-20f, -3.5f, 0f, 36.6f, 120f)) {
            assertEquals("round trip $c", c, Units.fromDisplay(Units.conv(c)), 0.001f)
        }
    }


    @Test
    fun outOfRangeReadingsShowTheCameraLimitsInTheSelectedUnit() {
        assertEquals("> 120", Reading(130f, Reading.Sat.High).text)
        assertEquals("< −20", Reading(-30f, Reading.Sat.Low).text)

        Units.fahrenheit = true
        assertEquals("> 248", Reading(130f, Reading.Sat.High).text)
        assertEquals("< −4", Reading(-30f, Reading.Sat.Low).text)
        // an in-range reading still shows its own value, not a limit
        assertEquals("98.6", Reading(37f).text)
    }

}
