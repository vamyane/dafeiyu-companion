package com.dafeiyu.companion.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dafeiyu.companion.app.OnboardingState
import com.dafeiyu.companion.app.UiState

@Composable
fun OnboardingScreen(
    state: UiState,
    onboarding: OnboardingState,
    onValidate: (String) -> Unit,
    onImportCard: () -> Unit,
    onCreateCard: (String, String, String) -> Unit,
    onSelectAvatar: (String) -> Unit,
    onPickCustomAvatar: () -> Unit,
    onResetAvatar: () -> Unit,
    onOpenDeepSeek: () -> Unit
) {
    var apiKey by rememberSaveable { mutableStateOf("") }
    var showTextDialog by rememberSaveable { mutableStateOf(false) }
    var showAvatarDialog by rememberSaveable { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Avatar(state.cardName, 76.dp, state.avatarModel)
            Spacer(Modifier.height(16.dp))
            Text("大肥鱼", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "一条刚来到你身边的小鱼，会慢慢记住你的事。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "当前角色",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(state.cardName, 56.dp, state.avatarModel)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                state.cardName.ifBlank { "大肥鱼" },
                                style = MaterialTheme.typography.titleLarge
                            )
                            TextButton(
                                onClick = { showAvatarDialog = true },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text("换头像")
                            }
                        }
                    }
                    if (state.cardDescription.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(
                            onClick = onImportCard,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Text("导入 PNG / JSON")
                        }
                        Spacer(Modifier.width(16.dp))
                        TextButton(
                            onClick = { showTextDialog = true },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Text("用文字创建")
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth()) {
                Text("先连上模型", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("DeepSeek API Key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = onboarding.error != null
                )
                Spacer(Modifier.height(8.dp))
                if (onboarding.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        onboarding.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onValidate(apiKey) },
                    enabled = apiKey.isNotBlank() && !onboarding.validating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (onboarding.validating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在验证…")
                    } else {
                        Text("验证并进入")
                    }
                }
                TextButton(
                    onClick = onOpenDeepSeek,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("去 DeepSeek 开放平台申请 Key")
                }
            }
        }
        if (showTextDialog) {
            TextCardDialog(
                onDismiss = { showTextDialog = false },
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
    }
}
