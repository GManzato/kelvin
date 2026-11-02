package be.apexlab.kelvin

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class CaptureKind { Photo, Video, Timelapse }

/** m:ss, as on the gallery badges. */
fun durationText(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(Locale.US, s / 60, s % 60)
}

/** One saved capture (photo, video or timelapse log) as listed in the gallery. */
class Capture(
    val dir: File,
    val time: Long,
    val emissivity: Float,
    val emissivityLabel: String,
    val reflectedC: Float,
    val palette: Palette,
    val min: Float,
    val max: Float,
    val locked: Boolean,
    val rangeMin: Float,
    val rangeMax: Float,
    val galleryUri: Uri?,
    /** Spot position when the photo was taken. */
    val spot: Pt?,
    val kind: CaptureKind = CaptureKind.Photo,
    /** Video length or timelapse span. */
    val durationMs: Long = 0,
    /** Timelapse samples. */
    val frames: Int = 0,
) {
    val id: String get() = dir.name
    val thumbFile: File get() = File(dir, "thermal.png")
    val videoFile: File get() = File(dir, "video.mp4")
}

/** Radiometric data of a capture, portrait PW×PH. */
class CaptureData(val temps: FloatArray, val sat: ByteArray) {
    fun reading(p: Pt): Reading {
        val i = Analysis.index(p)
        return Reading(temps[i], when (sat[i].toInt()) { 1 -> Reading.Sat.High; 2 -> Reading.Sat.Low; else -> Reading.Sat.None })
    }

    val hottest: Int get() = temps.indices.maxBy { temps[it] }
    val min: Float get() = temps.min()
    val max: Float get() = temps.max()
    val avg: Float get() = temps.average().toFloat()

    fun reading(i: Int): Reading = reading(Pt(i % PW + 0.5f, i / PW + 0.5f))
}

/** Colour range for showing a whole capture: the locked range if one was set, else the full frame. */
fun Capture.range(d: CaptureData): Pair<Float, Float> = if (locked) rangeMin to rangeMax else d.min to d.max

/**
 * Captures live in `files/captures/<stamp>/` with a `meta.json` (`kind`: photo, video or timelapse) and a
 * rendered `thermal.png` thumbnail. Photos add `temps.f32` (PW×PH little-endian float °C, emissivity
 * applied), `sat.u8` (per-pixel saturation) and `visible.jpg`; videos add `video.mp4`; timelapses add
 * `series.f32` (one PW×PH frame per sample) with the sample times in the meta. Photos and videos are
 * also exported to the shared gallery (Pictures/Kelvin, Movies/Kelvin).
 */
class Captures(private val ctx: Context) {
    private val root = File(ctx.filesDir, "captures").apply { mkdirs() }
    private val trash = File(ctx.filesDir, "captures-trash").apply { mkdirs() }

    init {
        purgeTrash()
    }

