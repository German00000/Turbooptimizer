package com.turbooptimizer.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.turbooptimizer.AppContainer
import com.turbooptimizer.device.InfoSection
import com.turbooptimizer.ui.components.InfoRow
import com.turbooptimizer.ui.components.SectionCard
import com.turbooptimizer.ui.components.TurboScaffold
import com.turbooptimizer.ui.components.list
import com.turbooptimizer.ui.navigation.turboViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceInfoViewModel(private val c: AppContainer) : ViewModel() {
    private val _sections = MutableStateFlow<List<InfoSection>?>(null)
    val sections = _sections.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _sections.value = null
        val snap = runCatching { c.monitor.snapshot() }.getOrNull()
        _sections.value = runCatching { c.deviceInfo.sections(snap) }.getOrDefault(emptyList())
    }
}

@Composable
fun DeviceInfoScreen(onBack: () -> Unit) {
    val vm = turboViewModel { DeviceInfoViewModel(it) }
    val sections by vm.sections.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var collapsed by remember { mutableStateOf(setOf("Sensores", "Características")) }

    TurboScaffold(
        "Información del dispositivo", onBack,
        actions = {
            IconButton(onClick = { vm.load() }) { Icon(Icons.Default.Refresh, "Actualizar") }
            IconButton(onClick = {
                // Solo al portapapeles local, a petición del usuario. Nada se envía a servidores.
                val text = sections.orEmpty().joinToString("\n\n") { s -> "## ${s.title}\n" + s.items.joinToString("\n") { "${it.first}: ${it.second}" } }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("TurboOptimizer", text))
                Toast.makeText(context, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.ContentCopy, "Copiar") }
        },
    ) { padding ->
        val list = sections
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TurboScaffold
        }
        LazyColumn(contentPadding = padding.list(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Datos obtenidos con APIs públicas de Android. No se recopilan identificadores personales ni se envía información a ningún servidor.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(list, key = { it.title }) { section ->
                val key = section.title.substringBefore(" (")
                val isCollapsed = collapsed.any { key.startsWith(it) }
                SectionCard(
                    title = section.title,
                    trailing = { Icon(if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, null) },
                    onClick = { collapsed = if (isCollapsed) collapsed.filterNot { key.startsWith(it) }.toSet() else collapsed + key },
                ) {
                    if (!isCollapsed) section.items.forEach { (k, v) -> InfoRow(k, v) }
                }
            }
        }
    }
}
