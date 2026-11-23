package io.github.iammaplezeng.chatgptvoiceball.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.github.iammaplezeng.chatgptvoiceball.launcher.ChatGptVoiceLauncher
import io.github.iammaplezeng.chatgptvoiceball.model.AppStateStore
import io.github.iammaplezeng.chatgptvoiceball.model.HookStatus
import io.github.iammaplezeng.chatgptvoiceball.model.LogRepository
import io.github.iammaplezeng.chatgptvoiceball.service.VoiceBallService
import io.github.iammaplezeng.chatgptvoiceball.xposed.HookProtocol

class HookResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppStateStore.init(context)
        when (intent.action) {
            HookProtocol.ACTION_PONG -> {
                val hasMiui = intent.getBooleanExtra(HookProtocol.EXTRA_HAS_MIUI_UTILS, false)
                val msg = intent.getStringExtra(HookProtocol.EXTRA_MESSAGE) ?: "已激活"
                val voiceActive = intent.getBooleanExtra(HookProtocol.EXTRA_VOICE_ACTIVE, false)
                AppStateStore.updateHookStatus(
                    HookStatus(
                        active = true,
                        hasMiuiUtils = hasMiui,
                        detail = msg
                    )
                )
                AppStateStore.setVoiceSessionActive(voiceActive)
                LogRepository.info("收到 LSPosed 握手 Pong: $msg (当前语音状态=$voiceActive)")
            }

            HookProtocol.ACTION_LAUNCH_RESULT -> {
                val reqId = intent.getLongExtra(HookProtocol.EXTRA_REQUEST_ID, 0L)
                val success = intent.getBooleanExtra(HookProtocol.EXTRA_SUCCESS, false)
                val engine = intent.getStringExtra(HookProtocol.EXTRA_ENGINE) ?: "unknown"
                val msg = intent.getStringExtra(HookProtocol.EXTRA_MESSAGE) ?: ""
                ChatGptVoiceLauncher.onHookLaunchResult(context, reqId, success, engine, msg)
            }

            HookProtocol.ACTION_LIVE_CAPTION -> {
                val caption = intent.getStringExtra(HookProtocol.EXTRA_CAPTION_TEXT) ?: ""
                if (caption.isNotBlank()) {
                    AppStateStore.setCaptionText(caption)
                }
            }

            HookProtocol.ACTION_VOICE_STATE_CHANGED -> {
                val active = intent.getBooleanExtra(HookProtocol.EXTRA_VOICE_ACTIVE, false)
                val channel = intent.getStringExtra(HookProtocol.EXTRA_VOICE_CHANNEL) ?: ""
                val isMuted = intent.getBooleanExtra(HookProtocol.EXTRA_IS_MUTED, false)
                val caption = intent.getStringExtra(HookProtocol.EXTRA_CAPTION_TEXT) ?: ""
                AppStateStore.setVoiceSessionActive(active)
                AppStateStore.setMicMuted(isMuted)
                if (caption.isNotBlank()) {
                    AppStateStore.setCaptionText(caption)
                }
                val settings = AppStateStore.settings.value

                if (active) {
                    LogRepository.info("ChatGPT 语音状态更新 (active=true, muted=$isMuted, channel=$channel)")
                    if (settings.autoShowOnVoice && Settings.canDrawOverlays(context) && !AppStateStore.serviceRunning.value) {
                        VoiceBallService.start(context)
                    }
                } else {
                    LogRepository.info("检测到 ChatGPT 语音聊天结束，自动隐藏悬浮球")
                    VoiceBallService.stop(context)
                }
            }
        }
    }
}
