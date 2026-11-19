package com.turbooptimizer.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turbooptimizer.BuildConfig
import com.turbooptimizer.TurboApp
import com.turbooptimizer.core.diagnostics.DiagEntry
import com.turbooptimizer.core.diagnostics.DiagLevel
import com.turbooptimizer.core.diagnostics.Diagnostics
import com.turbooptimizer.core.shizuku.ShizukuStatus
import com.turbooptimizer.ui.components.InfoRow
import com.turbooptimizer.ui.components.SectionCard
import com.turbooptimizer.ui.components.TurboScaffold
import com.turbooptimizer.ui.components.list
import com.turbooptimizer.ui.theme.TurboColors
import kotlinx.coroutines.launch

/** Registros de diagnóstico: Shizuku, permisos, comandos, SurfaceFlinger y medidor de FPS. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val entries by Diagnostics.entries.collectAsStateWithLifecycle()
    val shizuku by (context.applicationContext as TurboApp).container.shizuku.state.collectAsStateWithLifecycle()
    var onlyIssues by remember { mutableStateOf(true) }

    val visible = if (onlyIssues) entries.filter { it.level == DiagLevel.WARN || it.level == DiagLevel.ERROR } else entries
    val errors = entries.count { it.level == DiagLevel.ERROR }
    val warnings = entries.count { it.level == DiagLevel.WARN }
    val filterLabel = if (onlyIssues) "solo avisos y errores" else "todos los niveles"

    TurboScaffold("Diagnóstico", onBack, snackbar = snackbar) { padding ->
        LazyColumn(contentPadding = padding.list(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "Resumen", icon = Icons.Default.BugReport) {
                    InfoRow("Shizuku", "${shizuku.status.label()}${shizuku.modeLabel?.let { " · $it" } ?: ""}")
                    InfoRow("Errores", errors.toString())
                    InfoRow("Avisos", warnings.toString())
                    InfoRow("Registros en memoria", "${entries.size} de ${Diagnostics.CAPACITY}")
                    Text(
                        "Los registros se guardan solo en memoria y se borran al cerrar la app. Se escriben también en Logcat con la etiqueta «TurboOptimizer» (por ejemplo: adb logcat -s TurboOptimizer).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(onlyIssues, { onlyIssues = true }, { Text("Errores y avisos") })
                    FilterChip(!onlyIssues, { onlyIssues = false }, { Text("Todo") })
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val text = buildText(context, visible, filterLabel)
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("TurboOptimizer diagnóstico", text))
                        scope.launch { snackbar.showSnackbar("Registros copiados (${visible.size})") }
                    }, enabled = visible.isNotEmpty()) { Text("Copiar registros") }
                    OutlinedButton(onClick = { Diagnostics.clear() }, enabled = entries.isNotEmpty()) { Text("Borrar") }
                }
            }
            if (visible.isEmpty()) {
                item {
                    Text(
                        if (onlyIssues) "No hay errores ni avisos registrados." else "Todavía no hay registros.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Lo más reciente arriba; el texto copiado mantiene el orden cronológico.
            items(visible.asReversed()) { entry -> LogLine(entry) }
        }
    }
}

@Composable
private fun LogLine(entry: DiagEntry) {
    val color = when (entry.level) {
        DiagLevel.ERROR -> TurboColors.Bad
        DiagLevel.WARN -> TurboColors.Warn
        DiagLevel.INFO -> MaterialTheme.colorScheme.onSurface
        DiagLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        entry.format(),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun buildText(context: android.content.Context, entries: List<DiagEntry>, filterLabel: String): String {
    val header = listOf(
        "TurboOptimizer diagnóstico",
        "Versión: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "Dispositivo: ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "Filtro: $filterLabel · Líneas: ${entries.size}",
        "----",
    )
    return Diagnostics.export(entries, header)
}

private fun ShizukuStatus.label(): String = when (this) {
    ShizukuStatus.READY -> "funcionando"
    ShizukuStatus.NO_PERMISSION -> "sin permiso"
    ShizukuStatus.NOT_RUNNING -> "no iniciado"
    ShizukuStatus.UNSUPPORTED -> "versión no compatible"
    ShizukuStatus.NOT_INSTALLED -> "no instalado"
}
