package be.apexlab.kelvin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.max

/**
 * Video recording: every camera frame is drawn (the selected view, spot reading, hottest/coldest markers
 * and colour scale) onto an H.264 encoder's input surface and muxed to MP4. Encoding runs on its own
 * thread; the result becomes a video capture.
 */
class VideoRecorder(private val captures: Captures, private val cacheDir: File) {
    var recording by mutableStateOf(false)
        private set
    /** elapsedRealtime of the start, for the REC timer. */
    var startedAt by mutableLongStateOf(0L)
        private set

    private var session: Session? = null

    fun start(s: ViewSettings, info: CameraInfo): Boolean {
        if (recording) return true
        val sess = runCatching { Session(File(cacheDir, "recording.mp4"), s, info) }
            .onFailure { Log.w(TAG, "encoder setup failed", it) }.getOrNull() ?: return false
        session = sess
        startedAt = SystemClock.elapsedRealtime()
        recording = true
        return true
    }

    /** Called on the main thread for every camera frame. */
    fun offer(frame: CameraController.LiveFrame, s: ViewSettings) {
        session?.post(frame, s)
    }

    /** Stops and saves; [done] runs on the main thread with the new capture (null if nothing was recorded). */
    fun stop(done: (Capture?) -> Unit) {
        val sess = session ?: return done(null)
        session = null
        recording = false
        sess.finish(done)
    }

    private inner class Session(val file: File, val startSettings: ViewSettings, val info: CameraInfo) {
        private val thread = HandlerThread("video").apply { start() }
        private val handler = Handler(thread.looper)
        private val main = Handler(android.os.Looper.getMainLooper())
        private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val surface: Surface
        private val bufferInfo = MediaCodec.BufferInfo()
        private var track = -1
        private var firstAt = 0L
        private var lastAt = 0L
        private var frames = 0
        private var min = Float.NaN
        private var max = Float.NaN
        private var thumb: Bitmap? = null
        private val canvasBitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)

