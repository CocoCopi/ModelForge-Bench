package com.modelforge.bench

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.content.FileProvider
import com.modelforge.bench.source.ModelForgeClient
import com.modelforge.bench.ui.BenchViewModel
import com.modelforge.bench.ui.BenchmarksScreen
import com.modelforge.bench.ui.CompareScreen
import com.modelforge.bench.ui.HistoryScreen
import com.modelforge.bench.ui.LiveScreen
import com.modelforge.bench.ui.ModelsScreen
import com.modelforge.bench.ui.ResultScreen
import com.modelforge.bench.ui.Screen
import com.modelforge.bench.ui.SettingsScreen
import com.modelforge.bench.ui.SetupScreen
import com.modelforge.bench.ui.theme.BenchTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: BenchViewModel by viewModels()

    private val forgePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refreshForge() }

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* A denied permission only hides the notification; the run still works. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BenchTheme {
                BenchRoot(vm, ::shareReports, ::requestForgePermission)
            }
        }
        maybeAskNotifications()
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
        vm.refresh()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshForge()
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestForgePermission() {
        forgePermission.launch(ModelForgeClient.PERMISSION)
    }

    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        vm.importFile(uri)
    }

    private fun shareReports(runId: String, kind: String) {
        vm.generateReports(runId) { files ->
            if (files == null) {
                vm.reportError("The run could not be read from disk.")
                return@generateReports
            }
            val selected: List<File> = when (kind) {
                "html" -> listOfNotNull(files.html)
                "pdf" -> listOfNotNull(files.pdf)
                "csv" -> listOfNotNull(files.csv)
                "json" -> listOf(files.json)
                else -> files.all
            }
            if (selected.isEmpty()) {
                vm.reportError("No report of that type could be generated on this device.")
                return@generateReports
            }
            val uris = selected.map {
                FileProvider.getUriForFile(this, "$packageName.files", it)
            }
            val share = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeOf(selected.first())
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            runCatching {
                startActivity(Intent.createChooser(share, "Share ModelForge Bench report"))
            }.onFailure {
                vm.reportError("No app on this device can receive the report.")
            }
        }
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "html" -> "text/html"
        "pdf" -> "application/pdf"
        "csv" -> "text/csv"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }
}

@Composable
private fun BenchRoot(
    vm: BenchViewModel,
    onShare: (String, String) -> Unit,
    onRequestForgePermission: () -> Unit
) {
    val state by vm.state.collectAsState()
    val live by vm.live.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current

    val bundlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importFile)
    }
    val weightsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importFile)
    }
    val benchmarkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importBenchmark)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.consumeMessage()
    }

    LaunchedEffect(state.settings.keepScreenOn, live.running) {
        view.keepScreenOn = state.settings.keepScreenOn && live.running
    }

    val screen = state.screen
    val isRoot = screen is Screen.Models || screen is Screen.History ||
        screen is Screen.Compare || screen is Screen.Benchmarks || screen is Screen.Settings

    if (!isRoot) {
        BackHandler { vm.back() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            is Screen.Models -> "ModelForge Bench"
                            is Screen.Setup -> "Run setup"
                            Screen.Live -> "Live evaluation"
                            is Screen.Result -> "Report"
                            Screen.History -> "History"
                            Screen.Compare -> "Compare"
                            Screen.Benchmarks -> "Benchmarks"
                            Screen.Settings -> "Settings"
                        }
                    )
                },
                navigationIcon = {
                    if (!isRoot) {
                        IconButton(onClick = { vm.back() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (isRoot) {
                NavigationBar {
                    NavigationBarItem(
                        selected = screen is Screen.Models,
                        onClick = { vm.open(Screen.Models) },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Models") }
                    )
                    NavigationBarItem(
                        selected = screen is Screen.History,
                        onClick = { vm.open(Screen.History) },
                        icon = { Icon(Icons.Filled.List, contentDescription = null) },
                        label = { Text("History") }
                    )
                    NavigationBarItem(
                        selected = screen is Screen.Compare,
                        onClick = { vm.open(Screen.Compare) },
                        icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                        label = { Text("Compare") }
                    )
                    NavigationBarItem(
                        selected = screen is Screen.Benchmarks,
                        onClick = { vm.open(Screen.Benchmarks) },
                        icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                        label = { Text("Packs") }
                    )
                    NavigationBarItem(
                        selected = screen is Screen.Settings,
                        onClick = { vm.open(Screen.Settings) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val current = screen) {
                is Screen.Models -> ModelsScreen(
                    state = state,
                    onOpen = vm::open,
                    onPickBundle = { bundlePicker.launch(arrayOf("*/*")) },
                    onPickWeights = { weightsPicker.launch(arrayOf("*/*")) },
                    onImportForge = vm::importFromModelForge,
                    onRequestForgePermission = onRequestForgePermission,
                    onRefreshForge = vm::refreshForge,
                    onDelete = vm::deleteModel
                )
                is Screen.Setup -> {
                    val model = state.model(current.modelId)
                    if (model == null) {
                        LaunchedEffect(Unit) { vm.open(Screen.Models) }
                    } else {
                        SetupScreen(
                            model = model,
                            settings = state.settings,
                            packs = state.packs,
                            onStart = { mode, packId -> vm.startRun(model.id, mode, packId) },
                            onPickBenchmark = { benchmarkPicker.launch(arrayOf("*/*")) }
                        )
                    }
                }
                Screen.Live -> LiveScreen(
                    live = live,
                    onCancel = vm::cancelRun,
                    onOpenHistory = { vm.open(Screen.History) }
                )
                is Screen.Result -> {
                    val run = state.run(current.runId)
                    if (run == null) {
                        LaunchedEffect(Unit) { vm.open(Screen.History) }
                    } else {
                        ResultScreen(
                            run = run,
                            onShare = { kind -> onShare(run.id, kind) },
                            onDelete = { vm.deleteRun(run) },
                            onHistory = { vm.open(Screen.History) }
                        )
                    }
                }
                Screen.History -> HistoryScreen(
                    state = state,
                    onOpen = { vm.open(Screen.Result(it.id)) },
                    onCompare = { vm.open(Screen.Compare) }
                )
                Screen.Compare -> CompareScreen(
                    state = state,
                    onToggle = vm::toggleCompare,
                    onClear = vm::clearCompare,
                    onOpen = { vm.open(Screen.Result(it.id)) }
                )
                Screen.Benchmarks -> BenchmarksScreen(
                    state = state,
                    onImport = { benchmarkPicker.launch(arrayOf("*/*")) },
                    onDelete = vm::deleteBenchmark
                )
                Screen.Settings -> SettingsScreen(
                    state = state,
                    onSettings = vm::replaceSettings,
                    onCost = vm::setCost,
                    onMode = vm::setDefaultMode,
                    onSeed = vm::setSeed
                )
            }
        }
    }
}
