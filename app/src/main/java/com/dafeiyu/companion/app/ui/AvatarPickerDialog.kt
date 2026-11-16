package com.dafeiyu.companion.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun AvatarPickerDialog(
    currentModel: Any?,
    builtinAvatars: List<String>,
    selectedBuiltin: String?,
    onSelectBuiltin: (String) -> Unit,
    onPickCustom: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更换头像") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar("", 72.dp, currentModel)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("当前头像", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "选一张内置头像，或从相册选自己的图。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "内置头像",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.height(190.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(builtinAvatars) { asset ->
                        val selected = selectedBuiltin == asset
                        Box(
                            Modifier
                                .size(78.dp)
                                .clip(CircleShape)
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            3.dp,
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { onSelectBuiltin(asset) },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = "file:///android_asset/$asset",
                                contentDescription = "内置头像",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(if (selected) 4.dp else 0.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    TextButton(onClick = onPickCustom) { Text("从相册选择") }
                    TextButton(onClick = onReset) { Text("恢复默认") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}
