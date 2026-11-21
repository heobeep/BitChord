package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.sources.mornye.MornyeExtensionManager
import com.music.bitchord.data.sources.mornye.MornyeRegistryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal first-party browser for Mornye download providers. Only registry
 * entries in the download category are shown; metadata-only/lyrics extensions
 * are not yet wired into BitChord's separate metadata and lyrics interfaces.
 */
@Composable
fun MornyeExtensionsDialog(
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<MornyeRegistryEntry>>(emptyList()) }
    var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        runCatching {
            val registry = withContext(Dispatchers.IO) {
                MornyeExtensionManager.refreshRegistry()
            }
            val installed = withContext(Dispatchers.IO) {
                MornyeExtensionManager.installed()
            }
            entries = registry.filter { it.category.equals("download", ignoreCase = true) }
            installedIds = installed.map { it.name }.toSet()
        }.onFailure {
            error = it.message ?: "Could not load the Mornye registry"
        }
        loading = false
    }

    fun install(entry: MornyeRegistryEntry) {
        scope.launch {
            busyId = entry.id
            error = null
            runCatching {
                withContext(Dispatchers.IO) { MornyeExtensionManager.install(entry) }
            }.onSuccess {
                installedIds = installedIds + entry.id
            }.onFailure {
                error = it.message ?: "Could not install this extension"
            }
            busyId = null
        }
    }

    fun remove(entry: MornyeRegistryEntry) {
        scope.launch {
            busyId = entry.id
            error = null
            runCatching {
                withContext(Dispatchers.IO) { MornyeExtensionManager.remove(entry.id) }
            }.onSuccess {
                installedIds = installedIds - entry.id
            }.onFailure {
                error = it.message ?: "Could not remove this extension"
            }
            busyId = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mornye extensions") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Install supported music providers from the official Mornye extension registry.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading extensions…")
                    }
                }
                if (!loading && entries.isEmpty() && error == null) {
                    Text(
                        "No supported download providers were found in the registry.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "v" + entry.version + " · " + entry.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (busyId == entry.id) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(onClick = { install(entry) }) {
                                    Text(if (entry.id in installedIds) "Update" else "Install")
                                }
                                if (entry.id in installedIds) {
                                    TextButton(onClick = { remove(entry) }) {
                                        Text("Remove")
                                    }
                                }
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
