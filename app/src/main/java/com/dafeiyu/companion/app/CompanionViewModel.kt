package com.dafeiyu.companion.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dafeiyu.companion.app.data.AvatarRepository
import com.dafeiyu.companion.app.data.RoomMemberSeed
import com.dafeiyu.companion.app.runtime.AppRuntime
import com.dafeiyu.companion.app.runtime.BrainGraph
import com.dafeiyu.companion.app.runtime.BrainGraphBuilder
import com.dafeiyu.companion.app.runtime.BrainUiState
import com.dafeiyu.companion.app.runtime.LifeState
import com.dafeiyu.companion.app.runtime.Notifications
import com.dafeiyu.companion.app.runtime.ReminderAlarms
import com.dafeiyu.companion.app.runtime.RoomChatCoordinator
import com.dafeiyu.companion.app.runtime.VoiceCatalog
import com.dafeiyu.companion.app.runtime.VoicePlayer
import com.dafeiyu.companion.app.runtime.WavRecorder
import com.dafeiyu.companion.llm.ReplySplitter
import com.dafeiyu.companion.llm.VoiceMarkers
import com.dafeiyu.companion.model.Reminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class UiMessage(
    val key: String,
    val role: String,
    val text: String,
    val at: Instant,
    val imagePath: String? = null,
    val voicePath: String? = null,
    val voice: Boolean = false,
    val voiceSeconds: Int = 0,
    val sticker: String? = null,
    val stickerModel: Any? = null
) {
    val isVoice: Boolean
        get() = voice || voicePath != null
}

data class UiState(
    val loading: Boolean = true,
    val hasApiKey: Boolean = false,
    val cardName: String = "",
    val cardDescription: String = "",
    val cardFirstMessage: String = "",
    val presetId: String = "default",
    val avatarModel: Any? = null,
    val avatarBuiltin: String? = null,
    val avatarCustom: Boolean = false,
    val builtinAvatars: List<String> = emptyList(),
    val life: LifeState? = null,
    val fishFood: Int = 0,
    val canCheckIn: Boolean = true,
    val dailyFoodReward: Int = 20,
    val feedCost: Int = 5,
    val attachedImagePath: String? = null,
    val voiceProvider: String = "deepseek",
    val voiceId: String = "stella",
    val voiceLocalUrl: String = "http://127.0.0.1:5050",
    val voiceEnabled: Boolean = true,
    val stickersEnabled: Boolean = true,
    val stickerCount: Int = 0,
    val speaking: Boolean = false,
    val playingKey: String? = null,
    val recording: Boolean = false,
    val recordingSeconds: Int = 0,
    val messages: List<UiMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val clinginess: Float = 0.5f,
    val model: String = "deepseek-flash",
    val experimentalEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val exactAlarms: Boolean = false,
    val reminders: List<Reminder> = emptyList(),
    val memoryCount: Int = 0
) {
    val tone: String
        get() = when {
            clinginess < 0.3f -> "克制"
            clinginess <= 0.7f -> "自然"
            else -> "黏人"
        }
}

data class OnboardingState(
    val validating: Boolean = false,
    val error: String? = null
)

data class CardOptionUi(
    val id: String,
    val name: String,
    val description: String,
    val tags: String,
    val builtin: Boolean
)

data class RoomMemberUi(
    val id: String,
    val name: String,
    val avatar: Any?,
    val talkativeness: Double
)

data class RoomUiMessage(
    val key: String,
    val role: String,
    val speakerId: String?,
    val speakerName: String?,
    val avatar: Any?,
    val text: String,
    val at: Instant
)

data class RoomSummaryUi(
    val id: Long,
    val name: String,
    val members: List<RoomMemberUi>,
    val memberCount: Int,
    val lastText: String?,
    val lastAt: Instant?
)

data class RoomChatUiState(
    val roomId: Long? = null,
    val name: String = "",
    val members: List<RoomMemberUi> = emptyList(),
    val messages: List<RoomUiMessage> = emptyList(),
    val sending: Boolean = false,
    val typingName: String? = null,
    val error: String? = null
)

class CompanionViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as CompanionApp).graph
    private val coordinator = graph.coordinator()
    private val zoneId: ZoneId = graph.zoneId

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _onboarding = MutableStateFlow(OnboardingState())
    val onboarding = _onboarding.asStateFlow()

    private val _brain = MutableStateFlow(BrainUiState())
    val brain = _brain.asStateFlow()

    private val _rooms = MutableStateFlow<List<RoomSummaryUi>>(emptyList())
    val rooms = _rooms.asStateFlow()

    private val _cardOptions = MutableStateFlow<List<CardOptionUi>>(emptyList())
    val cardOptions = _cardOptions.asStateFlow()

    private val _roomChat = MutableStateFlow(RoomChatUiState())
    val roomChat = _roomChat.asStateFlow()

    private val roomCoordinator = graph.roomCoordinator()

    private var recorder: WavRecorder? = null
    private var recordingTicker: Job? = null
    private val ttsCache = mutableMapOf<String, File>()

    init {
        viewModelScope.launch {
            runCatching { loadAll() }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = "初始化失败：${error.message?.take(120) ?: "未知错误"}"
                    )
                }
            }
        }
        viewModelScope.launch {
            AppRuntime.incoming.collect {
                refreshMessages()
                refreshReminders()
                refreshLife()
            }
        }
        viewModelScope.launch { refreshRoomsInternal() }
        viewModelScope.launch { refreshCardOptionsInternal() }
    }

    fun refreshCardOptions() {
        viewModelScope.launch { refreshCardOptionsInternal() }
    }

    private suspend fun refreshCardOptionsInternal() {
        runCatching {
            withContext(Dispatchers.IO) {
                graph.cards.availableCards().map { source ->
                    CardOptionUi(
                        id = source.id,
                        name = source.name,
                        description = source.card.data.description,
                        tags = source.card.data.tags.joinToString(" "),
                        builtin = source.builtin
                    )
                }
            }
        }.onSuccess { _cardOptions.value = it }
    }

    fun onForeground() {
        refreshStatus()
        if (!graph.settings.hasApiKey()) return
        val now = System.currentTimeMillis()
        if (now - graph.settings.lastUiTickAt < UI_TICK_INTERVAL_MS) return
        graph.settings.lastUiTickAt = now
        viewModelScope.launch {
            runCatching { coordinator.tick(foreground = true, opened = true) }
            refreshMessages()
            refreshReminders()
            refreshLife()
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val imagePath = _state.value.attachedImagePath
        if ((trimmed.isEmpty() && imagePath == null) || _state.value.sending) return
        dispatchUserMessage(
            modelText = trimmed.ifBlank { IMAGE_ONLY_TEXT },
            imagePath = imagePath,
            voicePath = null,
            voiceSeconds = 0
        )
    }

    private fun dispatchUserMessage(
        modelText: String,
        imagePath: String?,
        voicePath: String?,
        voiceSeconds: Int
    ) {
        val optimistic = UiMessage(
            key = "local-${System.nanoTime()}",
            role = "user",
            text = modelText,
            at = Instant.now(),
            imagePath = imagePath,
            voicePath = voicePath,
            voiceSeconds = voiceSeconds
        )
        _state.update {
            it.copy(
                sending = true,
                error = null,
                notice = null,
                attachedImagePath = null,
                messages = it.messages + optimistic
            )
        }
        viewModelScope.launch { runSend(modelText, imagePath, voicePath, voiceSeconds) }
        if (imagePath != null) {
            viewModelScope.launch {
                val collected = runCatching {
                    coordinator.maybeCollectSticker(imagePath)
                }.getOrDefault(false)
                if (collected) {
                    val count = withContext(Dispatchers.IO) { graph.stickers.all().size }
                    _state.update {
                        it.copy(stickerCount = count, notice = "它把这张图收进表情库了")
                    }
                }
            }
        }
    }

    private suspend fun runSend(
        modelText: String,
        imagePath: String?,
        voicePath: String?,
        voiceSeconds: Int
    ) {
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                coordinator.send(modelText, imagePath, voicePath, voiceSeconds)
            }
        }
        val failure = outcome.exceptionOrNull()
        if (outcome.isSuccess) {
            val result = outcome.getOrThrow()
            val reminder = result.reminder
            if (result.replies.isEmpty()) {
                refreshMessages()
                _state.update {
                    it.copy(
                        sending = false,
                        error = result.error,
                        notice = reminder?.let { r -> "记下了：${formatDue(r)} · ${r.text}" }
                    )
                }
            } else {
                if (result.replies.firstOrNull()?.text?.isNotBlank() == true) {
                    delay(result.pacing.firstDelayMs)
                }
                result.replies.forEachIndexed { index, message ->
                    if (index > 0) delay(result.pacing.betweenDelayMs)
                    _state.update { state ->
                        val newMessage = message.toUiMessage(index)
                        val duplicate = state.messages.any {
                            it.role == newMessage.role &&
                                it.text == newMessage.text &&
                                it.at == newMessage.at
                        }
                        if (duplicate) {
                            state
                        } else {
                            state.copy(messages = state.messages + newMessage)
                        }
                    }
                }
                _state.update {
                    it.copy(
                        sending = false,
                        error = result.error,
                        notice = reminder?.let { r -> "记下了：${formatDue(r)} · ${r.text}" }
                    )
                }
            }
            refreshReminders()
            refreshLife()
        } else {
            refreshMessages()
            _state.update {
                it.copy(
                    sending = false,
                    error = failure?.message?.take(160) ?: "发送失败"
                )
            }
            refreshLife()
        }
    }

    fun startRecording(): Boolean {
        val current = _state.value
        if (current.recording || current.sending) return false
        val recorder = WavRecorder(getApplication())
        return runCatching { recorder.start() }
            .onSuccess {
                this.recorder = recorder
                _state.update {
                    it.copy(recording = true, recordingSeconds = 0, error = null)
                }
                recordingTicker?.cancel()
                recordingTicker = viewModelScope.launch {
                    while (isActive) {
                        delay(1000)
                        val seconds = _state.value.recordingSeconds + 1
                        _state.update { it.copy(recordingSeconds = seconds) }
                        if (seconds >= MAX_VOICE_SECONDS) {
                            stopRecordingAndSend()
                            break
                        }
                    }
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(error = "录音失败：${error.message ?: "麦克风不可用"}")
                }
            }
            .isSuccess
    }

    fun cancelRecording() {
        recordingTicker?.cancel()
        recordingTicker = null
        val recorder = recorder ?: return
        this.recorder = null
        runCatching { recorder.stop() }.getOrNull()?.file?.delete()
        _state.update { it.copy(recording = false, recordingSeconds = 0) }
    }

    fun stopRecordingAndSend() {
        val recorder = recorder ?: return
        recordingTicker?.cancel()
        recordingTicker = null
        this.recorder = null
        val recorded = runCatching { recorder.stop() }.getOrNull()
        _state.update { it.copy(recording = false, recordingSeconds = 0) }
        if (recorded == null || recorded.seconds < 1) {
            recorded?.file?.delete()
            _state.update { it.copy(error = "说得太短了，再说一次？") }
            return
        }
        if (_state.value.sending) {
            recorded.file.delete()
            _state.update { it.copy(error = "上一条还在回复，等一下再发") }
            return
        }
        val optimistic = UiMessage(
            key = "local-${System.nanoTime()}",
            role = "user",
            text = "",
            at = Instant.now(),
            voicePath = recorded.file.absolutePath,
            voiceSeconds = recorded.seconds
        )
        _state.update {
            it.copy(
                sending = true,
                error = null,
                notice = null,
                messages = it.messages + optimistic
            )
        }
        viewModelScope.launch {
            val transcript = runCatching {
                withContext(Dispatchers.IO) { coordinator.transcribeVoice(recorded.file) }
            }.getOrNull()?.trim().orEmpty()
            if (transcript.isEmpty()) {
                recorded.file.delete()
                _state.update {
                    it.copy(
                        sending = false,
                        error = "没听清，再说一遍？",
                        messages = it.messages.filterNot { m -> m.key == optimistic.key }
                    )
                }
                return@launch
            }
            runSend(transcript, null, recorded.file.absolutePath, recorded.seconds)
        }
    }

    fun handleMicDenied() {
        _state.update { it.copy(error = "需要麦克风权限才能发语音") }
    }

    fun playVoice(message: UiMessage) {
        if (message.isVoice && message.role == "assistant" && message.voicePath == null) {
            if (_state.value.speaking) return
            _state.update { it.copy(speaking = true, error = null) }
            viewModelScope.launch {
                val cacheKey = "${graph.settings.voiceId}|${message.text}"
                val file = ttsCache[cacheKey] ?: runCatching {
                    withContext(Dispatchers.IO) { coordinator.synthesizeVoice(message.text) }
                }.getOrNull()
                if (file == null) {
                    _state.update {
                        it.copy(speaking = false, error = "语音合成失败")
                    }
                    return@launch
                }
                ttsCache[cacheKey] = file
                _state.update { it.copy(speaking = false) }
                playFile(message.key, file)
            }
            return
        }
        val path = message.voicePath ?: return
        val file = File(path)
        if (!file.exists()) {
            _state.update { it.copy(error = "这条语音文件已经不在了") }
            return
        }
        playFile(message.key, file)
    }

    private fun playFile(key: String, file: File) {
        if (_state.value.playingKey == key) {
            VoicePlayer.release()
            _state.update { it.copy(playingKey = null) }
            return
        }
        val seconds = VoicePlayer.durationSeconds(file)
        if (seconds != null) {
            _state.update { state ->
                state.copy(
                    messages = state.messages.map {
                        if (it.key == key) it.copy(voiceSeconds = seconds) else it
                    }
                )
            }
        }
        _state.update { it.copy(playingKey = key) }
        VoicePlayer.play(
            file = file,
            onComplete = {
                _state.update { state ->
                    if (state.playingKey == key) state.copy(playingKey = null) else state
                }
            },
            onError = { message ->
                _state.update { it.copy(playingKey = null, error = message) }
            }
        )
    }

    fun validateAndSaveApiKey(key: String, onResult: (String?) -> Unit) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            onResult("请输入 API Key")
            return
        }
        _onboarding.update { it.copy(validating = true, error = null) }
        viewModelScope.launch {
            coordinator.validateKey(trimmed)
                .onSuccess {
                    coordinator.saveApiKey(trimmed)
                    _onboarding.update { it.copy(validating = false, error = null) }
                    _state.update { it.copy(hasApiKey = true, error = null) }
                    runCatching { loadAll() }
                    onResult(null)
                }
                .onFailure { error ->
                    val message = error.message?.take(160) ?: "验证失败"
                    _onboarding.update { it.copy(validating = false, error = message) }
                    onResult(message)
                }
        }
    }

    fun importCard(uri: Uri) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val fileName = queryFileName(uri)
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取文件")
                }
                withContext(Dispatchers.IO) { coordinator.importCard(bytes, fileName) }
                loadAll()
                _state.update { it.copy(notice = "角色卡已导入") }
            } catch (error: Exception) {
                _state.update {
                    it.copy(error = "角色卡导入失败：${error.message ?: "文件格式不支持"}")
                }
            }
        }
    }

    fun resetCard() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { coordinator.resetToDefaultCard() } }
                .onSuccess {
                    loadAll()
                    _state.update { it.copy(notice = "已恢复默认角色卡") }
                }
                .onFailure {
                    _state.update { state ->
                        state.copy(error = "恢复默认角色卡失败")
                    }
                }
        }
    }

    fun createCardFromText(name: String, description: String, firstMessage: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    coordinator.createCardFromText(name, description, firstMessage)
                }
                loadAll()
            }.onSuccess {
                _state.update { it.copy(notice = "角色卡已创建") }
            }.onFailure { error ->
                _state.update {
                    it.copy(error = "创建角色失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun selectAvatarBuiltin(asset: String) {
        viewModelScope.launch {
            runCatching {
                val companionId = withContext(Dispatchers.IO) {
                    coordinator.activeCard().data.name
                }
                withContext(Dispatchers.IO) {
                    graph.avatars.selectBuiltin(companionId, asset)
                }
                refreshAvatar()
            }.onSuccess {
                _state.update { it.copy(notice = "头像已更换") }
            }.onFailure { error ->
                _state.update {
                    it.copy(error = "头像更换失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun setCustomAvatar(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>()
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取图片")
                }
                val companionId = withContext(Dispatchers.IO) {
                    coordinator.activeCard().data.name
                }
                withContext(Dispatchers.IO) {
                    graph.avatars.setCustom(companionId, bytes)
                }
                refreshAvatar()
            }.onSuccess {
                _state.update { it.copy(notice = "头像已更换") }
            }.onFailure { error ->
                _state.update {
                    it.copy(error = "头像更新失败：${error.message ?: "请选择有效图片"}")
                }
            }
        }
    }

    fun resetAvatar() {
        viewModelScope.launch {
            runCatching {
                val companionId = withContext(Dispatchers.IO) {
                    coordinator.activeCard().data.name
                }
                withContext(Dispatchers.IO) {
                    graph.avatars.reset(companionId)
                }
                refreshAvatar()
            }.onSuccess {
                _state.update { it.copy(notice = "已恢复默认头像") }
            }.onFailure { error ->
                _state.update {
                    it.copy(error = "恢复默认头像失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun setClinginess(value: Double) {
        coordinator.setClinginess(value)
        _state.update { it.copy(clinginess = value.toFloat()) }
        viewModelScope.launch { refreshLife() }
    }

    fun setModel(model: String) {
        graph.settings.model = model
        _state.update { it.copy(model = model) }
    }

    fun setExperimental(enabled: Boolean) {
        graph.settings.experimentalEnabled = enabled
        _state.update { it.copy(experimentalEnabled = enabled) }
    }

    fun cancelReminder(reminderId: String) {
        viewModelScope.launch {
            val cancelled = withContext(Dispatchers.IO) {
                coordinator.cancelReminder(reminderId)
            }
            refreshReminders()
            if (cancelled) {
                _state.update { it.copy(notice = "提醒已取消") }
            }
        }
    }

    fun clearMemory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { coordinator.clearMemory() }
            withContext(Dispatchers.IO) { coordinator.ensureGreeting() }
            refreshMessages()
            refreshReminders()
            refreshLife()
            _state.update { it.copy(notice = "记忆已清空", memoryCount = 0) }
        }
    }

    fun refreshStatus() {
        val context = getApplication<Application>()
        _state.update {
            it.copy(
                notificationsEnabled = Notifications.canNotify(context),
                exactAlarms = ReminderAlarms.canScheduleExact(context),
                experimentalEnabled = graph.settings.experimentalEnabled,
                hasApiKey = graph.settings.hasApiKey()
            )
        }
    }

    fun refreshBrain() {
        viewModelScope.launch {
            _brain.update { it.copy(loading = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) { buildBrainGraph() }
            }.onSuccess { graph ->
                _brain.update { it.copy(loading = false, graph = graph) }
            }.onFailure { error ->
                _brain.update {
                    it.copy(
                        loading = false,
                        error = "读取记忆失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun consolidateMemory() {
        viewModelScope.launch {
            _brain.update { it.copy(updating = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) { coordinator.consolidateMemory() }
            }.onSuccess { facts ->
                _brain.update { it.copy(updating = false) }
                _state.update {
                    it.copy(notice = "记忆整理完成，新增 ${facts.size} 条事实")
                }
                refreshMessages()
                refreshBrain()
            }.onFailure { error ->
                _brain.update {
                    it.copy(
                        updating = false,
                        error = "整理记忆失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun clearBrainError() {
        _brain.update { it.copy(error = null) }
    }

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { graph.images.saveChatImage(uri) }
            }.onSuccess { path ->
                _state.update { it.copy(attachedImagePath = path) }
            }.onFailure { error ->
                _state.update {
                    it.copy(error = "图片添加失败：${error.message ?: "格式不支持"}")
                }
            }
        }
    }

    fun clearAttachedImage() {
        _state.update { it.copy(attachedImagePath = null) }
    }

    fun setVoiceProvider(provider: String) {
        graph.settings.voiceProvider = provider
        val current = _state.value.voiceId
        val voiceId = if (VoiceCatalog.isValid(provider, current)) {
            current
        } else {
            VoiceCatalog.forProvider(provider).first().id
        }
        graph.settings.voiceId = voiceId
        _state.update { it.copy(voiceProvider = provider, voiceId = voiceId) }
    }

    fun setVoiceId(voiceId: String) {
        graph.settings.voiceId = voiceId
        ttsCache.clear()
        _state.update { it.copy(voiceId = voiceId) }
    }

    fun setVoiceLocalUrl(url: String) {
        graph.settings.voiceLocalUrl = url
        _state.update { it.copy(voiceLocalUrl = url) }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        graph.settings.voiceEnabled = enabled
        _state.update { it.copy(voiceEnabled = enabled) }
    }

    fun setStickersEnabled(enabled: Boolean) {
        graph.settings.stickersEnabled = enabled
        _state.update { it.copy(stickersEnabled = enabled) }
    }

    fun renameCompanion(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        graph.settings.companionName = trimmed
        _state.update { it.copy(cardName = trimmed, notice = "名字已改") }
    }

    fun usePresetCard(preset: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { coordinator.usePresetCard(preset) }
                loadAll()
            }.onSuccess {
                _state.update { it.copy(notice = "角色已切换") }
            }.onFailure { error ->
                _state.update {
                    it.copy(error = "切换角色失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun importStickers(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.count { uri ->
                    runCatching {
                        val bytes = getApplication<Application>().contentResolver
                            .openInputStream(uri)?.use { it.readBytes() }
                            ?: return@runCatching false
                        graph.stickers.addUserSticker(bytes) != null
                    }.getOrDefault(false)
                }
            }
            val count = withContext(Dispatchers.IO) { graph.stickers.all().size }
            _state.update {
                it.copy(
                    stickerCount = count,
                    notice = if (added > 0) "已收藏 $added 张表情" else "没有可用的图片"
                )
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(speaking = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) { coordinator.synthesizeVoice(text) }
            }.onSuccess { file ->
                VoicePlayer.play(
                    file = file,
                    onComplete = { _state.update { it.copy(speaking = false) } },
                    onError = { message ->
                        _state.update { it.copy(speaking = false, error = message) }
                    }
                )
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        speaking = false,
                        error = "语音合成失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun testVoice() {
        speak("你好，我是${_state.value.cardName.ifBlank { "大肥鱼" }}。")
    }

    fun claimDailyFood() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { coordinator.claimDailyFood() }
            refreshFood()
            _state.update {
                it.copy(
                    notice = if (result.claimed) {
                        "签到成功，+${result.reward} 鱼粮"
                    } else {
                        "今天已经签到过了"
                    }
                )
            }
        }
    }

    fun feedFish() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { coordinator.feed() }
            result.message?.let { message ->
                _state.update { state ->
                    val ui = message.toUiMessage(state.messages.size)
                    if (state.messages.contains(ui)) {
                        state
                    } else {
                        state.copy(messages = state.messages + ui)
                    }
                }
            }
            refreshFood()
            refreshLife()
            _state.update {
                it.copy(
                    notice = when {
                        result.message == null -> result.reason ?: "现在不能投喂"
                        result.accepted && result.affectionGained > 0 ->
                            "它吃了，好感 +${result.affectionGained}"
                        result.accepted -> "它吃了。今天的好感已经涨过了"
                        else -> result.reason ?: "它现在不想吃"
                    }
                )
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun shouldAskNotificationPermission(): Boolean = !graph.settings.notificationAsked

    fun markNotificationAsked() {
        graph.settings.notificationAsked = true
    }

    fun showTestNotification() {
        val context = getApplication<Application>()
        val name = _state.value.cardName.ifBlank { "大肥鱼" }
        Notifications.showReminder(
            context,
            name,
            "这是一条测试提醒，看到就说明通知通道正常。",
            0
        )
        _state.update { it.copy(notice = "已发送测试通知") }
    }

    fun createTestReminder() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val card = coordinator.activeCard()
                val now = Instant.now()
                val reminder = Reminder(
                    id = java.util.UUID.randomUUID().toString(),
                    companionId = card.data.name,
                    text = "测试提醒",
                    dueAt = now.plusSeconds(120),
                    createdAt = now
                )
                graph.reminders.add(reminder)
                ReminderAlarms.schedule(getApplication(), reminder)
            }
            refreshReminders()
            _state.update { it.copy(notice = "已创建 2 分钟后的测试提醒，按 Home 键等待") }
        }
    }

    fun refreshRooms() {
        viewModelScope.launch { refreshRoomsInternal() }
    }

    fun createRoom(name: String, cardIds: List<String>, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val cards = graph.cards.availableCards().filter { it.id in cardIds }
                    require(cards.size >= 2) { "至少要拉两个角色进群" }
                    val seeds = cards.mapIndexed { index, source ->
                        if (graph.settings.avatarRef(source.name) == null) {
                            graph.settings.setAvatarRef(
                                source.name,
                                "builtin:" + AvatarRepository.BUILTINS[
                                    index % AvatarRepository.BUILTINS.size
                                ]
                            )
                        }
                        val talk = 0.4 + (kotlin.math.abs(source.name.hashCode()) % 5) * 0.1
                        RoomMemberSeed(
                            characterId = source.name,
                            displayName = source.name,
                            cardPath = source.path,
                            avatarRef = graph.settings.avatarRef(source.name),
                            talkativeness = talk
                        )
                    }
                    graph.rooms.createRoom(
                        name.trim().ifBlank { "小鱼缸" },
                        seeds,
                        System.currentTimeMillis()
                    )
                }
            }.onSuccess { roomId ->
                refreshRoomsInternal()
                openRoom(roomId)
                _roomChat.update { it.copy(error = null) }
                onDone(roomId)
            }.onFailure { error ->
                _roomChat.update {
                    it.copy(error = "建群失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun openRoom(roomId: Long) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val room = graph.rooms.room(roomId) ?: error("房间不存在")
                    val members = graph.rooms.members(roomId).map { it.toUiMember() }
                    val messages = graph.rooms.messages(roomId, 100).map { record ->
                        record.toUiMessage(members)
                    }
                    RoomChatUiState(
                        roomId = roomId,
                        name = room.name,
                        members = members,
                        messages = messages
                    )
                }
            }.onSuccess { _roomChat.value = it }
                .onFailure { error ->
                    _roomChat.update {
                        it.copy(error = "打开房间失败：${error.message ?: "未知错误"}")
                    }
                }
        }
    }

    fun closeRoom() {
        _roomChat.value = RoomChatUiState()
    }

    fun deleteRoom(roomId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.rooms.deleteRoom(roomId) }
            if (_roomChat.value.roomId == roomId) closeRoom()
            refreshRoomsInternal()
        }
    }

    fun sendRoomMessage(text: String, replyTo: String? = null) {
        val current = _roomChat.value
        val roomId = current.roomId ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || current.sending) return

        val optimistic = RoomUiMessage(
            key = "rm-local-${System.nanoTime()}",
            role = "user",
            speakerId = null,
            speakerName = null,
            avatar = null,
            text = trimmed,
            at = Instant.now()
        )
        _roomChat.update {
            it.copy(
                messages = it.messages + optimistic,
                sending = true,
                typingName = null,
                error = null
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    roomCoordinator.send(
                        roomId = roomId,
                        text = trimmed,
                        replyTo = replyTo,
                        onTyping = { name ->
                            _roomChat.update { it.copy(typingName = name) }
                        },
                        onReply = { reply ->
                            val member = _roomChat.value.members.firstOrNull {
                                it.id == reply.speakerId
                            }
                            _roomChat.update { chat ->
                                chat.copy(
                                    messages = chat.messages + RoomUiMessage(
                                        key = "rm-${reply.at}-${reply.speakerId}",
                                        role = "assistant",
                                        speakerId = reply.speakerId,
                                        speakerName = reply.speakerName,
                                        avatar = member?.avatar,
                                        text = reply.text,
                                        at = Instant.ofEpochMilli(reply.at)
                                    )
                                )
                            }
                        }
                    )
                }
            }.getOrElse { error ->
                com.dafeiyu.companion.app.runtime.RoomSendResult(
                    emptyList(),
                    error.message ?: "发送失败"
                )
            }
            _roomChat.update {
                it.copy(sending = false, typingName = null, error = result.error)
            }
            refreshRoomsInternal()
        }
    }

    fun clearRoomError() {
        _roomChat.update { it.copy(error = null) }
    }

    fun applyDebugConfig(baseUrl: String?, apiKey: String?) {
        if (!BuildConfig.DEBUG) return
        if (baseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                graph.settings.baseUrl = baseUrl
                graph.settings.saveApiKey(apiKey)
            }
            _state.update { it.copy(hasApiKey = true) }
            runCatching { loadAll() }
        }
    }

    private suspend fun refreshRoomsInternal() {
        runCatching {
            withContext(Dispatchers.IO) {
                graph.rooms.rooms().map { record ->
                    val members = graph.rooms.members(record.id).map { it.toUiMember() }
                    val last = graph.rooms.lastMessage(record.id)
                    RoomSummaryUi(
                        id = record.id,
                        name = record.name,
                        members = members,
                        memberCount = members.size,
                        lastText = last?.text,
                        lastAt = last?.let { Instant.ofEpochMilli(it.at) }
                    )
                }
            }
        }.onSuccess { _rooms.value = it }
    }

    private fun com.dafeiyu.companion.app.data.RoomMemberRecord.toUiMember(): RoomMemberUi =
        RoomMemberUi(
            id = characterId,
            name = displayName,
            avatar = graph.avatars.modelFor(characterId),
            talkativeness = talkativeness
        )

    private fun com.dafeiyu.companion.app.data.RoomMessageRecord.toUiMessage(
        members: List<RoomMemberUi>
    ): RoomUiMessage {
        val member = members.firstOrNull { it.id == characterId }
        return RoomUiMessage(
            key = "rm-$id",
            role = role,
            speakerId = characterId,
            speakerName = member?.name ?: characterId,
            avatar = member?.avatar,
            text = text,
            at = Instant.ofEpochMilli(at)
        )
    }

    private suspend fun loadAll() {
        val card = withContext(Dispatchers.IO) { coordinator.activeCard() }
        withContext(Dispatchers.IO) { coordinator.ensureGreeting() }
        val messages = withContext(Dispatchers.IO) { coordinator.loadMessages() }
        val reminders = withContext(Dispatchers.IO) { coordinator.reminders() }
        val memoryCount = withContext(Dispatchers.IO) { coordinator.memoryCount() }
        withContext(Dispatchers.IO) { coordinator.rescheduleReminders() }
        withContext(Dispatchers.IO) { coordinator.pruneVoiceFiles() }
        withContext(Dispatchers.IO) { coordinator.applyForgetting() }
        val displayName = graph.settings.companionName.takeIf { it.isNotBlank() }
            ?: card.data.name
        val storedVoice = graph.settings.voiceId
        if (!VoiceCatalog.isValid("deepseek", storedVoice)) {
            graph.settings.voiceId = "stella"
        }

        val companionId = card.data.name
        val fallback = card.data.companion?.clinginess ?: 0.5
        val clinginess = graph.settings.clinginess(companionId, fallback)
        val avatarModel = withContext(Dispatchers.IO) { graph.avatars.modelFor(companionId) }
        val avatarBuiltin = withContext(Dispatchers.IO) {
            graph.avatars.selectedBuiltin(companionId)
        }
        val avatarCustom = withContext(Dispatchers.IO) {
            graph.avatars.isCustom(companionId)
        }
        val life = withContext(Dispatchers.IO) { coordinator.lifeState() }
        val food = withContext(Dispatchers.IO) { coordinator.foodStatus() }
        val stickerCount = withContext(Dispatchers.IO) { graph.stickers.all().size }
        val activePath = graph.settings.activeCardPath.orEmpty()
        val presetId = when {
            activePath.endsWith("preset_xiao_jingyu.json") -> "xiao_jingyu"
            activePath.endsWith("default.json") -> "default"
            else -> "custom"
        }
        val context = getApplication<Application>()

        _state.update {
            it.copy(
                loading = false,
                hasApiKey = graph.settings.hasApiKey(),
                cardName = displayName,
                cardDescription = card.data.description,
                cardFirstMessage = card.data.firstMessage,
                presetId = presetId,
                avatarModel = avatarModel,
                avatarBuiltin = avatarBuiltin,
                avatarCustom = avatarCustom,
                builtinAvatars = AvatarRepository.BUILTINS,
                life = life,
                fishFood = food.food,
                canCheckIn = food.canCheckIn,
                dailyFoodReward = food.dailyReward,
                feedCost = food.feedCost,
                attachedImagePath = it.attachedImagePath,
                voiceProvider = graph.settings.voiceProvider,
                voiceId = graph.settings.voiceId,
                voiceLocalUrl = graph.settings.voiceLocalUrl,
                voiceEnabled = graph.settings.voiceEnabled,
                stickersEnabled = graph.settings.stickersEnabled,
                stickerCount = stickerCount,
                messages = messages.toUiMessages(),
                clinginess = clinginess.toFloat(),
                model = graph.settings.model,
                experimentalEnabled = graph.settings.experimentalEnabled,
                notificationsEnabled = Notifications.canNotify(context),
                exactAlarms = ReminderAlarms.canScheduleExact(context),
                reminders = reminders,
                memoryCount = memoryCount
            )
        }
    }

    private suspend fun refreshAvatar() {
        val companionId = withContext(Dispatchers.IO) {
            coordinator.activeCard().data.name
        }
        val model = withContext(Dispatchers.IO) { graph.avatars.modelFor(companionId) }
        val builtin = withContext(Dispatchers.IO) {
            graph.avatars.selectedBuiltin(companionId)
        }
        val custom = withContext(Dispatchers.IO) { graph.avatars.isCustom(companionId) }
        _state.update {
            it.copy(
                avatarModel = model,
                avatarBuiltin = builtin,
                avatarCustom = custom,
                builtinAvatars = AvatarRepository.BUILTINS
            )
        }
    }

    private suspend fun refreshLife() {
        val life = withContext(Dispatchers.IO) { coordinator.lifeState() }
        _state.update { it.copy(life = life) }
    }

    private suspend fun refreshFood() {
        val food = withContext(Dispatchers.IO) { coordinator.foodStatus() }
        _state.update {
            it.copy(
                fishFood = food.food,
                canCheckIn = food.canCheckIn,
                dailyFoodReward = food.dailyReward,
                feedCost = food.feedCost
            )
        }
    }

    private suspend fun buildBrainGraph(): BrainGraph {
        val card = withContext(Dispatchers.IO) { coordinator.activeCard() }
        val entries = withContext(Dispatchers.IO) { graph.memory.all(card.data.name) }
        val recent = withContext(Dispatchers.IO) { graph.memory.recent(card.data.name, 20) }
        return BrainGraphBuilder.build(card.data.name, entries, recent, Instant.now())
    }

    private suspend fun refreshMessages() {
        val messages = withContext(Dispatchers.IO) { coordinator.loadMessages() }
        _state.update { it.copy(messages = messages.toUiMessages()) }
    }

    private suspend fun refreshReminders() {
        val reminders = withContext(Dispatchers.IO) { coordinator.reminders() }
        _state.update { it.copy(reminders = reminders) }
    }

    private fun List<com.dafeiyu.companion.model.ChatMessage>.toUiMessages(): List<UiMessage> =
        flatMapIndexed { index, message -> message.toUiMessages(index) }

    private fun com.dafeiyu.companion.model.ChatMessage.toUiMessages(index: Int): List<UiMessage> {
        val base = toUiMessage(index)
        var shownText = base.text
        var shownVoice = base.isVoice
        if (base.role == "assistant") {
            if (VoiceMarkers.contains(shownText)) {
                shownVoice = true
                shownText = VoiceMarkers.strip(shownText).ifBlank { "……" }
            }
        }
        if (base.role != "assistant" || base.sticker != null || shownVoice) {
            return listOf(
                base.copy(
                    text = shownText,
                    voice = shownVoice,
                    voiceSeconds = if (shownVoice && base.voiceSeconds == 0) {
                        coordinator.estimateVoiceSeconds(shownText)
                    } else {
                        base.voiceSeconds
                    }
                )
            )
        }
        val parts = ReplySplitter.split(shownText, maxBubbles = 10)
        if (parts.size <= 1) return listOf(base.copy(text = shownText))
        return parts.mapIndexed { partIndex, part ->
            base.copy(
                key = "${base.key}-$partIndex",
                text = part,
                at = base.at.plusMillis(partIndex.toLong())
            )
        }
    }

    private fun com.dafeiyu.companion.model.ChatMessage.toUiMessage(index: Int): UiMessage =
        UiMessage(
            key = "${at.toEpochMilli()}-$index-$role",
            role = role,
            text = text,
            at = at,
            imagePath = imagePath,
            voicePath = voicePath,
            voice = voice,
            voiceSeconds = voiceSeconds,
            sticker = sticker,
            stickerModel = sticker?.let { graph.stickers.modelFor(it) }
        )

    private fun formatDue(reminder: Reminder): String =
        reminder.dueAt.atZone(zoneId).format(DATE_FORMAT)

    private fun queryFileName(uri: Uri): String {
        val context = getApplication<Application>()
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: "character.json"
    }

    private companion object {
        const val UI_TICK_INTERVAL_MS = 3 * 60 * 1000L
        const val IMAGE_ONLY_TEXT = "（发了一张图片）"
        const val MAX_VOICE_SECONDS = 60
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    }
}
