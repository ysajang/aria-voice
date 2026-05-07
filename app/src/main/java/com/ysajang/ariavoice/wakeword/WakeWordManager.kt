package com.ysajang.ariavoice.wakeword

import android.content.Context
import android.util.Log
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class WakeWordEvent(
    val name: String,
    val score: Float,
    val timestamp: Long = System.currentTimeMillis()
)

class WakeWordManager(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordManager"
        private const val COOLDOWN_MS = 3000L
    }

    private var engine: WakeWordEngine? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _detections = MutableSharedFlow<WakeWordEvent>()
    val detections: SharedFlow<WakeWordEvent> = _detections.asSharedFlow()

    fun initialize(modelName: String, threshold: Float) {
        release()

        try {
            val models = listOf(
                WakeWordModel(
                    name = "aria",
                    modelPath = modelName,
                    threshold = threshold
                )
            )

            engine = WakeWordEngine(
                context = context,
                models = models,
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = COOLDOWN_MS,
                scope = scope
            )

            // Collect from engine and re-emit as our own type
            scope.launch {
                engine?.detections?.collect { detection ->
                    _detections.emit(
                        WakeWordEvent(
                            name = detection.model.name,
                            score = detection.score,
                            timestamp = detection.timestamp
                        )
                    )
                }
            }

            Log.i(TAG, "WakeWord engine initialized: model=$modelName threshold=$threshold")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WakeWord engine", e)
            engine = null
        }
    }

    fun start() {
        try {
            engine?.start()
            Log.i(TAG, "WakeWord detection started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WakeWord detection", e)
        }
    }

    fun stop() {
        try {
            engine?.stop()
            Log.i(TAG, "WakeWord detection stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop WakeWord detection", e)
        }
    }

    suspend fun stopAndAwait() {
        try {
            engine?.stopAndAwait()
            Log.i(TAG, "WakeWord detection stopped (audio released)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop WakeWord detection", e)
        }
    }

    fun release() {
        try {
            engine?.release()
            engine = null
            Log.i(TAG, "WakeWord engine released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeWord engine", e)
        }
    }

    fun isInitialized(): Boolean = engine != null
}