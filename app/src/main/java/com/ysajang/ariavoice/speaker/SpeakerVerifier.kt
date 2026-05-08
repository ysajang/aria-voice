package com.ysajang.ariavoice.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Speaker verification using WeSpeaker ECAPA-TDNN (ONNX).
 *
 * Model: wespeaker-ecapa-tdnn512-LM (voxceleb_ECAPA512_LM.onnx, 24.9MB)
 * Input:  FBank features (1, T, 80) float32  — extracted by [FBankExtractor]
 * Output: Speaker embedding (1, 192) float32  — raw (NOT pre-normalized)
 *
 * Pipeline:
 *   AudioRecorder (16kHz PCM short→float)
 *     → FBankExtractor.extract() → (T, 80)
 *     → ONNX inference → (192,)
 *     → cosine similarity with enrolled embedding
 *
 * Thread-safety: [verify] and [extractEmbedding] are blocking but thread-safe
 *                (ONNX Runtime is thread-safe per session).
 */
class SpeakerVerifier(private val context: Context) {

    companion object {
        private const val TAG = "SpeakerVerifier"
        private const val MODEL_ASSET = "ecapa_tdnn.onnx"
        private const val EMB_DIM = 192
        private const val PREF_FILE = "aria_speaker_prefs"
        private const val KEY_EMBEDDING = "enrolled_embedding_v2"
        private const val KEY_THRESHOLD = "verify_threshold_v2"
        private const val KEY_ENROLLED_COUNT = "enrolled_count"

        /** Default cosine-similarity threshold. Tuned for short wake word (~1s). */
        const val DEFAULT_THRESHOLD = 0.45f

        /** Minimum audio samples to process (0.25 sec at 16kHz). */
        private const val MIN_SAMPLES = 4_000

        /** Target audio window for verification (~2 sec at 16kHz). */
        private const val TARGET_SAMPLES = 32_000
    }

    // ── ONNX Runtime ──────────────────────────────────────────────────────
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    // ── Storage ───────────────────────────────────────────────────────────
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Load ONNX model from assets. Call once (e.g. in Service.onCreate).
     * @throws IllegalStateException if model asset not found.
     */
    fun initialize() {
        if (ortSession != null) return
        val startMs = System.currentTimeMillis()
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        ortSession = ortEnv.createSession(bytes, opts)
        val elapsed = System.currentTimeMillis() - startMs
        Log.i(TAG, "ONNX session ready (${bytes.size / 1024}KB, ${elapsed}ms)")
    }

    fun release() {
        ortSession?.close()
        ortSession = null
        Log.i(TAG, "ONNX session released")
    }

    // ── Core inference ────────────────────────────────────────────────────

