package com.gpssimulator.app

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTimeScreen(
    repo: GpxRepository,
    files: List<GpxFile>,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val untimed = remember(files) { files.filter { !it.hasTime } }
    var selectedId by remember { mutableStateOf(untimed.firstOrNull()?.id) }
    var startText by remember {
        mutableStateOf(
            DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
            ),
        )
    }
    var paceText by remember { mutableStateOf("5:30") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(untimed) {
        if (selectedId != null && untimed.none { it.id == selectedId }) {
            selectedId = untimed.firstOrNull()?.id
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ctx.getString(R.string.tool_add_time)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (untimed.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ctx.getString(R.string.add_time_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                ctx.getString(R.string.add_time_pick_file),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(untimed, key = { it.id }) { f ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (f.id == selectedId)
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else CardDefaults.cardColors(),
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = f.id == selectedId,
                                onClick = { selectedId = f.id },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, fontWeight = FontWeight.Medium)
                                Text(
                                    "%.2f km · %d pts".format(f.distanceKm, f.pointCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = startText,
                onValueChange = { startText = it },
                label = { Text(ctx.getString(R.string.add_time_start)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = paceText,
                onValueChange = { paceText = it },
                label = { Text(ctx.getString(R.string.add_time_pace)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            errorText?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && selectedId != null,
                onClick = {
                    val target = untimed.firstOrNull { it.id == selectedId } ?: return@Button
                    val start = runCatching { Instant.parse(startText.trim()) }
                        .getOrElse {
                            errorText = "Bad start datetime: ${it.message}"
                            return@Button
                        }
                    val paceSec = runCatching { parsePaceMmSs(paceText) }
                        .getOrElse {
                            errorText = it.message
                            return@Button
                        }
                    errorText = null
                    busy = true
                    scope.launch {
                        runCatching {
                            val raw = repo.downloadBytes(target)
                            val timed = withContext(Dispatchers.Default) {
                                GpxTimeAdder.addTimes(raw, start, paceSec)
                            }
                            val newName = deriveTimedName(target.name)
                            repo.uploadBytes(timed.bytes, newName)
                        }.onSuccess {
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.add_time_done),
                                Toast.LENGTH_SHORT,
                            ).show()
                            busy = false
                            onDone()
                        }.onFailure {
                            busy = false
                            errorText = it.message ?: "Failed."
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
                Text(ctx.getString(R.string.add_time_apply))
            }
        }
    }
}

private fun deriveTimedName(original: String): String {
    val base = original.removeSuffix(".gpx").removeSuffix(".GPX")
    return "${base}_timed.gpx"
}
