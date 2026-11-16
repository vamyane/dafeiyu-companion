package com.dafeiyu.companion.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.companion.app.runtime.BrainEdgeKind
import com.dafeiyu.companion.app.runtime.BrainGraph
import com.dafeiyu.companion.app.runtime.BrainNode
import com.dafeiyu.companion.app.runtime.BrainNodeKind
import com.dafeiyu.companion.app.runtime.BrainUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainScreen(
    brain: BrainUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onConsolidate: () -> Unit,
    onDismissError: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val shape by produceState<BrainShape?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { BrainShapeLoader.load(context) }
    }
    var selected by remember { mutableStateOf<BrainNode?>(null) }
    val graph = brain.graph

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
                title = { Text("记忆大脑") },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !brain.loading) {
                        Text("刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "它记住的事实、日记和最近的对话，会在这里长成一张网。点节点看内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (graph != null) {
                Text(
                    "事实 ${graph.facts} · 日记 ${graph.reflections} · 最近对话 ${graph.recentMessages}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.Transparent,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(430.dp)
                ) {
                    if (graph == null && brain.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (graph != null) {
                        BrainCanvas(
                            graph = graph,
                            shape = shape,
                            onNodeClick = { selected = it }
                        )
                        if (graph.nodes.size <= 1) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "还没有足够的记忆。",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "多聊几句，或点下面的「整理记忆」。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            LegendRow()

            Button(
                onClick = onConsolidate,
                enabled = !brain.updating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (brain.updating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在整理…")
                } else {
                    Text("整理记忆")
                }
            }
            Text(
                "整理记忆会调用模型：从最近对话提取事实，并写一篇日记。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (brain.error != null) {
                Text(
                    brain.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onDismissError) { Text("知道了") }
            }

            Spacer(Modifier.navigationBarsPadding())
        }
    }

    selected?.let { node ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = {
                Text(
                    when (node.kind) {
                        BrainNodeKind.CENTER -> "记忆中心"
                        BrainNodeKind.FACT -> "事实"
                        BrainNodeKind.REFLECTION -> "日记"
                        BrainNodeKind.RECENT -> "最近的对话"
                    }
                )
            },
            text = {
                Column {
                    Text(node.detail, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        node.meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun LegendRow() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendDot("事实", MaterialTheme.colorScheme.primary)
        LegendDot("日记", MaterialTheme.colorScheme.secondary)
        LegendDot("最近对话", MaterialTheme.colorScheme.onSurfaceVariant)
        LegendDot("中心", MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class RenderedNode(
    val node: BrainNode,
    val center: Offset,
    val radius: Float
)

@Composable
private fun BrainCanvas(
    graph: BrainGraph,
    shape: BrainShape?,
    onNodeClick: (BrainNode) -> Unit
) {
    val density = LocalDensity.current
    val outline = MaterialTheme.colorScheme.outlineVariant
    val outlineStrong = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val coral = primary
    val moss = MaterialTheme.colorScheme.secondary
    val ash = MaterialTheme.colorScheme.onSurfaceVariant
    val pulse by rememberInfiniteTransition(label = "brain-pulse").animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val canvasBg = MaterialTheme.colorScheme.background
    val canvasBg2 = MaterialTheme.colorScheme.surfaceVariant

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(canvasBg)
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val rendered = remember(graph, shape, widthPx, heightPx, density) {
            layoutBrainNodes(graph, widthPx, heightPx, shape, density)
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(rendered) {
                    detectTapGestures { offset ->
                        val hit = rendered.minByOrNull { (it.center - offset).getDistance() }
                        if (hit != null &&
                            (hit.center - offset).getDistance() <= hit.radius + 20.dp.toPx()
                        ) {
                            onNodeClick(hit.node)
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.radialGradient(
                    colors = listOf(canvasBg, canvasBg2),
                    center = Offset(w * 0.5f, h * 0.48f),
                    radius = w * 0.75f
                )
            )

            val content = brainRect(w, h, shape)
            if (shape != null) {
                val path = Path()
                val step = (shape.points.size / 260).coerceAtLeast(1)
                shape.points.forEachIndexed { index, point ->
                    if (index % step != 0 && index != shape.points.lastIndex) {
                        return@forEachIndexed
                    }
                    val x = content.left +
                        (point.x - shape.minX) / shape.spanX * content.width
                    val y = content.top +
                        (point.y - shape.minY) / shape.spanY * content.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color = outlineStrong.copy(alpha = 0.055f), style = Stroke(9.dp.toPx()))
                drawPath(path, color = outlineStrong.copy(alpha = 0.16f), style = Stroke(3.dp.toPx()))
                drawPath(
                    path,
                    color = outlineStrong.copy(alpha = 0.52f),
                    style = Stroke(width = 1.3.dp.toPx())
                )

                // 几条脑沟回，让轮廓一眼能读成“大脑”，而不只是一个圆块。
                repeat(3) { index ->
                    val y = content.top + content.height * (0.30f + index * 0.16f)
                    val left = Path().apply {
                        moveTo(content.left + content.width * 0.16f, y)
                        cubicTo(
                            content.left + content.width * 0.24f,
                            y - content.height * 0.10f,
                            content.left + content.width * 0.34f,
                            y + content.height * 0.10f,
                            content.left + content.width * 0.43f,
                            y
                        )
                    }
                    drawPath(
                        left,
                        color = outlineStrong.copy(alpha = 0.13f),
                        style = Stroke(width = 1.1.dp.toPx())
                    )
                    val right = Path().apply {
                        moveTo(content.left + content.width * 0.57f, y)
                        cubicTo(
                            content.left + content.width * 0.66f,
                            y - content.height * 0.10f,
                            content.left + content.width * 0.76f,
                            y + content.height * 0.10f,
                            content.left + content.width * 0.84f,
                            y
                        )
                    }
                    drawPath(
                        right,
                        color = outlineStrong.copy(alpha = 0.13f),
                        style = Stroke(width = 1.1.dp.toPx())
                    )
                }
            } else {
                drawOval(
                    color = outline.copy(alpha = 0.10f),
                    topLeft = Offset(w * 0.12f, h * 0.16f),
                    size = androidx.compose.ui.geometry.Size(w * 0.38f, h * 0.68f)
                )
                drawOval(
                    color = outline.copy(alpha = 0.10f),
                    topLeft = Offset(w * 0.50f, h * 0.16f),
                    size = androidx.compose.ui.geometry.Size(w * 0.38f, h * 0.68f)
                )
            }
            drawLine(
                color = outlineStrong.copy(alpha = 0.30f),
                start = Offset(w * 0.5f, content.top + content.height * 0.08f),
                end = Offset(w * 0.5f, content.top + content.height * 0.92f),
                strokeWidth = 1.2.dp.toPx()
            )

            val byId = rendered.associateBy { it.node.id }
            graph.edges.forEach { edge ->
                val from = byId[edge.from] ?: return@forEach
                val to = byId[edge.to] ?: return@forEach
                val color = when (edge.kind) {
                    BrainEdgeKind.HUB -> outline.copy(alpha = 0.14f)
                    BrainEdgeKind.REFLECTION_FACT -> moss.copy(alpha = 0.35f)
                    BrainEdgeKind.SUBJECT -> coral.copy(alpha = 0.25f)
                }
                drawLine(
                    color = color,
                    start = from.center,
                    end = to.center,
                    strokeWidth = 1.dp.toPx()
                )
            }

            rendered.forEach { renderedNode ->
                val color = when (renderedNode.node.kind) {
                    BrainNodeKind.CENTER -> primary
                    BrainNodeKind.FACT -> coral
                    BrainNodeKind.REFLECTION -> moss
                    BrainNodeKind.RECENT -> ash
                }
                val glow = renderedNode.radius * (1.8f + 0.5f * pulse)
                drawCircle(color.copy(alpha = 0.07f), glow, renderedNode.center)
                drawCircle(color.copy(alpha = 0.22f), renderedNode.radius * 1.5f, renderedNode.center)
                drawCircle(color, renderedNode.radius, renderedNode.center)
                if (renderedNode.node.kind == BrainNodeKind.CENTER) {
                    drawCircle(
                        Color.White.copy(alpha = 0.85f),
                        renderedNode.radius * 0.32f,
                        renderedNode.center
                    )
                }
            }

            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 10.sp.toPx()
                color = onSurface.toArgb()
            }
            rendered
                .filter {
                    it.node.kind == BrainNodeKind.CENTER ||
                        it.node.kind == BrainNodeKind.REFLECTION
                }
                .sortedByDescending { it.radius }
                .take(10)
                .forEach { renderedNode ->
                    val text = if (renderedNode.node.kind == BrainNodeKind.CENTER) {
                        renderedNode.node.label
                    } else {
                        renderedNode.node.label.take(8)
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        text,
                        renderedNode.center.x - paint.measureText(text) / 2f,
                        renderedNode.center.y - renderedNode.radius - 6.dp.toPx(),
                        paint
                    )
                }
        }

        Text(
            "点节点看内容",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun layoutBrainNodes(
    graph: BrainGraph,
    width: Float,
    height: Float,
    shape: BrainShape?,
    density: Density
): List<RenderedNode> {
    val content = brainRect(width, height, shape)
    return graph.nodes.map { node ->
        val center = Offset(
            content.left + node.nx * content.width,
            content.top + node.ny * content.height
        )
        RenderedNode(
            node = node,
            center = center,
            radius = with(density) { node.radius.dp.toPx() }
        )
    }
}

private fun brainRect(width: Float, height: Float, shape: BrainShape?): Rect {
    if (shape == null || shape.spanX <= 0f || shape.spanY <= 0f) {
        return Rect(0f, 0f, width, height)
    }
    val scale = min(width / shape.spanX, height / shape.spanY) * 0.94f
    val w = shape.spanX * scale
    val h = shape.spanY * scale
    return Rect((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
}
