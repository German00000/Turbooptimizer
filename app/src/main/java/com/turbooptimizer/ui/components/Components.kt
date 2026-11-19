package com.turbooptimizer.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TurboScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    snackbar: SnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        bottomBar = bottomBar,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/** Padding estándar para listas dentro de un Scaffold. */
fun PaddingValues.list(extraBottom: Dp = 24.dp) = PaddingValues(
    start = 16.dp, end = 16.dp, top = calculateTopPadding() + 4.dp, bottom = calculateBottomPadding() + extraBottom,
)

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = modifier.fillMaxWidth().clip(shape).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/** Anillo de progreso animado (270°). */
@Composable
fun GaugeRing(
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 8.dp,
    content: @Composable () -> Unit = {},
) {
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(700), label = "gauge")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val s = stroke.toPx()
            val arc = Size(size.width - s, size.height - s)
            val tl = Offset(s / 2, s / 2)
            drawArc(track, 135f, 270f, false, tl, arc, style = Stroke(s, cap = StrokeCap.Round))
            if (fraction != null) drawArc(color, 135f, 270f * animated, false, tl, arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Tarjeta de métrica con anillo. */
@Composable
fun MetricTile(
    title: String,
    value: String,
    subtitle: String,
    fraction: Float?,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = modifier.clip(shape).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GaugeRing(fraction, color, Modifier.size(58.dp), stroke = 6.dp) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Gráfico de líneas simple; cada serie contiene valores 0..1 (null = sin dato). */
@Composable
fun LineChart(series: List<Pair<Color, List<Float?>>>, modifier: Modifier = Modifier, maxPoints: Int = 60) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val step = size.width / (maxPoints - 1).coerceAtLeast(1)
        series.forEach { (color, values) ->
            val path = Path()
            var started = false
            val offset = maxPoints - values.size
            values.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                val x = (offset + i) * step
                val y = size.height * (1f - v.coerceIn(0f, 1f))
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.let { if (mono) it.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp) else it },
            modifier = Modifier.weight(0.55f),
        )
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), maxLines = 1)
    }
}

@Composable
fun CommandBox(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirmar",
    dangerous: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                extra()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------- Iconos de apps ----------------

object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(250)
    fun peek(pkg: String): ImageBitmap? = cache[pkg]
    fun load(ctx: Context, pkg: String): ImageBitmap? = cache[pkg] ?: runCatching {
        ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
    }.getOrNull()?.also { cache.put(pkg, it) }
}

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp by produceState(AppIconCache.peek(pkg), pkg) {
        if (value == null) value = withContext(Dispatchers.IO) { AppIconCache.load(ctx, pkg) }
    }
    val image = bmp
    if (image != null) {
        Image(image, null, Modifier.size(size).clip(RoundedCornerShape(10.dp)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
