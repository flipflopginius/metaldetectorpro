package com.tetranova.prerex

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Motore audio in stile VLF classico (soglia continua + tono discriminante), come nei
 * detector commerciali a discriminazione (Garrett/Fisher/Minelab):
 * - Sotto soglia: un ronzio di soglia COSTANTE (frequenza e volume fissi), che NON segue
 *   l'avvicinamento del segnale. Serve solo come riferimento uditivo continuo, non come
 *   anticipazione del bersaglio — un ronzio che "gonfia" prima del trigger reale abitua
 *   l'orecchio e genera falsi allarmi percepiti.
 * - Bersaglio rilevato: il tono cambia bruscamente in uno dei due toni di discriminazione
 *   (grave per ferroso, acuto per non ferroso), con volume proporzionale alla forza del
 *   segnale (vr.confidence, già calcolata da VectorProcessor sull'ampiezza reale) — dà
 *   un feedback di "quanto è forte/vicino" il bersaglio, come sui detector professionali.
 */
class VectorAudioEngine {

    companion object {
        private const val TAG = "VectorAudioEngine"
        private const val SAMPLE_RATE = 44100

        // Ronzio di soglia: fisso, non proporzionale al segnale.
        private const val FREQ_THRESHOLD = 200f
        private const val AMP_THRESHOLD = 0.12f

        // Toni di discriminazione: grave per ferroso, acuto per non ferroso (stile VLF).
        private const val FREQ_FERROUS = 420f
        private const val FREQ_NONFERROUS = 900f
        // Bersaglio ambiguo (raro in detection reale grazie al Target ID Lock, ma
        // gestito comunque): tono intermedio, distinguibile da entrambi gli altri due.
        private const val FREQ_AMBIGUOUS = 650f

        // Volume del tono di rilevazione: proporzionale alla confidenza (0-100%), tra
        // un minimo udibile e il massimo — dà feedback di intensità del bersaglio.
        private const val AMP_TARGET_MIN = 0.55f
        private const val AMP_TARGET_MAX = 1.0f

        private const val AMP_HEADROOM = 0.99f

        // Attacco rapido quando scatta un bersaglio (risposta a "colpo secco"), rilascio
        // leggermente più lento per non produrre click bruschi tornando al ronzio di soglia.
        private const val ALPHA_ATTACK = 0.04f
        private const val ALPHA_RELEASE = 0.015f
        private const val CHUNK_SIZE = 512
    }

    @Volatile private var targetFreq = FREQ_THRESHOLD
    @Volatile private var targetAmp = AMP_THRESHOLD
    @Volatile private var rising = false

    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    @Volatile private var isRunning = false

    fun update(result: VectorResult) {
        if (!isRunning) return

        if (result.isDetected) {
            val newFreq = when (result.metalType.trim()) {
                "FERRO" -> FREQ_FERROUS
                "NON_FERRO" -> FREQ_NONFERROUS
                else -> FREQ_AMBIGUOUS
            }
            val strength = (result.confidence / 100f).coerceIn(0f, 1f)
            rising = newFreq > targetFreq
            targetFreq = newFreq
            targetAmp = AMP_TARGET_MIN + (AMP_TARGET_MAX - AMP_TARGET_MIN) * strength
        } else {
            rising = FREQ_THRESHOLD > targetFreq
            targetFreq = FREQ_THRESHOLD
            targetAmp = AMP_THRESHOLD
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
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
        targetFreq = FREQ_THRESHOLD
        targetAmp = AMP_THRESHOLD

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
                    // Attacco più rapido quando il segnale sale (rilevazione che scatta),
                    // rilascio più morbido quando scende (ritorno alla soglia).
                    val alpha = if (rising) ALPHA_ATTACK else ALPHA_RELEASE

                    for (i in buf.indices) {
                        currentFreq += (tf - currentFreq) * alpha
                        currentAmp += (ta - currentAmp) * alpha

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
