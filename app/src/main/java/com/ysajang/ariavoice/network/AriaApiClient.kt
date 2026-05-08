package com.ysajang.ariavoice.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AriaApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun query(
        serverUrl: String,
        apiKey: String,
        queryText: String
    ): Result<AriaResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = json.encodeToString(
                AriaRequest.serializer(),
                AriaRequest(query = queryText)
            )

            val request = Request.Builder()
                .url("$serverUrl/v1/query")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-API-Key", apiKey.trim())
                .post(requestBody.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw AriaApiException(
                    code = response.code,
                    message = "ARIA API error: ${response.code} ${response.message}"
                )
            }

            val body = response.body?.string()
                ?: throw AriaApiException(code = 0, message = "Empty response body")

            json.decodeFromString(AriaResponse.serializer(), body)
        }
    }

    suspend fun confirm(
        serverUrl: String,
        apiKey: String,
        confirmationId: String,
        confirmed: Boolean
    ): Result<AriaResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = json.encodeToString(
                AriaConfirmRequest.serializer(),
                AriaConfirmRequest(
                    confirmationId = confirmationId,
                    confirmed = confirmed
                )
            )

            val request = Request.Builder()
                .url("$serverUrl/v1/confirm")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-API-Key", apiKey.trim())
                .post(requestBody.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw AriaApiException(
                    code = response.code,
                    message = "ARIA confirm error: ${response.code} ${response.message}"
                )
            }

            val body = response.body?.string()
                ?: throw AriaApiException(code = 0, message = "Empty response body")

            json.decodeFromString(AriaResponse.serializer(), body)
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /**
     * 서버 TTS 호출: text + emotion → WAV audio bytes
     * @return WAV 오디오 바이트 또는 실패 시 null
     */
    suspend fun synthesize(
        ttsServerUrl: String,
        ttsApiKey: String,
        text: String,
        emotion: String = "neutral"
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = json.encodeToString(
                TtsRequest.serializer(),
                TtsRequest(text = text, emotion = emotion)
            )

            val requestBuilder = Request.Builder()
                .url("$ttsServerUrl/v1/tts")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody(jsonMediaType))

            if (ttsApiKey.isNotBlank()) {
                requestBuilder.addHeader("X-API-Key", ttsApiKey.trim())
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                throw AriaApiException(
                    code = response.code,
                    message = "TTS server error: ${response.code} ${response.message}"
                )
            }

            response.body?.bytes()
                ?: throw AriaApiException(code = 0, message = "Empty TTS response")
        }
    }
}

class AriaApiException(val code: Int, override val message: String) : Exception(message)
