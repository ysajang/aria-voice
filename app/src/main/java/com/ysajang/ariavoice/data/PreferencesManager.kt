package com.ysajang.ariavoice.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aria_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_WAKE_WORD_SENSITIVITY = floatPreferencesKey("wake_word_sensitivity")
        private val KEY_WAKE_WORD_MODEL = stringPreferencesKey("wake_word_model")
        private val KEY_TTS_SERVER_URL = stringPreferencesKey("tts_server_url")
        private val KEY_TTS_API_KEY = stringPreferencesKey("tts_api_key")

        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8100"
        const val DEFAULT_SENSITIVITY = 0.5f
        const val DEFAULT_WAKE_WORD_MODEL = "aria.onnx"
        const val DEFAULT_TTS_SERVER_URL = ""  // 빈 값 = on-device TTS fallback
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_API_KEY] ?: ""
    }

    val wakeWordSensitivity: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_WORD_SENSITIVITY] ?: DEFAULT_SENSITIVITY
    }

    val wakeWordModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_WORD_MODEL] ?: DEFAULT_WAKE_WORD_MODEL
    }

    val ttsServerUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TTS_SERVER_URL] ?: DEFAULT_TTS_SERVER_URL
    }

    val ttsApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TTS_API_KEY] ?: ""
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = url.trimEnd('/')
        }
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_KEY] = key
        }
    }

    suspend fun setWakeWordSensitivity(sensitivity: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WAKE_WORD_SENSITIVITY] = sensitivity.coerceIn(0.01f, 1.0f)
        }
    }

    suspend fun setWakeWordModel(model: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WAKE_WORD_MODEL] = model
        }
    }

    suspend fun setTtsServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TTS_SERVER_URL] = url.trimEnd('/')
        }
    }

    suspend fun setTtsApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TTS_API_KEY] = key
        }
    }
}
