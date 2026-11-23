package io.github.iammaplezeng.chatgptvoiceball.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.iammaplezeng.chatgptvoiceball.model.AppStateStore
import io.github.iammaplezeng.chatgptvoiceball.model.LogRepository
import io.github.iammaplezeng.chatgptvoiceball.xposed.HookProtocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object ChatGptVoiceLauncher {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestCounter = AtomicLong(1L)
    private val pendingFallbacks = ConcurrentHashMap<Long, Runnable>()

    fun isChatGptInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(HookProtocol.CHATGPT_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getChatGptVersion(context: Context): String? {
        return try {
            val info = context.packageManager.getPackageInfo(HookProtocol.CHATGPT_PACKAGE, 0)
            info.versionName
        } catch (_: Exception) {
            null
        }
    }

    fun pingSystemHook(context: Context) {
        syncConfigToHook(context)
        val intent = Intent(HookProtocol.ACTION_PING).apply {
            putExtra(HookProtocol.EXTRA_CALLER_TOKEN, createCallerToken(context))
        }
        context.sendBroadcast(intent)
        LogRepository.info("已向 system_server 发送 LSPosed 握手 Ping")
    }

    fun syncConfigToHook(context: Context) {
        val settings = AppStateStore.settings.value
        val intent = Intent(HookProtocol.ACTION_SYNC_CONFIG).apply {
            putExtra(HookProtocol.EXTRA_CALLER_TOKEN, createCallerToken(context))
            putExtra(HookProtocol.EXTRA_AUTO_SHOW_ON_VOICE, settings.autoShowOnVoice)
            putExtra(HookProtocol.EXTRA_SUPPRESS_CHATGPT_ISLAND, settings.suppressChatGptIsland)
        }
        context.sendBroadcast(intent)
    }

    fun triggerMuteToggle(context: Context) {
        val intent = Intent(HookProtocol.ACTION_TRIGGER_MUTE).apply {
            putExtra(HookProtocol.EXTRA_CALLER_TOKEN, createCallerToken(context))
        }
        context.applicationContext.sendBroadcast(intent)
        LogRepository.info("已向 system_server 发送静音切换指令")
    }

    fun triggerHangUp(context: Context) {
        val intent = Intent(HookProtocol.ACTION_TRIGGER_HANGUP).apply {
            putExtra(HookProtocol.EXTRA_CALLER_TOKEN, createCallerToken(context))
        }
        context.applicationContext.sendBroadcast(intent)
        LogRepository.info("已向 system_server 发送结束语音通话指令")
    }

    fun setChatGptSpeakerMuted(context: Context, muted: Boolean) {
        val intent = Intent(HookProtocol.ACTION_SET_SPEAKER_MUTED).apply {
            setPackage(HookProtocol.CHATGPT_PACKAGE)
            putExtra(HookProtocol.EXTRA_CALLER_TOKEN, createCallerToken(context))
            putExtra(HookProtocol.EXTRA_SPEAKER_MUTED, muted)
        }
        context.applicationContext.sendBroadcast(intent)
        LogRepository.info("已向 ChatGPT 发送 AudioTrack 扬声器静音=$muted")
    }

    fun launchVoiceChat(
        context: Context,
        forceFreeformOverride: Boolean? = null,
        resumeExisting: Boolean = false
    ) {
        val appContext = context.applicationContext
        if (!isChatGptInstalled(appContext)) {
            LogRepository.error("未检测到 ChatGPT 官方 App (${HookProtocol.CHATGPT_PACKAGE})")
            Toast.makeText(appContext, "请先安装 ChatGPT 官方 App", Toast.LENGTH_SHORT).show()
            return
        }

        val settings = AppStateStore.settings.value
        val useFreeform = forceFreeformOverride ?: settings.useHyperOsFreeform
        val reqId = requestCounter.getAndIncrement()

        val broadcastIntent = Intent(HookProtocol.ACTION_LAUNCH_VOICE).apply {
            putExtra(HookProtocol.EXTRA_CALLER_TOKEN, createCallerToken(appContext))
            putExtra(HookProtocol.EXTRA_REQUEST_ID, reqId)
            putExtra(HookProtocol.EXTRA_USE_FREEFORM, useFreeform)
            putExtra(HookProtocol.EXTRA_PREFER_MIUI_UTILS, settings.preferMiuiNativeUtils)
            putExtra(HookProtocol.EXTRA_RESUME_EXISTING, resumeExisting)
            putExtra(HookProtocol.EXTRA_WINDOW_WIDTH_RATIO, 0.78f)
            putExtra(HookProtocol.EXTRA_WINDOW_HEIGHT_RATIO, 0.52f)
        }

        // Schedule graceful fallback if LSPosed module is not active yet
        val fallbackRunnable = Runnable {
            if (pendingFallbacks.remove(reqId) != null) {
                LogRepository.warn("LSPosed 模块未在 600ms 内响应，自动降级为标准官方深链启动")
                launchDirectFallback(appContext)
            }
        }
        pendingFallbacks[reqId] = fallbackRunnable
        mainHandler.postDelayed(fallbackRunnable, 600L)

        appContext.sendBroadcast(broadcastIntent)
        LogRepository.info(
            "请求启动 ChatGPT 语音 (req=$reqId, 小窗=$useFreeform, 原生MiuiUtils=${settings.preferMiuiNativeUtils})"
        )
    }

    fun onHookLaunchResult(context: Context, requestId: Long, success: Boolean, engine: String, message: String) {
        val pending = pendingFallbacks.remove(requestId)
        if (pending != null) {
            mainHandler.removeCallbacks(pending)
        }
        if (success) {
            LogRepository.info("LSPosed 启动成功 [engine=$engine]: $message")
        } else {
            LogRepository.error("LSPosed 启动返回失败 [engine=$engine]: $message，尝试降级启动")
            launchDirectFallback(context.applicationContext)
        }
    }

    private fun launchDirectFallback(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(HookProtocol.CHATGPT_VOICE_URI)).apply {
                setPackage(HookProtocol.CHATGPT_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            context.startActivity(intent)
            LogRepository.info("降级启动成功：官方语音深链 (${HookProtocol.CHATGPT_VOICE_URI})")
        } catch (t: Throwable) {
            LogRepository.error("降级启动语音深链失败: ${t.message}，尝试打开 ChatGPT 主界面")
            try {
                val mainIntent = context.packageManager.getLaunchIntentForPackage(HookProtocol.CHATGPT_PACKAGE)
                if (mainIntent != null) {
                    mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(mainIntent)
                }
            } catch (e: Throwable) {
                LogRepository.error("打开 ChatGPT 主界面失败: ${e.message}")
            }
        }
    }

    private fun createCallerToken(context: Context): PendingIntent {
        val tokenIntent = Intent("io.github.iammaplezeng.chatgptvoiceball.INTERNAL_TOKEN").apply {
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            tokenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
