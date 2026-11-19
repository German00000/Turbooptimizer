package com.turbooptimizer.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.turbooptimizer.AppContainer
import com.turbooptimizer.data.AppSettings
import com.turbooptimizer.data.RecorderPreset
import com.turbooptimizer.data.RecorderSettings
import com.turbooptimizer.recorder.RecorderCapabilities
import com.turbooptimizer.recorder.RecorderState
import com.turbooptimizer.recorder.ScreenRecorderService
import com.turbooptimizer.ui.components.ConfirmDialog
import com.turbooptimizer.ui.components.InfoRow
import com.turbooptimizer.ui.components.SectionCard
import com.turbooptimizer.ui.components.TurboScaffold
import com.turbooptimizer.ui.components.list
import com.turbooptimizer.ui.navigation.turboViewModel
import com.turbooptimizer.ui.theme.TurboColors
import com.turbooptimizer.util.Permissions
import com.turbooptimizer.util.formatBytes
import com.turbooptimizer.util.formatDuration
import com.turbooptimizer.util.formatTemp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class RecorderViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val state = RecorderState.state
    val snapshot = c.snapshots

    fun update(t: (RecorderSettings) -> RecorderSettings) = viewModelScope.launch {
        c.settings.update { it.copy(recorder = t(it.recorder)) }
    }

    fun applyPreset(p: RecorderPreset) = update {
        if (p == RecorderPreset.CUSTOM) it.copy(preset = p)
        else it.copy(preset = p, shortSide = p.shortSide, fps = p.fps, bitrateMbps = p.bitrateMbps)
    }
}

