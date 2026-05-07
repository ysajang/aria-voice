package com.ysajang.ariavoice

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AriaVoiceApp : Application() {

    companion object {
        const val CHANNEL_ID = "aria_voice_service"
        const val CHANNEL_NAME = "ARIA Voice Service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "ARIA 음성 대기 서비스"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
