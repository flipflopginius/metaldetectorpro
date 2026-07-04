package com.tetranova.metaldetectorpro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Motore audio aggiornato per MetalDetectorPro.
 * - Frequenza sussurro base: 100 Hz
 * - Usage: MEDIA (per massimo volume e fedeltà)
 */
class VectorAudioEngine {

    companion object {
        private const val TAG = "VectorAudioEngine"
        private const val SAMPLE_RATE = 44100

        // ★ Modificato: 100Hz per maggiore efficienza con gli speaker del telefono
        private const val FREQ_WHISPER_MIN = 100f
        private const val FREQ_WHISPER_MAX = 200f
        private const val AMP_WHISPER_MIN = 0.40f
        private const val AMP_WHISPER_MAX = 0.85f
        private const val MAX_DIST_WHISPER = 0.30f

        private const val FREQ_FERROUS = 550f
        private const val FREQ_NONFERROUS = 950f
        private const val AMP_TARGET = 1.0f

        private const val AMP_HEADROOM = 0.99f // Spinto al limite per volume max

        private const val ALPHA = 0.01f
        private const val CHUNK_SIZE = 512
    }

    @Volatile private var targetFreq = FREQ_WHISPER_MIN
    @Volatile private var targetAmp = AMP_WHISPER_MIN
    @Volatile private var isRunning = false

    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null

    fun update(result: VectorResult) {
        if (!isRunning) return

        val t = (result.vectorDistance / MAX_DIST_WHISPER).coerceIn(0f, 1f)

        if (result.isDetected) {
            when (result.metalType.trim()) {
                "FERRO" -> {
                    targetFreq = FREQ_FERROUS
                    targetAmp = AMP_TARGET
                }
                "NON_FERRO" -> {
                    targetFreq = FREQ_NONFERROUS
                    targetAmp = AMP_TARGET
                }
                else -> {
                    targetFreq = FREQ_WHISPER_MIN + (FREQ_WHISPER_MAX - FREQ_WHISPER_MIN) * t
                    targetAmp = AMP_WHISPER_MIN + (AMP_WHISPER_MAX - AMP_WHISPER_MIN) * t
                }
            }
        } else {
            targetFreq = FREQ_WHISPER_MIN + (FREQ_WHISPER_MAX - FREQ_WHISPER_MIN) * t
            targetAmp = AMP_WHISPER_MIN + (AMP_WHISPER_MAX - AMP_WHISPER_MIN) * t
        }
    }

    fun start() {
        if (isRunning) return

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufSize = minBuf.coerceAtLeast(CHUNK_SIZE * 4)

        audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA) // ★ Modificato per volume massimo
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile creare AudioTrack", e)
            return
        }

        isRunning = true

        audioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val track = audioTrack ?: return@Thread
            val buf = ShortArray(CHUNK_SIZE)

            var phase = 0.0
            var currentFreq = targetFreq
            var currentAmp = targetAmp

            try {
                track.play()

                while (isRunning) {
                    val tf = targetFreq
                    val ta = targetAmp

                    for (i in buf.indices) {
                        currentFreq += (tf - currentFreq) * ALPHA
                        currentAmp += (ta - currentAmp) * ALPHA

                        phase += (currentFreq / SAMPLE_RATE) * 2.0 * PI
                        if (phase >= 2.0 * PI) phase -= 2.0 * PI

                        val sample = (sin(phase) * currentAmp * AMP_HEADROOM * 32767.0)
                        buf[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }

                    if (track.write(buf, 0, CHUNK_SIZE) < 0) break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore loop audio", e)
            } finally {
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                        track.flush()
                        track.stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Errore chiusura track", e)
                }
                isRunning = false
            }
        }, "AudioGen").apply {
            start()
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        audioThread?.interrupt()
        audioThread?.join(500)
        audioTrack?.release()
        audioTrack = null
        audioThread = null
    }
}