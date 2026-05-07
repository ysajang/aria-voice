package com.ysajang.ariavoice.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Speaker verification using SpeechBrain ECAPA-TDNN (ONNX).
 *
 * Model expects: waveform (1, N) float32, raw PCM short→float (NOT pre-normalized).
 *               Normalization is done inside the ONNX graph.
 * Model output:  embedding (1, 192) float32, L2-normalized.
 *
 * Thread-safety: [verify] and [extractEmbedding] are blocking but safe to call
 *                from any thread; ONNX Runtime is thread-safe per session.
 */
class SpeakerVerifier(private val context: Context) {

    companion object {
        private const val TAG             = "SpeakerVerifier"
        private const val MODEL_ASSET     = "ecapa_speaker.onnx"
        private const val EMB_DIM         = 192
        private const val PREF_FILE       = "aria_speaker_prefs"
        private const val KEY_EMBEDDING   = "enrolled_embedding"
        private const val KEY_THRESHOLD   = "verify_threshold"

        /** Default cosine-similarity threshold. Tune based on false-accept/reject logs. */
        const val DEFAULT_THRESHOLD       = 0.72f

        /** Minimum audio samples to accept (1 second at 16kHz). */
        private const val MIN_SAMPLES     = 16_000

        /** Audio window sent to model (~1.5 sec at 16kHz). */
        private const val VERIFY_SAMPLES  = 24_000
    }

    // ── ONNX Runtime ──────────────────────────────────────────────────────────
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    // ── Encrypted storage ─────────────────────────────────────────────────────
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Load ONNX model from assets. Call once (e.g. in Service.onCreate).
     * @throws IllegalStateException if asset not found.
     */
    fun initialize() {
        if (ortSession != null) return
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val opts  = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        ortSession = ortEnv.createSession(bytes, opts)
        Log.i(TAG, "ONNX session initialized (model: $MODEL_ASSET, ${bytes.size / 1024} KB)")
    }

    fun release() {
        ortSession?.close()
        ortSession = null
    }

    // ── Core inference ────────────────────────────────────────────────────────

