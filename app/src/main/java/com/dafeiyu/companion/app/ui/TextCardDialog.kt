package com.dafeiyu.companion.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TextCardDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, firstMessage: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var firstMessage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用文字创建角色") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("角色名") },
                    placeholder = { Text("例如：岚") },
                    singleLine = true,
                    isError = error != null && name.isBlank()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("角色设定") },
                    placeholder = {
                        Text("直接粘贴一段人设文字：身份、性格、说话方式、和你的关系…")
                    },
                    minLines = 5,
                    maxLines = 10,
                    isError = error != null && description.isBlank()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = firstMessage,
                    onValueChange = { firstMessage = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("开场白（可选）") },
                    placeholder = { Text("第一次打开聊天时，角色说的第一句话") },
                    minLines = 2,
                    maxLines = 4
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "创建后会保存为标准 SillyTavern v2 角色卡，之后可以正常导入导出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        name.isBlank() -> error = "请填写角色名"
                        description.isBlank() -> error = "请填写角色设定"
                        else -> {
                            onCreate(name, description, firstMessage)
                            onDismiss()
                        }
                    }
                }
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