/** Comprobaciones previas: temperatura, batería y almacenamiento. */
private data class PreCheck(val warnings: List<String>, val availableBytes: Long, val perMinute: Long)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RecorderScreen(onBack: () -> Unit) {
    val vm = turboViewModel { RecorderViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val rec = settings.recorder
    val supports60 = remember { RecorderCapabilities.supports60(context) }
    var precheck by remember { mutableStateOf<PreCheck?>(null) }
    val audioWanted = rec.internalAudio || rec.microphone

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                context,
                ScreenRecorderService.startIntent(context, result.resultCode, data, rec.shortSide, rec.fps, rec.bitrateMbps, rec.internalAudio, rec.microphone, rec.folderUri),
            )
        } else {
            RecorderState.state.value = RecorderState.state.value.copy(error = "Captura de pantalla cancelada")
        }
    }
    val launchCapture = {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { launchCapture() }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.update { it.copy(folderUri = uri.toString()) }
        }
    }

    fun startFlow() {
        val needed = buildList {
            if (!Permissions.hasNotifications(context)) add(Manifest.permission.POST_NOTIFICATIONS)
            if (audioWanted && !Permissions.hasMicrophone(context)) add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray()) else launchCapture()
    }

    val busy = state.status != RecorderState.Status.IDLE

    TurboScaffold("Grabador de pantalla", onBack) { padding ->
        LazyColumn(contentPadding = padding.list(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) {
                item {
                    SectionCard(title = when (state.status) {
                        RecorderState.Status.PAUSED -> "En pausa"
                        RecorderState.Status.STARTING -> "Iniciando…"
                        RecorderState.Status.STOPPING -> "Guardando…"
                        else -> "Grabando"
                    }, icon = Icons.Default.FiberManualRecord, accent = TurboColors.Bad) {
                        Text(formatDuration(state.elapsedMs), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        state.config?.let { InfoRow("Configuración", "${it.width}×${it.height} · ${it.fps} FPS · ${it.bitrate / 1_000_000} Mbps") }
                        state.outputName?.let { InfoRow("Archivo", it) }
                        state.audioNote?.let { Text(it, color = TurboColors.Warn, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.status == RecorderState.Status.RECORDING) OutlinedButton(onClick = { ScreenRecorderService.command(context, ScreenRecorderService.ACTION_PAUSE) }) { Text("Pausar") }
                            if (state.status == RecorderState.Status.PAUSED) OutlinedButton(onClick = { ScreenRecorderService.command(context, ScreenRecorderService.ACTION_RESUME) }) { Text("Reanudar") }
                            Button(
                                onClick = { ScreenRecorderService.command(context, ScreenRecorderService.ACTION_STOP) },
                                enabled = state.status != RecorderState.Status.STOPPING,
                                colors = ButtonDefaults.buttonColors(containerColor = TurboColors.Bad),
                            ) { Text("Detener") }
                        }
                    }
                }
            }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.lastOutput?.takeIf { !busy }?.let { uri ->
                item {
                    SectionCard(title = "Última grabación guardada", icon = Icons.Default.Videocam, accent = TurboColors.Good) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text("Reproducir") }
                    }
                }
            }
            item {
                SectionCard(title = "Perfil de grabación", icon = Icons.Default.Videocam) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RecorderPreset.entries.forEach { p ->
                            val enabled = p != RecorderPreset.P720_60 || supports60
                            FilterChip(rec.preset == p, { vm.applyPreset(p) }, { Text(p.label + if (!enabled) " (no compatible)" else "") }, enabled = enabled && !busy)
                        }
                    }
                }
            }
            if (rec.preset == RecorderPreset.CUSTOM) {
                item {
                    SectionCard(title = "Configuración personalizada", icon = Icons.Default.Tune) {
                        Text("Resolución (lado corto)")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(480, 720, 1080, 1440, 0).forEach { side ->
                                FilterChip(rec.shortSide == side, { vm.update { it.copy(shortSide = side) } }, { Text(if (side == 0) "Nativa" else "${side}p") })
                            }
                        }
                        Text("FPS")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(24, 30, 48, 60, 90).forEach { f ->
                                val (w, h) = RecorderCapabilities.outputSize(context, rec.shortSide)
                                val ok = RecorderCapabilities.supports(w, h, f) && f <= RecorderCapabilities.maxRefreshRate(context) + 1
                                FilterChip(rec.fps == f, { vm.update { it.copy(fps = f) } }, { Text("$f") }, enabled = ok)
                            }
                        }
                        Text("Bitrate: ${rec.bitrateMbps} Mbps")
                        Slider(rec.bitrateMbps.toFloat(), { v -> vm.update { it.copy(bitrateMbps = v.roundToInt().coerceIn(1, 40)) } }, valueRange = 1f..40f, steps = 38)
                    }
                }
            }
            item {
                SectionCard(title = "Audio", icon = Icons.Default.Mic) {
                    Row {
                        Text("Audio interno (juegos y multimedia)", Modifier.weight(1f))
                        Switch(rec.internalAudio, { v -> vm.update { it.copy(internalAudio = v) } }, enabled = !busy)
                    }
                    Row {
                        Text("Micrófono", Modifier.weight(1f))
                        Switch(rec.microphone, { v -> vm.update { it.copy(microphone = v) } }, enabled = !busy)
                    }
                    Text(
                        "El audio interno usa AudioPlaybackCapture: algunas apps impiden su captura y se grabarán en silencio. Ambos requieren el permiso de micrófono.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionCard(title = "Carpeta de destino", icon = Icons.Default.FolderOpen) {
                    val name = rec.folderUri?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() }
                    Text(name ?: "Movies/TurboOptimizer (predeterminada)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { folderLauncher.launch(null) }, enabled = !busy) { Text("Elegir carpeta") }
                        if (rec.folderUri != null) TextButton(onClick = { vm.update { it.copy(folderUri = null) } }) { Text("Usar predeterminada") }
                    }
                    InfoRow("Espacio disponible", formatBytes(RecorderCapabilities.availableBytes()))
                    InfoRow("Tamaño estimado", "${formatBytes(RecorderCapabilities.bytesPerMinute(rec.bitrateMbps, audioWanted))} por minuto")
                }
            }
            item {
                Button(
                    onClick = {
                        val available = RecorderCapabilities.availableBytes()
                        val perMin = RecorderCapabilities.bytesPerMinute(rec.bitrateMbps, audioWanted)
                        val warnings = buildList {
                            snap?.bestTempC?.let { if (it >= 42f) add("Temperatura alta (${formatTemp(it)}): grabar puede calentar más el dispositivo y reducir el rendimiento.") }
                            snap?.let { if (it.batteryPercent in 0..15 && !it.charging) add("Batería baja (${it.batteryPercent} %).") }
                            if (available < perMin * 10) add("Espacio para menos de 10 minutos (${formatBytes(available)} libres).")
                        }
                        precheck = PreCheck(warnings, available, perMin)
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TurboColors.Bad),
                ) { Text("Comenzar a grabar", fontWeight = FontWeight.Bold) }
            }
        }
    }

    precheck?.let { pc ->
        ConfirmDialog(
            title = "Antes de grabar",
            message = buildString {
                append("Temperatura: ${formatTemp(snap?.bestTempC)}\n")
                append("Batería: ${snap?.batteryPercent ?: "?"} %\n")
                append("Espacio libre: ${formatBytes(pc.availableBytes)} (~${if (pc.perMinute > 0) pc.availableBytes / pc.perMinute else 0} min)\n")
                if (pc.warnings.isEmpty()) append("\nTodo correcto.") else append("\n⚠ " + pc.warnings.joinToString("\n⚠ "))
                append("\n\nAndroid te pedirá confirmar qué se va a capturar.")
            },
            confirmText = "Grabar",
            dangerous = pc.warnings.isNotEmpty(),
            onConfirm = { precheck = null; startFlow() },
            onDismiss = { precheck = null },
        )
    }
}
