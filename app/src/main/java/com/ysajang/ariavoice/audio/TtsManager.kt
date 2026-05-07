package com.ysajang.ariavoice.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class TtsManager(context: Context) {
    companion object {
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
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aria_${System.currentTimeMillis()}")
    }

    suspend fun speakAndWait(text: String): Boolean = suspendCancellableCoroutine { cont ->
        if (!isReady) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

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

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
