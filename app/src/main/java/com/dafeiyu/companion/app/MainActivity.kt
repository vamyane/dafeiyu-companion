package com.dafeiyu.companion.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dafeiyu.companion.app.runtime.AppRuntime
import com.dafeiyu.companion.app.runtime.Notifications
import com.dafeiyu.companion.app.ui.BrainScreen
import com.dafeiyu.companion.app.ui.ChatScreen
import com.dafeiyu.companion.app.ui.CompanionTheme
import com.dafeiyu.companion.app.ui.ExperimentalWebScreen
import com.dafeiyu.companion.app.ui.OnboardingScreen
import com.dafeiyu.companion.app.ui.RoomChatScreen
import com.dafeiyu.companion.app.ui.RoomListScreen
import com.dafeiyu.companion.app.ui.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow

enum class AppScreen { CHAT, SETTINGS, EXPERIMENTAL_WEB, BRAIN, ROOMS, ROOM_CHAT }

class MainActivity : ComponentActivity() {

    private val viewModel: CompanionViewModel by viewModels()
    private val screen = MutableStateFlow(AppScreen.CHAT)
    private var webReturnScreen = AppScreen.CHAT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            viewModel.applyDebugConfig(
                intent.getStringExtra("debug_base_url"),
                intent.getStringExtra("debug_api_key")
            )
        }
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            CompanionTheme(darkTheme = darkTheme) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val onboarding by viewModel.onboarding.collectAsStateWithLifecycle()
                val brain by viewModel.brain.collectAsStateWithLifecycle()
                val rooms by viewModel.rooms.collectAsStateWithLifecycle()
                val cardOptions by viewModel.cardOptions.collectAsStateWithLifecycle()
                val roomChat by viewModel.roomChat.collectAsStateWithLifecycle()
                val currentScreen by screen.collectAsStateWithLifecycle()
                val context = LocalContext.current

                val cardPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) viewModel.importCard(uri)
                }
                val avatarPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null) viewModel.setCustomAvatar(uri)
                }
                val chatImagePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null) viewModel.attachImage(uri)
                }
                val stickerPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    if (uris.isNotEmpty()) viewModel.importStickers(uris)
                }
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {
                    viewModel.refreshStatus()
                }
                val audioPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        viewModel.startRecording()
                    } else {
                        viewModel.handleMicDenied()
                    }
                }

                LaunchedEffect(state.hasApiKey) {
                    if (state.hasApiKey &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED &&
                        viewModel.shouldAskNotificationPermission()
                    ) {
                        viewModel.markNotificationAsked()
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                if (state.loading) {
                    LoadingScreen()
                } else if (!state.hasApiKey) {
                    OnboardingScreen(
                        state = state,
                        onboarding = onboarding,
                        onValidate = { key -> viewModel.validateAndSaveApiKey(key) { } },
                        onImportCard = { cardPicker.launch(arrayOf("*/*")) },
                        onCreateCard = viewModel::createCardFromText,
                        onSelectAvatar = viewModel::selectAvatarBuiltin,
                        onPickCustomAvatar = { avatarPicker.launch("image/*") },
                        onResetAvatar = viewModel::resetAvatar,
                        onOpenDeepSeek = {
                            openUrl(context, "https://platform.deepseek.com/api_keys")
                        }
                    )
                } else {
                    when (currentScreen) {
                        AppScreen.CHAT -> ChatScreen(
                            state = state,
                            onSend = viewModel::send,
                            onOpenSettings = { screen.value = AppScreen.SETTINGS },
                            onOpenWeb = {
                                webReturnScreen = AppScreen.CHAT
                                screen.value = AppScreen.EXPERIMENTAL_WEB
                            },
                            onCheckIn = viewModel::claimDailyFood,
                            onFeed = viewModel::feedFish,
                            onPickImage = { chatImagePicker.launch("image/*") },
                            onClearImage = viewModel::clearAttachedImage,
                            onPlayVoice = viewModel::playVoice,
                            onStartRecording = {
                                if (ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    viewModel.startRecording()
                                } else {
                                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onStopRecording = viewModel::stopRecordingAndSend,
                            onCancelRecording = viewModel::cancelRecording,
                            onDismissError = viewModel::clearError,
                            onDismissNotice = viewModel::clearNotice
                        )

                        AppScreen.SETTINGS -> SettingsScreen(
                            state = state,
                            onBack = { screen.value = AppScreen.CHAT },
                            onModelChange = viewModel::setModel,
                            onClinginessChange = viewModel::setClinginess,
                            onVoiceChange = viewModel::setVoiceId,
                            onVoiceEnabledChange = viewModel::setVoiceEnabled,
                            onStickersEnabledChange = viewModel::setStickersEnabled,
                            onImportStickers = { stickerPicker.launch(arrayOf("image/*")) },
                            onRename = viewModel::renameCompanion,
                            onUsePreset = viewModel::usePresetCard,
                            onTestVoice = viewModel::testVoice,
                            onImportCard = { cardPicker.launch(arrayOf("*/*")) },
                            onCreateCard = viewModel::createCardFromText,
                            onSelectAvatar = viewModel::selectAvatarBuiltin,
                            onPickCustomAvatar = { avatarPicker.launch("image/*") },
                            onResetAvatar = viewModel::resetAvatar,
                            onResetCard = viewModel::resetCard,
                            onUpdateApiKey = viewModel::validateAndSaveApiKey,
                            onCancelReminder = viewModel::cancelReminder,
                            onExperimentalChange = viewModel::setExperimental,
                            onOpenWeb = {
                                webReturnScreen = AppScreen.SETTINGS
                                screen.value = AppScreen.EXPERIMENTAL_WEB
                            },
                            onOpenBrain = {
                                viewModel.refreshBrain()
                                screen.value = AppScreen.BRAIN
                            },
                            onOpenRooms = {
                                viewModel.refreshRooms()
                                viewModel.refreshCardOptions()
                                screen.value = AppScreen.ROOMS
                            },
                            onClearMemory = viewModel::clearMemory,
                            onRequestNotificationPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermission.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                } else {
                                    openNotificationSettings(context)
                                }
                            },
                            onOpenExactAlarmSettings = {
                                openExactAlarmSettings(context)
                            },
                            onOpenBatterySettings = {
                                openUrl(context, "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
                            },
                            debugMode = BuildConfig.DEBUG,
                            onTestNotification = viewModel::showTestNotification,
                            onTestReminder = viewModel::createTestReminder
                        )

                        AppScreen.EXPERIMENTAL_WEB -> ExperimentalWebScreen(
                            onBack = { screen.value = webReturnScreen }
                        )

                        AppScreen.BRAIN -> BrainScreen(
                            brain = brain,
                            onBack = { screen.value = AppScreen.SETTINGS },
                            onRefresh = viewModel::refreshBrain,
                            onConsolidate = viewModel::consolidateMemory,
                            onDismissError = viewModel::clearBrainError
                        )

                        AppScreen.ROOMS -> RoomListScreen(
                            rooms = rooms,
                            cardOptions = cardOptions,
                            onBack = { screen.value = AppScreen.SETTINGS },
                            onRefresh = viewModel::refreshRooms,
                            onCreate = { name, ids ->
                                viewModel.createRoom(name, ids) {
                                    screen.value = AppScreen.ROOM_CHAT
                                }
                            },
                            onOpen = { roomId ->
                                viewModel.openRoom(roomId)
                                screen.value = AppScreen.ROOM_CHAT
                            },
                            onDelete = viewModel::deleteRoom
                        )

                        AppScreen.ROOM_CHAT -> RoomChatScreen(
                            state = roomChat,
                            onBack = {
                                viewModel.closeRoom()
                                screen.value = AppScreen.ROOMS
                            },
                            onSend = { text -> viewModel.sendRoomMessage(text) },
                            onClearError = viewModel::clearRoomError
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppRuntime.isForeground = true
        viewModel.onForeground()
    }

    override fun onStop() {
        AppRuntime.isForeground = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Notifications.EXTRA_OPEN_CHAT, false)) {
            screen.value = AppScreen.CHAT
        }
    }
}

private fun openUrl(context: Context, url: String) {
    if (url.startsWith("android.settings.")) {
        runCatching { context.startActivity(Intent(url)) }
            .onFailure { openAppSettings(context) }
        return
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        )
    }.onFailure { openAppSettings(context) }
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }.onFailure { openAppSettings(context) }
    } else {
        openAppSettings(context)
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
