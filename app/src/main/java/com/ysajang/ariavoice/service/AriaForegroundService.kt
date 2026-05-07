package com.ysajang.ariavoice.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ysajang.ariavoice.AriaVoiceApp
import com.ysajang.ariavoice.MainActivity
import com.ysajang.ariavoice.R
import com.ysajang.ariavoice.audio.SttManager
import com.ysajang.ariavoice.audio.TtsManager
import com.ysajang.ariavoice.data.ConversationEntry
import com.ysajang.ariavoice.data.ConversationRepository
import com.ysajang.ariavoice.data.PreferencesManager
import com.ysajang.ariavoice.network.AriaApiClient
import com.ysajang.ariavoice.speaker.CircularAudioBuffer
import com.ysajang.ariavoice.speaker.SpeakerVerifier
import com.ysajang.ariavoice.wakeword.WakeWordManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

enum class AriaState {
    IDLE, LISTENING_WAKE_WORD, LISTENING_SPEECH, PROCESSING, SPEAKING, ERROR
}

class AriaForegroundService : Service() {

    companion object {
        private const val TAG = "AriaService"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_WORD_DEBOUNCE_MS = 5000L

        const val ACTION_START = "com.ysajang.ariavoice.START"
        const val ACTION_STOP = "com.ysajang.ariavoice.STOP"
        const val ACTION_MANUAL_TRIGGER = "com.ysajang.ariavoice.MANUAL_TRIGGER"
    }

    private val binder = AriaBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var prefsManager: PreferencesManager
    private lateinit var wakeWordManager: WakeWordManager
    private lateinit var sttManager: SttManager
    private lateinit var ttsManager: TtsManager
    private lateinit var speakerVerifier: SpeakerVerifier
    private val audioBuffer = CircularAudioBuffer()  // 1.5s ring buffer for speaker verification
    private val apiClient = AriaApiClient()
    val conversationRepo = ConversationRepository()

    private val _state = MutableStateFlow(AriaState.IDLE)
    val state: StateFlow<AriaState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<ConversationEntry?>(null)
    val pendingConfirmation: StateFlow<ConversationEntry?> = _pendingConfirmation.asStateFlow()

    private var wakeWordJob: Job? = null
    /** Debounce guard: prevents re-triggering within WAKE_WORD_DEBOUNCE_MS */
    private var lastWakeWordProcessedAt = 0L

