package com.ysajang.ariavoice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.resume

class TtsManager(private val context: Context) {
    companion object {
        private const val TAG = "TtsManager"

        fun stripMarkdown(text: String): String {
            return text
                // TTS 발음 치환
                .replace("ARIA", "아리아")
                .replace("Aria", "아리아")
                .replace("aria", "아리아")
                // XML/HTML 태그 제거
                .replace(Regex("</?[a-zA-Z][^>]*>"), "")
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                .replace(Regex("\\*(.+?)\\*"), "$1")
                .replace(Regex("```[\\s\\S]*?```"), "")
                .replace(Regex("`(.+?)`"), "$1")
                .replace(Regex("#{1,6}\\s"), "")
                .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")
                .replace(Regex("[*_~]"), "")
                .replace(Regex("\\n{2,}"), "\n")
                // emoji + special symbols 제거
                .replace(Regex("[\\p{So}\\p{Sk}]"), "")
                .replace(Regex("[\uD83C-\uDBFF\uDC00-\uDFFF]+"), "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
        }
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var mediaPlayer: MediaPlayer? = null

    /** 마지막 응답 텍스트 — "다시 말해줘" 구현용 */
    var lastSpokenText: String = ""
        private set

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                isReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(1.1f)
            }
        }
    }

    suspend fun playReadyTone() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            delay(200)
            toneGen.release()
        } catch (_: Exception) {
            // tone is optional — don't block voice flow
        }
    }

    fun speak(text: String) {
        if (!isReady) return
        lastSpokenText = text
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aria_${System.currentTimeMillis()}")
    }

    suspend fun speakAndWait(text: String): Boolean = suspendCancellableCoroutine { cont ->
        if (!isReady) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        lastSpokenText = text
        val utteranceId = "aria_${System.currentTimeMillis()}"

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                if (id == utteranceId && cont.isActive) {
                    cont.resume(true)
                }
            }

            @Deprecated("Deprecated")
            override fun onError(id: String?) {
                if (id == utteranceId && cont.isActive) {
                    cont.resume(false)
                }
            }
        })

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        cont.invokeOnCancellation {
            tts?.stop()
        }
    }

    /**
     * 서버에서 받은 WAV 오디오 바이트를 재생하고 완료까지 대기.
     * @return true=성공 / false=실패 (on-device fallback 필요)
     */
    suspend fun speakFromAudioAndWait(audioBytes: ByteArray, originalText: String): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                lastSpokenText = originalText

                // WAV 바이트를 임시 파일에 저장
                val tmpFile = File(context.cacheDir, "aria_tts_${System.currentTimeMillis()}.wav")
                FileOutputStream(tmpFile).use { it.write(audioBytes) }

                releaseMediaPlayer()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .build()
                    )
                    setDataSource(tmpFile.absolutePath)
                    setOnCompletionListener {
                        tmpFile.delete()
                        releaseMediaPlayer()
                        if (cont.isActive) cont.resume(true)
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        tmpFile.delete()
                        releaseMediaPlayer()
                        if (cont.isActive) cont.resume(false)
                        true
                    }
                    prepare()
                    start()
                }

                cont.invokeOnCancellation {
                    tmpFile.delete()
                    releaseMediaPlayer()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play server audio", e)
                if (cont.isActive) cont.resume(false)
            }
        }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun stop() {
        tts?.stop()
        releaseMediaPlayer()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        releaseMediaPlayer()
    }
}
