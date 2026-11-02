package be.apexlab.kelvin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalValidityTest {
    private val lowEmissivity = ViewSettings(
        emissivityPreset = Emissivity.Custom,
        customEmissivity = 0.3f,
        reflectedC = 20f,
    )

    @Test
    fun impossibleCorrectedSignalIsNotReportedAs220Degrees() {
        // At epsilon=1 raw 2500 is about 0 C. With these reflection settings the
        // inferred object signal is below the calibration's zero-signal offset.
        val a = Thermal.analyze(IntArray(PW * PH) { 2500 }, lowEmissivity, false)
        assertTrue("No temperature can be recovered from this signal", a.at(Pt(30f, 40f)).c.isNaN())
    }

    @Test
    fun invalidPixelsCannotMatchAnAlarmOrBecomeThePeak() {
        val a = Thermal.analyze(
            IntArray(PW * PH) { 2500 },
            lowEmissivity.copy(alarmOn = true, isoMode = IsoMode.Below, isoLo = 300f),
            false,
        )
        assertEquals(0f, a.isoMatchPct, 0f)
        assertTrue("An invalid frame has no measurable peak", a.hotReading.c.isNaN())
    }
}
