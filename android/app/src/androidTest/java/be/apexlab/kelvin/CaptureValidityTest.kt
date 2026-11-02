package be.apexlab.kelvin

import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith

/**
 * Regression tests for invalid-temperature handling in the capture pipeline: an invalid reading
 * (NaN) must survive save/reload/render/export as "no data" — never as fake heat (e.g. 220 °C),
 * never as a JSON exception, and never as a fabricated default setting.
 *
 * Capture operations use a temp-scoped folder through [ContextWrapper], and created MediaStore
 * entries are deleted afterwards. Run on an isolated debug emulator: normal application startup
 * still runs before instrumentation, independently of this test's scoped capture operations.
 */
@RunWith(AndroidJUnit4::class)
class CaptureValidityTest {

    private class IsoContext(base: Context, private val tmp: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = File(tmp, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(tmp, "cache").apply { mkdirs() }
    }

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val base: Context get() = instr.targetContext
    private lateinit var tmp: File
    private lateinit var captures: Captures
    /** MediaStore rows this test created; removed in [tearDown]. */
    private val createdUris = mutableListOf<Uri>()

    @Before
    fun setUp() {
        tmp = File(base.cacheDir, "validity-test-" + System.nanoTime()).apply { mkdirs() }
        captures = Captures(IsoContext(base, tmp))
    }

    @After
    fun tearDown() {
        for (uri in createdUris) runCatching { base.contentResolver.delete(uri, null, null) }
        createdUris.clear()
        tmp.deleteRecursively()
    }

    /** A minimal analysis over [temps] with no valid extreme anywhere (as core produces for an all-invalid frame); [sat] carries the sensor's 0/1/2 flags. */
    private fun analysis(temps: FloatArray, sat: ByteArray = ByteArray(temps.size)): Analysis = Analysis(
        temps,
        BooleanArray(temps.size) { sat[it].toInt() == 1 },
        BooleanArray(temps.size) { sat[it].toInt() == 2 },
        IntArray(temps.size) { Thermal.INVALID_COLOR },
        null, Reading(Float.NaN), null, Reading(Float.NaN),
        Float.NaN, Float.NaN, Float.NaN, Float.NaN,
        false, false, 0f, 0,
    )

    private fun blankThumb(): Bitmap = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888)

    /** Saves a photo, sensor saturation flags included, and remembers its gallery export for cleanup. */
    private fun savePhoto(temps: FloatArray, s: ViewSettings = ViewSettings(), sat: ByteArray = ByteArray(temps.size)): Capture {
        val c = captures.save(analysis(temps, sat), s, blankThumb(), ByteArray(4), CameraInfo())
        c.galleryUri?.let { createdUris += it }
        return c
    }

    @Test
    fun allInvalidPhotoSavesNullsNotFakeHeat() {
        val n = PW * PH
        val c = savePhoto(FloatArray(n) { Float.NaN })

        // meta.json written without a JSONException and with explicit nulls, not fabricated numbers
        val meta = JSONObject(File(c.dir, "meta.json").readText())
        assertTrue(meta.has("min") && meta.isNull("min"))
        assertTrue(meta.has("max") && meta.isNull("max"))
        assertTrue(meta.has("rangeMin") && meta.isNull("rangeMin"))
        assertTrue(meta.has("rangeMax") && meta.isNull("rangeMax"))

        // reload keeps the invalid marker instead of defaulting
        val r = captures.get(c.id)!!
        assertTrue(r.min.isNaN())
        assertTrue(r.max.isNaN())

        // radiometric reload preserves NaN pixels; aggregates have no numeric value
        val d = captures.data(r)
        assertEquals(0, d.validCount)
        assertFalse(d.hasValidReadings)
        assertTrue(d.min.isNaN() && d.max.isNaN() && d.avg.isNaN())
        assertEquals(-1, d.hottest)
        assertEquals(-1, d.coldest)
        assertTrue(d.temps[0].isNaN())
        assertFalse(d.reading(Pt(1f, 1f)).valid)
        assertFalse(d.reading(-1).valid)
    }

