package io.github.iammaplezeng.chatgptvoiceball.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import io.github.iammaplezeng.chatgptvoiceball.R
import io.github.iammaplezeng.chatgptvoiceball.VoiceBallApp
import io.github.iammaplezeng.chatgptvoiceball.launcher.ChatGptVoiceLauncher
import io.github.iammaplezeng.chatgptvoiceball.model.AppStateStore
import io.github.iammaplezeng.chatgptvoiceball.model.BallSettings
import io.github.iammaplezeng.chatgptvoiceball.model.LogRepository
import io.github.iammaplezeng.chatgptvoiceball.overlay.ChatGptOrbView
import io.github.iammaplezeng.chatgptvoiceball.ui.MainActivity
import io.github.iammaplezeng.chatgptvoiceball.xposed.HookProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class VoiceBallService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_TOGGLE_VISIBILITY = "io.github.iammaplezeng.chatgptvoiceball.service.TOGGLE_VISIBILITY"
        private const val ACTION_STOP_SERVICE = "io.github.iammaplezeng.chatgptvoiceball.service.STOP"
        private const val IDLE_DELAY_MS = 4000L

        fun start(context: Context) {
            val intent = Intent(context, VoiceBallService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceBallService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager

    private var orbView: ChatGptOrbView? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var expandedBottomContainer: View? = null
    private var expandedTopRightBar: View? = null
    private var quickMenuView: View? = null

    // Live references inside expanded overlay for instant state updates
    private var dockMicButton: ImageView? = null
    private var dockCaptionView: TextView? = null
    private var topCaptionButton: ImageView? = null
    private var topSpeakerButton: ImageView? = null

    private var isScreenLocked = false
    private var isUserHidden = false
    private var isDockExpanded = false
    private var isCaptionVisible = true
    private var isSpeakerMuted = false
    private var savedVoiceCallVolume = -1
    private var lastTopBarTouchMs = 0L
    private var lastAppliedSettings: BallSettings = AppStateStore.settings.value

    private val idleRunnable = Runnable {
        enterIdleState()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenLocked = true
                    collapseExpandedDock()
                    dismissQuickMenu()
                    updateOrbVisibility()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenLocked = false
                    updateOrbVisibility()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppStateStore.init(this)
        windowManager = getSystemService(WindowManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)

        startForegroundCompat()
        registerScreenReceiver()

        if (!Settings.canDrawOverlays(this)) {
            LogRepository.error("缺少悬浮窗权限，服务无法显示悬浮球")
            stopSelf()
            return
        }

        createOrbOverlay()
        AppStateStore.setServiceRunning(true)
        AppStateStore.setOrbVisible(true)
        LogRepository.info("ChatGPT 语音悬浮球服务已启动")

        serviceScope.launch {
            AppStateStore.settings.collect { settings ->
                applySettings(settings)
            }
        }
        serviceScope.launch {
            AppStateStore.isMicMuted.collect { muted ->
                refreshMicButtonUi(muted)
                refreshCaptionUi()
            }
        }
        serviceScope.launch {
            AppStateStore.captionText.collect {
                refreshCaptionUi()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.hasExtra(HookProtocol.EXTRA_IS_MUTED)) {
                AppStateStore.setMicMuted(intent.getBooleanExtra(HookProtocol.EXTRA_IS_MUTED, false))
            }
            val caption = intent.getStringExtra(HookProtocol.EXTRA_CAPTION_TEXT)
            if (!caption.isNullOrBlank()) {
                AppStateStore.setCaptionText(caption)
            }
        }

        when (intent?.action) {
            HookProtocol.ACTION_SYSTEM_SHOW_ORB -> {
                AppStateStore.setVoiceSessionActive(true)
                isUserHidden = false
                if (!AppStateStore.settings.value.autoShowOnVoice) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                updateOrbVisibility()
                updateNotification()
                LogRepository.info("system_server 触发：ChatGPT 语音进行中，已弹出悬浮球")
            }
            HookProtocol.ACTION_SYSTEM_HIDE_ORB -> {
                AppStateStore.setVoiceSessionActive(false)
                LogRepository.info("system_server 触发：ChatGPT 语音已结束，自动关闭悬浮球")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_VISIBILITY -> {
                isUserHidden = !isUserHidden
                collapseExpandedDock()
                dismissQuickMenu()
                updateOrbVisibility()
                updateNotification()
                LogRepository.info(if (isUserHidden) "悬浮球已临时隐藏" else "悬浮球已恢复显示")
            }
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        collapseExpandedDock()
        dismissQuickMenu()
        repositionOrbFromRatios()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        restoreSpeakerVolumeIfMuted()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        collapseExpandedDock()
        dismissQuickMenu()
        removeOrbOverlay()
        AppStateStore.setServiceRunning(false)
        AppStateStore.setOrbVisible(false)
        LogRepository.info("ChatGPT 语音悬浮球服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceBallService::class.java).setAction(ACTION_TOGGLE_VISIBILITY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, VoiceBallService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleLabel = if (isUserHidden) "显示悬浮球" else "隐藏悬浮球"
        val statusText = if (isUserHidden) "悬浮球已隐藏 · 下拉通知栏可恢复" else "ChatGPT 语音悬浮球显示中（不占用超级岛）"

        val hyperOsExtras = android.os.Bundle().apply {
            putBoolean("miui.focus.enable", false)
            putBoolean("miui.enableFloat", false)
            putBoolean("miui.enableKeyguard", false)
            putBoolean("miui.showAction", false)
        }

        return NotificationCompat.Builder(this, VoiceBallApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_orb)
            .setContentTitle("ChatGPTVoiceBall")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .addExtras(hyperOsExtras)
            .setContentIntent(openAppIntent)
            .addAction(0, toggleLabel, toggleIntent)
            .addAction(0, "关闭", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOrbOverlay() {
        val settings = AppStateStore.settings.value
        val sizePx = dpToPx(settings.orbSizeDp)
        val (screenW, screenH) = getScreenSize()
        val margin = dpToPx(12)

        val startX = ((screenW - sizePx - margin * 2) * settings.posXRatio + margin).roundToInt()
        val startY = ((screenH - sizePx - dpToPx(120)) * settings.posYRatio + dpToPx(48)).roundToInt()

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX.coerceIn(margin, (screenW - sizePx - margin).coerceAtLeast(margin))
            y = startY.coerceIn(dpToPx(48), (screenH - sizePx - dpToPx(72)).coerceAtLeast(dpToPx(48)))
        }

        val view = ChatGptOrbView(this, isPillMode = false).apply {
            alpha = settings.activeAlpha
        }

        attachOrbTouchListener(view, params)
        orbView = view
        orbParams = params
        windowManager.addView(view, params)
        scheduleIdleState()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachOrbTouchListener(view: ChatGptOrbView, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var longPressTriggered = false

        val longPressRunnable = Runnable {
            if (!isDragging) {
                longPressTriggered = true
                vibrate(28L)
                view.setOrbPressed(false)
                showQuickMenu()
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wakeFromIdle()
                    dismissQuickMenu()
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressTriggered = false
                    view.setOrbPressed(true)
                    mainHandler.postDelayed(longPressRunnable, 460L)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).roundToInt()
                    val dy = (event.rawY - initialTouchY).roundToInt()
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        val (screenW, screenH) = getScreenSize()
                        params.x = (initialX + dx).coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(
                            dpToPx(32),
                            (screenH - params.height - dpToPx(36)).coerceAtLeast(dpToPx(32))
                        )
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    view.setOrbPressed(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !isDragging && !longPressTriggered) {
                        vibrate(16L)
                        expandBottomDock()
                    } else if (isDragging) {
                        if (AppStateStore.settings.value.autoSnapEdge) {
                            snapToEdge(view, params)
                        } else {
                            persistPosition(params)
                        }
                    }
                    scheduleIdleState()
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Gemini Live style expanded overlay:
     * - Top-right: [Caption Toggle] [Speaker Toggle]
     * - Above bottom dock: Dark rounded ChatGPT voice caption bar
     * - Bottom dock: [Small Window] [Fullscreen]  ( Blue Glowing Pill )  [ChatGPT Mic] [Hang Up]
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun expandBottomDock() {
        if (isDockExpanded) return
        dismissQuickMenu()
        isDockExpanded = true
        updateOrbVisibility()

        // 1. Top-right 2 buttons (Caption toggle + Speaker playback toggle)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val captionToggleBtn = createCircleIconButton(
            if (isCaptionVisible) R.drawable.ic_voice_caption_on else R.drawable.ic_voice_caption_off,
            "开关字幕"
        ) {
            lastTopBarTouchMs = android.os.SystemClock.uptimeMillis()
            vibrate(14L)
            isCaptionVisible = !isCaptionVisible
            topCaptionButton?.setImageResource(
                if (isCaptionVisible) R.drawable.ic_voice_caption_on else R.drawable.ic_voice_caption_off
            )
            refreshCaptionUi()
        }.apply {
            setOnTouchListener { _, _ ->
                lastTopBarTouchMs = android.os.SystemClock.uptimeMillis()
                false
            }
        }
        topCaptionButton = captionToggleBtn

        val speakerToggleBtn = createCircleIconButton(
            if (isSpeakerMuted) R.drawable.ic_voice_speaker_off else R.drawable.ic_voice_speaker_on,
            "开关声音"
        ) {
            lastTopBarTouchMs = android.os.SystemClock.uptimeMillis()
            vibrate(14L)
            toggleSpeakerPlayback()
        }.apply {
            setOnTouchListener { _, _ ->
                lastTopBarTouchMs = android.os.SystemClock.uptimeMillis()
                false
            }
        }
        topSpeakerButton = speakerToggleBtn

        topBar.addView(captionToggleBtn)
        topBar.addView(createSpacer(10))
        topBar.addView(speakerToggleBtn)

        val topParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(16)
            y = dpToPx(50)
        }
        expandedTopRightBar = topBar
        windowManager.addView(topBar, topParams)

        // 2. Bottom container: Caption bar (above) + Control Dock (below)
        val (screenW, _) = getScreenSize()
        val bottomWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val hPad = dpToPx(14)
            setPadding(hPad, 0, hPad, dpToPx(8))
        }

        val captionView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.START
            val padH = dpToPx(20)
            val padV = dpToPx(16)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 10, 12, 16))
                cornerRadius = dpToPx(22).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                (screenW - dpToPx(32)).coerceAtLeast(dpToPx(260)),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(14)
            }
        }
        dockCaptionView = captionView
        refreshCaptionUi()

        val dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Button 1: Open ChatGPT Voice in HyperOS 4 Small Window (resumes existing session without ending!)
        val smallWinBtn = createCircleIconButton(R.drawable.ic_voice_pip, "小窗打开") {
            vibrate(14L)
            collapseExpandedDock()
            ChatGptVoiceLauncher.launchVoiceChat(
                this,
                forceFreeformOverride = true,
                resumeExisting = true
            )
        }

        // Button 2: Resume ChatGPT Voice Fullscreen (resumes existing session without ending!)
        val fullBtn = createCircleIconButton(R.drawable.ic_voice_fullscreen, "全屏切回") {
            vibrate(14L)
            collapseExpandedDock()
            ChatGptVoiceLauncher.launchVoiceChat(
                this,
                forceFreeformOverride = false,
                resumeExisting = true
            )
        }

        // Center: Wide luminous sky-blue glowing pill orb — tapping does NOT close expanded state!
        val centerPill = ChatGptOrbView(this, isPillMode = true).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(116), dpToPx(58)).apply {
                setMargins(dpToPx(10), 0, dpToPx(10), 0)
            }
            setOnClickListener {
                vibrate(12L)
                triggerLaunchRipple()
            }
        }

        // Button 3: ChatGPT-style Mic Mute/Unmute (synced with system_server live state!)
        val micBtn = createCircleIconButton(
            if (AppStateStore.isMicMuted.value) R.drawable.ic_voice_mic_off else R.drawable.ic_voice_mic,
            "静音切换"
        ) {
            vibrate(16L)
            ChatGptVoiceLauncher.triggerMuteToggle(this)
        }
        dockMicButton = micBtn
        refreshMicButtonUi(AppStateStore.isMicMuted.value)

        // Button 4: Hang Up / End Voice Session (executes ChatGPT official Hang Up in system_server!)
        val endBtn = createCircleIconButton(R.drawable.ic_voice_close, "结束语音") {
            vibrate(24L)
            collapseExpandedDock()
            ChatGptVoiceLauncher.triggerHangUp(this)
            // Fallback stop if notification cancel arrives slightly delayed
            mainHandler.postDelayed({
                if (!AppStateStore.voiceSessionActive.value) {
                    stopSelf()
                }
            }, 600L)
        }

        dockRow.addView(smallWinBtn)
        dockRow.addView(createSpacer(10))
        dockRow.addView(fullBtn)
        dockRow.addView(centerPill)
        dockRow.addView(micBtn)
        dockRow.addView(createSpacer(10))
        dockRow.addView(endBtn)

        bottomWrapper.addView(captionView)
        bottomWrapper.addView(dockRow)

        val bottomParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(22)
        }

        bottomWrapper.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                // Delay slightly so taps on the top-right Caption/Speaker window update lastTopBarTouchMs first
                mainHandler.postDelayed({
                    val elapsed = android.os.SystemClock.uptimeMillis() - lastTopBarTouchMs
                    if (isDockExpanded && elapsed > 300L) {
                        collapseExpandedDock()
                    }
                }, 70L)
                true
            } else {
                false
            }
        }

        bottomWrapper.alpha = 0f
        bottomWrapper.translationY = dpToPx(24).toFloat()
        expandedBottomContainer = bottomWrapper
        windowManager.addView(bottomWrapper, bottomParams)
        bottomWrapper.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun createCircleIconButton(
        @DrawableRes iconRes: Int,
        desc: String,
        onClick: () -> Unit
    ): ImageView {
        val size = dpToPx(50)
        val iconPad = dpToPx(13)
        return ImageView(this).apply {
            setImageResource(iconRes)
            contentDescription = desc
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(iconPad, iconPad, iconPad, iconPad)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(244, 245, 250, 255))
                setStroke(dpToPx(1), Color.argb(85, 175, 200, 230))
            }
            elevation = dpToPx(6).toFloat()
            setOnClickListener { onClick() }
        }
    }

    private fun refreshMicButtonUi(muted: Boolean) {
        val btn = dockMicButton ?: return
        btn.setImageResource(if (muted) R.drawable.ic_voice_mic_off else R.drawable.ic_voice_mic)
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (muted) {
                setColor(Color.argb(246, 255, 235, 235))
                setStroke(dpToPx(1), Color.argb(150, 217, 48, 37))
            } else {
                setColor(Color.argb(244, 245, 250, 255))
                setStroke(dpToPx(1), Color.argb(85, 175, 200, 230))
            }
        }
    }

    private fun refreshCaptionUi() {
        val tv = dockCaptionView ?: return
        tv.visibility = if (isCaptionVisible) View.VISIBLE else View.GONE
        val liveText = AppStateStore.captionText.value
        tv.text = when {
            liveText.isNotBlank() -> liveText
            AppStateStore.isMicMuted.value -> "ChatGPT 语音通话中 · 麦克风已静音"
            isSpeakerMuted -> "ChatGPT 语音通话中 · 扬声器已静音"
            else -> "ChatGPT 语音通话中 · 正在聆听..."
        }
    }

    private fun toggleSpeakerPlayback() {
        isSpeakerMuted = !isSpeakerMuted
        // Primary: Mute ChatGPT's own AudioTrack gain directly to 0.0f via LSPosed hook
        // (bypasses Android STREAM_VOICE_CALL minVolume=1 limitation!)
        ChatGptVoiceLauncher.setChatGptSpeakerMuted(this, isSpeakerMuted)

        // Secondary fallback: adjust VOICE_CALL stream volume
        try {
            val stream = AudioManager.STREAM_VOICE_CALL
            if (isSpeakerMuted) {
                savedVoiceCallVolume = audioManager.getStreamVolume(stream)
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            } else {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                if (savedVoiceCallVolume > 0) {
                    audioManager.setStreamVolume(stream, savedVoiceCallVolume, 0)
                }
            }
        } catch (_: Throwable) {
        }

        topSpeakerButton?.setImageResource(
            if (isSpeakerMuted) R.drawable.ic_voice_speaker_off else R.drawable.ic_voice_speaker_on
        )
        refreshCaptionUi()
    }

    private fun restoreSpeakerVolumeIfMuted() {
        if (isSpeakerMuted) {
            ChatGptVoiceLauncher.setChatGptSpeakerMuted(this, false)
            try {
                audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                if (savedVoiceCallVolume > 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceCallVolume, 0)
                }
            } catch (_: Throwable) {
            }
            isSpeakerMuted = false
        }
    }

    private fun createSpacer(dp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(dp), 1)
        }
    }

    private fun collapseExpandedDock() {
        if (!isDockExpanded && expandedBottomContainer == null && expandedTopRightBar == null) return
        isDockExpanded = false
        expandedBottomContainer?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {
            }
            expandedBottomContainer = null
        }
        expandedTopRightBar?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {
            }
            expandedTopRightBar = null
        }
        dockMicButton = null
        dockCaptionView = null
        topCaptionButton = null
        topSpeakerButton = null
        updateOrbVisibility()
        scheduleIdleState()
    }

    private fun snapToEdge(view: ChatGptOrbView, params: WindowManager.LayoutParams) {
        val (screenW, _) = getScreenSize()
        val margin = dpToPx(10)
        val centerX = params.x + params.width / 2
        val targetX = if (centerX < screenW / 2) margin else (screenW - params.width - margin).coerceAtLeast(margin)

        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                try {
                    if (orbView != null) {
                        windowManager.updateViewLayout(view, params)
                    }
                } catch (_: Throwable) {
                }
            }
            start()
        }

        val finalParams = WindowManager.LayoutParams().apply {
            copyFrom(params)
            x = targetX
        }
        persistPosition(finalParams)
    }

    private fun persistPosition(params: WindowManager.LayoutParams) {
        val (screenW, screenH) = getScreenSize()
        val maxW = (screenW - params.width).coerceAtLeast(1)
        val maxH = (screenH - params.height).coerceAtLeast(1)
        val xRatio = (params.x.toFloat() / maxW).coerceIn(0f, 1f)
        val yRatio = (params.y.toFloat() / maxH).coerceIn(0.05f, 0.92f)
        AppStateStore.updateSettings {
            it.copy(posXRatio = xRatio, posYRatio = yRatio)
        }
    }

    private fun repositionOrbFromRatios() {
        val view = orbView ?: return
        val params = orbParams ?: return
        val settings = AppStateStore.settings.value
        val (screenW, screenH) = getScreenSize()
        val margin = dpToPx(10)
        val rawX = ((screenW - params.width) * settings.posXRatio).roundToInt()
        params.x = if (settings.autoSnapEdge) {
            if (settings.posXRatio < 0.5f) margin else (screenW - params.width - margin).coerceAtLeast(margin)
        } else {
            rawX.coerceIn(margin, (screenW - params.width - margin).coerceAtLeast(margin))
        }
        params.y = ((screenH - params.height) * settings.posYRatio).roundToInt()
            .coerceIn(dpToPx(36), (screenH - params.height - dpToPx(44)).coerceAtLeast(dpToPx(36)))
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private fun applySettings(settings: BallSettings) {
        val view = orbView ?: return
        val params = orbParams ?: return

        val sizeChanged = settings.orbSizeDp != lastAppliedSettings.orbSizeDp
        lastAppliedSettings = settings

        if (sizeChanged) {
            val newSizePx = dpToPx(settings.orbSizeDp)
            params.width = newSizePx
            params.height = newSizePx
            repositionOrbFromRatios()
        }
        view.alpha = settings.activeAlpha
        updateOrbVisibility()
        scheduleIdleState()
    }

    private fun updateOrbVisibility() {
        val view = orbView ?: return
        val hideDueToLock = isScreenLocked && AppStateStore.settings.value.hideOnLockScreen
        val shouldShow = !isUserHidden && !hideDueToLock && !isDockExpanded
        view.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (shouldShow) {
            view.resumeAnimations()
        } else {
            view.pauseAnimations()
        }
        AppStateStore.setOrbVisible(shouldShow || isDockExpanded)
    }

    private fun wakeFromIdle() {
        mainHandler.removeCallbacks(idleRunnable)
        orbView?.apply {
            setIdleMode(false)
            animate().alpha(AppStateStore.settings.value.activeAlpha).setDuration(150L).start()
        }
    }

    private fun scheduleIdleState() {
        mainHandler.removeCallbacks(idleRunnable)
        mainHandler.postDelayed(idleRunnable, IDLE_DELAY_MS)
    }

    private fun enterIdleState() {
        if (quickMenuView != null || isDockExpanded) return
        orbView?.apply {
            setIdleMode(true)
            animate().alpha(AppStateStore.settings.value.idleAlpha).setDuration(350L).start()
        }
    }

    private fun showQuickMenu() {
        dismissQuickMenu()
        val orbP = orbParams ?: return
        val (screenW, screenH) = getScreenSize()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 22, 26, 38))
                cornerRadius = dpToPx(18).toFloat()
                setStroke(dpToPx(1), Color.argb(120, 140, 205, 255))
            }
            elevation = dpToPx(10).toFloat()
        }

        container.addView(createMenuItem("小窗打开 ChatGPT 语音") {
            dismissQuickMenu()
            ChatGptVoiceLauncher.launchVoiceChat(this, forceFreeformOverride = true, resumeExisting = true)
        })
        container.addView(createMenuItem("暂时隐藏悬浮球") {
            isUserHidden = true
            dismissQuickMenu()
            updateOrbVisibility()
            updateNotification()
        })
        container.addView(createMenuItem("打开设置与日志") {
            dismissQuickMenu()
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
        container.addView(createMenuItem("结束语音通话", Color.parseColor("#FF7A7A")) {
            dismissQuickMenu()
            ChatGptVoiceLauncher.triggerHangUp(this)
        })

        val menuWidth = dpToPx(180)
        val menuHeightEstimate = dpToPx(200)
        val onRightHalf = (orbP.x + orbP.width / 2) > screenW / 2
        val menuX = if (onRightHalf) {
            (orbP.x - menuWidth - dpToPx(8)).coerceAtLeast(dpToPx(12))
        } else {
            (orbP.x + orbP.width + dpToPx(8)).coerceAtMost(screenW - menuWidth - dpToPx(12))
        }
        val menuY = (orbP.y - dpToPx(40)).coerceIn(dpToPx(48), (screenH - menuHeightEstimate - dpToPx(48)).coerceAtLeast(dpToPx(48)))

        val menuParams = WindowManager.LayoutParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = menuX
            y = menuY
        }

        container.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismissQuickMenu()
                scheduleIdleState()
                true
            } else {
                false
            }
        }

        quickMenuView = container
        windowManager.addView(container, menuParams)
    }

    private fun createMenuItem(title: String, textColor: Int = Color.WHITE, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val hPad = dpToPx(12)
            val vPad = dpToPx(10)
            setPadding(hPad, vPad, hPad, vPad)
            setOnClickListener { onClick() }
        }
    }

    private fun dismissQuickMenu() {
        quickMenuView?.let { menu ->
            try {
                windowManager.removeView(menu)
            } catch (_: Throwable) {
            }
            quickMenuView = null
        }
    }

    private fun removeOrbOverlay() {
        orbView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {
            }
            orbView = null
            orbParams = null
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val bounds = windowManager.currentWindowMetrics.bounds
        return Pair(bounds.width(), bounds.height())
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    private fun vibrate(ms: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {
        }
    }
}
