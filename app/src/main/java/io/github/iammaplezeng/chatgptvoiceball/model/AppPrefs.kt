package io.github.iammaplezeng.chatgptvoiceball.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BallSettings(
    val autoShowOnVoice: Boolean = true,
    val suppressChatGptIsland: Boolean = true,
    val orbSizeDp: Int = 58,
    val activeAlpha: Float = 0.94f,
    val idleAlpha: Float = 0.55f,
    val autoSnapEdge: Boolean = true,
    val useHyperOsFreeform: Boolean = true,
    val preferMiuiNativeUtils: Boolean = true,
    val hideOnLockScreen: Boolean = false,
    val posXRatio: Float = 1.0f, // 1.0 = right edge
    val posYRatio: Float = 0.78f // default bottom-right area
)

data class HookStatus(
    val active: Boolean = false,
    val hasMiuiUtils: Boolean = false,
    val detail: String = "未检测到 LSPosed 模块响应（请在 LSPosed 中启用并勾选系统框架与系统界面后重启）"
)

object AppStateStore {
    private const val PREF_NAME = "chatgpt_voice_ball_prefs"
    private lateinit var prefs: SharedPreferences

    private val _settings = MutableStateFlow(BallSettings())
    val settings: StateFlow<BallSettings> = _settings.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _voiceSessionActive = MutableStateFlow(false)
    val voiceSessionActive: StateFlow<Boolean> = _voiceSessionActive.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _captionText = MutableStateFlow("")
    val captionText: StateFlow<String> = _captionText.asStateFlow()

    private val _orbVisible = MutableStateFlow(false)
    val orbVisible: StateFlow<Boolean> = _orbVisible.asStateFlow()

    private val _hookStatus = MutableStateFlow(HookStatus())
    val hookStatus: StateFlow<HookStatus> = _hookStatus.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _settings.value = BallSettings(
            autoShowOnVoice = prefs.getBoolean("auto_show_on_voice", true),
            suppressChatGptIsland = prefs.getBoolean("suppress_chatgpt_island", true),
            orbSizeDp = prefs.getInt("orb_size_dp", 58),
            activeAlpha = prefs.getFloat("active_alpha", 0.94f),
            idleAlpha = prefs.getFloat("idle_alpha", 0.55f),
            autoSnapEdge = prefs.getBoolean("auto_snap_edge", true),
            useHyperOsFreeform = prefs.getBoolean("use_freeform", true),
            preferMiuiNativeUtils = prefs.getBoolean("prefer_miui_utils", true),
            hideOnLockScreen = prefs.getBoolean("hide_lockscreen", false),
            posXRatio = prefs.getFloat("pos_x_ratio", 1.0f),
            posYRatio = prefs.getFloat("pos_y_ratio", 0.78f)
        )
    }

    fun updateSettings(transform: (BallSettings) -> BallSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        if (::prefs.isInitialized) {
            prefs.edit()
                .putBoolean("auto_show_on_voice", next.autoShowOnVoice)
                .putBoolean("suppress_chatgpt_island", next.suppressChatGptIsland)
                .putInt("orb_size_dp", next.orbSizeDp)
                .putFloat("active_alpha", next.activeAlpha)
                .putFloat("idle_alpha", next.idleAlpha)
                .putBoolean("auto_snap_edge", next.autoSnapEdge)
                .putBoolean("use_freeform", next.useHyperOsFreeform)
                .putBoolean("prefer_miui_utils", next.preferMiuiNativeUtils)
                .putBoolean("hide_lockscreen", next.hideOnLockScreen)
                .putFloat("pos_x_ratio", next.posXRatio)
                .putFloat("pos_y_ratio", next.posYRatio)
                .apply()
        }
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun setVoiceSessionActive(active: Boolean) {
        _voiceSessionActive.value = active
        if (!active) {
            _isMicMuted.value = false
            _captionText.value = ""
        }
    }

    fun setMicMuted(muted: Boolean) {
        _isMicMuted.value = muted
    }

    fun setCaptionText(text: String) {
        _captionText.value = text
    }

    fun setOrbVisible(visible: Boolean) {
        _orbVisible.value = visible
    }

    fun updateHookStatus(status: HookStatus) {
        _hookStatus.value = status
    }
}
