package com.gpssimulator.app

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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.File

private sealed class Screen {
    data object Library : Screen()
    data object Tools : Screen()
    data object AddTime : Screen()
    data class Replay(val file: GpxFile, val localPath: String) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authRepo = AuthRepository(this)
        val api = ApiClient(BuildConfig.API_BASE_URL, authRepo)
        val repo = GpxRepository(applicationContext, api)
        setContent { SimulatorApp(repo, authRepo) }
    }
}

@Composable
private fun SimulatorApp(repo: GpxRepository, authRepo: AuthRepository) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var update by remember { mutableStateOf<UpdateChecker.LatestRelease?>(null) }

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AuthGate(authRepo) { user ->
                MainContent(
                    repo = repo,
                    user = user,
                    onSignOut = { scope.launch { authRepo.signOut() } },
                )
            }
            update?.let { release ->
                UpdateDialog(
                    release = release,
                    onDownload = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        update = null
                    },
                    onLater = {
                        UpdateChecker.markDismissed(context, release.version)
                        update = null
                    },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        // Restore silent-refresh: SignedIn state from prefs but no in-memory token.
        val state = authRepo.state.value
        if (state is AuthState.SignedIn && authRepo.currentIdToken() == null) {
            runCatching { authRepo.silentRefresh() }
                .onFailure { authRepo.signOut() }
        }
    }

    LaunchedEffect(Unit) {
        update = runCatching { UpdateChecker.checkOnce(context) }.getOrNull()
    }
}

@Composable
private fun UpdateDialog(
    release: UpdateChecker.LatestRelease,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    val notes = release.notes.take(600).let { if (release.notes.length > 600) "$it…" else it }
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("${stringRes(R.string.update_title)} — v${release.version}") },
        text = {
            Column {
                if (release.name.isNotBlank() && release.name != release.version) {
                    Text(
                        release.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (notes.isNotBlank()) {
                    Text(notes, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) { Text(stringRes(R.string.update_download)) }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringRes(R.string.update_later)) }
        },
    )
}

@Composable
private fun AuthGate(authRepo: AuthRepository, content: @Composable (AuthState.SignedIn) -> Unit) {
    val state by authRepo.state.collectAsState()
    when (val s = state) {
        is AuthState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is AuthState.SignedOut -> LoginScreen(authRepo)
        is AuthState.SignedIn -> content(s)
    }
}

