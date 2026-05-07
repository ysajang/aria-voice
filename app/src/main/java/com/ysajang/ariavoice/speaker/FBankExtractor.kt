package com.ysajang.ariavoice.speaker

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WeSpeaker-compatible FBank feature extractor (Kaldi-style).
 *
 * Config (from wespeaker-ecapa-tdnn512-LM/config.yaml):
 *   frame_length = 25ms  →  400 samples @ 16kHz
 *   frame_shift  = 10ms  →  160 samples @ 16kHz
 *   num_mel_bins = 80
 *   n_fft        = 512
 *
 * Pipeline: pre-emphasis → framing → hamming → FFT → mel filterbank → log → CMVN
 *
 * Thread-safety: stateless — safe to call from any thread.
 */
object FBankExtractor {

    private const val SAMPLE_RATE = 16000
    private const val FRAME_LENGTH_MS = 25
    private const val FRAME_SHIFT_MS = 10
    private const val NUM_MEL_BINS = 80
    private const val N_FFT = 512
    private const val PRE_EMPHASIS = 0.97f

    private val FRAME_LENGTH = SAMPLE_RATE * FRAME_LENGTH_MS / 1000   // 400
    private val FRAME_SHIFT = SAMPLE_RATE * FRAME_SHIFT_MS / 1000     // 160
    private val FFT_SIZE = N_FFT / 2 + 1                               // 257

    /** Pre-computed Hamming window (400 samples). */
    private val hammingWindow: FloatArray = FloatArray(FRAME_LENGTH) { n ->
        (0.54 - 0.46 * cos(2.0 * PI * n / (FRAME_LENGTH - 1))).toFloat()
    }

    /** Pre-computed mel filterbank matrix (80 x 257). */
    private val melFilterbank: Array<FloatArray> = buildMelFilterbank()

    /**
     * Extract 80-dim FBank features from raw PCM audio.
     *
     * @param audio  FloatArray from AudioRecorder (raw short→float, values ~-32768..32768).
     *               Will be normalized internally.
     * @return Array of FloatArray — shape (numFrames, 80).
     *         Returns null if audio is too short (< 1 frame).
     */
    fun extract(audio: FloatArray): Array<FloatArray>? {
        if (audio.size < FRAME_LENGTH) return null

        // Normalize to [-1, 1]
        val normalized = FloatArray(audio.size) { audio[it] / 32768f }

        // Pre-emphasis
        val emphasized = FloatArray(normalized.size)
        emphasized[0] = normalized[0]
        for (i in 1 until normalized.size) {
            emphasized[i] = normalized[i] - PRE_EMPHASIS * normalized[i - 1]
        }

        // Framing
        val numFrames = 1 + (emphasized.size - FRAME_LENGTH) / FRAME_SHIFT
        if (numFrames < 1) return null

        val logMel = Array(numFrames) { FloatArray(NUM_MEL_BINS) }

        for (f in 0 until numFrames) {
            val offset = f * FRAME_SHIFT

            // Windowed frame
            val frame = FloatArray(N_FFT) // zero-padded to N_FFT
            for (i in 0 until FRAME_LENGTH) {
                frame[i] = emphasized[offset + i] * hammingWindow[i]
            }

            // FFT → power spectrum
            val (real, imag) = fft(frame)
            val powerSpectrum = FloatArray(FFT_SIZE) { i ->
                real[i] * real[i] + imag[i] * imag[i]
            }

            // Mel filterbank → log
            for (m in 0 until NUM_MEL_BINS) {
                var energy = 0f
                val filter = melFilterbank[m]
                for (k in filter.indices) {
                    energy += powerSpectrum[k] * filter[k]
                }
                logMel[f][m] = log(maxOf(energy, 1e-10f))
            }
        }

        // Per-utterance CMVN (mean normalization)
        val mean = FloatArray(NUM_MEL_BINS)
        for (f in 0 until numFrames) {
            for (m in 0 until NUM_MEL_BINS) {
                mean[m] += logMel[f][m]
            }
        }
        for (m in 0 until NUM_MEL_BINS) {
            mean[m] /= numFrames
        }
        for (f in 0 until numFrames) {
            for (m in 0 until NUM_MEL_BINS) {
                logMel[f][m] -= mean[m]
            }
        }

        return logMel
    }

    // ── FFT (Cooley-Tukey radix-2, N=512) ────────────────────────────────

    private fun fft(input: FloatArray): Pair<FloatArray, FloatArray> {
        val n = input.size // must be power of 2
        val real = input.copyOf()
        val imag = FloatArray(n)

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tmpR = real[i]; real[i] = real[j]; real[j] = tmpR
                val tmpI = imag[i]; imag[i] = imag[j]; imag[j] = tmpI
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m
                m /= 2
            }
            j += m
        }

        // Butterfly
        var step = 2
        while (step <= n) {
            val halfStep = step / 2
            val angle = -2.0 * PI / step
            for (group in 0 until n step step) {
                for (k in 0 until halfStep) {
                    val theta = angle * k
                    val wr = cos(theta).toFloat()
                    val wi = sin(theta).toFloat()

                    val evenIdx = group + k
                    val oddIdx = group + k + halfStep

                    val tR = wr * real[oddIdx] - wi * imag[oddIdx]
                    val tI = wr * imag[oddIdx] + wi * real[oddIdx]

                    real[oddIdx] = real[evenIdx] - tR
                    imag[oddIdx] = imag[evenIdx] - tI
                    real[evenIdx] = real[evenIdx] + tR
                    imag[evenIdx] = imag[evenIdx] + tI
                }
            }
            step *= 2
        }

        return Pair(real, imag)
    }

    // ── Mel filterbank construction ──────────────────────────────────────

    private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

    private fun buildMelFilterbank(): Array<FloatArray> {
        val lowFreqMel = hzToMel(0f)
        val highFreqMel = hzToMel(SAMPLE_RATE / 2f)

        val melPoints = FloatArray(NUM_MEL_BINS + 2) { i ->
            lowFreqMel + i * (highFreqMel - lowFreqMel) / (NUM_MEL_BINS + 1)
        }
        val hzPoints = FloatArray(melPoints.size) { melToHz(melPoints[it]) }
        val binPoints = IntArray(hzPoints.size) { i ->
            floor((N_FFT + 1).toFloat() * hzPoints[i] / SAMPLE_RATE).toInt()
        }

        return Array(NUM_MEL_BINS) { m ->
            val filter = FloatArray(FFT_SIZE)
            val fLeft = binPoints[m]
            val fCenter = binPoints[m + 1]
            val fRight = binPoints[m + 2]

            for (k in fLeft until fCenter) {
                if (k < FFT_SIZE && fCenter != fLeft) {
                    filter[k] = (k - fLeft).toFloat() / (fCenter - fLeft)
                }
            }
            for (k in fCenter until fRight) {
                if (k < FFT_SIZE && fRight != fCenter) {
                    filter[k] = (fRight - k).toFloat() / (fRight - fCenter)
                }
            }
            filter
        }
    }
}
