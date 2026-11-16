package com.dafeiyu.companion.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dafeiyu.companion.app.UiState
import com.dafeiyu.companion.app.runtime.VoiceCatalog
import com.dafeiyu.companion.model.Reminder
import com.dafeiyu.companion.model.ReminderRepeat
import com.dafeiyu.companion.model.ReminderStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onModelChange: (String) -> Unit,
    onClinginessChange: (Double) -> Unit,
    onVoiceChange: (String) -> Unit,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onStickersEnabledChange: (Boolean) -> Unit,
    onImportStickers: () -> Unit,
    onRename: (String) -> Unit,
    onUsePreset: (String) -> Unit,
    onTestVoice: () -> Unit,
    onImportCard: () -> Unit,
    onCreateCard: (String, String, String) -> Unit,
    onSelectAvatar: (String) -> Unit,
    onPickCustomAvatar: () -> Unit,
    onResetAvatar: () -> Unit,
    onResetCard: () -> Unit,
    onUpdateApiKey: (String, (String?) -> Unit) -> Unit,
    onCancelReminder: (String) -> Unit,
    onExperimentalChange: (Boolean) -> Unit,
    onOpenWeb: () -> Unit,
    onOpenBrain: () -> Unit,
    onOpenRooms: () -> Unit,
    onClearMemory: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    debugMode: Boolean = false,
    onTestNotification: () -> Unit = {},
    onTestReminder: () -> Unit = {}
) {
    BackHandler(onBack = onBack)
    var showKeyDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showTextCardDialog by remember { mutableStateOf(false) }
    var showAvatarDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var riskStep by remember { mutableIntStateOf(0) }
    var riskAck by remember { mutableStateOf(false) }
    var sliderValue by remember(state.clinginess) { mutableFloatStateOf(state.clinginess) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                title = { Text("设置") }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionCard("连接") {
                InfoRow(
                    label = "API Key",
                    value = if (state.hasApiKey) "已配置" else "未配置",
                    action = if (state.hasApiKey) "更换" else "配置",
                    onAction = { showKeyDialog = true }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Text("模型", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.model == "deepseek-flash",
                        onClick = { onModelChange("deepseek-flash") },
                        label = { Text("flash") }
                    )
                    FilterChip(
                        selected = state.model == "deepseek-v4-pro",
                        onClick = { onModelChange("deepseek-v4-pro") },
                        label = { Text("v4-pro") }
                    )
                }
            }

            SectionCard("语音") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("语音回复", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = state.voiceEnabled,
                        onCheckedChange = onVoiceEnabledChange
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceCatalog.voices.forEach { voice ->
                        FilterChip(
                            selected = state.voiceId == voice.id,
                            onClick = { onVoiceChange(voice.id) },
                            label = { Text(voice.label) },
                            enabled = state.voiceEnabled
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SecondaryAction(
                        text = if (state.speaking) "合成中…" else "试听",
                        enabled = state.voiceEnabled && !state.speaking,
                        onClick = onTestVoice
                    )
                }
                Text(
                    "发语音会额外消耗 token",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard("表情包") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("表情回复", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = state.stickersEnabled,
                        onCheckedChange = onStickersEnabledChange
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryAction(
                        text = "收藏表情",
                        enabled = state.stickersEnabled,
                        onClick = onImportStickers
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "已有 ${state.stickerCount} 张",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "发表情会额外消耗 token",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard("角色") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(state.cardName, 48.dp, state.avatarModel)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        state.cardName.ifBlank { "大肥鱼" },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.weight(1f))
                    SecondaryAction(text = "改名", onClick = { showRenameDialog = true })
                    SecondaryAction(text = "换头像", onClick = { showAvatarDialog = true })
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("预设", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = state.presetId == "default",
                        onClick = { onUsePreset("default") },
                        label = { Text("大肥鱼") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = state.presetId == "xiao_jingyu",
                        onClick = { onUsePreset("xiao_jingyu") },
                        label = { Text("小鲸鱼") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryAction(text = "导入角色卡", onClick = onImportCard)
                    SecondaryAction(text = "用文字创建", onClick = { showTextCardDialog = true })
                    SecondaryAction(text = "恢复默认", onClick = { showResetConfirm = true })
                }
            }

            SectionCard("依恋度") {
                Text("当前：${toneOf(sliderValue)}", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onClinginessChange(sliderValue.toDouble()) },
                    valueRange = 0f..1f
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("克制", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text("自然", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text("黏人", style = MaterialTheme.typography.labelSmall)
                }
            }

            SectionCard("通知与后台") {
                InfoRow(
                    label = "通知权限",
                    value = if (state.notificationsEnabled) "已开启" else "未开启",
                    action = if (state.notificationsEnabled) null else "去开启",
                    onAction = onRequestNotificationPermission
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(
                    label = "精确提醒",
                    value = if (state.exactAlarms) "已允许" else "未允许",
                    action = if (state.exactAlarms) null else "去允许",
                    onAction = onOpenExactAlarmSettings
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(
                    label = "后台运行",
                    value = "建议加入电池白名单",
                    action = "去设置",
                    onAction = onOpenBatterySettings
                )
            }

            SectionCard("提醒") {
                val pending = state.reminders.filter { it.status == ReminderStatus.PENDING }
                if (pending.isEmpty()) {
                    Text(
                        "还没有提醒。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    pending.forEachIndexed { index, reminder ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        ReminderRow(reminder = reminder, onCancel = onCancelReminder)
                    }
                }
            }

            SectionCard("实验室") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("网页版实验模式", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = state.experimentalEnabled,
                        onCheckedChange = { checked ->
                            if (checked) {
                                riskAck = false
                                riskStep = 1
                            } else {
                                onExperimentalChange(false)
                            }
                        }
                    )
                }
                if (state.experimentalEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "实验模式 · 可能封号",
                        modifier = Modifier
                            .background(
                                Color(0xFFFFE2B8),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF7A4B00),
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = onOpenWeb) { Text("打开网页版") }
                }
                if (debugMode) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onTestNotification) { Text("测试通知") }
                        TextButton(onClick = onTestReminder) { Text("2 分钟后测试提醒") }
                    }
                }
            }

            SectionCard("多 AI 房间") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("本地多 AI 群聊", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "已上线",
                        modifier = Modifier
                            .background(
                                Color(0xFFDDF2D8),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF2F5D25),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "把多张角色卡拉进同一个群，它们会按人设自己接话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                SecondaryAction(text = "进入房间", onClick = onOpenRooms)
            }

            SectionCard("记忆大脑") {
                SecondaryAction(text = "打开大脑", onClick = onOpenBrain)
            }

            SectionCard("记忆") {
                InfoRow(
                    label = "已记住",
                    value = "${state.memoryCount} 条",
                    action = "清空",
                    onAction = { showClearConfirm = true }
                )
            }

            SectionCard("关于") {
                Text("大肥鱼 0.1.0 · M4", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (showKeyDialog) {
        var key by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) showKeyDialog = false },
            title = { Text("更换 API Key") },
            text = {
                Column {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("DeepSeek API Key") },
                        placeholder = { Text("sk-…") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = error != null
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = key.isNotBlank() && !saving,
                    onClick = {
                        saving = true
                        error = null
                        onUpdateApiKey(key) { message ->
                            saving = false
                            if (message == null) showKeyDialog = false else error = message
                        }
                    }
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("验证并保存")
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { showKeyDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showTextCardDialog) {
        TextCardDialog(
            onDismiss = { showTextCardDialog = false },
            onCreate = onCreateCard
        )
    }

    if (showAvatarDialog) {
        AvatarPickerDialog(
            currentModel = state.avatarModel,
            builtinAvatars = state.builtinAvatars,
            selectedBuiltin = state.avatarBuiltin,
            onSelectBuiltin = onSelectAvatar,
            onPickCustom = {
                showAvatarDialog = false
                onPickCustomAvatar()
            },
            onReset = onResetAvatar,
            onDismiss = { showAvatarDialog = false }
        )
    }

    if (showRenameDialog) {
        var name by remember { mutableStateOf(state.cardName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("给它起个名字") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("名字") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onRename(name)
                        showRenameDialog = false
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复默认角色卡？") },
            text = { Text("当前角色会被大肥鱼替换，但已有聊天和记忆不会删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onResetCard()
                    }
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空当前角色的记忆？") },
            text = { Text("会删除聊天记录、事实与日记，无法恢复。提醒不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearMemory()
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    if (riskStep > 0) {
        AlertDialog(
            onDismissRequest = {
                riskStep = 0
                riskAck = false
            },
            title = {
                Text(if (riskStep == 1) "开启网页版实验模式？" else "再次确认")
            },
            text = {
                if (riskStep == 1) {
                    Column {
                        Text(RISK_TEXT, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = riskAck,
                                onCheckedChange = { riskAck = it }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("我已了解风险")
                        }
                    }
                } else {
                    Text("开启后聊天页会常驻「实验模式 · 可能封号」标识，且该模式随时可能失效。关闭后再次开启需要重新确认。")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = if (riskStep == 1) riskAck else true,
                    onClick = {
                        if (riskStep == 1) {
                            riskStep = 2
                        } else {
                            onExperimentalChange(true)
                            riskStep = 0
                            riskAck = false
                        }
                    }
                ) {
                    Text(if (riskStep == 1) "继续" else "确认开启")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (riskStep == 2) {
                            riskStep = 1
                        } else {
                            riskStep = 0
                            riskAck = false
                        }
                    }
                ) {
                    Text(if (riskStep == 2) "返回" else "取消")
                }
            }
        )
    }
}

@Composable
private fun ReminderRow(reminder: Reminder, onCancel: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(reminder.text, style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    append(reminder.dueAt.atZone(ZoneId.systemDefault()).format(REMINDER_TIME))
                    when (reminder.repeat) {
                        ReminderRepeat.DAILY -> append(" · 每天")
                        ReminderRepeat.WEEKLY -> append(" · 每周")
                        ReminderRepeat.NONE -> Unit
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { onCancel(reminder.id) }) { Text("取消") }
    }
}

@Composable
private fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Text(text)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (value.isNotBlank()) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun toneOf(value: Float): String = when {
    value < 0.3f -> "克制"
    value <= 0.7f -> "自然"
    else -> "黏人"
}

private const val RISK_TEXT =
    "网页版实验模式通过自动化方式读取 DeepSeek 网页对话，可能违反其服务条款，可能导致账号被限流或封禁。该模式不支持角色卡、长期记忆和主动消息，且随时可能失效。"

private val REMINDER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