        init {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 9)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
        }

        fun post(frame: CameraController.LiveFrame, s: ViewSettings) {
            handler.post {
                runCatching { draw(frame, s) }.onFailure { Log.w(TAG, "frame failed", it) }
                drain(false)
            }
        }

        private fun draw(frame: CameraController.LiveFrame, s: ViewSettings) {
            val a = Thermal.analyze(frame.raw, s, isoPreview = false)
            // finite full-frame extremes straight from the analysis (no extra per-frame stats pass); an all-invalid frame contributes nothing
            if (a.autoMin.isFinite()) min = if (min.isNaN()) a.autoMin else minOf(min, a.autoMin)
            if (a.autoMax.isFinite()) max = if (max.isNaN()) a.autoMax else maxOf(max, a.autoMax)
            val cv = Canvas(canvasBitmap)
            render(cv, a, frame, s)
            if (thumb == null) thumb = Bitmap.createScaledBitmap(canvasBitmap, W / 2, H / 2, true)
            val c = surface.lockHardwareCanvas()
            try { c.drawBitmap(canvasBitmap, 0f, 0f, null) } finally { surface.unlockCanvasAndPost(c) }
            val now = SystemClock.elapsedRealtime()
            if (frames == 0) firstAt = now
            lastAt = now
            frames++
        }

        private fun render(cv: Canvas, a: Analysis, f: CameraController.LiveFrame, s: ViewSettings) {
            cv.drawColor(0xFF000000.toInt())
            val dst = Rect(0, 0, W, H)
            val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
            val thermal = Bitmap.createBitmap(a.pixels, PW, PH, Bitmap.Config.ARGB_8888)
            val view = if (s.alarmOn) ViewMode.Thermal else s.view
            when (view) {
                ViewMode.Thermal -> cv.drawBitmap(thermal, null, dst, smooth)
                ViewMode.Visible -> f.visible?.let { cv.drawBitmap(it, null, dst, smooth) } ?: cv.drawBitmap(thermal, null, dst, smooth)
                ViewMode.Blend -> {
                    f.visible?.let { cv.drawBitmap(it, null, dst, smooth) }
                    cv.drawBitmap(thermal, null, dst, Paint(smooth).apply { alpha = (s.blend * 255).toInt() })
                }
                ViewMode.Edges -> {
                    cv.drawBitmap(thermal, null, dst, smooth)
                    f.edges?.let { cv.drawBitmap(it, null, dst, Paint(smooth).apply { alpha = 217 }) }
                }
            }
            val sx = W.toFloat() / PW
            val sy = H.toFloat() / PH
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE; setShadowLayer(3f, 0f, 0f, 0xCC000000.toInt())
            }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = 34f; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(5f, 0f, 1f, 0xE6000000.toInt())
            }
            // hottest / coldest markers only where a valid extreme exists
            if (a.hasValidReadings) {
                val marker = Paint(line).apply { strokeWidth = 4f }
                a.hot?.let { p ->
                    marker.color = 0xFFFF4D4D.toInt()
                    cv.drawCircle(p.x * sx, p.y * sy, 11f, marker)
                }
                a.cold?.let { p ->
                    marker.color = 0xFF4DA3FF.toInt()
                    cv.drawCircle(p.x * sx, p.y * sy, 11f, marker)
                }
            }
            if (!a.hasValidReadings) {
                val warn = Paint(text).apply { color = 0xFFF5B83D.toInt(); textAlign = Paint.Align.CENTER }
                cv.drawText("NO VALID TEMPERATURES", W / 2f, H / 2f, warn)
            }
            // spot crosshair and reading
            val x = s.spotA.x * sx
            val y = s.spotA.y * sy
            cv.drawLine(x - 26, y, x - 8, y, line); cv.drawLine(x + 8, y, x + 26, y, line)
            cv.drawLine(x, y - 26, x, y - 8, line); cv.drawLine(x, y + 8, x, y + 26, line)
            val spot = a.at(s.spotA)
            val label = spot.text + if (spot.valid) " " + Units.label else ""
            val tx = if (x + 36 + text.measureText(label) > W - 70) x - 36 - text.measureText(label) else x + 36
            cv.drawText(label, tx, y + 12, text)
            // colour scale
            val top = 70f
            val bottom = H - 70f
            val grad = Paint().apply { shader = LinearGradient(0f, top, 0f, bottom, s.palette.colors.reversedArray(), null, Shader.TileMode.CLAMP) }
            cv.drawRoundRect(RectF(W - 34f, top, W - 22f, bottom), 6f, 6f, grad)
            val small = Paint(text).apply { textSize = 24f; textAlign = Paint.Align.RIGHT }
            cv.drawText(Units.t(a.rangeMax), W - 14f, top - 16, small)
            cv.drawText(Units.t(a.rangeMin), W - 14f, bottom + 36, small)
            val info = Paint(text).apply { textSize = 22f; typeface = Typeface.MONOSPACE }
            cv.drawText("ε %.2f".format(java.util.Locale.US, s.emissivity), 16f, H - 18f, info)
        }

        private fun drain(end: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + if (end) 2_000 else 0
            while (true) {
                val idx = codec.dequeueOutputBuffer(bufferInfo, if (end) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || SystemClock.elapsedRealtime() > deadline) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        val config = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && bufferInfo.size > 0 && track >= 0) muxer.writeSampleData(track, buf, bufferInfo)
                        codec.releaseOutputBuffer(idx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun finish(done: (Capture?) -> Unit) {
            handler.post {
                var saved: Capture? = null
                try {
                    codec.signalEndOfInputStream()
                    drain(true)
                    codec.stop()
                    codec.release()
                    surface.release()
                    if (track >= 0) muxer.stop()
                    muxer.release()
                    val t = thumb
                    if (frames >= 2 && t != null) {
                        val durationMs = max(lastAt - firstAt, 0L)
                        val wallStart = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - firstAt)
                        saved = captures.saveVideo(file, wallStart, durationMs, min, max, t, startSettings, info)
                    } else file.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "finishing the recording failed", e)
                    file.delete()
                }
                thread.quitSafely()
                main.post { done(saved) }
            }
        }
    }

    companion object {
        private const val TAG = "VideoRecorder"
        /** Output size: 8× the portrait sensor image. */
        const val W = PW * 8
        const val H = PH * 8
    }
}
