package com.gilles.flirone

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One logged frame: wall-clock [time] and the whole portrait PW×PH temperature image (°C). */
class TimelapseSample(val time: Long, val temps: FloatArray) {
    val max: Float = temps.max()
    val min: Float = temps.min()
    val avg: Float = temps.average().toFloat()
}

/**
 * Timelapse logging: keeps one whole frame every [INTERVAL_MS] while running. App-level, so it keeps
 * logging while other screens are open (the stream stays on while it runs).
 */
class TimelapseLogger {
    val samples = mutableStateListOf<TimelapseSample>()
    var running by mutableStateOf(false)
        private set
    private var lastAt = 0L

    fun start() {
        samples.clear()
        lastAt = 0L
        running = true
    }

    /** Called for every camera frame; samples the first frame and then every [INTERVAL_MS]. */
    fun offer(raw: IntArray, s: ViewSettings) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (lastAt != 0L && now - lastAt < INTERVAL_MS) return
        lastAt = now
        val a = Thermal.analyze(raw, s.copy(alarmOn = false), isoPreview = false)
        samples.add(TimelapseSample(System.currentTimeMillis(), a.temps))
    }

    /** Stops logging; returns what was logged (the list stays readable until the next start). */
    fun stop(): List<TimelapseSample> {
        running = false
        return samples.toList()
    }

    companion object {
        const val INTERVAL_MS = 30_000L
    }
}