    /**
     * Extract speaker embedding from raw PCM audio.
     *
     * @param audioRaw  FloatArray from AudioRecorder (raw short→float, NOT normalized).
     *                  Values expected in ~[-32768, 32768].
     * @return FloatArray(192) L2-normalized, or null on failure.
     */
    fun extractEmbedding(audioRaw: FloatArray): FloatArray? {
        val session = ortSession ?: run {
            Log.e(TAG, "Not initialized — call initialize() first")
            return null
        }
        if (audioRaw.size < MIN_SAMPLES) {
            Log.w(TAG, "Audio too short: ${audioRaw.size} samples (min $MIN_SAMPLES)")
            return null
        }

        // Use exactly VERIFY_SAMPLES: trim or zero-pad
        val samples = when {
            audioRaw.size >= VERIFY_SAMPLES -> audioRaw.copyOfRange(
                audioRaw.size - VERIFY_SAMPLES, audioRaw.size
            )
            else -> FloatArray(VERIFY_SAMPLES).also { audioRaw.copyInto(it) }
        }

        return try {
            val tensor = OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(samples),
                longArrayOf(1L, samples.size.toLong()),
            )
            val results = session.run(mapOf("waveform" to tensor))
            @Suppress("UNCHECKED_CAST")
            val embedding = (results.get("embedding").get().value as Array<FloatArray>)[0]
            tensor.close()
            results.close()
            embedding
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            null
        }
    }

    // ── Enrollment ────────────────────────────────────────────────────────────

    /**
     * Enroll the owner: compute mean embedding from [audioSamples] (3–5 utterances).
     * Stores the result in EncryptedSharedPreferences.
     *
     * @return true on success, false if inference failed for too many samples.
     */
    fun enroll(audioSamples: List<FloatArray>): Boolean {
        require(audioSamples.size in 3..10) {
            "Provide 3–10 samples, got ${audioSamples.size}"
        }
        val embeddings = audioSamples.mapNotNull { extractEmbedding(it) }
        if (embeddings.size < 3) {
            Log.e(TAG, "Enrollment failed: only ${embeddings.size} valid embeddings")
            return false
        }

        // Average and re-normalize
        val avg = FloatArray(EMB_DIM)
        for (emb in embeddings) {
            for (i in 0 until EMB_DIM) avg[i] += emb[i]
        }
        val norm = sqrt(avg.map { it * it }.sum())
        val normalized = FloatArray(EMB_DIM) { avg[it] / (norm + 1e-8f) }

        prefs.edit()
            .putString(KEY_EMBEDDING, floatArrayToBase64(normalized))
            .apply()

        Log.i(TAG, "Speaker enrolled — ${embeddings.size} samples merged")
        return true
    }

    // ── Verification ──────────────────────────────────────────────────────────

    /**
     * Verify a speaker against the enrolled profile.
     *
     * @param audioRaw  Audio captured around the wake word trigger.
     * @return [VerifyResult] with accepted/rejected + similarity score.
     */
    fun verify(audioRaw: FloatArray): VerifyResult {
        val stored = loadEnrolledEmbedding()
            ?: return VerifyResult(accepted = true, similarity = -1f, reason = "no_enrollment").also {
                Log.w(TAG, "speaker_verify_skip: no enrollment → passthrough")
            }

        val query = extractEmbedding(audioRaw)
            ?: return VerifyResult(accepted = false, similarity = 0f, reason = "inference_failed").also {
                Log.e(TAG, "speaker_rejected: inference_failed")
            }

        val similarity = cosineSimilarity(query, stored)
        val threshold  = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        val accepted   = similarity >= threshold

        if (accepted) {
            Log.i(TAG, "speaker_accepted: similarity=${"%.4f".format(similarity)} threshold=$threshold")
        } else {
            Log.w(TAG, "speaker_rejected: similarity=${"%.4f".format(similarity)} threshold=$threshold")
        }

        return VerifyResult(
            accepted   = accepted,
            similarity = similarity,
            reason     = if (accepted) "verified" else "below_threshold",
        )
    }

    // ── Threshold management ──────────────────────────────────────────────────

    fun getThreshold(): Float = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    fun setThreshold(threshold: Float) {
        require(threshold in 0.5f..0.99f) { "Threshold must be 0.5–0.99" }
        prefs.edit().putFloat(KEY_THRESHOLD, threshold).apply()
        Log.i(TAG, "Threshold updated to $threshold")
    }

    // ── Enrollment state ──────────────────────────────────────────────────────

    fun isEnrolled(): Boolean = prefs.contains(KEY_EMBEDDING)

    fun clearEnrollment() {
        prefs.edit()
            .remove(KEY_EMBEDDING)
            .apply()
        Log.i(TAG, "Speaker enrollment cleared")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun loadEnrolledEmbedding(): FloatArray? {
        val encoded = prefs.getString(KEY_EMBEDDING, null) ?: return null
        return base64ToFloatArray(encoded)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (sqrt(na) * sqrt(nb) + 1e-8f)
    }

    private fun floatArrayToBase64(arr: FloatArray): String {
        val buf = java.nio.ByteBuffer.allocate(arr.size * 4)
        arr.forEach { buf.putFloat(it) }
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    private fun base64ToFloatArray(encoded: String): FloatArray {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val buf   = java.nio.ByteBuffer.wrap(bytes)
        return FloatArray(bytes.size / 4) { buf.getFloat() }
    }
}

// ── Result data class ─────────────────────────────────────────────────────────

data class VerifyResult(
    val accepted:   Boolean,
    val similarity: Float,      // -1.0 = not measured
    val reason:     String,     // "verified" | "below_threshold" | "no_enrollment" | "inference_failed"
)
