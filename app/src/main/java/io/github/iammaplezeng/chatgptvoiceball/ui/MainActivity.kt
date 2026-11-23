package io.github.iammaplezeng.chatgptvoiceball.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iammaplezeng.chatgptvoiceball.launcher.ChatGptVoiceLauncher
import io.github.iammaplezeng.chatgptvoiceball.model.AppStateStore
import io.github.iammaplezeng.chatgptvoiceball.model.LogRepository
import io.github.iammaplezeng.chatgptvoiceball.service.VoiceBallService
import io.github.iammaplezeng.chatgptvoiceball.util.HyperOsPermissionHelper
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var permissionRefreshTick by mutableStateOf(0)

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permissionRefreshTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppStateStore.init(this)

        setContent {
            ChatGptVoiceBallTheme {
                MainScreen(
                    refreshTick = permissionRefreshTick,
                    onRequestNotifPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionRefreshTick++
        ChatGptVoiceLauncher.pingSystemHook(this)
    }
}

@Composable
private fun ChatGptVoiceBallTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) {
            darkColorScheme(
                primary = Color(0xFF38BDF8),
                secondary = Color(0xFF818CF8)
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF0284C7),
                secondary = Color(0xFF4F46E5)
            )
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    refreshTick: Int,
    onRequestNotifPermission: () -> Unit
) {
    val context = LocalContext.current
    val settings by AppStateStore.settings.collectAsState()
    val serviceRunning by AppStateStore.serviceRunning.collectAsState()
    val voiceSessionActive by AppStateStore.voiceSessionActive.collectAsState()
    val hookStatus by AppStateStore.hookStatus.collectAsState()
    val logs by LogRepository.logs.collectAsState()

    val hasOverlay = remember(refreshTick) { HyperOsPermissionHelper.hasOverlayPermission(context) }
    val hasNotif = remember(refreshTick) { HyperOsPermissionHelper.hasNotificationPermission(context) }
    val ignoringBattery = remember(refreshTick) { HyperOsPermissionHelper.isIgnoringBatteryOptimizations(context) }
    val chatGptVersion = remember(refreshTick) { ChatGptVoiceLauncher.getChatGptVersion(context) }

    LaunchedEffect(Unit) {
        ChatGptVoiceLauncher.pingSystemHook(context)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("ChatGPTVoiceBall", fontWeight = FontWeight.Bold)
                        Text(
                            "澎湃 OS 4 · LSPosed 系统小窗版",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { ChatGptVoiceLauncher.pingSystemHook(context) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新检测 LSPosed 状态")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Hero Orb Control Card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            Color(0xFF7CF7FF),
                                            Color(0xFF2563EB),
                                            Color(0xFF312E81)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    voiceSessionActive -> "ChatGPT 语音进行中 · 悬浮球已弹出"
                                    serviceRunning -> "悬浮球预览中（平时不常驻）"
                                    settings.autoShowOnVoice -> "已就绪 · 聊语音时自动弹出"
                                    else -> "语音悬浮球已关闭"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (chatGptVersion != null) {
                                    "官方 ChatGPT v$chatGptVersion · 平时不常驻屏幕"
                                } else {
                                    "未检测到 ChatGPT 官方 App"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.autoShowOnVoice,
                            onCheckedChange = { wantEnable ->
                                if (wantEnable && !HyperOsPermissionHelper.hasOverlayPermission(context)) {
                                    Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                                    HyperOsPermissionHelper.openOverlaySettings(context)
                                } else {
                                    AppStateStore.updateSettings { it.copy(autoShowOnVoice = wantEnable) }
                                    ChatGptVoiceLauncher.syncConfigToHook(context)
                                    if (!wantEnable && serviceRunning) {
                                        VoiceBallService.stop(context)
                                    }
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { ChatGptVoiceLauncher.launchVoiceChat(context, true) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("打开小窗语音")
                        }
                        FilledTonalButton(
                            onClick = {
                                if (serviceRunning) {
                                    VoiceBallService.stop(context)
                                } else if (!HyperOsPermissionHelper.hasOverlayPermission(context)) {
                                    HyperOsPermissionHelper.openOverlaySettings(context)
                                } else {
                                    VoiceBallService.start(context)
                                }
                            },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(if (serviceRunning) "结束预览" else "预览悬浮球")
                        }
                    }
                }
            }

            // 2. LSPosed System Framework Status Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (hookStatus.active) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = if (hookStatus.active) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = if (hookStatus.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hookStatus.active) "LSPosed 系统框架模块已激活" else "LSPosed 模块未激活",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = hookStatus.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!hookStatus.active) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "启用步骤：打开 LSPosed → 模块 → 启用「ChatGPTVoiceBall」→ 勾选「系统框架 (android)」「系统界面 (com.android.systemui)」「ChatGPT (com.openai.chatgpt)」→ 重启手机。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 3. Appearance & Behavior Settings
            SectionCard(title = "悬浮球与启动设置", icon = Icons.Rounded.Tune) {
                ToggleRow(
                    title = "显示悬浮球时隐藏 ChatGPT 超级岛",
                    subtitle = "仅屏蔽 ChatGPT 语音的超级岛显示，绝不关闭系统超级岛，不影响其他应用",
                    checked = settings.suppressChatGptIsland,
                    onCheckedChange = { v ->
                        AppStateStore.updateSettings { it.copy(suppressChatGptIsland = v) }
                        ChatGptVoiceLauncher.syncConfigToHook(context)
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ToggleRow(
                    title = "点击悬浮球以澎湃 OS 4 小窗打开语音",
                    subtitle = "通过 LSPosed 在当前 App 上方以自由小窗拉起语音",
                    checked = settings.useHyperOsFreeform,
                    onCheckedChange = { v -> AppStateStore.updateSettings { it.copy(useHyperOsFreeform = v) } }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ToggleRow(
                    title = "优先小米原生小窗参数",
                    subtitle = "优先调用 MiuiMultiWindowUtils，失败自动回退 Freeform 模式 5",
                    checked = settings.preferMiuiNativeUtils,
                    onCheckedChange = { v -> AppStateStore.updateSettings { it.copy(preferMiuiNativeUtils = v) } }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ToggleRow(
                    title = "拖动松手自动贴边",
                    subtitle = "平滑吸附至屏幕左/右侧边缘",
                    checked = settings.autoSnapEdge,
                    onCheckedChange = { v -> AppStateStore.updateSettings { it.copy(autoSnapEdge = v) } }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ToggleRow(
                    title = "锁屏时自动隐藏悬浮球",
                    subtitle = "解锁后若仍在语音中则自动恢复显示",
                    checked = settings.hideOnLockScreen,
                    onCheckedChange = { v -> AppStateStore.updateSettings { it.copy(hideOnLockScreen = v) } }
                )

                Spacer(Modifier.height(12.dp))

                SliderRow(
                    label = "悬浮球大小",
                    valueLabel = "${settings.orbSizeDp} dp",
                    value = settings.orbSizeDp.toFloat(),
                    range = 44f..80f,
                    onValueChange = { v -> AppStateStore.updateSettings { it.copy(orbSizeDp = v.roundToInt()) } }
                )
                SliderRow(
                    label = "活跃透明度",
                    valueLabel = "${(settings.activeAlpha * 100).roundToInt()}%",
                    value = settings.activeAlpha,
                    range = 0.4f..1.0f,
                    onValueChange = { v -> AppStateStore.updateSettings { it.copy(activeAlpha = v) } }
                )
                SliderRow(
                    label = "贴边休眠透明度",
                    valueLabel = "${(settings.idleAlpha * 100).roundToInt()}%",
                    value = settings.idleAlpha,
                    range = 0.15f..0.9f,
                    onValueChange = { v -> AppStateStore.updateSettings { it.copy(idleAlpha = v) } }
                )
            }

            // 4. HyperOS 4 Permissions & Keep-Alive Guide
            SectionCard(title = "澎湃 OS 4 权限与防清理引导", icon = Icons.Rounded.Security) {
                PermissionRow(
                    icon = Icons.Rounded.Layers,
                    title = "全局悬浮窗权限",
                    granted = hasOverlay,
                    actionText = if (hasOverlay) "已授权" else "去授权",
                    onClick = { HyperOsPermissionHelper.openOverlaySettings(context) }
                )
                PermissionRow(
                    icon = Icons.Rounded.NotificationsActive,
                    title = "常驻通知权限（前台保活）",
                    granted = hasNotif,
                    actionText = if (hasNotif) "已授权" else "去授权",
                    onClick = onRequestNotifPermission
                )
                PermissionRow(
                    icon = Icons.Rounded.BatteryChargingFull,
                    title = "忽略系统电池优化",
                    granted = ignoringBattery,
                    actionText = if (ignoringBattery) "已放行" else "去设置",
                    onClick = { HyperOsPermissionHelper.openBatteryOptimizationSettings(context) }
                )
                PermissionRow(
                    icon = Icons.Rounded.PowerSettingsNew,
                    title = "澎湃 OS 自启动管理",
                    granted = null,
                    actionText = "去开启",
                    onClick = { HyperOsPermissionHelper.openMiuiAutoStartSettings(context) }
                )
                PermissionRow(
                    icon = Icons.Rounded.BatteryChargingFull,
                    title = "澎湃 OS 省电策略（设为无限制）",
                    granted = null,
                    actionText = "去设置",
                    onClick = { HyperOsPermissionHelper.openMiuiBatterySaverSettings(context) }
                )
            }

            // 5. Diagnostic Logs Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "运行与诊断日志",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm.setPrimaryClip(ClipData.newPlainText("GPTVoiceBallLogs", LogRepository.exportText()))
                                Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "复制日志", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { LogRepository.clear() }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "清空日志", modifier = Modifier.size(18.dp))
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
                            .padding(10.dp)
                    ) {
                        if (logs.isEmpty()) {
                            Text(
                                "暂无日志",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                logs.takeLast(14).reversed().forEach { entry ->
                                    Text(
                                        text = "[${entry.timestamp}] [${entry.level}] ${entry.message}",
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = when (entry.level) {
                                            "ERR" -> MaterialTheme.colorScheme.error
                                            "WARN" -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    granted: Boolean?,
    actionText: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when (granted) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (granted == true) {
            OutlinedButton(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Text(actionText, fontSize = 12.sp)
            }
        } else {
            FilledTonalButton(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(actionText, fontSize = 12.sp)
            }
        }
    }
}