    @Test
    fun invalidPixelsRenderNeutralGray() {
        val n = PW * PH
        val temps = FloatArray(n) { Float.NaN }
        // a non-finite range with all-invalid samples must not crash or pick a palette colour
        val bmp = captures.render(temps, Palette.Iron, Float.NaN, Float.NaN)
        for (i in 0 until n) assertEquals(Thermal.INVALID_COLOR, bmp.getPixel(i % PW, i / PW))
        // a valid pixel under a broken range also renders neutral, never extreme palette heat
        temps[10] = 25f
        val mixed = captures.render(temps, Palette.Iron, Float.NaN, Float.NaN)
        assertEquals(Thermal.INVALID_COLOR, mixed.getPixel(10 % PW, 10 / PW))
        assertEquals(Thermal.INVALID_COLOR, mixed.getPixel(0, 0))
    }

    @Test
    fun mixedGridIgnoresInvalidsAndKeepsRealZero() {
        val n = PW * PH
        val temps = FloatArray(n) { Float.NaN }
        temps[0] = 0f      // a valid zero stays exactly zero
        temps[7] = 36.5f
        temps[13] = -10f
        val sat = ByteArray(n)
        sat[13] = 1        // sensor over-range flag on a *valid* value: the mask still applies
        sat[20] = 2        // sensor clipping flag on an invalid value: the flag persists, the display shows —
        val d = CaptureData(temps, sat)

        assertEquals(3, d.validCount)
        assertEquals(-10f, d.min, 1e-6f)
        assertEquals(36.5f, d.max, 1e-6f)
        assertEquals(26.5f / 3f, d.avg, 1e-4f)
        assertEquals(7, d.hottest)
        assertEquals(13, d.coldest)

        val onScale = d.reading(13)
        assertEquals(-10f, onScale.c, 1e-6f)
        assertEquals(Reading.Sat.High, onScale.sat)

        val invalid = d.reading(20)
        assertFalse(invalid.valid)
        assertEquals(Reading.Sat.Low, invalid.sat) // clipping evidence survives an invalid corrected value

        // extremes of a mixed photo save as finite-only numbers
        val c = savePhoto(temps, ViewSettings(), sat)
        val meta = JSONObject(File(c.dir, "meta.json").readText())
        assertEquals(-10.0, meta.getDouble("min"), 1e-6)
        assertEquals(36.5, meta.getDouble("max"), 1e-6)
        val r = captures.get(c.id)!!
        assertEquals(-10f, r.min, 1e-6f)
        assertEquals(36.5f, r.max, 1e-6f)
        val reloaded = captures.data(r)
        assertEquals(3, reloaded.validCount)
        assertEquals(Reading.Sat.High, reloaded.reading(13).sat)
        assertEquals(Reading.Sat.Low, reloaded.reading(20).sat)
    }

    @Test
    fun invalidSettingsRoundTripAsNullNotDefault() {
        val s = ViewSettings(
            emissivityPreset = Emissivity.Custom,
            customEmissivity = Float.NaN,
            reflectedC = Float.POSITIVE_INFINITY,
        )
        val c = savePhoto(FloatArray(PW * PH) { Float.NaN }, s)
        val meta = JSONObject(File(c.dir, "meta.json").readText())
        assertTrue(meta.has("emissivity") && meta.isNull("emissivity"))
        assertTrue(meta.has("reflectedC") && meta.isNull("reflectedC"))
        val r = captures.get(c.id)!!
        // the legacy defaults (0.95 / 20 °C) must NOT be silently fabricated for present-but-invalid fields
        assertTrue(r.emissivity.isNaN())
        assertTrue(r.reflectedC.isNaN())
    }

    @Test
    fun legacyFileStillGetsDefaultsForAbsentFields() {
        // a hand-written pre-validity meta.json: fields simply absent keep their legacy defaults
        val dir = File(tmp, "files/captures/20240101_000000_000").apply { mkdirs() }
        File(dir, "meta.json").writeText(
            JSONObject()
                .put("version", 2).put("kind", "photo").put("time", 1_700_000_000_000L)
                .put("min", 10.0).put("max", 30.0)
                .toString(),
        )
        val r = Captures(IsoContext(base, tmp)).get("20240101_000000_000")!!
        assertEquals(0.95f, r.emissivity, 1e-6f)
        assertEquals(20f, r.reflectedC, 1e-6f)
        assertEquals(30f, r.max, 1e-6f)
    }

