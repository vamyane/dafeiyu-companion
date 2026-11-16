package com.dafeiyu.companion.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import coil.compose.AsyncImage
import com.dafeiyu.companion.app.UiMessage
import com.dafeiyu.companion.app.UiState
import java.io.File
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit,
    onCheckIn: () -> Unit,
    onFeed: () -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onPlayVoice: (UiMessage) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            onDismissError()
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            onDismissNotice()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ChatInput(
                sending = state.sending,
                recording = state.recording,
                recordingSeconds = state.recordingSeconds,
                attachedImagePath = state.attachedImagePath,
                onSend = onSend,
                onPickImage = onPickImage,
                onClearImage = onClearImage,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onCancelRecording = onCancelRecording
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            ChatHeader(
                state = state,
                onOpenSettings = onOpenSettings,
                onCheckIn = onCheckIn,
                onFeed = onFeed
            )

            if (state.experimentalEnabled) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFE2B8))
                        .clickable(onClick = onOpenWeb)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "实验模式 · 可能封号",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF7A4B00),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "打开网页版 ›",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF7A4B00)
                    )
                }
            }

            if (state.messages.isEmpty() && !state.sending) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            "说点什么吧。",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "它会记得你们聊过的事，也会在合适的时候自己开口。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                MessageList(state, onPlayVoice)
            }
        }
    }
}

@Composable
private fun MessageList(
    state: UiState,
    onPlayVoice: (UiMessage) -> Unit
) {
    val listState = rememberLazyListState()
    var scrolledOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.messages.size, state.sending) {
        val lastIndex = state.messages.lastIndex + if (state.sending) 1 else 0
        if (lastIndex < 0) return@LaunchedEffect
        if (!scrolledOnce) {
            listState.scrollToItem(lastIndex)
            scrolledOnce = true
            return@LaunchedEffect
        }
        // 只有本来就接近底部、或用户刚发完消息时才跟随；翻旧记录时不要把他拽回去。
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible >= state.messages.lastIndex - 1
        if (nearBottom || state.sending) listState.animateScrollToItem(lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(state.messages, key = { _, message -> message.key }) { index, message ->
            val previous = state.messages.getOrNull(index - 1)
            val showTime = previous == null ||
                Duration.between(previous.at, message.at).toMinutes() >= 5
            Column(Modifier.fillMaxWidth()) {
                if (showTime) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            message.at.atZone(ZoneId.systemDefault()).format(TIME_FORMAT),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                MessageBubble(
                    message = message,
                    companionName = state.cardName,
                    companionAvatar = state.avatarModel,
                    playing = state.playingKey == message.key,
                    onPlayVoice = onPlayVoice
                )
            }
        }
        if (state.sending) {
            item(key = "typing") { TypingBubble(state.cardName, state.avatarModel) }
        }
    }
}

@Composable
private fun MessageBubble(
    message: UiMessage,
    companionName: String,
    companionAvatar: Any?,
    playing: Boolean,
    onPlayVoice: (UiMessage) -> Unit
) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser) {
            Avatar(companionName, 30.dp, companionAvatar)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Surface(
                shape = if (isUser) {
                    RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                } else {
                    RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
                },
                color = if (isUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = if (isUser) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.imagePath != null) {
                        AsyncImage(
                            model = File(message.imagePath),
                            contentDescription = "聊天图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .widthIn(max = 260.dp)
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        if (message.text.isNotBlank() &&
                            message.text != "（发了一张图片）"
                        ) {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    if (isUser && message.imagePath != null) {
                        Spacer(Modifier.height(2.dp))
                    }
                    if (message.stickerModel != null) {
                        AsyncImage(
                            model = message.stickerModel,
                            contentDescription = "表情",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(128.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                        if (message.isVoice) {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    if (message.isVoice) {
                        VoiceBar(
                            seconds = message.voiceSeconds,
                            isUser = isUser,
                            playing = playing,
                            onClick = { onPlayVoice(message) }
                        )
                    } else if (message.text.isNotBlank() &&
                        message.text != "（发了一张图片）" &&
                        message.text != "（发了个表情）"
                    ) {
                        SelectionContainer {
                            Text(
                                message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isUser) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceBar(
    seconds: Int,
    isUser: Boolean,
    playing: Boolean,
    onClick: () -> Unit
) {
    val duration = seconds.coerceAtLeast(1)
    val width = (86 + duration.coerceAtMost(50) * 2).dp
    val color = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val transition = rememberInfiniteTransition(label = "voiceWave")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voicePulse"
    )
    Row(
        modifier = Modifier
            .width(width)
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (playing) CompanionIcons.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "暂停语音" else "播放语音",
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            WAVE_HEIGHTS.forEachIndexed { index, height ->
                Box(
                    Modifier
                        .width(3.dp)
                        .height(height.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            color.copy(alpha = if (playing) {
                                (0.35f + pulse * 0.65f) * (if (index % 2 == 0) 1f else 0.8f)
                            } else {
                                0.75f
                            })
                        )
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "$duration''",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private val WAVE_HEIGHTS = listOf(8, 14, 20, 12, 24, 16, 10, 18)

@Composable
private fun TypingBubble(companionName: String, companionAvatar: Any?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(companionName, 30.dp, companionAvatar)
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(
                "正在输入…",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatInput(
    sending: Boolean,
    recording: Boolean,
    recordingSeconds: Int,
    attachedImagePath: String?,
    onSend: (String) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (sending || (text.isBlank() && attachedImagePath == null)) return
        val value = text.trim()
        text = ""
        onSend(value)
    }
    Surface(
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 8.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (recording) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "录音中 ${formatClock(recordingSeconds)} / 1:00",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancelRecording) { Text("取消") }
                    Spacer(Modifier.width(4.dp))
                    FilledIconButton(
                        onClick = onStopRecording,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "发送语音")
                    }
                }
                return@Column
            }
            if (attachedImagePath != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = File(attachedImagePath),
                        contentDescription = "待发送图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "已添加图片，可以继续说点什么",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClearImage) { Text("移除") }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = onStartRecording,
                    enabled = !sending
                ) {
                    Icon(
                        imageVector = CompanionIcons.Mic,
                        contentDescription = "语音",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = onPickImage) {
                    Icon(
                        imageVector = CompanionIcons.Picture,
                        contentDescription = "图片",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("说点什么…") },
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() })
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { submit() },
                    enabled = (text.isNotBlank() || attachedImagePath != null) && !sending,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
fun Avatar(name: String, size: androidx.compose.ui.unit.Dp, model: Any? = null) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.take(1).ifBlank { "鱼" },
                style = if (size > 32.dp) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.labelMedium
                },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold
            )
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            }
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatClock(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}