@Composable
private fun MainContent(
    repo: GpxRepository,
    user: AuthState.SignedIn,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    val files = remember { mutableStateListOf<GpxFile>() }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var noTimeNotice by remember { mutableStateOf<GpxFile?>(null) }

    suspend fun refresh() {
        loading = true
        loadError = null
        runCatching { repo.list() }
            .onSuccess { fresh ->
                files.clear(); files.addAll(fresh)
            }
            .onFailure { loadError = it.message ?: "Failed to load files." }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    noTimeNotice?.let { file ->
        AlertDialog(
            onDismissRequest = { noTimeNotice = null },
            title = { Text(stringRes(R.string.no_time_dialog_title)) },
            text = { Text(stringRes(R.string.no_time_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    noTimeNotice = null
                    screen = Screen.AddTime
                }) { Text(stringRes(R.string.no_time_dialog_open_tools)) }
            },
            dismissButton = {
                TextButton(onClick = { noTimeNotice = null }) {
                    Text(stringRes(R.string.no_time_dialog_dismiss))
                }
            },
        )
    }

    when (val s = screen) {
        is Screen.Library -> LibraryScreen(
            user = user,
            files = files.filter { it.hasTime },
            loading = loading,
            errorMessage = loadError,
            onRefresh = { scope.launch { refresh() } },
            onImport = { uri, name ->
                scope.launch {
                    runCatching { repo.upload(uri, name) }
                        .onSuccess {
                            Toast.makeText(context, "Uploaded ${it.name}", Toast.LENGTH_SHORT).show()
                            refresh()
                            if (!it.hasTime) noTimeNotice = it
                        }
                        .onFailure {
                            Toast.makeText(context, "Upload failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                }
            },
            onRename = { file, newName ->
                scope.launch {
                    runCatching { repo.rename(file, newName) }
                        .onSuccess { refresh() }
                        .onFailure {
                            Toast.makeText(context, "Rename failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                }
            },
            onDelete = { file ->
                scope.launch {
                    runCatching { repo.delete(file) }
                        .onSuccess { refresh() }
                        .onFailure {
                            Toast.makeText(context, "Delete failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                }
            },
            onPick = { file ->
                scope.launch {
                    val cached = runCatching { repo.ensureCached(file) }
                    cached.onSuccess { local ->
                        screen = Screen.Replay(file, local.absolutePath)
                    }.onFailure {
                        Toast.makeText(context, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onOpenTools = { screen = Screen.Tools },
            onSignOut = onSignOut,
        )
        is Screen.Tools -> ToolsScreen(
            onBack = { screen = Screen.Library },
            onOpenAddTime = { screen = Screen.AddTime },
        )
        is Screen.AddTime -> AddTimeScreen(
            repo = repo,
            files = files,
            onBack = { screen = Screen.Tools },
            onDone = {
                scope.launch { refresh() }
                screen = Screen.Library
            },
        )
        is Screen.Replay -> ReplayScreen(
            file = s.file,
            localPath = s.localPath,
            onBack = { screen = Screen.Library },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    user: AuthState.SignedIn,
    files: List<GpxFile>,
    loading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onImport: (Uri, String) -> Unit,
    onRename: (GpxFile, String) -> Unit,
    onDelete: (GpxFile) -> Unit,
    onPick: (GpxFile) -> Unit,
    onOpenTools: () -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    var renameTarget by remember { mutableStateOf<GpxFile?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.queryDisplayName(uri) ?: "route.gpx"
        onImport(uri, name)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringRes(R.string.library_title))
                        val subtitle = user.displayName ?: user.email ?: user.sub
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenTools) {
                        Icon(Icons.Default.Build, contentDescription = stringRes(R.string.tools_title))
                    }
                    IconButton(onClick = onRefresh, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                picker.launch(arrayOf("application/gpx+xml", "application/octet-stream", "*/*"))
            }) {
                Icon(Icons.Default.Add, contentDescription = stringRes(R.string.import_gpx))
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && files.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                errorMessage != null && files.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRefresh) { Text("Retry") }
                    }
                }

                files.isEmpty() -> EmptyState()

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(files, key = { it.id }) { file ->
                        FileRow(
                            file = file,
                            onPick = { onPick(file) },
                            onRename = { renameTarget = file },
                            onDelete = { onDelete(file) },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { file ->
        RenameDialog(
            currentName = file.name,
            onCancel = { renameTarget = null },
            onConfirm = { newName ->
                onRename(file, newName)
                renameTarget = null
            },
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
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
private fun FileRow(
    file: GpxFile,
    onPick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
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
                        file.distanceKm, file.durationFormatted, file.pointCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "Rename")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onPick) { Text("Open") }
        }
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("File name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.trim().isNotEmpty() && text.trim() != currentName,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplayScreen(file: GpxFile, localPath: String, onBack: () -> Unit) {
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
                    Text("Points: ${file.pointCount}")
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
                enabled = issues.isEmpty() && File(localPath).exists(),
                colors = if (running)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors(),
                onClick = {
                    if (running) {
                        MockLocationService.stop(context)
                    } else {
                        MockLocationService.start(context, localPath)
                    }
                },
            ) {
                Text(if (running) stringRes(R.string.stop_sim) else stringRes(R.string.start_sim))
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
                        "Tap Start, then open your activity-tracking app and start recording.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is RunState.Running -> {
                    Text(
                        "Status: simulating",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Elapsed: ${fmtTime(state.elapsedSeconds)} / ${fmtTime(state.totalSeconds)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.holdingLastPoint) {
                        Text("Holding last point — stop the recording in your tracking app, then stop here.")
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
