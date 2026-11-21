package com.modelforge.bench.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.modelforge.bench.data.AppSettings
import com.modelforge.bench.data.BenchmarkStore
import com.modelforge.bench.data.InstalledModel
import com.modelforge.bench.data.ModelOrigin
import com.modelforge.bench.data.ModelRepository
import com.modelforge.bench.data.RunStore
import com.modelforge.bench.data.SettingsStore
import com.modelforge.bench.data.StoredPackInfo
import com.modelforge.bench.eval.BenchRun
import com.modelforge.bench.eval.BenchmarkImporter
import com.modelforge.bench.eval.BenchmarkPack
import com.modelforge.bench.eval.CostSettings
import com.modelforge.bench.eval.RunMode
import com.modelforge.bench.report.ReportWriter
import com.modelforge.bench.service.EvalBus
import com.modelforge.bench.service.EvalLive
import com.modelforge.bench.service.EvalService
import com.modelforge.bench.source.ModelForgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Models : Screen
    data class Setup(val modelId: String) : Screen
    data object Live : Screen
    data class Result(val runId: String) : Screen
    data object History : Screen
    data object Compare : Screen
    data object Benchmarks : Screen
    data object Settings : Screen
}

data class UiState(
    val models: List<InstalledModel> = emptyList(),
    val runs: List<BenchRun> = emptyList(),
    val packs: List<StoredPackInfo> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val forgeAvailability: ModelForgeClient.Availability = ModelForgeClient.Availability.NOT_INSTALLED,
    val forgeModels: List<ModelForgeClient.ForgeModel> = emptyList(),
    val importedForgeIds: Set<String> = emptySet(),
    val busy: Boolean = false,
    val message: String? = null,
    val screen: Screen = Screen.Models,
    val compareIds: Set<String> = emptySet()
) {
    fun model(id: String): InstalledModel? = models.firstOrNull { it.id == id }
    fun run(id: String): BenchRun? = runs.firstOrNull { it.id == id }
    fun runsFor(modelId: String): List<BenchRun> =
        runs.filter { it.modelId == modelId && it.status != com.modelforge.bench.eval.RunStatus.RUNNING }

    /** Newest completed run for each model, used by the comparison table. */
    fun latestByModel(): Map<String, BenchRun> =
        runs.filter { it.status == com.modelforge.bench.eval.RunStatus.COMPLETED }
            .groupBy { it.modelId }
            .mapValues { (_, list) -> list.first() }
}

class BenchViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ModelRepository(app)
    private val runStore = RunStore(app)
    private val settingsStore = SettingsStore(app)
    private val benchmarkStore = BenchmarkStore(app)
    private val reportWriter = ReportWriter(app, runStore)
    private val forge = ModelForgeClient(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val live: StateFlow<EvalLive> = EvalBus.state

    private var handledFinishedRun: String? = null

    init {
        refresh()
        viewModelScope.launch {
            EvalBus.state.collect { event ->
                val finished = event.finishedRunId
                if (finished != null && finished != handledFinishedRun) {
                    handledFinishedRun = finished
                    refreshRuns()
                    _state.update { it.copy(screen = Screen.Result(finished)) }
                    EvalBus.consume()
                }
                val error = event.error
                if (error != null) {
                    _state.update { it.copy(message = error) }
                    EvalBus.consume()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                Loaded(
                    models = repository.models(),
                    runs = runStore.runs(),
                    packs = benchmarkStore.list(),
                    settings = settingsStore.load()
                )
            }
            val availability = withContext(Dispatchers.IO) { forge.availability() }
            val forgeModels = if (availability == ModelForgeClient.Availability.READY) {
                withContext(Dispatchers.IO) { runCatching { forge.list() }.getOrDefault(emptyList()) }
            } else {
                emptyList()
            }
            _state.update { current ->
                current.copy(
                    models = loaded.models,
                    runs = loaded.runs,
                    packs = loaded.packs,
                    settings = loaded.settings,
                    forgeAvailability = availability,
                    forgeModels = forgeModels,
                    importedForgeIds = loaded.models
                        .filter { it.origin == ModelOrigin.MODELFORGE && it.sourceId.isNotBlank() }
                        .map { it.sourceId }
                        .toSet()
                )
            }
        }
    }

    private data class Loaded(
        val models: List<InstalledModel>,
        val runs: List<BenchRun>,
        val packs: List<StoredPackInfo>,
        val settings: AppSettings
    )

    private fun refreshRuns() {
        viewModelScope.launch {
            val runs = withContext(Dispatchers.IO) { runStore.runs() }
            _state.update { it.copy(runs = runs) }
        }
    }

    fun open(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    fun back() {
        val current = _state.value.screen
        val target = when (current) {
            is Screen.Setup -> Screen.Models
            is Screen.Result -> Screen.History
            Screen.Live -> Screen.Models
            else -> Screen.Models
        }
        _state.update { it.copy(screen = target) }
    }

    fun reportError(message: String) {
        _state.update { it.copy(message = message) }
    }

    fun replaceSettings(settings: AppSettings) {
        updateSettings { settings }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun refreshForge() {
        refresh()
    }

    // ------------------------------------------------------------------
    // Importing
    // ------------------------------------------------------------------

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "Importing…") }
            try {
                val name = displayName(getApplication<Application>().contentResolver, uri)
                val isBundle = name.endsWith(".zip", true) || name.endsWith(".mfbench", true)
                val model = withContext(Dispatchers.IO) {
                    if (isBundle) {
                        val incoming = File(getApplication<Application>().cacheDir, "incoming").apply { mkdirs() }
                        val copy = File(incoming, name.substringAfterLast('/').ifBlank { "model.mfbench" })
                        getApplication<Application>().contentResolver.openInputStream(uri)
                            ?.use { input -> copy.outputStream().use { output -> input.copyTo(output) } }
                            ?: throw IllegalStateException("The selected file could not be opened.")
                        try {
                            repository.importBundle(copy, ModelOrigin.IMPORT)
                        } finally {
                            copy.delete()
                        }
                    } else {
                        val input = getApplication<Application>().contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("The selected file could not be opened.")
                        repository.importWeights(input, name, ModelOrigin.IMPORT)
                    }
                }
                refresh()
                _state.update {
                    it.copy(busy = false, message = "Imported ${model.name}.")
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(busy = false, message = t.message ?: "The model could not be imported.")
                }
            }
        }
    }

    fun importFromModelForge(model: ModelForgeClient.ForgeModel) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "Copying ${model.name} from ModelForge…") }
            try {
                val imported = withContext(Dispatchers.IO) {
                    val checkpoint = forge.openCheckpoint(model.id, "best")
                        ?: forge.openCheckpoint(model.id, "last")
                    if (checkpoint != null) {
                        checkpoint.use { weights ->
                            repository.importWeights(
                                input = weights,
                                displayName = model.name + ".mfck",
                                origin = ModelOrigin.MODELFORGE,
                                sourceId = model.id,
                                tokenizer = forge.openTokenizer(model.id),
                                manifest = forge.openManifest(model.id)
                            )
                        }
                    } else if (model.packedBits > 0) {
                        val packed = forge.openPacked(model.id, model.packedBits)
                            ?: throw IllegalStateException(
                                "ModelForge reports a packed model but did not return it. " +
                                    "Re-export the model in ModelForge and try again."
                            )
                        packed.use { weights ->
                            repository.importWeights(
                                input = weights,
                                displayName = model.name + ".mfpk",
                                origin = ModelOrigin.MODELFORGE,
                                sourceId = model.id,
                                tokenizer = forge.openTokenizer(model.id),
                                manifest = forge.openManifest(model.id)
                            )
                        }
                    } else {
                        throw IllegalStateException(
                            "ModelForge has no checkpoint or packed file for this model yet."
                        )
                    }
                }
                refresh()
                _state.update {
                    it.copy(busy = false, message = "Copied ${imported.displayKind.lowercase()} for ${imported.name}.")
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(busy = false, message = t.message ?: "The model could not be copied.")
                }
            }
        }
    }

    fun deleteModel(model: InstalledModel) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { repository.delete(model.id) }
            refresh()
            _state.update {
                it.copy(
                    message = if (ok) "Deleted ${model.name}." else "The model could not be deleted."
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Runs
    // ------------------------------------------------------------------

    fun startRun(modelId: String, mode: RunMode, packId: String?) {
        val model = _state.value.model(modelId) ?: return
        EvalBus.start(model.id, model.name)
        EvalService.start(getApplication(), modelId, mode, packId)
        _state.update { it.copy(screen = Screen.Live) }
    }

    fun cancelRun() {
        EvalService.cancel(getApplication())
    }

    fun deleteRun(run: BenchRun) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runStore.delete(run.id) }
            refreshRuns()
            _state.update { it.copy(screen = Screen.History, message = "Run deleted.") }
        }
    }

    fun generateReports(runId: String, onReady: (ReportWriter.Files?) -> Unit) {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                runStore.load(runId)?.let { run ->
                    runCatching { reportWriter.writeAll(run) }.getOrNull()
                }
            }
            onReady(files)
        }
    }

    // ------------------------------------------------------------------
    // Benchmarks
    // ------------------------------------------------------------------

    fun importBenchmark(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "Importing benchmark…") }
            try {
                val name = displayName(getApplication<Application>().contentResolver, uri)
                val pack = withContext(Dispatchers.IO) {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("The selected file could not be opened.")
                    BenchmarkImporter.import(text, name)
                }
                withContext(Dispatchers.IO) { benchmarkStore.save(pack) }
                refresh()
                _state.update {
                    it.copy(busy = false, message = "Imported ${pack.items.size} questions.")
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(busy = false, message = t.message ?: "The benchmark could not be imported.")
                }
            }
        }
    }

    fun deleteBenchmark(pack: StoredPackInfo) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { benchmarkStore.delete(pack.id) }
            refresh()
            _state.update { it.copy(message = "Deleted ${pack.name}.") }
        }
    }

    fun loadPack(id: String): BenchmarkPack? = runCatching { benchmarkStore.load(id) }.getOrNull()

    // ------------------------------------------------------------------
    // Settings and comparison
    // ------------------------------------------------------------------

    fun setDefaultMode(mode: RunMode) {
        updateSettings { it.copy(defaultMode = mode) }
    }

    fun setSeed(seed: Long) {
        updateSettings { it.copy(seed = seed) }
    }

    fun setKeepScreenOn(value: Boolean) {
        updateSettings { it.copy(keepScreenOn = value) }
    }

    fun setCost(cost: CostSettings) {
        updateSettings { it.copy(cost = cost) }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value.settings)
        _state.update { it.copy(settings = next) }
        settingsStore.save(next)
    }

    fun toggleCompare(modelId: String) {
        _state.update { current ->
            val next = current.compareIds.toMutableSet()
            if (!next.add(modelId)) next.remove(modelId)
            current.copy(compareIds = next)
        }
    }

    fun clearCompare() {
        _state.update { it.copy(compareIds = emptySet()) }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "model.mfck"
    }
}
