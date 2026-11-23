package io.github.iammaplezeng.chatgptvoiceball.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.iammaplezeng.chatgptvoiceball.launcher.ChatGptVoiceLauncher
import io.github.iammaplezeng.chatgptvoiceball.model.AppStateStore
import io.github.iammaplezeng.chatgptvoiceball.model.LogRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AppStateStore.init(context)
            // Do NOT show floating orb on boot — only sync config to LSPosed hook.
            // The orb will appear automatically ONLY when ChatGPT voice chat starts.
            ChatGptVoiceLauncher.syncConfigToHook(context)
            LogRepository.info("收到系统事件 ($action)，已同步配置到 LSPosed（等待 ChatGPT 语音触发）")
        }
    }
}
