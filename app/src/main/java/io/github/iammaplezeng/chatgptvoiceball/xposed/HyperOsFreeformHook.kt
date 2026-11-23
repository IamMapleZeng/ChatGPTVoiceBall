package io.github.iammaplezeng.chatgptvoiceball.xposed

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed Hook for HyperOS 4 (Android 14-17):
 *
 * 1. Scope `android` (system_server):
 *    - Detects ChatGPT official voice chat lifecycle via NotificationManagerService
 *      (channels: `voice_mode_ongoing`, `codex_realtime_voice`).
 *    - Notifies our app to show the floating voice orb ONLY while ChatGPT voice chat
 *      is active, and automatically hide it when voice chat ends.
 *    - Suppresses HyperOS 4 超级岛 (Focus/Dynamic Island) ONLY for ChatGPT's voice
 *      notification when the floating orb is enabled, without affecting any other app.
 *    - Launches ChatGPT voice in HyperOS 4 native freeform small window when the orb is tapped.
 *
 * 2. Scope `com.android.systemui`:
 *    - Secondary guard in SystemUI Focus/Island controllers to filter out ONLY
 *      `com.openai.chatgpt` voice notifications from 超级岛 when suppression is enabled.
 */
class HyperOsFreeformHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[GPTVoiceBall-LSPosed]"
        private const val FLAG_PROMOTED_ONGOING = 1 shl 18

        private val systemRegistered = AtomicBoolean(false)
        private val systemUiRegistered = AtomicBoolean(false)

        @Volatile
        private var suppressChatGptIsland = true

        @Volatile
        private var autoShowOnVoice = true

        @Volatile
        private var cachedSystemContext: Context? = null

        // Track active ChatGPT voice notification IDs
        private val activeVoiceNotifIds = ConcurrentHashMap.newKeySet<Int>()

        @Volatile
        private var lastStartSentMs = 0L

        @Volatile
        private var lastMuteAction: PendingIntent? = null

        @Volatile
        private var lastEndAction: PendingIntent? = null

        @Volatile
        private var lastContentIntent: PendingIntent? = null

        @Volatile
        private var lastIsMuted: Boolean = false

        @Volatile
        private var lastCaptionText: String = ""
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> hookSystemServer(lpparam.classLoader)
            "com.android.systemui" -> hookSystemUi(lpparam.classLoader)
            HookProtocol.CHATGPT_PACKAGE -> hookChatGptLiveCaptions(lpparam.classLoader)
        }
    }

    // =========================================================================
    // 1. system_server (android)
    // =========================================================================

    private fun hookSystemServer(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService",
                classLoader
            )
            XposedBridge.hookAllMethods(amsClass, "systemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!systemRegistered.compareAndSet(false, true)) return
                    val systemContext = resolveSystemContext(param.thisObject)
                    if (systemContext == null) {
                        XposedBridge.log("$TAG Failed to obtain systemContext in systemReady")
                        systemRegistered.set(false)
                        return
                    }
                    cachedSystemContext = systemContext
                    registerSystemCommandReceiver(systemContext, classLoader)
                }
            })
            XposedBridge.log("$TAG Hooked ActivityManagerService.systemReady")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook systemReady: ${t.stackTraceToString()}")
        }

        hookNotificationManagerService(classLoader)
    }

    private fun hookNotificationManagerService(classLoader: ClassLoader) {
        try {
            val nmsClass = XposedHelpers.findClass(
                "com.android.server.notification.NotificationManagerService",
                classLoader
            )

            // Detect ChatGPT voice notification enqueue + strip 超级岛 extras for ChatGPT ONLY
            XposedBridge.hookAllMethods(nmsClass, "enqueueNotificationInternal", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args.firstOrNull { it is String } as? String ?: return
                    if (pkg != HookProtocol.CHATGPT_PACKAGE) return

                    val notification = param.args.firstOrNull { it is Notification } as? Notification ?: return
                    val channelId = notification.channelId ?: ""
                    if (!isChatGptVoiceNotification(channelId, notification)) return

                    val id = extractNotificationId(param.args)
                    val wasEmpty = activeVoiceNotifIds.isEmpty()
                    activeVoiceNotifIds.add(id)

                    // Extract ChatGPT official Mute, Hang Up & Content PendingIntents from notification
                    val prevMuted = lastIsMuted
                    val prevCaption = lastCaptionText
                    extractVoiceActions(notification)
                    val stateChanged = (prevMuted != lastIsMuted) || (prevCaption != lastCaptionText)

                    if (suppressChatGptIsland && autoShowOnVoice) {
                        stripHyperOsIslandExtras(notification)
                    }

                    val now = System.currentTimeMillis()
                    val shouldStartService = wasEmpty || (now - lastStartSentMs > 4000L)
                    if (shouldStartService) {
                        lastStartSentMs = now
                        notifyVoiceStateChanged(active = true, channelId = channelId, startOrStopService = true)
                    } else if (stateChanged) {
                        // Immediately sync mute/caption state to floating dock with zero delay!
                        notifyVoiceStateChanged(active = true, channelId = channelId, startOrStopService = false)
                    }
                }
            })

            // Detect cancellation by pkg/tag/id (match any Int arg against tracked voice notification IDs)
            val cancelHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (activeVoiceNotifIds.isEmpty()) return
                    val hasChatGpt = param.args.any { it == HookProtocol.CHATGPT_PACKAGE }
                    if (!hasChatGpt) return

                    var removed = false
                    for (arg in param.args) {
                        if (arg is Int && activeVoiceNotifIds.remove(arg)) {
                            removed = true
                        }
                    }
                    if (removed && activeVoiceNotifIds.isEmpty()) {
                        lastStartSentMs = 0L
                        lastMuteAction = null
                        lastEndAction = null
                        notifyVoiceStateChanged(active = false, channelId = "")
                    }
                }
            }
            XposedBridge.hookAllMethods(nmsClass, "cancelNotificationInternal", cancelHook)
            try {
                XposedBridge.hookAllMethods(nmsClass, "cancelNotificationWithTag", cancelHook)
            } catch (_: Throwable) {
            }

            // Detect cancelAll / app death cleanup
            XposedBridge.hookAllMethods(nmsClass, "cancelAllNotificationsInt", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (activeVoiceNotifIds.isEmpty()) return
                    val hasChatGpt = param.args.any { it == HookProtocol.CHATGPT_PACKAGE }
                    if (hasChatGpt) {
                        activeVoiceNotifIds.clear()
                        lastStartSentMs = 0L
                        lastMuteAction = null
                        lastEndAction = null
                        notifyVoiceStateChanged(active = false, channelId = "")
                    }
                }
            })

            XposedBridge.log("$TAG Hooked NotificationManagerService for ChatGPT voice detection & 超级岛 filtering")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook NotificationManagerService: ${t.stackTraceToString()}")
        }
    }

    private fun isChatGptVoiceNotification(channelId: String, notification: Notification): Boolean {
        if (channelId in HookProtocol.CHATGPT_VOICE_CHANNELS) return true
        val lower = channelId.lowercase()
        if (lower.contains("voice") && (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            return true
        }
        return false
    }

    /**
     * Strips HyperOS 4 超级岛 / Focus Notification / Promoted Ongoing flags from THIS notification only.
     * Does NOT touch system global 超级岛 settings or any other app.
     */
    private fun stripHyperOsIslandExtras(notification: Notification) {
        try {
            notification.flags = notification.flags and FLAG_PROMOTED_ONGOING.inv()
            if (notification.category == Notification.CATEGORY_CALL) {
                notification.category = Notification.CATEGORY_SERVICE
            }
            val extras = notification.extras ?: Bundle().also { notification.extras = it }
            val keysToRemove = extras.keySet().filter { key ->
                key.startsWith("miui.focus") ||
                    key.startsWith("miui.island") ||
                    key.startsWith("miui.dynamic") ||
                    key == "android.requestPromotedOngoing"
            }
            keysToRemove.forEach { extras.remove(it) }
            extras.putBoolean("miui.focus.enable", false)
            extras.putBoolean("miui.isDynamicIsland", false)
            extras.putBoolean("miui.enableFloat", false)
            extras.putBoolean("android.requestPromotedOngoing", false)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG stripHyperOsIslandExtras error: ${t.message}")
        }
    }

    private fun extractNotificationId(args: Array<Any?>): Int {
        // In enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification, ...)
        // id is typically the Int immediately preceding Notification, or arg index 5
        val notifIdx = args.indexOfFirst { it is Notification }
        if (notifIdx > 0 && args[notifIdx - 1] is Int) {
            return args[notifIdx - 1] as Int
        }
        if (args.size > 5 && args[5] is Int) {
            return args[5] as Int
        }
        return 0
    }

    private fun extractNotificationTag(args: Array<Any?>): String {
        val notifIdx = args.indexOfFirst { it is Notification }
        if (notifIdx > 1 && args[notifIdx - 2] is String) {
            return args[notifIdx - 2] as String
        }
        return ""
    }

    private fun extractVoiceActions(notification: Notification) {
        lastContentIntent = notification.contentIntent

        val extras = notification.extras
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        lastCaptionText = bigText.ifBlank { text }

        // Verified on ChatGPT v1.2026.314:
        // extras["android.hangUpIntent"] & actions[0]="Hang Up", actions[1]="Mute"/"Unmute"
        @Suppress("DEPRECATION")
        var endIntent = extras?.getParcelable<PendingIntent>("android.hangUpIntent")
        var muteIntent: PendingIntent? = null
        var mutedState = lastIsMuted

        val actions = notification.actions
        if (actions != null) {
            for (action in actions) {
                val title = action.title?.toString()?.lowercase() ?: ""
                val pi = action.actionIntent ?: continue
                when {
                    title.contains("unmute") || title.contains("取消静音") -> {
                        muteIntent = pi
                        mutedState = true
                    }
                    title.contains("mute") || title.contains("静音") -> {
                        muteIntent = pi
                        mutedState = false
                    }
                    title.contains("hang up") || title.contains("hangup") || title.contains("挂断") ||
                        title.contains("end") || title.contains("stop") || title.contains("结束") -> {
                        endIntent = pi
                    }
                }
            }
            if (endIntent == null && actions.isNotEmpty()) {
                endIntent = actions[0].actionIntent
            }
            if (muteIntent == null && actions.size >= 2) {
                muteIntent = actions[1].actionIntent
            }
        }

        if (muteIntent != null) lastMuteAction = muteIntent
        if (endIntent != null) lastEndAction = endIntent
        lastIsMuted = mutedState
    }

    private fun notifyVoiceStateChanged(
        active: Boolean,
        channelId: String,
        startOrStopService: Boolean = true
    ) {
        val ctx = cachedSystemContext ?: return
        XposedBridge.log("$TAG ChatGPT voice session active=$active muted=$lastIsMuted (startStop=$startOrStopService)")

        if (startOrStopService && (autoShowOnVoice || !active)) {
            try {
                val currentUser = XposedHelpers.getStaticObjectField(UserHandle::class.java, "CURRENT") as UserHandle
                val serviceIntent = Intent(
                    if (active) HookProtocol.ACTION_SYSTEM_SHOW_ORB else HookProtocol.ACTION_SYSTEM_HIDE_ORB
                ).apply {
                    setClassName(HookProtocol.MODULE_PACKAGE, HookProtocol.SERVICE_CLASS)
                    putExtra(HookProtocol.EXTRA_VOICE_ACTIVE, active)
                    putExtra(HookProtocol.EXTRA_VOICE_CHANNEL, channelId)
                    putExtra(HookProtocol.EXTRA_IS_MUTED, lastIsMuted)
                    putExtra(HookProtocol.EXTRA_CAPTION_TEXT, lastCaptionText)
                    lastMuteAction?.let { putExtra(HookProtocol.EXTRA_MUTE_ACTION, it) }
                    lastEndAction?.let { putExtra(HookProtocol.EXTRA_END_ACTION, it) }
                }
                if (active) {
                    val startFgs = Context::class.java.getMethod(
                        "startForegroundServiceAsUser",
                        Intent::class.java,
                        UserHandle::class.java
                    )
                    startFgs.invoke(ctx, serviceIntent, currentUser)
                } else {
                    val stopSvc = Context::class.java.getMethod(
                        "stopServiceAsUser",
                        Intent::class.java,
                        UserHandle::class.java
                    )
                    stopSvc.invoke(ctx, serviceIntent, currentUser)
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Direct start/stop VoiceBallService failed: ${t.stackTraceToString()}")
            }
        }

        val intent = Intent(HookProtocol.ACTION_VOICE_STATE_CHANGED).apply {
            setPackage(HookProtocol.MODULE_PACKAGE)
            putExtra(HookProtocol.EXTRA_VOICE_ACTIVE, active)
            putExtra(HookProtocol.EXTRA_VOICE_CHANNEL, channelId)
            putExtra(HookProtocol.EXTRA_IS_MUTED, lastIsMuted)
            putExtra(HookProtocol.EXTRA_CAPTION_TEXT, lastCaptionText)
        }
        sendReplyBroadcast(ctx, intent)
    }

    private fun resolveSystemContext(amsInstance: Any?): Context? {
        try {
            if (amsInstance != null) {
                val ctx = XposedHelpers.getObjectField(amsInstance, "mContext") as? Context
                if (ctx != null) return ctx
            }
        } catch (_: Throwable) {
        }
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
            XposedHelpers.callMethod(currentAt, "getSystemContext") as? Context
        } catch (_: Throwable) {
            null
        }
    }

    private fun registerSystemCommandReceiver(systemContext: Context, classLoader: ClassLoader) {
        val filter = IntentFilter().apply {
            addAction(HookProtocol.ACTION_PING)
            addAction(HookProtocol.ACTION_LAUNCH_VOICE)
            addAction(HookProtocol.ACTION_SYNC_CONFIG)
            addAction(HookProtocol.ACTION_TRIGGER_MUTE)
            addAction(HookProtocol.ACTION_TRIGGER_HANGUP)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!verifyCaller(intent)) {
                    XposedBridge.log("$TAG Rejected unauthorized broadcast: ${intent.action}")
                    return
                }

                when (intent.action) {
                    HookProtocol.ACTION_PING -> handlePing(systemContext, classLoader)
                    HookProtocol.ACTION_LAUNCH_VOICE -> handleLaunchVoice(systemContext, classLoader, intent)
                    HookProtocol.ACTION_SYNC_CONFIG -> {
                        suppressChatGptIsland = intent.getBooleanExtra(HookProtocol.EXTRA_SUPPRESS_CHATGPT_ISLAND, true)
                        autoShowOnVoice = intent.getBooleanExtra(HookProtocol.EXTRA_AUTO_SHOW_ON_VOICE, true)
                        XposedBridge.log("$TAG Config synced: autoShow=$autoShowOnVoice, suppressIsland=$suppressChatGptIsland")
                    }
                    HookProtocol.ACTION_TRIGGER_MUTE -> {
                        val pi = lastMuteAction
                        if (pi != null) {
                            try {
                                pi.send()
                                XposedBridge.log("$TAG Triggered ChatGPT Mute PendingIntent in system_server")
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG Failed to send Mute PendingIntent: ${t.message}")
                            }
                        } else {
                            XposedBridge.log("$TAG Trigger Mute skipped: no active Mute PendingIntent")
                        }
                    }
                    HookProtocol.ACTION_TRIGGER_HANGUP -> {
                        val pi = lastEndAction
                        if (pi != null) {
                            try {
                                pi.send()
                                XposedBridge.log("$TAG Triggered ChatGPT Hang Up PendingIntent in system_server")
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG Failed to send Hang Up PendingIntent: ${t.message}")
                            }
                        } else {
                            XposedBridge.log("$TAG Trigger Hang Up skipped: no active Hang Up PendingIntent")
                        }
                    }
                }
            }
        }

        val mainHandler = Handler(Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemContext.registerReceiver(receiver, filter, null, mainHandler, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            systemContext.registerReceiver(receiver, filter, null, mainHandler)
        }
        XposedBridge.log("$TAG Command receiver registered in system_server (SDK ${Build.VERSION.SDK_INT})")
    }

    // =========================================================================
    // 2. com.android.systemui (HyperOS 4 超级岛精准过滤，仅过滤 ChatGPT 语音)
    // =========================================================================

    private fun hookSystemUi(classLoader: ClassLoader) {
        try {
            // Register config sync in SystemUI process once Application attaches
            XposedBridge.hookAllMethods(
                android.app.Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!systemUiRegistered.compareAndSet(false, true)) return
                        val app = param.thisObject as? Context ?: return
                        val filter = IntentFilter(HookProtocol.ACTION_SYNC_CONFIG)
                        val rx = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                if (!verifyCaller(intent)) return
                                suppressChatGptIsland = intent.getBooleanExtra(HookProtocol.EXTRA_SUPPRESS_CHATGPT_ISLAND, true)
                                autoShowOnVoice = intent.getBooleanExtra(HookProtocol.EXTRA_AUTO_SHOW_ON_VOICE, true)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            app.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            @Suppress("DEPRECATION")
                            app.registerReceiver(rx, filter)
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        // Defensively hook known HyperOS Focus / Island eligibility checks for com.openai.chatgpt ONLY
        val candidateClasses = listOf(
            "com.android.systemui.statusbar.notification.focus.FocusCoordinator",
            "com.android.systemui.statusbar.notification.utils.NotificationUtil",
            "com.android.systemui.statusbar.notification.MiuiNotificationCompat"
        )
        val candidateMethods = listOf(
            "isFocusNotification",
            "isDynamicIsland",
            "isIslandNotification",
            "isPromotedOngoing"
        )

        for (className in candidateClasses) {
            val clazz = try {
                XposedHelpers.findClass(className, classLoader)
            } catch (_: Throwable) {
                null
            } ?: continue

            for (methodName in candidateMethods) {
                try {
                    XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!suppressChatGptIsland || !autoShowOnVoice) return
                            if (isChatGptVoiceArg(param.args)) {
                                param.result = false
                            }
                        }
                    })
                    XposedBridge.log("$TAG Hooked SystemUI $className.$methodName")
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun isChatGptVoiceArg(args: Array<Any?>): Boolean {
        for (arg in args) {
            if (arg is StatusBarNotification) {
                if (arg.packageName == HookProtocol.CHATGPT_PACKAGE &&
                    isChatGptVoiceNotification(arg.notification.channelId ?: "", arg.notification)
                ) {
                    return true
                }
            } else if (arg != null) {
                // NotificationEntry wrapper in SystemUI has getSbn()
                try {
                    val sbn = XposedHelpers.callMethod(arg, "getSbn") as? StatusBarNotification
                    if (sbn != null &&
                        sbn.packageName == HookProtocol.CHATGPT_PACKAGE &&
                        isChatGptVoiceNotification(sbn.notification.channelId ?: "", sbn.notification)
                    ) {
                        return true
                    }
                } catch (_: Throwable) {
                }
            }
        }
        return false
    }

    // =========================================================================
    // 3. com.openai.chatgpt (WebRTC DataChannel 逐字实时字幕 + AudioTrack 精准静音)
    // =========================================================================

    private val hookedObserverClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val captionBuilder = StringBuilder()
    private val trackedAudioTracks = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<android.media.AudioTrack, Boolean>())
    )
    private val chatGptReceiverRegistered = AtomicBoolean(false)

    @Volatile
    private var chatGptAppContext: Context? = null

    @Volatile
    private var speakerMutedInChatGpt: Boolean = false

    @Volatile
    private var lastEventLogCount = 0

    private fun hookChatGptLiveCaptions(classLoader: ClassLoader) {
        android.util.Log.i("GPTVoiceBall", "hookChatGptLiveCaptions loaded in com.openai.chatgpt")

        // 3a. Register speaker mute receiver when ChatGPT Application creates
        try {
            XposedBridge.hookAllMethods(
                android.app.Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.thisObject as? Context ?: return
                        if (chatGptAppContext == null) {
                            chatGptAppContext = ctx.applicationContext
                        }
                        registerChatGptControlReceiver(ctx.applicationContext)
                    }
                }
            )
        } catch (_: Throwable) {
        }

        // 3b. Hook AudioTrack inside com.openai.chatgpt for 100% pure speaker silence toggle
        //     (bypasses Android STREAM_VOICE_CALL minVolume=1 restriction!)
        try {
            val audioTrackClass = android.media.AudioTrack::class.java
            XposedBridge.hookAllConstructors(audioTrackClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val track = param.thisObject as? android.media.AudioTrack ?: return
                    trackedAudioTracks.add(track)
                    if (speakerMutedInChatGpt) {
                        try {
                            track.setVolume(0f)
                        } catch (_: Throwable) {
                        }
                    }
                }
            })
            XposedBridge.hookAllMethods(audioTrackClass, "play", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val track = param.thisObject as? android.media.AudioTrack ?: return
                    trackedAudioTracks.add(track)
                    if (speakerMutedInChatGpt) {
                        try {
                            track.setVolume(0f)
                        } catch (_: Throwable) {
                        }
                    }
                }
            })
            XposedBridge.hookAllMethods(audioTrackClass, "setVolume", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (speakerMutedInChatGpt && param.args.isNotEmpty() && param.args[0] is Float) {
                        param.args[0] = 0f
                    }
                }
            })
            android.util.Log.i("GPTVoiceBall", "Hooked AudioTrack in com.openai.chatgpt for speaker mute")
        } catch (t: Throwable) {
            android.util.Log.e("GPTVoiceBall", "Failed to hook AudioTrack: ${t.message}")
        }

        // 3c. Hook ChatGPT v1.2026.314's exact DataChannel$Observer implementation (far0.onMessage)
        try {
            val far0Class = XposedHelpers.findClass("far0", classLoader)
            XposedBridge.hookAllMethods(far0Class, "onMessage", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val bufObj = param.args.firstOrNull() ?: return
                    handleWebRtcDataChannelBuffer(bufObj)
                }
            })
            android.util.Log.i("GPTVoiceBall", "Hooked far0.onMessage directly!")
        } catch (t: Throwable) {
            android.util.Log.w("GPTVoiceBall", "far0.onMessage not found: ${t.message}")
        }

        // 3d. Universal fallback: hook DataChannel.nativeRegisterObserver + registerObserver
        try {
            val dataChannelClass = XposedHelpers.findClass(
                "livekit.org.webrtc.DataChannel",
                classLoader
            )
            val obsRegisterHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val observer = param.args.firstOrNull() ?: return
                    val obsClass = observer.javaClass
                    if (!hookedObserverClasses.add(obsClass)) return
                    try {
                        XposedBridge.hookAllMethods(obsClass, "onMessage", object : XC_MethodHook() {
                            override fun beforeHookedMethod(msgParam: MethodHookParam) {
                                val bufferObj = msgParam.args.firstOrNull() ?: return
                                handleWebRtcDataChannelBuffer(bufferObj)
                            }
                        })
                        android.util.Log.i("GPTVoiceBall", "Hooked dynamic DataChannel Observer: ${obsClass.name}")
                    } catch (t: Throwable) {
                        android.util.Log.e("GPTVoiceBall", "Failed to hook onMessage: ${t.message}")
                    }
                }
            }
            XposedBridge.hookAllMethods(dataChannelClass, "registerObserver", obsRegisterHook)
            XposedBridge.hookAllMethods(dataChannelClass, "nativeRegisterObserver", obsRegisterHook)
        } catch (_: Throwable) {
        }

        // 3e. Also hook ChatGPT's transcript_delta parser directly (v1.2026.314: z5o.i(String))
        try {
            val parserClass = XposedHelpers.findClass("z5o", classLoader)
            XposedBridge.hookAllMethods(parserClass, "i", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val raw = param.args.firstOrNull() as? String ?: return
                    if (raw.contains("transcript_delta") || raw.contains("transcript")) {
                        handleRawCaptionPayload(raw)
                    }
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun registerChatGptControlReceiver(appContext: Context) {
        if (!chatGptReceiverRegistered.compareAndSet(false, true)) return
        try {
            val filter = IntentFilter(HookProtocol.ACTION_SET_SPEAKER_MUTED)
            val rx = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!verifyCaller(intent)) return
                    val muted = intent.getBooleanExtra(HookProtocol.EXTRA_SPEAKER_MUTED, false)
                    speakerMutedInChatGpt = muted
                    applySpeakerMuteToTrackedTracks(muted)
                    android.util.Log.i("GPTVoiceBall", "Applied ChatGPT AudioTrack speakerMuted=$muted (tracks=${trackedAudioTracks.size})")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(rx, filter)
            }
        } catch (t: Throwable) {
            chatGptReceiverRegistered.set(false)
            android.util.Log.e("GPTVoiceBall", "registerChatGptControlReceiver failed: ${t.message}")
        }
    }

    private fun applySpeakerMuteToTrackedTracks(muted: Boolean) {
        val snapshot = synchronized(trackedAudioTracks) { trackedAudioTracks.toList() }
        val targetGain = if (muted) 0f else 1f
        for (track in snapshot) {
            try {
                track.setVolume(targetGain)
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleWebRtcDataChannelBuffer(bufferObj: Any) {
        try {
            val byteBuffer = XposedHelpers.getObjectField(bufferObj, "data") as? java.nio.ByteBuffer ?: return
            val dup = byteBuffer.duplicate()
            val remaining = dup.remaining()
            if (remaining <= 2 || remaining > 65536) return

            val bytes = ByteArray(remaining)
            dup.get(bytes)
            val rawStr = String(bytes, Charsets.UTF_8)
            handleRawCaptionPayload(rawStr)
        } catch (_: Throwable) {
        }
    }

    private fun handleRawCaptionPayload(rawStr: String) {
        try {
            // Extract embedded JSON object (works for both plain JSON and LiveKit protobuf UserPacket)
            val firstBrace = rawStr.indexOf('{')
            val lastBrace = rawStr.lastIndexOf('}')
            if (firstBrace < 0 || lastBrace <= firstBrace) return

            val jsonStr = rawStr.substring(firstBrace, lastBrace + 1)
            val json = org.json.JSONObject(jsonStr)
            val type = json.optString("type", "").lowercase()

            if (lastEventLogCount < 20) {
                lastEventLogCount++
                val preview = if (jsonStr.length > 220) jsonStr.substring(0, 220) + "..." else jsonStr
                android.util.Log.i("GPTVoiceBall", "CaptionPayload #$lastEventLogCount: $preview")
            }

            // 1. Direct ChatGPT v1.2026.314 key: "transcript_delta" (verified from classes5.dex!)
            val transcriptDelta = json.optString("transcript_delta")
            if (transcriptDelta.isNotEmpty()) {
                val updated = appendCaptionDelta(transcriptDelta)
                if (updated.isNotEmpty()) broadcastLiveCaption(updated)
                return
            }

            // 2. Standard OpenAI Realtime / LiveKit event types
            when {
                type.contains("response.created") ||
                    type.contains("speech_started") ||
                    type.contains("output_item.added") -> {
                    synchronized(captionBuilder) { captionBuilder.setLength(0) }
                }

                (type.contains("transcript") && type.contains("delta")) ||
                    type.contains("text.delta") -> {
                    val delta = json.optString("delta")
                        .ifEmpty { json.optString("text") }
                        .ifEmpty { json.optString("content") }
                    if (delta.isNotEmpty()) {
                        val updated = appendCaptionDelta(delta)
                        if (updated.isNotEmpty()) broadcastLiveCaption(updated)
                    }
                }

                type.contains("transcript") && (type.contains("done") || type.contains("completed")) -> {
                    val full = json.optString("transcript")
                        .ifEmpty { json.optString("text") }
                        .trim()
                    if (full.isNotEmpty()) {
                        replaceCaption(full)
                        broadcastLiveCaption(full)
                    }
                }

                else -> {
                    // Check nested payload / data / event objects for transcript_delta or transcript
                    val nested = json.optJSONObject("data")
                        ?: json.optJSONObject("payload")
                        ?: json.optJSONObject("event")
                    val nestedDelta = nested?.optString("transcript_delta").orEmpty()
                        .ifEmpty { nested?.optString("delta").orEmpty() }
                    val directTranscript = json.optString("transcript")
                        .ifEmpty { nested?.optString("transcript").orEmpty() }
                    val directDelta = json.optString("delta")

                    when {
                        nestedDelta.isNotEmpty() -> {
                            val updated = appendCaptionDelta(nestedDelta)
                            if (updated.isNotEmpty()) broadcastLiveCaption(updated)
                        }
                        directDelta.isNotEmpty() && (type.contains("voice") || type.contains("audio") || type.contains("caption") || type.contains("message")) -> {
                            val updated = appendCaptionDelta(directDelta)
                            if (updated.isNotEmpty()) broadcastLiveCaption(updated)
                        }
                        directTranscript.isNotEmpty() -> {
                            replaceCaption(directTranscript.trim())
                            broadcastLiveCaption(directTranscript.trim())
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun appendCaptionDelta(delta: String): String {
        return synchronized(captionBuilder) {
            captionBuilder.append(delta)
            if (captionBuilder.length > 220) {
                captionBuilder.delete(0, captionBuilder.length - 180)
            }
            captionBuilder.toString().trim()
        }
    }

    private fun replaceCaption(full: String) {
        synchronized(captionBuilder) {
            captionBuilder.setLength(0)
            captionBuilder.append(full)
        }
    }

    private fun broadcastLiveCaption(text: String) {
        val ctx = chatGptAppContext ?: resolveChatGptAppContext() ?: return
        try {
            val intent = Intent(HookProtocol.ACTION_LIVE_CAPTION).apply {
                setPackage(HookProtocol.MODULE_PACKAGE)
                putExtra(HookProtocol.EXTRA_CAPTION_TEXT, text)
            }
            ctx.sendBroadcast(intent)
        } catch (_: Throwable) {
        }
    }

    private fun resolveChatGptAppContext(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val app = XposedHelpers.callStaticMethod(atClass, "currentApplication") as? Context
            if (app != null) chatGptAppContext = app.applicationContext
            chatGptAppContext
        } catch (_: Throwable) {
            null
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private fun verifyCaller(intent: Intent): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val token = intent.getParcelableExtra<PendingIntent>(HookProtocol.EXTRA_CALLER_TOKEN)
            token?.creatorPackage == HookProtocol.MODULE_PACKAGE
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Caller token verification failed: ${t.message}")
            false
        }
    }

    private fun handlePing(systemContext: Context, classLoader: ClassLoader) {
        val hasMiuiUtils = findMiuiMultiWindowUtils(classLoader) != null
        val pong = Intent(HookProtocol.ACTION_PONG).apply {
            setPackage(HookProtocol.MODULE_PACKAGE)
            putExtra(HookProtocol.EXTRA_SUCCESS, true)
            putExtra(HookProtocol.EXTRA_HAS_MIUI_UTILS, hasMiuiUtils)
            putExtra(HookProtocol.EXTRA_SDK_INT, Build.VERSION.SDK_INT)
            putExtra(HookProtocol.EXTRA_VOICE_ACTIVE, activeVoiceNotifIds.isNotEmpty())
            putExtra(
                HookProtocol.EXTRA_MESSAGE,
                "system_server 已就绪 (API ${Build.VERSION.SDK_INT}, 语音监听+小窗+超级岛过滤已加载)"
            )
        }
        sendReplyBroadcast(systemContext, pong)
    }

    private fun handleLaunchVoice(
        systemContext: Context,
        classLoader: ClassLoader,
        request: Intent
    ) {
        val requestId = request.getLongExtra(HookProtocol.EXTRA_REQUEST_ID, 0L)
        val useFreeform = request.getBooleanExtra(HookProtocol.EXTRA_USE_FREEFORM, true)
        val preferMiuiUtils = request.getBooleanExtra(HookProtocol.EXTRA_PREFER_MIUI_UTILS, true)
        val resumeExisting = request.getBooleanExtra(HookProtocol.EXTRA_RESUME_EXISTING, false)
        val widthRatio = request.getFloatExtra(HookProtocol.EXTRA_WINDOW_WIDTH_RATIO, 0.78f).coerceIn(0.45f, 0.96f)
        val heightRatio = request.getFloatExtra(HookProtocol.EXTRA_WINDOW_HEIGHT_RATIO, 0.52f).coerceIn(0.35f, 0.85f)

        val hasActiveVoice = activeVoiceNotifIds.isNotEmpty()

        try {
            // If resuming an ACTIVE voice call (Small Window OR Fullscreen), launch MainActivity with
            // REORDER_TO_FRONT instead of https://chatgpt.com/voice deeplink (which would reset/end the call!)
            // Also avoids BAL restrictions that can block PendingIntent.send() in fullscreen mode.
            val targetIntent = if (resumeExisting && hasActiveVoice) {
                Intent(Intent.ACTION_MAIN).apply {
                    setClassName(HookProtocol.CHATGPT_PACKAGE, "${HookProtocol.CHATGPT_PACKAGE}.MainActivity")
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(HookProtocol.CHATGPT_VOICE_URI)).apply {
                    setPackage(HookProtocol.CHATGPT_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            }

            val (optionsBundle, engineUsed) = if (useFreeform) {
                buildFreeformOptions(systemContext, classLoader, preferMiuiUtils, widthRatio, heightRatio)
            } else {
                Pair(null, "system_fullscreen")
            }

            startActivityAsCurrentUser(systemContext, targetIntent, optionsBundle)
            XposedBridge.log("$TAG Opened ChatGPT Voice via $engineUsed (resume=$resumeExisting, active=$hasActiveVoice, req=$requestId)")
            sendLaunchResult(systemContext, requestId, true, engineUsed, "已通过 $engineUsed 打开 ChatGPT 语音")
        } catch (t: Throwable) {
            val err = "${t.javaClass.simpleName}: ${t.message}"
            XposedBridge.log("$TAG Launch failed: ${t.stackTraceToString()}")
            sendLaunchResult(systemContext, requestId, false, "error", err)
        }
    }

    private fun buildFreeformOptions(
        systemContext: Context,
        classLoader: ClassLoader,
        preferMiuiUtils: Boolean,
        widthRatio: Float,
        heightRatio: Float
    ): Pair<Bundle?, String> {
        if (preferMiuiUtils) {
            val miuiOptions = tryBuildMiuiNativeOptions(systemContext, classLoader)
            if (miuiOptions != null) {
                return Pair(miuiOptions, "hyperos_miui_freeform")
            }
        }

        val options = ActivityOptions.makeBasic()
        try {
            val setMode = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            setMode.invoke(options, HookProtocol.WINDOWING_MODE_FREEFORM)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG setLaunchWindowingMode reflection failed: ${t.message}")
        }

        val dm = systemContext.resources.displayMetrics
        val winW = (dm.widthPixels * widthRatio).toInt()
        val winH = (dm.heightPixels * heightRatio).toInt()
        val left = (dm.widthPixels - winW) / 2
        val top = ((dm.heightPixels - winH) * 0.45f).toInt()
        options.launchBounds = Rect(left, top, left + winW, top + winH)

        return Pair(options.toBundle(), "aosp_freeform_mode5")
    }

    private fun findMiuiMultiWindowUtils(classLoader: ClassLoader): Class<*>? {
        val candidates = listOf(
            "android.util.MiuiMultiWindowUtils",
            "miui.util.MiuiMultiWindowUtils"
        )
        for (name in candidates) {
            try {
                return Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
            }
            try {
                return Class.forName(name)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun tryBuildMiuiNativeOptions(systemContext: Context, classLoader: ClassLoader): Bundle? {
        val utilsClass = findMiuiMultiWindowUtils(classLoader) ?: return null
        val methods = utilsClass.declaredMethods.filter { it.name == "getActivityOptions" }

        for (method in methods) {
            try {
                method.isAccessible = true
                val params = method.parameterTypes
                val result = when {
                    params.size == 4 &&
                        Context::class.java.isAssignableFrom(params[0]) &&
                        params[1] == String::class.java &&
                        params[2] == Boolean::class.javaPrimitiveType &&
                        params[3] == Boolean::class.javaPrimitiveType -> {
                        method.invoke(null, systemContext, HookProtocol.CHATGPT_PACKAGE, true, false)
                    }
                    params.size == 3 &&
                        Context::class.java.isAssignableFrom(params[0]) &&
                        params[1] == String::class.java &&
                        params[2] == Boolean::class.javaPrimitiveType -> {
                        method.invoke(null, systemContext, HookProtocol.CHATGPT_PACKAGE, true)
                    }
                    params.size == 2 &&
                        Context::class.java.isAssignableFrom(params[0]) &&
                        params[1] == String::class.java -> {
                        method.invoke(null, systemContext, HookProtocol.CHATGPT_PACKAGE)
                    }
                    else -> null
                }
                when (result) {
                    is ActivityOptions -> return result.toBundle()
                    is Bundle -> return result
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG MiuiMultiWindowUtils.${method.name}(${method.parameterTypes.size}) failed: ${t.message}")
            }
        }
        return null
    }

    private fun startActivityAsCurrentUser(
        systemContext: Context,
        intent: Intent,
        options: Bundle?
    ) {
        try {
            val currentUser = XposedHelpers.getStaticObjectField(UserHandle::class.java, "CURRENT") as UserHandle
            val method = Context::class.java.getMethod(
                "startActivityAsUser",
                Intent::class.java,
                Bundle::class.java,
                UserHandle::class.java
            )
            method.invoke(systemContext, intent, options, currentUser)
        } catch (_: Throwable) {
            if (options != null) {
                systemContext.startActivity(intent, options)
            } else {
                systemContext.startActivity(intent)
            }
        }
    }

    private fun sendLaunchResult(
        systemContext: Context,
        requestId: Long,
        success: Boolean,
        engine: String,
        message: String
    ) {
        val reply = Intent(HookProtocol.ACTION_LAUNCH_RESULT).apply {
            setPackage(HookProtocol.MODULE_PACKAGE)
            putExtra(HookProtocol.EXTRA_REQUEST_ID, requestId)
            putExtra(HookProtocol.EXTRA_SUCCESS, success)
            putExtra(HookProtocol.EXTRA_ENGINE, engine)
            putExtra(HookProtocol.EXTRA_MESSAGE, message)
        }
        sendReplyBroadcast(systemContext, reply)
    }

    private fun sendReplyBroadcast(systemContext: Context, intent: Intent) {
        try {
            val currentUser = XposedHelpers.getStaticObjectField(UserHandle::class.java, "CURRENT") as UserHandle
            val method = Context::class.java.getMethod(
                "sendBroadcastAsUser",
                Intent::class.java,
                UserHandle::class.java
            )
            method.invoke(systemContext, intent, currentUser)
        } catch (_: Throwable) {
            systemContext.sendBroadcast(intent)
        }
    }
}