    fun list(): List<Capture> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray()).mapNotNull(::load).sortedByDescending { it.time }

    fun count(): Int = root.listFiles { f -> f.isDirectory }?.size ?: 0

    fun latest(): Capture? = list().firstOrNull()

    fun get(id: String): Capture? = load(File(root, id))

    private fun load(dir: File): Capture? {
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: return null
        val time = meta.optLong("time").takeIf { it > 0 }
            ?: runCatching { STAMP.parse(dir.name)!!.time }.getOrDefault(dir.lastModified())
        val e = meta.optDouble("emissivity", 0.95).toFloat()
        return Capture(
            dir = dir,
            time = time,
            emissivity = e,
            emissivityLabel = meta.optString("emissivityLabel").ifEmpty { Emissivity.entries.firstOrNull { it.value == e }?.label ?: "Custom" },
            reflectedC = meta.optDouble("reflectedC", Thermal.T_REFLECTED).toFloat(),
            palette = runCatching { Palette.valueOf(meta.optString("palette")) }.getOrDefault(Palette.Iron),
            min = meta.optDouble("min").toFloat(),
            max = meta.optDouble("max").toFloat(),
            locked = meta.optBoolean("locked"),
            rangeMin = meta.optDouble("rangeMin").toFloat(),
            rangeMax = meta.optDouble("rangeMax").toFloat(),
            galleryUri = meta.optString("galleryUri").takeIf { it.isNotEmpty() }?.let(Uri::parse),
            spot = meta.optJSONObject("spot")?.let { Pt(it.optDouble("x").toFloat(), it.optDouble("y").toFloat()) },
            kind = when (meta.optString("kind")) { "video" -> CaptureKind.Video; "timelapse" -> CaptureKind.Timelapse; else -> CaptureKind.Photo },
            durationMs = meta.optLong("durationMs"),
            frames = meta.optInt("frames"),
        )
    }

    fun data(c: Capture): CaptureData {
        val bytes = File(c.dir, "temps.f32").readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val temps = FloatArray(bytes.size / 4) { buf.getFloat() }
        val satFile = File(c.dir, "sat.u8")
        // captures from before sat.u8 existed: infer from the camera's −20…120 °C limits
        val sat = if (satFile.exists()) satFile.readBytes() else ByteArray(temps.size) { i ->
            if (temps[i] >= Thermal.RANGE_HI - 0.5f) 1 else if (temps[i] <= Thermal.RANGE_LO + 0.5f) 2 else 0
        }
        return CaptureData(temps, sat)
    }

    fun thumb(c: Capture): Bitmap? = BitmapFactory.decodeFile(c.thumbFile.path)

    fun save(a: Analysis, s: ViewSettings, rendered: Bitmap, jpeg: ByteArray, info: CameraInfo): Capture {
        val now = System.currentTimeMillis()
        val stamp = STAMP.format(Date(now))
        val dir = File(root, stamp).apply { mkdirs() }
        val buf = ByteBuffer.allocate(a.temps.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        a.temps.forEach { buf.putFloat(it) }
        File(dir, "temps.f32").writeBytes(buf.array())
        File(dir, "sat.u8").writeBytes(a.satMask())
        File(dir, "visible.jpg").writeBytes(jpeg)
        File(dir, "thermal.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = exportImage("kelvin_$stamp.png", rendered)
        val meta = JSONObject()
            .put("version", 2)
            .put("time", now)
            .put("width", PW).put("height", PH)
            .put("emissivity", s.emissivity.toDouble())
            .put("emissivityLabel", s.emissivityPreset.label)
            .put("reflectedC", s.reflectedC.toDouble())
            .put("palette", s.palette.name)
            .put("rangeMin", a.rangeMin.toDouble()).put("rangeMax", a.rangeMax.toDouble())
            .put("locked", s.locked)
            .put("spot", JSONObject().put("x", s.spotA.x.toDouble()).put("y", s.spotA.y.toDouble()))
            // full-frame extremes: the viewer and gallery show the whole frame, not the live view's crop
            .put("min", a.temps.min().toDouble()).put("max", a.temps.max().toDouble())
            .put("model", info.model).put("serial", info.serial).put("firmware", info.firmware)
            .put("galleryUri", uri?.toString() ?: "")
        File(dir, "meta.json").writeText(meta.toString(2))
        return load(dir)!!
    }

    /** Settings and camera fields shared by every capture kind. */
    private fun baseMeta(kind: String, time: Long, s: ViewSettings, info: CameraInfo) = JSONObject()
        .put("version", 2)
        .put("kind", kind)
        .put("time", time)
        .put("width", PW).put("height", PH)
        .put("emissivity", s.emissivity.toDouble())
        .put("emissivityLabel", s.emissivityPreset.label)
        .put("reflectedC", s.reflectedC.toDouble())
        .put("palette", s.palette.name)
        .put("locked", s.locked)
        .put("rangeMin", s.lockLo.toDouble()).put("rangeMax", s.lockHi.toDouble())
        .put("model", info.model).put("serial", info.serial).put("firmware", info.firmware)

    /** Moves a finished recording ([mp4], written in the cache) into a new video capture. */
    fun saveVideo(mp4: File, start: Long, durationMs: Long, min: Float, max: Float, thumb: Bitmap, s: ViewSettings, info: CameraInfo): Capture {
        val stamp = STAMP.format(Date(start))
        val dir = File(root, stamp).apply { mkdirs() }
        val video = File(dir, "video.mp4")
        if (!mp4.renameTo(video)) { mp4.copyTo(video, overwrite = true); mp4.delete() }
        File(dir, "thermal.png").outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = exportVideo("kelvin_$stamp.mp4", video)
        val meta = baseMeta("video", start, s, info)
            .put("durationMs", durationMs)
            .put("min", min.toDouble()).put("max", max.toDouble())
            .put("galleryUri", uri?.toString() ?: "")
        File(dir, "meta.json").writeText(meta.toString(2))
        return load(dir)!!
    }

    /** Saves a timelapse log: every sampled frame plus its time. */
    fun saveTimelapse(log: List<TimelapseSample>, s: ViewSettings, info: CameraInfo, thumb: Bitmap): Capture {
        val start = log.first().time
        val stamp = STAMP.format(Date(start))
        val dir = File(root, stamp).apply { mkdirs() }
        val buf = ByteBuffer.allocate(log.size * PW * PH * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (smp in log) smp.temps.forEach { buf.putFloat(it) }
        File(dir, "series.f32").writeBytes(buf.array())
        File(dir, "thermal.png").outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val meta = baseMeta("timelapse", start, s, info)
            .put("durationMs", log.last().time - start)
            .put("frames", log.size)
            .put("intervalMs", TimelapseLogger.INTERVAL_MS)
            .put("times", org.json.JSONArray(log.map { it.time }))
            .put("min", log.minOf { it.min }.toDouble()).put("max", log.maxOf { it.max }.toDouble())
        File(dir, "meta.json").writeText(meta.toString(2))
        return load(dir)!!
    }

    /** The samples of a saved timelapse. */
    fun timelapse(c: Capture): List<TimelapseSample> {
        val meta = JSONObject(File(c.dir, "meta.json").readText())
        val times = meta.getJSONArray("times")
        val bytes = File(c.dir, "series.f32").readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = minOf(times.length(), bytes.size / (PW * PH * 4))
        return List(n) { k -> TimelapseSample(times.getLong(k), FloatArray(PW * PH) { buf.getFloat() }) }
    }

    /** Max/min/average per sample as CSV in Downloads/Kelvin; returns the file name. */
    fun exportTimelapseCsv(log: List<TimelapseSample>, unitLabel: String, convert: (Float) -> Float): String? {
        if (log.isEmpty()) return null
        val t0 = log.first().time
        val name = "timelapse_${STAMP.format(Date(t0))}.csv"
        val sb = StringBuilder()
        sb.append("# Kelvin timelapse from ${DISPLAY.format(Date(t0))}; whole frame; unit $unitLabel\n")
        sb.append("time,elapsed_s,max,min,avg\n")
        for (smp in log) {
            sb.append(DISPLAY.format(Date(smp.time))).append(',').append((smp.time - t0) / 1000)
            for (v in listOf(smp.max, smp.min, smp.avg)) sb.append(',').append(String.format(Locale.US, "%.2f", convert(v)))
            sb.append('\n')
        }
        return writeDownload(name, sb.toString())
    }

    // ---- delete with undo: the capture moves to a trash folder, purged at the next start ----------

    fun delete(c: Capture): Boolean = c.dir.renameTo(File(trash, c.dir.name))

    fun undoDelete(id: String): Capture? {
        val d = File(trash, id)
        return if (d.renameTo(File(root, id))) load(File(root, id)) else null
    }

    private fun purgeTrash() {
        for (d in trash.listFiles() ?: emptyArray()) {
            load(d)?.galleryUri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            d.deleteRecursively()
        }
    }

    // ---- rendering ------------------------------------------------------------------------------

    /** False-colour image of portrait PW×PH [temps] with [palette] over [lo]…[hi]. */
    fun render(temps: FloatArray, palette: Palette, lo: Float, hi: Float): Bitmap {
        val span = maxOf(hi - lo, 0.5f)
        val px = IntArray(temps.size) { i -> palette.lut[((temps[i] - lo) / span * 255f).roundToInt().coerceIn(0, 255)] }
        return Bitmap.createBitmap(px, PW, PH, Bitmap.Config.ARGB_8888)
    }

    /** Shareable PNG: the image at 8×, numbered spots, hottest marker and the colour scale. */
    fun shareImage(c: Capture, d: CaptureData, palette: Palette, spots: List<Pt>, units: (Float) -> String): File {
        val scale = 8f
        val w = (PW * scale).toInt()
        val h = (PH * scale).toInt()
        val bar = 96
        val out = Bitmap.createBitmap(w + bar, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(0xFF0B0B0F.toInt())
        val (lo, hi) = c.range(d)
        val img = Bitmap.createScaledBitmap(render(d.temps, palette, lo, hi), w, h, true)
        cv.drawBitmap(img, 0f, 0f, null)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 1f, 0xE6000000.toInt())
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2.5f; style = Paint.Style.STROKE; setShadowLayer(2f, 0f, 0f, 0xE6000000.toInt()) }
        spots.forEachIndexed { n, p ->
            val x = p.x * scale; val y = p.y * scale
            cv.drawLine(x - 20, y, x - 6, y, line); cv.drawLine(x + 6, y, x + 20, y, line)
            cv.drawLine(x, y - 20, x, y - 6, line); cv.drawLine(x, y + 6, x, y + 20, line)
            cv.drawText("${n + 1}  ${units(d.reading(p).c)}", x + 14, y - 14, text)
        }
        val hot = d.hottest
        val hx = (hot % PW + 0.5f) * scale; val hy = (hot / PW + 0.5f) * scale
        cv.drawCircle(hx, hy, 10f, Paint(line).apply { color = 0xFFFF4D4D.toInt(); strokeWidth = 4f })
        // colour scale
        val grad = Paint().apply {
            shader = LinearGradient(0f, 40f, 0f, h - 40f, palette.colors.reversedArray(), null, Shader.TileMode.CLAMP)
        }
        cv.drawRoundRect(RectF(w + 38f, 40f, w + 58f, h - 40f), 10f, 10f, grad)
        val small = Paint(text).apply { textSize = 22f; textAlign = Paint.Align.CENTER }
        cv.drawText(units(hi), w + bar / 2f, 30f, small)
        cv.drawText(units(lo), w + bar / 2f, h - 12f, small)
        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "kelvin_${c.id}.png")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** Writes the temperatures as CSV (PH rows × PW columns) to Downloads/Kelvin; returns the file name. */
    fun exportCsv(c: Capture, d: CaptureData, unitLabel: String, convert: (Float) -> Float): String? {
        val name = "kelvin_${c.id}.csv"
        val sb = StringBuilder()
        sb.append("# Kelvin capture ${DISPLAY.format(Date(c.time))}\n")
        sb.append("# emissivity ${c.emissivity}; reflected ${c.reflectedC} °C; unit $unitLabel; ${PW}x$PH, row-major from top-left\n")
        for (y in 0 until PH) {
            for (x in 0 until PW) {
                if (x > 0) sb.append(',')
                sb.append(String.format(Locale.US, "%.2f", convert(d.temps[y * PW + x])))
            }
            sb.append('\n')
        }
        return writeDownload(name, sb.toString())
    }

    private fun writeDownload(name: String, csv: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Kelvin")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } ?: return null
        return name
    }

    private fun exportVideo(name: String, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Kelvin")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        return uri
    }

    private fun exportImage(name: String, bmp: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kelvin")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }

    companion object {
        private val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        private val DISPLAY = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
