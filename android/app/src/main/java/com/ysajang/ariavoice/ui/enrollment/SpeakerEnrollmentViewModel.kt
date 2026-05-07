package com.ysajang.ariavoice.ui.enrollment

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysajang.ariavoice.speaker.SpeakerVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG           = "EnrollmentVM"
private const val SAMPLE_RATE   = 16_000
private const val RECORD_MS     = 2_500          // 2.5 sec per utterance
private const val TARGET_SAMPLES = 3              // minimum utterances required
private const val MAX_SAMPLES    = 5

class SpeakerEnrollmentViewModel(
    private val context: Context,
    private val verifier: SpeakerVerifier,
) : ViewModel() {

    sealed class State {
        object Idle         : State()
        object Initializing : State()
        data class ReadyToRecord(val count: Int, val target: Int) : State()
        data class Recording(val count: Int, val remaining: Int)  : State()
        data class Processing(val count: Int)                     : State()
        data class Success(val sampleCount: Int)                  : State()
        data class Error(val message: String)                     : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val capturedSamples = mutableListOf<FloatArray>()

    // ── Init ──────────────────────────────────────────────────────────────────

    fun initialize() {
        viewModelScope.launch {
            _state.value = State.Initializing
            withContext(Dispatchers.IO) {
                try {
                    verifier.initialize()
                } catch (e: Exception) {
                    Log.e(TAG, "ONNX init failed", e)
                    _state.value = State.Error("모델 로드 실패: ${e.message}")
                    return@withContext
                }
            }
            _state.value = State.ReadyToRecord(
                count  = capturedSamples.size,
                target = TARGET_SAMPLES,
            )
        }
    }

    // ── Record one utterance ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun recordUtterance() {
        val current = _state.value
        if (current !is State.ReadyToRecord) return
        if (capturedSamples.size >= MAX_SAMPLES) return

        viewModelScope.launch {
            val utteranceIndex = capturedSamples.size + 1
            _state.value = State.Recording(count = utteranceIndex, remaining = RECORD_MS)

            // Countdown display
            val audioData = withContext(Dispatchers.IO) {
                recordForMs(RECORD_MS)
            }

            if (audioData == null) {
                _state.value = State.Error("녹음 실패 — 마이크 권한을 확인해주세요")
                return@launch
            }

            _state.value = State.Processing(utteranceIndex)

            // Quick embedding check (reject near-silence)
            val emb = withContext(Dispatchers.IO) { verifier.extractEmbedding(audioData) }
            if (emb == null) {
                _state.value = State.Error("임베딩 추출 실패 ($utteranceIndex번째)")
                return@launch
            }

            capturedSamples.add(audioData)
            val count = capturedSamples.size

            if (count >= MAX_SAMPLES) {
                finalize()
            } else {
                _state.value = State.ReadyToRecord(count = count, target = TARGET_SAMPLES)
            }
        }
    }

    // ── Finalize (after target reached or user taps "완료") ───────────────────

    fun finalize() {
        val count = capturedSamples.size
        if (count < TARGET_SAMPLES) return

        viewModelScope.launch {
            _state.value = State.Processing(count)
            val ok = withContext(Dispatchers.IO) {
                try { verifier.enroll(capturedSamples) }
                catch (e: Exception) { Log.e(TAG, "enroll error", e); false }
            }
            if (ok) {
                _state.value = State.Success(count)
            } else {
                _state.value = State.Error("등록 실패 — 다시 시도해주세요")
                capturedSamples.clear()
            }
        }
    }

    fun reset() {
        capturedSamples.clear()
        _state.value = State.ReadyToRecord(count = 0, target = TARGET_SAMPLES)
    }

    val canFinalize: Boolean
        get() = capturedSamples.size >= TARGET_SAMPLES

    val targetSamples: Int = TARGET_SAMPLES
    val maxSamples:    Int = MAX_SAMPLES

    // ── AudioRecord helper ────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun recordForMs(durationMs: Int): FloatArray? {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE) return null

        val totalSamples = (SAMPLE_RATE * durationMs / 1000)
        val pcm16  = ShortArray(totalSamples)
        var offset = 0

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(bufferSize, 4096),
        )

        return try {
            recorder.startRecording()
            val chunk = ShortArray(1280)
            while (offset < totalSamples) {
                val read = recorder.read(chunk, 0, minOf(chunk.size, totalSamples - offset))
                if (read <= 0) break
                chunk.copyInto(pcm16, offset, 0, read)
                offset += read
            }
            // Convert short → float (raw, NOT normalized — verifier normalizes internally)
            FloatArray(offset) { pcm16[it].toFloat() }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord error", e)
            null
        } finally {
            recorder.stop()
            recorder.release()
        }
    }
}
