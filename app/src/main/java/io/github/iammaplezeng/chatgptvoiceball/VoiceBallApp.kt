package io.github.iammaplezeng.chatgptvoiceball

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.iammaplezeng.chatgptvoiceball.model.AppStateStore
import io.github.iammaplezeng.chatgptvoiceball.model.LogRepository

class VoiceBallApp : Application() {

    companion object {
        const val CHANNEL_ID = "voice_ball_service_silent_channel"
    }

    override fun onCreate() {
        super.onCreate()
        AppStateStore.init(this)
        createNotificationChannel()
        LogRepository.info("应用已启动 (ChatGPTVoiceBall v${BuildConfig.VERSION_NAME})")
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_MIN collapses the foreground notification and prevents occupying HyperOS 4 超级岛
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ChatChatGPTVoiceBall后台保活",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "静默保持悬浮球运行，不占用状态栏图标与澎湃 OS 4 超级岛"
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }
}
