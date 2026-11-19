package com.turbooptimizer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.turbooptimizer.core.shizuku.ShizukuState
import com.turbooptimizer.core.shizuku.ShizukuStatus
import com.turbooptimizer.ui.theme.TurboColors

fun ShizukuState.indicator(): Triple<String, String, Color> = when (status) {
    ShizukuStatus.READY -> Triple("🟢", "Shizuku funcionando", TurboColors.Good)
    ShizukuStatus.NO_PERMISSION -> Triple("🟡", "Shizuku iniciado · falta conceder permiso", TurboColors.Warn)
    ShizukuStatus.NOT_RUNNING -> Triple("🟡", "Shizuku instalado pero no iniciado", TurboColors.Warn)
    ShizukuStatus.UNSUPPORTED -> Triple("🔴", "Versión de Shizuku no compatible", TurboColors.Bad)
    ShizukuStatus.NOT_INSTALLED -> Triple("🔴", "Shizuku no disponible", TurboColors.Bad)
}

@Composable
fun ShizukuStatusCard(state: ShizukuState, onRequestPermission: () -> Unit, onOpenShizuku: () -> Boolean, compact: Boolean = false) {
    val (dot, label, color) = state.indicator()
    val uri = LocalUriHandler.current
    SectionCard(title = "$dot  $label", icon = Icons.Default.AdminPanelSettings, accent = color) {
        val detail = when (state.status) {
            ShizukuStatus.READY -> listOfNotNull(
                state.serverVersion?.let { "Servidor v$it" },
                state.modeLabel?.let { "modo $it" },
                state.managerVersion?.let { "app $it" },
            ).joinToString(" · ") + ". Funciones avanzadas activas."
            ShizukuStatus.NO_PERMISSION -> "Concede el permiso para que TurboOptimizer pueda detener apps y aplicar ajustes seguros."
            ShizukuStatus.NOT_RUNNING -> "Abre Shizuku e inícialo con «Depuración inalámbrica» (Android 11+). Mientras tanto la app funciona en modo limitado."
            ShizukuStatus.UNSUPPORTED -> "Actualiza Shizuku a la versión 13 o superior."
            ShizukuStatus.NOT_INSTALLED -> "La app funciona en modo limitado con APIs normales de Android. Instala Shizuku para funciones avanzadas (sin root)."
        }
        if (!compact || state.status != ShizukuStatus.READY) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (state.status) {
            ShizukuStatus.NO_PERMISSION -> Button(onClick = onRequestPermission) { Text("Conceder permiso") }
            ShizukuStatus.NOT_RUNNING -> OutlinedButton(onClick = { onOpenShizuku() }) { Text("Abrir Shizuku") }
            ShizukuStatus.NOT_INSTALLED, ShizukuStatus.UNSUPPORTED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    runCatching { uri.openUri("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api") }
                        .onFailure { runCatching { uri.openUri("https://shizuku.rikka.app/download/") } }
                }) { Text("Obtener Shizuku") }
                OutlinedButton(onClick = { runCatching { uri.openUri("https://shizuku.rikka.app/guide/setup/") } }) { Text("Guía") }
            }
            ShizukuStatus.READY -> Unit
        }
    }
}

@Composable
fun StatusDot(color: Color) {
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
}

@Composable
fun LabeledDot(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
