package com.strava.spoof

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed class Screen {
    data object Library : Screen()
    data class Replay(val file: GpxFile) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = GpxFileRepository(this)
        setContent { SpoofApp(repo) }
    }
}

@Composable
private fun SpoofApp(repo: GpxFileRepository) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var screen by remember { mutableStateOf<Screen>(Screen.Library) }
            val files = remember { mutableStateListOf<GpxFile>() }

            LaunchedEffect(Unit) {
                files.clear()
                files.addAll(withContext(Dispatchers.IO) { repo.list() })
            }

            when (val s = screen) {
                is Screen.Library -> LibraryScreen(
                    files = files,
                    onImport = { uri, name ->
                        runCatching {
                            val imported = repo.import(uri, name)
                            files.clear()
                            files.addAll(repo.list())
                            imported
                        }
                    },
                    onDelete = { f ->
                        repo.delete(f)
                        files.clear()
                        files.addAll(repo.list())
                    },
                    onPick = { f -> screen = Screen.Replay(f) },
                )
                is Screen.Replay -> ReplayScreen(
                    file = s.file,
                    onBack = { screen = Screen.Library },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    files: List<GpxFile>,
    onImport: (Uri, String) -> Result<GpxFile>,
    onDelete: (GpxFile) -> Unit,
    onPick: (GpxFile) -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.queryDisplayName(uri) ?: "route.gpx"
        onImport(uri, name)
            .onFailure { e ->
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            .onSuccess {
                Toast.makeText(context, "Imported ${it.name}", Toast.LENGTH_SHORT).show()
            }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringRes(R.string.library_title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                picker.launch(arrayOf("application/gpx+xml", "application/octet-stream", "*/*"))
            }) {
                Icon(Icons.Default.Add, contentDescription = stringRes(R.string.import_gpx))
            }
        },
    ) { padding ->
        if (files.isEmpty()) {
            EmptyState(padding)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files, key = { it.path }) { file ->
                    FileRow(file = file, onPick = { onPick(file) }, onDelete = { onDelete(file) })
                }
            }
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringRes(R.string.empty_library),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileRow(file: GpxFile, onPick: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "%.2f km · %s · %d pts".format(
                        file.distanceKm, file.durationFormatted, file.meta.pointCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onPick) { Text("Open") }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplayScreen(file: GpxFile, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by ServiceState.state.collectAsState()

    var hasLocationPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotifPerm by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var issues by remember { mutableStateOf<List<SetupIssue>>(emptyList()) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPerm = granted
        issues = SetupChecker.missingIssues(context, hasLocationPerm, hasNotifPerm)
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPerm = granted
        issues = SetupChecker.missingIssues(context, hasLocationPerm, hasNotifPerm)
    }

    LaunchedEffect(Unit) {
        issues = SetupChecker.missingIssues(context, hasLocationPerm, hasNotifPerm)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Distance: %.2f km".format(file.distanceKm))
                    Text("Duration: ${file.durationFormatted}")
                    Text("Points: ${file.meta.pointCount}")
                }
            }

            if (issues.isNotEmpty()) {
                IssuesCard(
                    issues = issues,
                    onGrantLocation = {
                        locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                    onGrantNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenDevOptions = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            Toast.makeText(
                                context,
                                "Open Settings → Developer options manually",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    onRecheck = {
                        issues = SetupChecker.missingIssues(context, hasLocationPerm, hasNotifPerm)
                    },
                )
            }

            StatusCard(state = state)

            val running = state is RunState.Running
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = issues.isEmpty(),
                colors = if (running)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors(),
                onClick = {
                    if (running) {
                        MockLocationService.stop(context)
                    } else {
                        MockLocationService.start(context, file.path)
                    }
                },
            ) {
                Text(if (running) stringRes(R.string.stop_spoof) else stringRes(R.string.start_spoof))
            }
        }
    }
}

@Composable
private fun IssuesCard(
    issues: List<SetupIssue>,
    onGrantLocation: () -> Unit,
    onGrantNotification: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onRecheck: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringRes(R.string.setup_dev_options_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            issues.forEach { issue ->
                when (issue) {
                    SetupIssue.NoLocationPermission -> {
                        Text(stringRes(R.string.setup_perm_body))
                        Button(onClick = onGrantLocation) { Text(stringRes(R.string.grant_permission)) }
                    }
                    SetupIssue.NoNotificationPermission -> {
                        Text("Notification permission required for the active-session notice.")
                        Button(onClick = onGrantNotification) { Text(stringRes(R.string.grant_permission)) }
                    }
                    SetupIssue.MockLocationNotAllowed -> {
                        Text(stringRes(R.string.setup_dev_options_body))
                        Button(onClick = onOpenDevOptions) {
                            Text(stringRes(R.string.setup_open_dev_options))
                        }
                        Button(onClick = onRecheck) { Text("I did this — re-check") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: RunState) {
    val container = when (state) {
        is RunState.Running -> MaterialTheme.colorScheme.primaryContainer
        is RunState.Failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (state) {
                is RunState.Idle -> {
                    Text("Status: idle", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap Start, then open Strava and start your run.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is RunState.Running -> {
                    Text(
                        "Status: spoofing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Elapsed: ${fmtTime(state.elapsedSeconds)} / ${fmtTime(state.totalSeconds)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.holdingLastPoint) {
                        Text("Holding last point — stop the Strava run, then stop here.")
                    }
                }
                is RunState.Failed -> {
                    Text("Status: failed", style = MaterialTheme.typography.titleMedium)
                    Text(state.message)
                }
            }
        }
    }
}

private fun fmtTime(total: Long): String {
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun Context.queryDisplayName(uri: Uri): String? {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return c.getString(idx)
        }
    }
    return uri.lastPathSegment
}

@Composable
private fun stringRes(id: Int): String = LocalContext.current.getString(id)