    inner class AriaBinder : Binder() {
        fun getService(): AriaForegroundService = this@AriaForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(this)
        wakeWordManager = WakeWordManager(this)
        sttManager = SttManager(this)
        ttsManager = TtsManager(this)
        speakerVerifier = SpeakerVerifier(this)

        // Feed audio chunks to ring buffer for speaker verification
        wakeWordManager.onAudioChunk = { chunk -> audioBuffer.write(chunk) }

        // Load ONNX model on background thread
        serviceScope.launch(Dispatchers.IO) {
            try {
                speakerVerifier.initialize()
                Log.i(TAG, "SpeakerVerifier initialized (enrolled=${speakerVerifier.isEnrolled()})")
            } catch (e: Exception) {
                Log.e(TAG, "SpeakerVerifier init failed — verification disabled", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP -> stopListening()
            ACTION_MANUAL_TRIGGER -> manualTrigger()
        }
        return START_STICKY
    }

    private fun startListening() {
        startForeground(NOTIFICATION_ID, createNotification("대기 중..."))

        serviceScope.launch {
            val modelName = prefsManager.wakeWordModel.first()
            val sensitivity = prefsManager.wakeWordSensitivity.first()

            wakeWordManager.initialize(modelName, sensitivity)
            wakeWordManager.start()
            _state.value = AriaState.LISTENING_WAKE_WORD

            wakeWordJob?.cancel()
            wakeWordJob = launch {
                wakeWordManager.detections.collect { event ->
                    Log.i(TAG, "Wake word detected: ${event.name} score=${event.score}")
                    onWakeWordDetected()
                }
            }
        }
    }

    private fun stopListening() {
        wakeWordJob?.cancel()
        wakeWordManager.stop()
        _state.value = AriaState.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onWakeWordDetected() {
        val now = System.currentTimeMillis()
        if (now - lastWakeWordProcessedAt < WAKE_WORD_DEBOUNCE_MS) {
            Log.d(TAG, "Wake word ignored — debounce (${now - lastWakeWordProcessedAt}ms since last)")
            return
        }
        lastWakeWordProcessedAt = now
        serviceScope.launch {
            processVoiceCommand()
        }
    }

    fun manualTrigger() {
        if (_state.value == AriaState.LISTENING_WAKE_WORD || _state.value == AriaState.IDLE) {
            serviceScope.launch {
                processVoiceCommand()
            }
        }
    }

    private suspend fun processVoiceCommand() {
        try {
            // Step 0: Grab audio snapshot BEFORE stopping (contains wake word utterance)
            val wakeWordAudio = audioBuffer.snapshot()

            // Step 1: Stop wake word listening temporarily
            wakeWordManager.stopAndAwait()
            delay(300)

            // Step 1.1: Speaker verification (if enrolled)
            if (speakerVerifier.isEnrolled()) {
                val verifyResult = speakerVerifier.verify(wakeWordAudio)
                if (!verifyResult.accepted) {
                    Log.w(TAG, "speaker_rejected: reason=${verifyResult.reason} " +
                            "sim=${"%.4f".format(verifyResult.similarity)}")
                    resumeWakeWordListening()
                    return
                }
                Log.i(TAG, "speaker_accepted: sim=${"%.4f".format(verifyResult.similarity)}")
            }

            // Step 1.5: Haptic feedback — 웨이크워드 감지 알림
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            _state.value = AriaState.LISTENING_SPEECH
            updateNotification("듣는 중...", headsUp = true)
            // Step 1.5: Audio cue so user knows to speak
            ttsManager.playReadyTone()
            // Step 2: STT
            val sttResult = sttManager.recognize()
            val queryText = sttResult.getOrElse { e ->
                _lastError.value = "STT 실패: ${e.message}"
                _state.value = AriaState.ERROR
                resumeWakeWordListening()
                return
            }

            if (queryText.isBlank()) {
                resumeWakeWordListening()
                return
            }

            // Step 2.5: Local voice commands (no network needed)
            val stopKeywords = listOf("종료", "꺼져", "서비스 중지", "중지해", "그만")
            if (stopKeywords.any { queryText.contains(it) }) {
                ttsManager.speak("서비스를 종료합니다")
                delay(1500)
                stopListening()
                return
            }

            // Step 3: Call ARIA API
            _state.value = AriaState.PROCESSING
            updateNotification("처리 중: $queryText")

            val serverUrl = prefsManager.serverUrl.first()
            val apiKey = prefsManager.apiKey.first()

            if (apiKey.isBlank()) {
                _lastError.value = "API Key가 설정되지 않았습니다"
                ttsManager.speak("API 키가 설정되지 않았습니다")
                _state.value = AriaState.ERROR
                resumeWakeWordListening()
                return
            }

            val apiResult = apiClient.query(serverUrl, apiKey, queryText)
            val response = apiResult.getOrElse { e ->
                _lastError.value = "API 오류: ${e.message}"
                ttsManager.speak("서버 연결에 실패했습니다")
                _state.value = AriaState.ERROR
                resumeWakeWordListening()
                return
            }
            _lastError.value = null
// Step 4: Save conversation
            val cleanAnswer = response.answer.replace(Regex("</?[a-zA-Z][^>]*>"), "").trim()
            val entry = ConversationEntry(
                query = queryText,
                answer = response.answer,
                confidence = response.confidence,
                pendingConfirmation = response.pendingConfirmation,
                confirmationId = response.confirmationId
            )
            conversationRepo.addEntry(entry)

            // Step 5: Handle HITL or speak response
            if (response.pendingConfirmation) {
                _pendingConfirmation.value = entry
                ttsManager.speak(TtsManager.stripMarkdown("${response.answer}. 확인하시겠습니까?"))
            } else {
                _state.value = AriaState.SPEAKING
                updateNotification("응답 중...")
                ttsManager.speakAndWait(TtsManager.stripMarkdown(response.answer))
            }

            // Step 6: Resume wake word listening (TTS 잔향 방지)
            delay(1500)
            resumeWakeWordListening()

        } catch (e: Exception) {
            Log.e(TAG, "Error processing voice command", e)
            _lastError.value = "처리 오류: ${e.message}"
            _state.value = AriaState.ERROR
            resumeWakeWordListening()
        }
    }

    fun confirmAction(confirmationId: String, confirmed: Boolean) {
        serviceScope.launch {
            try {
                val serverUrl = prefsManager.serverUrl.first()
                val apiKey = prefsManager.apiKey.first()

                apiClient.confirm(serverUrl, apiKey, confirmationId, confirmed)
                conversationRepo.updateConfirmation(confirmationId, confirmed)
                _pendingConfirmation.value = null

                val msg = if (confirmed) "실행했습니다" else "취소했습니다"
                ttsManager.speak(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Confirm failed", e)
                _lastError.value = "확인 처리 실패: ${e.message}"
            }
        }
    }

    private fun resumeWakeWordListening() {
        lastWakeWordProcessedAt = System.currentTimeMillis() // debounce starts from resume
        wakeWordManager.start()
        _state.value = AriaState.LISTENING_WAKE_WORD
        updateNotification("대기 중...")
    }

    private fun createNotification(text: String, headsUp: Boolean = false): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, AriaVoiceApp.CHANNEL_ID)
            .setContentTitle("ARIA Voice")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_aria_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (headsUp) {
                    priority = NotificationCompat.PRIORITY_HIGH
                    setCategory(NotificationCompat.CATEGORY_CALL)
                }
            }
            .build()
    }

    private fun updateNotification(text: String, headsUp: Boolean = false) {
        val notification = createNotification(text, headsUp)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        wakeWordJob?.cancel()
        wakeWordManager.release()
        sttManager.cancel()
        ttsManager.release()
        speakerVerifier.release()
        apiClient.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }
}
