package com.dafeiyu.companion.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dafeiyu.companion.app.UiState
import com.dafeiyu.companion.app.runtime.LifeState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ChatHeader(
    state: UiState,
    onOpenSettings: () -> Unit,
    onCheckIn: () -> Unit,
    onFeed: () -> Unit
) {
    val life = state.life
    val mood = life?.mood ?: 10
    val energy = life?.energy ?: 70
    val missing = life?.missing ?: 0

    Box(
        Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    bottomStart = 26.dp,
                    bottomEnd = 26.dp
                )
            )
    ) {
        AuraBackground(
            mood = mood,
            energy = energy,
            missing = missing,
            modifier = Modifier.matchParentSize()
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(state.cardName, 36.dp, state.avatarModel)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.cardName.ifBlank { "大肥鱼" },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        life?.let {
                            "${state.tone} · ${it.moodLabel} · ${it.activityLabel}"
                        } ?: "${state.tone} · 正在感知…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (life != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "“${life.thought}”",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "鱼粮 ${state.fishFood}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (state.canCheckIn) "签到 +${state.dailyFoodReward}" else "已签到",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.canCheckIn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(enabled = state.canCheckIn, onClick = onCheckIn)
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "投喂 -${state.feedCost}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.fishFood >= state.feedCost) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(
                            enabled = state.fishFood >= state.feedCost,
                            onClick = onFeed
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun AuraBackground(
    mood: Int,
    energy: Int,
    missing: Int,
    modifier: Modifier = Modifier
) {
    val moodFactor = ((mood + 100) / 200f).coerceIn(0f, 1f)
    val energyFactor = (energy / 100f).coerceIn(0f, 1f)
    val missingFactor = (missing / 100f).coerceIn(0f, 1f)
    val warm = Color(0xFFF0A46B)
    val cool = Color(0xFF7A8FB8)
    val pink = Color(0xFFD98BA6)
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    val transition = rememberInfiniteTransition(label = "life-aura")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (9000 - energy * 45).coerceIn(4200, 9000),
                easing = LinearEasing
            )
        ),
        label = "phase"
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.linearGradient(
                colors = listOf(surface, surfaceVariant.copy(alpha = 0.75f)),
                start = Offset.Zero,
                end = Offset(w, h)
            )
        )

        fun blob(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                radius = radius,
                center = Offset(cx, cy)
            )
        }

        blob(
            w * (0.24f + 0.06f * sin(phase)),
            h * (0.35f + 0.08f * cos(phase * 0.8f)),
            w * 0.45f,
            warm,
            0.08f + 0.20f * moodFactor
        )
        blob(
            w * (0.76f + 0.05f * cos(phase * 0.7f)),
            h * (0.60f + 0.06f * sin(phase)),
            w * 0.50f,
            cool,
            0.08f + 0.18f * (1f - moodFactor)
        )
        if (missingFactor > 0.02f) {
            blob(
                w * (0.50f + 0.10f * sin(phase * 1.3f)),
                h * 0.88f,
                w * 0.35f,
                pink,
                0.04f + 0.16f * missingFactor
            )
        }

        val waveBrush = Brush.horizontalGradient(
            listOf(
                warm.copy(alpha = 0.30f),
                cool.copy(alpha = 0.22f),
                Color.Transparent
            )
        )
        repeat(2) { index ->
            val path = Path()
            val baseY = h * (0.66f + index * 0.17f)
            val amplitude = h * (0.025f + 0.055f * energyFactor)
            var x = 0f
            path.moveTo(0f, baseY + sin(phase + index) * amplitude)
            while (x <= w) {
                x += (w / 28f).coerceAtLeast(1f)
                path.lineTo(
                    x,
                    baseY + sin(x / w * 9.42f + phase + index * 1.7f) * amplitude
                )
            }
            drawPath(
                path = path,
                brush = waveBrush,
                style = Stroke(
                    width = (1.1f + index * 0.5f).dp.toPx(),
                    cap = StrokeCap.Round
                )
            )
        }
    }
}
