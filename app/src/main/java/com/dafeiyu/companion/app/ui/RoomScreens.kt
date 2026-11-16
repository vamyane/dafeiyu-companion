package com.dafeiyu.companion.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import coil.compose.AsyncImage
import com.dafeiyu.companion.app.CardOptionUi
import com.dafeiyu.companion.app.RoomChatUiState
import com.dafeiyu.companion.app.RoomSummaryUi
import com.dafeiyu.companion.app.RoomUiMessage
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ROOM_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListScreen(
    rooms: List<RoomSummaryUi>,
    cardOptions: List<CardOptionUi>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCreate: (String, List<String>) -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RoomSummaryUi?>(null) }

    LaunchedEffect(Unit) { onRefresh() }

    androidx.compose.material3.Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("多 AI 房间") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ 返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "把几张角色卡拉进同一个群，让它们自己聊起来。",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            if (rooms.isEmpty()) {
                EmptyRooms()
            } else {
                rooms.forEach { room ->
                    RoomCard(
                        room = room,
                        onOpen = { onOpen(room.id) },
                        onDelete = { pendingDelete = room }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = { showCreate = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("＋ 新建房间") }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (showCreate) {
        CreateRoomDialog(
            cardOptions = cardOptions,
            onDismiss = { showCreate = false },
            onCreate = { name, ids ->
                showCreate = false
                onCreate(name, ids)
            }
        )
    }

    pendingDelete?.let { room ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${room.name}」？") },
            text = { Text("房间里的聊天记录会一起删掉，角色卡本身不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(room.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EmptyRooms() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("还没有房间", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "选两张角色卡，建一个只属于你们的小群。",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RoomCard(
    room: RoomSummaryUi,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.name,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${room.memberCount} 个角色",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "删除",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onDelete)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                room.members.take(5).forEach { member ->
                    MemberAvatar(member.name, member.avatar, 26.dp)
                }
                Spacer(Modifier.weight(1f))
                room.lastAt?.let {
                    Text(
                        formatRoomTime(it),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                room.lastText ?: "还没开始聊天",
                maxLines = 2,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreateRoomDialog(
    cardOptions: List<CardOptionUi>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("小鱼缸") }
    val selected = remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建群聊") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("房间名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "选择角色（至少 2 个）",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(4.dp))
                cardOptions.forEach { option ->
                    val checked = option.id in selected.value
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected.value = if (checked) {
                                    selected.value - option.id
                                } else {
                                    selected.value + option.id
                                }
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { isChecked ->
                                selected.value = if (isChecked) {
                                    selected.value + option.id
                                } else {
                                    selected.value - option.id
                                }
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.name,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                            )
                            if (option.description.isNotBlank()) {
                                Text(
                                    option.description,
                                    maxLines = 1,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (cardOptions.isEmpty()) {
                    Text(
                        "没有可用角色卡，先去设置里导入一张。",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.value.size >= 2,
                onClick = { onCreate(name.ifBlank { "小鱼缸" }, selected.value.toList()) }
            ) { Text("建群") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomChatScreen(
    state: RoomChatUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onClearError: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val totalRows = state.messages.size + if (state.typingName != null) 1 else 0

    LaunchedEffect(totalRows) {
        if (totalRows > 0) listState.animateScrollToItem(totalRows - 1)
    }

    androidx.compose.material3.Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.name.ifBlank { "群聊" })
                        Text(
                            state.members.joinToString("、") { it.name },
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ 返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            if (state.error != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFE2B8))
                        .clickable(onClick = onClearError)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        state.error,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = Color(0xFF7A4B00),
                        modifier = Modifier.weight(1f)
                    )
                    Text("知道了", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Color(0xFF7A4B00))
                }
            }

            if (state.messages.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("群里还没人说话", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "发一句试试，@ 某个角色它就会先开口。",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(state.messages, key = { _, message -> message.key }) { index, message ->
                        val previous = state.messages.getOrNull(index - 1)
                        val showTime = previous == null ||
                            Duration.between(previous.at, message.at).toMinutes() >= 5
                        Column {
                            if (showTime) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(
                                        message.at.atZone(ZoneId.systemDefault()).format(ROOM_TIME),
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            RoomBubble(message)
                        }
                    }
                    if (state.typingName != null) {
                        item(key = "typing") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val member = state.members.firstOrNull { it.name == state.typingName }
                                MemberAvatar(state.typingName.orEmpty(), member?.avatar, 28.dp)
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Text(
                                        "${state.typingName} 正在输入…",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (state.sending && state.typingName == null) {
                        item(key = "waiting") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "大家在打字…",
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.members.forEach { member ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable {
                            val mention = "@${member.name} "
                            if (!text.contains(mention.trim())) {
                                text = if (text.isBlank()) mention else "$text$mention"
                            }
                        }
                    ) {
                        Text(
                            "@${member.name}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("在群里说点什么…") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank() && !state.sending) {
                            onSend(text.trim())
                            text = ""
                        }
                    })
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        if (text.isNotBlank() && !state.sending) {
                            onSend(text.trim())
                            text = ""
                        }
                    },
                    enabled = text.isNotBlank() && !state.sending
                ) {
                    Text("↑", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun RoomBubble(message: RoomUiMessage) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser) {
            MemberAvatar(message.speakerName.orEmpty(), message.avatar, 28.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            if (!isUser) {
                Text(
                    message.speakerName.orEmpty(),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
            }
            Surface(
                shape = if (isUser) {
                    RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                } else {
                    RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
                },
                color = if (isUser) {
                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                } else {
                    androidx.compose.material3.MaterialTheme.colorScheme.surface
                },
                border = if (isUser) null else BorderStroke(
                    1.dp,
                    androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    message.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    color = if (isUser) {
                        androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
        if (isUser) {
            Spacer(Modifier.width(8.dp))
            MemberAvatar("我", null, 28.dp)
        }
    }
}

@Composable
private fun MemberAvatar(name: String, avatar: Any?, size: androidx.compose.ui.unit.Dp) {
    if (avatar != null) {
        AsyncImage(
            model = avatar,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
        )
    } else {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                name.takeLast(1),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

private fun formatRoomTime(at: Instant): String {
    val now = Instant.now()
    val minutes = Duration.between(at, now).toMinutes()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> at.atZone(ZoneId.systemDefault()).format(ROOM_TIME)
    }
}