    @Test
    fun timelapseKeepsInvalidSamplesAndFiniteExtremes() {
        val n = PW * PH
        val t0 = 1_700_000_000_000L
        val ok = FloatArray(n) { Float.NaN }
        ok[0] = 0f
        ok[5] = 40f
        val log = listOf(
            TimelapseSample(t0, ok),
            TimelapseSample(t0 + TimelapseLogger.INTERVAL_MS, FloatArray(n) { Float.NaN }),
        )
        val c = captures.saveTimelapse(log, ViewSettings(), CameraInfo(), blankThumb())

        // whole-log extremes: finite-only, not poisoned by the invalid frame
        val meta = JSONObject(File(c.dir, "meta.json").readText())
        assertEquals(0.0, meta.getDouble("min"), 1e-6)
        assertEquals(40.0, meta.getDouble("max"), 1e-6)

        // reload: the all-invalid sample keeps its timestamp slot (a gap, not a skipped time)
        val r = captures.get(c.id)!!
        assertEquals(0f, r.min, 1e-6f)
        assertEquals(40f, r.max, 1e-6f)
        val frames = captures.timelapse(r)
        assertEquals(2, frames.size)
        assertEquals(t0 + TimelapseLogger.INTERVAL_MS, frames[1].time)
        assertEquals(2, frames[0].validCount)
        assertEquals(0, frames[1].validCount)
        assertTrue(frames[1].min.isNaN() && frames[1].max.isNaN() && frames[1].avg.isNaN())
        assertEquals(-1, frames[1].hottest)
        assertTrue(frames[1].temps[3].isNaN()) // NaN survived series.f32

        // a fully invalid log saves null extremes rather than NaN (JSON) or ±Float.MAX_VALUE
        val allBad = captures.saveTimelapse(
            listOf(TimelapseSample(t0 + 2 * TimelapseLogger.INTERVAL_MS, FloatArray(n) { Float.NaN })),
            ViewSettings(), CameraInfo(), blankThumb(),
        )
        val allBadMeta = JSONObject(File(allBad.dir, "meta.json").readText())
        assertTrue(allBadMeta.has("min") && allBadMeta.isNull("min"))
        assertTrue(allBadMeta.has("max") && allBadMeta.isNull("max"))
        assertTrue(captures.get(allBad.id)!!.min.isNaN())
    }

    @Test
    fun csvWritesEmptyCellsForInvalidsAndRealZeros() {
        val n = PW * PH
        val temps = FloatArray(n) { Float.NaN }
        temps[0] = 0f
        temps[1] = 21.25f
        temps[60] = Float.NaN
        val c = savePhoto(temps)
        val r = captures.get(c.id)!!
        val d = captures.data(r)
        val name = captures.exportCsv(r, d, "°C", { it })
        assertNotNull(name)

        val uri = findDownload(name!!) ?: throw AssertionError("test CSV not found in Downloads/Kelvin")
        createdUris += uri
        val text = base.contentResolver.openInputStream(uri)!!.reader().readText()
        val rows = text.lines().filter { !it.startsWith("#") && it.isNotEmpty() }
        assertEquals(PH, rows.size)
        val first = rows.first().split(',')
        assertEquals(PW, first.size) // grid shape kept
        assertEquals("0.00", first[0]) // a measured zero is a real value
        assertEquals("21.25", first[1])
        assertEquals("", first[2]) // invalid renders as an empty cell, never "NaN" or 220
        assertFalse(text.contains("NaN"))
        // and the timelapse CSV keeps its shape with empty aggregate cells for invalid frames
        val log = listOf(
            TimelapseSample(1_700_000_000_000L, temps),
            TimelapseSample(1_700_000_030_000L, FloatArray(n) { Float.NaN }),
        )
        val tlName = captures.exportTimelapseCsv(log, "°C", { it })
        assertNotNull(tlName)
        val tlUri = findDownload(tlName!!) ?: throw AssertionError("test timelapse CSV not found")
        createdUris += tlUri
        val tl = base.contentResolver.openInputStream(tlUri)!!.reader().readText()
        val tlRows = tl.lines().filter { !it.startsWith("#") && it.isNotEmpty() }
        assertEquals(3, tlRows.size) // header + both samples, the invalid one kept
        val bad = tlRows.last().split(',')
        assertEquals(5, bad.size) // time,elapsed_s,max,min,avg shape kept
        assertEquals("", bad[2]); assertEquals("", bad[3]); assertEquals("", bad[4])
        val good = tlRows[1].split(',')
        assertEquals("21.25", good[2]) // finite max of the mixed sample
        assertEquals("0.00", good[3])  // finite min is the measured zero, not an empty cell
    }

    private fun findDownload(name: String): Uri? {
        val rows = base.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null,
        )
        rows?.use {
            if (it.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0))
        }
        return null
    }
}
