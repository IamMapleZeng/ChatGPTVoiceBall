package io.github.iammaplezeng.chatgptvoiceball.xposed

object HookProtocol {
    const val MODULE_PACKAGE = "io.github.iammaplezeng.chatgptvoiceball"
    const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    const val CHATGPT_VOICE_URI = "https://chatgpt.com/voice"

    // ChatGPT official ongoing voice notification channels (verified on v1.2026.314)
    val CHATGPT_VOICE_CHANNELS = setOf(
        "voice_mode_ongoing",
        "codex_realtime_voice"
    )

    // App -> system_server / systemui / chatgpt
    const val ACTION_PING = "io.github.iammaplezeng.chatgptvoiceball.xposed.ACTION_PING"
    const val ACTION_LAUNCH_VOICE = "io.github.iammaplezeng.chatgptvoiceball.xposed.ACTION_LAUNCH_VOICE"
    const val ACTION_SYNC_CONFIG = "io.github.iammaplezeng.chatgptvoiceball.xposed.ACTION_SYNC_CONFIG"
    const val ACTION_TRIGGER_MUTE = "io.github.iammaplezeng.chatgptvoiceball.xposed.ACTION_TRIGGER_MUTE"
    const val ACTION_TRIGGER_HANGUP = "io.github.iammaplezeng.chatgptvoiceball.xposed.ACTION_TRIGGER_HANGUP"
    const val ACTION_SET_SPEAKER_MUTED = "io.github.iammaplezeng.chatgptvoiceball.xposed.ACTION_SET_SPEAKER_MUTED"

    // system_server / chatgpt -> App
    const val ACTION_PONG = "io.github.iammaplezeng.chatgptvoiceball.ACTION_PONG"
    const val ACTION_LAUNCH_RESULT = "io.github.iammaplezeng.chatgptvoiceball.ACTION_LAUNCH_RESULT"
    const val ACTION_VOICE_STATE_CHANGED = "io.github.iammaplezeng.chatgptvoiceball.ACTION_VOICE_STATE_CHANGED"
    const val ACTION_LIVE_CAPTION = "io.github.iammaplezeng.chatgptvoiceball.ACTION_LIVE_CAPTION"

    // Direct Service component started by system_server (bypasses HyperOS freeze & bg broadcast blocks)
    const val SERVICE_CLASS = "io.github.iammaplezeng.chatgptvoiceball.service.VoiceBallService"
    const val ACTION_SYSTEM_SHOW_ORB = "io.github.iammaplezeng.chatgptvoiceball.service.SYSTEM_SHOW_ORB"
    const val ACTION_SYSTEM_HIDE_ORB = "io.github.iammaplezeng.chatgptvoiceball.service.SYSTEM_HIDE_ORB"

    // Extras
    const val EXTRA_CALLER_TOKEN = "extra_caller_token"
    const val EXTRA_USE_FREEFORM = "extra_use_freeform"
    const val EXTRA_PREFER_MIUI_UTILS = "extra_prefer_miui_utils"
    const val EXTRA_WINDOW_WIDTH_RATIO = "extra_window_width_ratio"
    const val EXTRA_WINDOW_HEIGHT_RATIO = "extra_window_height_ratio"
    const val EXTRA_REQUEST_ID = "extra_request_id"
    const val EXTRA_SUPPRESS_CHATGPT_ISLAND = "extra_suppress_chatgpt_island"
    const val EXTRA_AUTO_SHOW_ON_VOICE = "extra_auto_show_on_voice"

    // Response / event extras
    const val EXTRA_SUCCESS = "extra_success"
    const val EXTRA_ENGINE = "extra_engine"
    const val EXTRA_MESSAGE = "extra_message"
    const val EXTRA_HAS_MIUI_UTILS = "extra_has_miui_utils"
    const val EXTRA_SDK_INT = "extra_sdk_int"
    const val EXTRA_VOICE_ACTIVE = "extra_voice_active"
    const val EXTRA_VOICE_CHANNEL = "extra_voice_channel"
    const val EXTRA_MUTE_ACTION = "extra_mute_action"
    const val EXTRA_END_ACTION = "extra_end_action"
    const val EXTRA_IS_MUTED = "extra_is_muted"
    const val EXTRA_SPEAKER_MUTED = "extra_speaker_muted"
    const val EXTRA_RESUME_EXISTING = "extra_resume_existing"
    const val EXTRA_CAPTION_TEXT = "extra_caption_text"

    // AOSP WindowConfiguration.WINDOWING_MODE_FREEFORM
    const val WINDOWING_MODE_FREEFORM = 5
}
