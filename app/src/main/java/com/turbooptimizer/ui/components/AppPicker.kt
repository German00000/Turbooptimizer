package com.turbooptimizer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.turbooptimizer.apps.AppEntry

/**
 * Selector de aplicaciones (múltiple o simple).
 * @param disabledReason devuelve un motivo si una app no puede elegirse (p. ej. crítica).
 */
@Composable
fun AppPickerDialog(
    title: String,
    apps: List<AppEntry>,
    initial: Set<String>,
    multi: Boolean = true,
    gamesFirst: Boolean = false,
    disabledReason: (String) -> String? = { null },
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val filtered = remember(apps, query, showSystem, gamesFirst) {
        apps.asSequence()
            .filter { showSystem || !it.isSystem || it.isUpdatedSystem || it.packageName in initial }
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .sortedWith(compareByDescending<AppEntry> { gamesFirst && it.isGame }.thenBy { it.label.lowercase() })
            .toList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("Mostrar apps del sistema") })
                LazyColumn(Modifier.height(380.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        val reason = disabledReason(app.packageName)
                        val isSel = app.packageName in selected
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = reason == null) {
                                selected = if (multi) { if (isSel) selected - app.packageName else selected + app.packageName } else setOf(app.packageName)
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.packageName, 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label + if (app.isGame) "  🎮" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    reason ?: app.packageName, style = MaterialTheme.typography.bodySmall,
                                    color = if (reason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (multi) Checkbox(isSel, null, enabled = reason == null) else RadioButton(isSel, null, enabled = reason == null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Aceptar (${selected.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
