# ChatGPTVoiceBall

为澎湃 OS 4（HyperOS 4 / Android 14–17 / SukiSU Ultra / LSPosed）打造的 **ChatGPT 官方语音悬浮球**（Gemini Live 风格），采用 LSPosed 模块方案：

- **包名**：`io.github.iammaplezeng.chatgptvoiceball`
- **应用名称**：`ChatGPTVoiceBall`

## 核心功能

1. **按需自动弹出，绝不常驻屏幕**
   - 平时未进行 ChatGPT 语音聊天时，桌面与其他应用界面完全无悬浮球、后台零常驻。
   - 检测到 ChatGPT 官方语音通道（`voice_mode_ongoing` / `codex_realtime_voice`）启动后，由 `system_server` 直接拉起悬浮球；语音结束自动消失。
2. **仅隐藏 ChatGPT 语音的澎湃 OS 4 超级岛**
   - 在 `android`（`NotificationManagerService`）与 `com.android.systemui` 双层仅过滤 `com.openai.chatgpt` 的语音通知上超级岛，**不关闭系统全局超级岛**，不影响任何其他应用。
3. **Gemini Live 风格悬浮球与底部展开控制栏**
   - **收起态**：纯白顶部到天蓝流动光晕的柔光球，自由拖动、自动贴边、休眠降帧低耗电。
   - **单击展开**：
     - 底部控制栏：**澎湃 OS 4 小窗切回** / **全屏切回**（无缝切回当前语音不断线） + **中间发光胶囊光球** + **ChatGPT 风格麦克风静音按钮**（实时双向同步） + **挂断结束语音按钮**（直接触发官方 `Hang Up`）。
     - 底部上方：**深色圆角实时字幕条**（Hook `com.openai.chatgpt` WebRTC `DataChannel`）。
     - 屏幕右上角：**字幕开关按钮** + **扬声器静音按钮**（Hook `com.openai.chatgpt` `AudioTrack` 实现纯零静音）。

## LSPosed 启用方式

1. 安装 APK（`./gradlew assembleDebug`）。
2. 打开 **LSPosed** → **模块** → 启用 **`ChatGPTVoiceBall`**，勾选推荐作用域：
   - **系统框架（`android`）**
   - **系统界面（`com.android.systemui`）**
   - **ChatGPT（`com.openai.chatgpt`）**
3. 重启手机，授予悬浮窗权限即可使用。
