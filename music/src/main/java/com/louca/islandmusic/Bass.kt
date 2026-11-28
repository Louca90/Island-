package com.louca.islandmusic

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Bass level 0..1 for the background.
 * - This phone plays: Android's Visualizer on the output (the actual song, bins under ~150 Hz).
 * - Another device plays (PC, speaker): the microphone hears the room; a low-pass keeps the kicks and bass.
 * Auto gain: the level is relative to the recent loudest bass, so it moves the same at low or high volume.
 */
object Bass {
    val level = MutableStateFlow(0f)
    val source = MutableStateFlow("")          // "", "lecture", "micro"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var viz: Visualizer? = null

    fun start(ctx: Context) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val local = ctx.getSystemService(AudioManager::class.java).isMusicActive
                if (local) runVisualizer() else runMic()
                delay(300)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { viz?.enabled = false; viz?.release() }; viz = null
        level.value = 0f; source.value = ""
    }

    private var peak = 1e-3f
    private var smooth = 0f

    /** Fast attack, slow release, normalised by a slowly decaying peak. */
    private fun push(raw: Float) {
        peak = max(raw, peak * 0.995f).coerceAtLeast(1e-4f)
        val v = (raw / peak).coerceIn(0f, 1f)
        smooth = if (v > smooth) smooth + (v - smooth) * .6f else smooth + (v - smooth) * .12f
        level.value = smooth
    }

    private suspend fun runVisualizer() {
        val v = runCatching {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                enabled = true
            }
        }.getOrNull() ?: run { runMic(); return }
        viz = v
        source.value = "lecture"
        val fft = ByteArray(v.captureSize)
        val rate = v.samplingRate / 1000f // Hz
        val binHz = rate / v.captureSize
        val lastBin = (150f / binHz).toInt().coerceIn(1, v.captureSize / 2 - 1)
        try {
            while (scope.isActive && job?.isActive == true) {
                if (v.getFft(fft) == Visualizer.SUCCESS) {
                    var e = 0f
                    for (k in 1..lastBin) e += hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
                    push(e / lastBin)
                }
                delay(33)
                // the phone stopped playing (music moved to another device): switch to the mic
                if (!AudioStateHolder.musicActive()) break
            }
        } finally {
            runCatching { v.enabled = false; v.release() }; viz = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runMic() {
        val rate = 22050
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 4096))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 4096)) }
                .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: return
        source.value = "micro"
        val buf = ShortArray(735) // ~33 ms
        // one-pole low-pass ≈ 140 Hz: keeps kick drum and bass line, drops voices
        val a = 1f - kotlin.math.exp(-2f * Math.PI.toFloat() * 140f / rate)
        var lp = 0f
        try {
            rec.startRecording()
            while (scope.isActive && job?.isActive == true) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { delay(30); continue }
                var sum = 0f
                for (i in 0 until n) { lp += a * (buf[i] - lp); sum += lp * lp }
                push(sqrt(sum / n))
                if (AudioStateHolder.musicActive()) break
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
        }
    }
}

/** Lets the bass loops check playback without holding a Context. */
object AudioStateHolder {
    lateinit var am: AudioManager
    fun musicActive() = ::am.isInitialized && am.isMusicActive
}