    /**
     * Extract 192-dim speaker embedding from raw PCM audio.
     *
     * @param audioRaw  FloatArray from AudioRecorder (short→float, ~-32768..32768).
     * @return FloatArray(192) L2-normalized, or null on failure.
     */
    fun extractEmbedding(audioRaw: FloatArray, debugLabel: String? = null): FloatArray? {
        val session = ortSession ?: run {
            Log.e(TAG, "Not initialized — call initialize() first")
            return null
        }
        if (audioRaw.size < MIN_SAMPLES) {
            Log.w(TAG, "Audio too short: ${audioRaw.size} samples (min $MIN_SAMPLES)")
            return null
        }

        // Trim to last TARGET_SAMPLES if longer
        var samples = if (audioRaw.size > TARGET_SAMPLES) {
            audioRaw.copyOfRange(audioRaw.size - TARGET_SAMPLES, audioRaw.size)
        } else {
            audioRaw
        }

        // VAD: trim silence — only keep speech region
        samples = trimSilence(samples)
        if (samples.size < MIN_SAMPLES) {
            Log.w(TAG, "After VAD trim: ${samples.size} samples — too short")
            return null
        }

        // Debug: save raw audio as WAV (after VAD trim)
        if (debugLabel != null) {
            saveDebugWav(samples, debugLabel)
        }

        // FBank feature extraction
        val fbank = FBankExtractor.extract(samples) ?: run {
            Log.e(TAG, "FBank extraction failed")
            return null
        }
        Log.d(TAG, "FBank: ${fbank.size} frames x ${fbank[0].size} bins")

        // Debug: log first FBank frame
        if (debugLabel != null && fbank.isNotEmpty()) {
            val f0 = fbank[0].take(5).joinToString { "%.4f".format(it) }
            Log.d(TAG, "FBank[0][:5] = [$f0]")
        }

        return try {
            // Flatten to 1D for OnnxTensor: shape (1, T, 80)
            val numFrames = fbank.size
            val flat = FloatArray(numFrames * NUM_MEL_BINS)
            for (t in 0 until numFrames) {
                System.arraycopy(fbank[t], 0, flat, t * NUM_MEL_BINS, NUM_MEL_BINS)
            }

            val tensor = OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(flat),
                longArrayOf(1L, numFrames.toLong(), NUM_MEL_BINS.toLong()),
            )
            val results = session.run(mapOf("feats" to tensor))

            @Suppress("UNCHECKED_CAST")
            val rawEmb = (results.get("embs").get().value as Array<FloatArray>)[0]
            tensor.close()
            results.close()

            // L2 normalize
            val emb = l2Normalize(rawEmb)

            // Debug: log embedding stats
            if (debugLabel != null) {
                val norm = sqrt(rawEmb.map { it * it }.sum())
                val e5 = emb.take(5).joinToString { "%.4f".format(it) }
                Log.i(TAG, "[$debugLabel] emb[:5]=[$e5] rawNorm=%.4f".format(norm))
            }

            emb
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference error: ${e.message}", e)
            null
        }
    }

    /**
     * Save raw audio as 16-bit WAV for offline debugging.
     * Files go to app's internal filesDir: /data/data/.../files/debug_*.wav
     */
    private fun saveDebugWav(audio: FloatArray, label: String) {
        try {
            val file = java.io.File(context.filesDir, "debug_${label}.wav")
            val shortData = ShortArray(audio.size) { audio[it].toInt().coerceIn(-32768, 32767).toShort() }
            val byteData = ByteBuffer.allocate(shortData.size * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .apply { shortData.forEach { putShort(it) } }
                .array()

            java.io.FileOutputStream(file).use { fos ->
                val dataSize = byteData.size
                val header = ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray())
                header.putInt(36 + dataSize)
                header.put("WAVE".toByteArray())
                header.put("fmt ".toByteArray())
                header.putInt(16)           // chunk size
                header.putShort(1)          // PCM
                header.putShort(1)          // mono
                header.putInt(16000)        // sample rate
                header.putInt(32000)        // byte rate
                header.putShort(2)          // block align
                header.putShort(16)         // bits per sample
                header.put("data".toByteArray())
                header.putInt(dataSize)
                fos.write(header.array())
                fos.write(byteData)
            }
            Log.i(TAG, "Debug WAV saved: ${file.absolutePath} (${audio.size} samples)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save debug WAV: ${e.message}")
        }
    }

    // ── Enrollment ────────────────────────────────────────────────────────

    /**
     * Enroll owner voice from 3–5 utterances of "아리아".
     * Computes mean embedding and stores in SharedPreferences.
     *
     * @param audioSamples  List of FloatArray (each = one "아리아" utterance).
     * @return true on success.
     */
    fun enroll(audioSamples: List<FloatArray>): Boolean {
        require(audioSamples.size in 3..10) {
            "Need 3–10 samples, got ${audioSamples.size}"
        }

        val embeddings = audioSamples.mapIndexedNotNull { idx, audio ->
            extractEmbedding(audio, debugLabel = "enroll_$idx")
        }
        if (embeddings.size < 3) {
            Log.e(TAG, "Enrollment failed: only ${embeddings.size}/${audioSamples.size} valid")
            return false
        }

        // Average embeddings
        val avg = FloatArray(EMB_DIM)
        for (emb in embeddings) {
            for (i in 0 until EMB_DIM) avg[i] += emb[i]
        }
        for (i in 0 until EMB_DIM) avg[i] /= embeddings.size

        // Re-normalize
        val normalized = l2Normalize(avg)

        // Store
        prefs.edit()
            .putString(KEY_EMBEDDING, floatArrayToBase64(normalized))
            .putInt(KEY_ENROLLED_COUNT, embeddings.size)
            .apply()

        Log.i(TAG, "Enrolled with ${embeddings.size} samples")
        return true
    }

    // ── Verification ──────────────────────────────────────────────────────

    /**
     * Verify speaker against enrolled profile.
     *
     * @param audioRaw  Audio captured around wake word trigger.
     * @return [VerifyResult] — accepted/rejected with similarity score.
     */
    fun verify(audioRaw: FloatArray): VerifyResult {
        val stored = loadEnrolledEmbedding()
            ?: return VerifyResult(
                accepted = true,
                similarity = -1f,
                reason = "no_enrollment",
            ).also {
                Log.w(TAG, "speaker_verify_skip: no enrollment → passthrough")
            }

        val query = extractEmbedding(audioRaw, debugLabel = "verify")
            ?: return VerifyResult(
                accepted = false,
                similarity = 0f,
                reason = "inference_failed",
            ).also {
                Log.e(TAG, "speaker_rejected: inference_failed")
            }

        val similarity = cosineSimilarity(query, stored)
        val threshold = getThreshold()
        val accepted = similarity >= threshold

        // Debug: log both embeddings for comparison
        val storedE5 = stored.take(5).joinToString { "%.4f".format(it) }
        val queryE5 = query.take(5).joinToString { "%.4f".format(it) }
        Log.d(TAG, "stored[:5]=[$storedE5] query[:5]=[$queryE5]")

        if (accepted) {
            Log.i(TAG, "speaker_accepted: sim=${"%.4f".format(similarity)} thr=$threshold")
        } else {
            Log.w(TAG, "speaker_rejected: sim=${"%.4f".format(similarity)} thr=$threshold")
        }

        return VerifyResult(
            accepted = accepted,
            similarity = similarity,
            reason = if (accepted) "verified" else "below_threshold",
        )
    }

    // ── Threshold ─────────────────────────────────────────────────────────

    fun getThreshold(): Float = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    fun setThreshold(value: Float) {
        require(value in 0.3f..0.99f) { "Threshold must be 0.3–0.99" }
        prefs.edit().putFloat(KEY_THRESHOLD, value).apply()
        Log.i(TAG, "Threshold → $value")
    }

    // ── Enrollment state ──────────────────────────────────────────────────

    fun isEnrolled(): Boolean = prefs.contains(KEY_EMBEDDING)

    fun enrolledSampleCount(): Int = prefs.getInt(KEY_ENROLLED_COUNT, 0)

    fun clearEnrollment() {
        prefs.edit()
            .remove(KEY_EMBEDDING)
            .remove(KEY_ENROLLED_COUNT)
            .remove(KEY_THRESHOLD)
            .apply()
        Log.i(TAG, "Enrollment cleared (threshold reset to default)")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Simple energy-based VAD: trim leading/trailing silence.
     * Keeps only the speech region + padding.
     */
    private fun trimSilence(audio: FloatArray): FloatArray {
        val frameSize = 320    // 20ms @ 16kHz
        val hopSize = 160      // 10ms hop
        val minOutputSamples = 16000  // keep at least 1 second
        val numFrames = (audio.size - frameSize) / hopSize + 1
        if (numFrames < 3) return audio

        // Compute frame-level RMS energy
        val energy = FloatArray(numFrames) { f ->
            val offset = f * hopSize
            var sum = 0f
            for (i in 0 until frameSize) {
                val s = audio[offset + i]
                sum += s * s
            }
            sqrt(sum / frameSize)
        }

        // Adaptive threshold: 15% of max energy
        val maxEnergy = energy.maxOrNull() ?: return audio
        val threshold = maxEnergy * 0.15f

        // Find speech boundaries
        var firstActive = 0
        for (i in energy.indices) {
            if (energy[i] > threshold) { firstActive = i; break }
        }
        var lastActive = energy.size - 1
        for (i in energy.indices.reversed()) {
            if (energy[i] > threshold) { lastActive = i; break }
        }

        // Convert to sample indices
        var startSample = firstActive * hopSize
        var endSample = minOf(audio.size, lastActive * hopSize + frameSize)

        // Ensure minimum output length — expand symmetrically around speech center
        val currentLen = endSample - startSample
        if (currentLen < minOutputSamples) {
            val center = (startSample + endSample) / 2
            startSample = maxOf(0, center - minOutputSamples / 2)
            endSample = minOf(audio.size, startSample + minOutputSamples)
            if (endSample - startSample < minOutputSamples) {
                startSample = maxOf(0, endSample - minOutputSamples)
            }
        }

        val trimmed = audio.copyOfRange(startSample, endSample)
        Log.d(TAG, "VAD trim: ${audio.size} → ${trimmed.size} samples " +
                "(maxE=%.0f thr=%.0f)".format(maxEnergy, threshold))
        return trimmed
    }

    private fun loadEnrolledEmbedding(): FloatArray? {
        val encoded = prefs.getString(KEY_EMBEDDING, null) ?: return null
        return base64ToFloatArray(encoded)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb) + 1e-8f)
    }

    private fun l2Normalize(arr: FloatArray): FloatArray {
        val norm = sqrt(arr.map { it * it }.sum())
        return FloatArray(arr.size) { arr[it] / (norm + 1e-8f) }
    }

    private fun floatArrayToBase64(arr: FloatArray): String {
        val buf = ByteBuffer.allocate(arr.size * 4)
        arr.forEach { buf.putFloat(it) }
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    private fun base64ToFloatArray(encoded: String): FloatArray {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val buf = ByteBuffer.wrap(bytes)
        return FloatArray(bytes.size / 4) { buf.getFloat() }
    }
}

private const val NUM_MEL_BINS = 80

// ── Result ────────────────────────────────────────────────────────────────

data class VerifyResult(
    val accepted: Boolean,
    val similarity: Float,      // -1.0 = not measured (no enrollment)
    val reason: String,         // "verified" | "below_threshold" | "no_enrollment" | "inference_failed"
)
