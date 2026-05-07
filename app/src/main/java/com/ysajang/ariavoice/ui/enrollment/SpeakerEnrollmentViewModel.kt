package com.ysajang.ariavoice.ui.enrollment

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ysajang.ariavoice.speaker.SpeakerVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for speaker enrollment flow.
 *
 * Records 5 utterances of "아리아" (2s each), extracts embeddings,
 * and stores the mean embedding via SpeakerVerifier.
 */
class SpeakerEnrollmentViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "EnrollmentVM"
        private const val SAMPLE_RATE = 16000
        private const val RECORD_DURATION_MS = 2000L
        private const val TOTAL_SAMPLES = 5
        private const val RECORD_SAMPLES = (SAMPLE_RATE * RECORD_DURATION_MS / 1000).toInt() // 32000
    }

    private val verifier = SpeakerVerifier(application)
    private var enrollmentJob: Job? = null

    // ── State ─────────────────────────────────────────────────────────────
    data class EnrollmentState(
        val phase: Phase = Phase.IDLE,
        val isEnrolled: Boolean = false,
        val enrolledCount: Int = 0,
        val currentSample: Int = 0,       // 1-based during recording
        val totalSamples: Int = TOTAL_SAMPLES,
        val message: String = "",
        val threshold: Float = SpeakerVerifier.DEFAULT_THRESHOLD,
    )

    enum class Phase { IDLE, INITIALIZING, COUNTDOWN, RECORDING, PROCESSING, DONE, ERROR }

    private val _state = MutableStateFlow(EnrollmentState())
    val state: StateFlow<EnrollmentState> = _state.asStateFlow()

    init {
        refreshEnrollmentStatus()
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun startEnrollment() {
        if (enrollmentJob?.isActive == true) return

        enrollmentJob = viewModelScope.launch {
            try {
                // Initialize ONNX model
                _state.value = _state.value.copy(
                    phase = Phase.INITIALIZING,
                    message = "모델 로딩 중..."
                )
                withContext(Dispatchers.IO) { verifier.initialize() }

                // Record N utterances
                val audioSamples = mutableListOf<FloatArray>()

                for (i in 1..TOTAL_SAMPLES) {
                    // Countdown
                    _state.value = _state.value.copy(
                        phase = Phase.COUNTDOWN,
                        currentSample = i,
                        message = "$i/$TOTAL_SAMPLES — 준비..."
                    )
                    delay(800)

                    // Record
                    _state.value = _state.value.copy(
                        phase = Phase.RECORDING,
                        message = "$i/$TOTAL_SAMPLES — \"아리아\"라고 말해주세요"
                    )

                    val audio = withContext(Dispatchers.IO) { recordAudio() }
                    if (audio == null) {
                        _state.value = _state.value.copy(
                            phase = Phase.ERROR,
                            message = "녹음 실패 — 마이크 권한을 확인하세요"
                        )
                        return@launch
                    }
                    audioSamples.add(audio)
                    Log.i(TAG, "Sample $i/${TOTAL_SAMPLES} recorded: ${audio.size} samples")

                    // Brief pause between recordings
                    if (i < TOTAL_SAMPLES) {
                        _state.value = _state.value.copy(
                            phase = Phase.PROCESSING,
                            message = "$i/$TOTAL_SAMPLES 완료 ✓"
                        )
                        delay(500)
                    }
                }

                // Enroll
                _state.value = _state.value.copy(
                    phase = Phase.PROCESSING,
                    message = "임베딩 생성 중..."
                )

                val success = withContext(Dispatchers.IO) {
                    verifier.enroll(audioSamples)
                }

                if (success) {
                    _state.value = _state.value.copy(
                        phase = Phase.DONE,
                        isEnrolled = true,
                        enrolledCount = verifier.enrolledSampleCount(),
                        message = "등록 완료!"
                    )
                    Log.i(TAG, "Enrollment success")
                } else {
                    _state.value = _state.value.copy(
                        phase = Phase.ERROR,
                        message = "등록 실패 — 조용한 환경에서 다시 시도하세요"
                    )
                    Log.e(TAG, "Enrollment failed")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Enrollment error", e)
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    message = "오류: ${e.message}"
                )
            }
        }
    }

    fun cancelEnrollment() {
        enrollmentJob?.cancel()
        enrollmentJob = null
        _state.value = _state.value.copy(
            phase = Phase.IDLE,
            currentSample = 0,
            message = ""
        )
    }

    fun clearEnrollment() {
        verifier.clearEnrollment()
        refreshEnrollmentStatus()
    }

    fun updateThreshold(value: Float) {
        verifier.setThreshold(value)
        _state.value = _state.value.copy(threshold = value)
    }

    fun resetToIdle() {
        refreshEnrollmentStatus()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun refreshEnrollmentStatus() {
        _state.value = EnrollmentState(
            phase = Phase.IDLE,
            isEnrolled = verifier.isEnrolled(),
            enrolledCount = verifier.enrolledSampleCount(),
            threshold = verifier.getThreshold(),
        )
    }

    /**
     * Record [RECORD_DURATION_MS] ms of 16kHz mono PCM.
     * Returns FloatArray (raw short→float) or null on failure.
     */
    @Suppress("MissingPermission")
    private fun recordAudio(): FloatArray? {
        val ctx = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return null
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuf, RECORD_SAMPLES * 2) // bytes

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            recorder.release()
            return null
        }

        return try {
            val shortBuf = ShortArray(RECORD_SAMPLES)
            recorder.startRecording()

            var totalRead = 0
            while (totalRead < RECORD_SAMPLES) {
                val read = recorder.read(shortBuf, totalRead, RECORD_SAMPLES - totalRead)
                if (read <= 0) break
                totalRead += read
            }

            recorder.stop()

            // Convert short → float (raw, not normalized — SpeakerVerifier handles normalization)
            FloatArray(totalRead) { shortBuf[it].toFloat() }
        } catch (e: Exception) {
            Log.e(TAG, "Recording error", e)
            null
        } finally {
            recorder.release()
        }
    }

    override fun onCleared() {
        super.onCleared()
        enrollmentJob?.cancel()
        verifier.release()
    }
}
