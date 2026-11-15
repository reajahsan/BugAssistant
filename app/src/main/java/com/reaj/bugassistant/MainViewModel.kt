package com.reaj.bugassistant

import android.app.ActivityManager
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import androidx.core.content.edit
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reaj.bugassistant.diagnostics.BugReportParser
import com.reaj.bugassistant.diagnostics.DeviceCollector
import com.reaj.bugassistant.diagnostics.DeviceReport
import com.reaj.bugassistant.diagnostics.ReportDigest
import com.reaj.bugassistant.llama.GenEvent
import com.reaj.bugassistant.llama.LlamaEngine
import com.reaj.bugassistant.model.DeviceResources
import com.reaj.bugassistant.model.ModelChoice
import com.reaj.bugassistant.model.ModelDownloader
import com.reaj.bugassistant.model.ModelSelector
import com.reaj.bugassistant.storage.ReportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = LlamaEngine()
    private val downloader = ModelDownloader()
    private val collector = DeviceCollector(app)
    private val repository = ReportRepository.get(app)
    private var job: Job? = null
    /** Set after each scan; the summary starts as soon as both the report and the model are ready. */
    private var analyzeWhenReady = false

    // App-specific storage: no permission needed, and llama.cpp can mmap it.
    // /sdcard/Android/data/com.reaj.bugassistant/files/<model.fileName>
    private val modelDir: File = app.getExternalFilesDir(null) ?: app.filesDir
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val appFilter = MutableStateFlow(prefs.getString(KEY_APP_FILTER, null))

    var state by mutableStateOf(MainUiState(modelStatus = ModelStatus.Starting, appFilter = appFilter.value)); private set

    init {
        // Both run automatically when the app opens; the scan does not need the model.
        prepareModel()
        scan()
        // History is optional: the current report still works without the database.
        viewModelScope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            appFilter.flatMapLatest { repository.history(it) }
                .catch { }
                .collect { state = state.copy(history = it) }
        }
        viewModelScope.launch {
            repository.apps.catch { }.collect { state = state.copy(savedApps = it) }
        }
    }

    /**
     * Narrows the issue list, counts, AI summary, history and share text to one app package
     * (null = all apps). Remembered across launches, so field devices can stay focused on your app.
     */
    fun setAppFilter(app: String?) {
        val value = app?.trim()?.ifEmpty { null }
        if (value == state.appFilter) return
        prefs.edit { putString(KEY_APP_FILTER, value) }
        appFilter.value = value
        state = state.copy(appFilter = value, summary = "", genStats = GenStats.None)
        viewModelScope.launch {
            stopGeneration()
            loadStoredSummaryOrAnalyze()
        }
    }

    /** Collects crashes, ANRs and memory data from the device, then summarizes them when possible. */
    fun scan() {
        if (!state.canScan) return
        state = state.copy(scan = ScanStatus.Scanning, summary = "", genStats = GenStats.None, currentReportId = null)
        viewModelScope.launch {
            stopGeneration()
            try {
                val report = collector.collect()
                // Only scans that found something are worth keeping.
                showReport(report, save = report.hasIssues)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(scan = ScanStatus.Failed(e.message))
            }
        }
    }

    /**
     * Imports an Android bug report (zip or txt) from the file picker or a share. When several files
     * are shared at once (Android shares the bug report together with a screenshot), the bug report is picked.
     */
    fun importBugReport(uris: List<Uri>) {
        if (uris.isEmpty() || state.scanning) return
        state = state.copy(scan = ScanStatus.Importing("", null), summary = "", genStats = GenStats.None, currentReportId = null)
        viewModelScope.launch {
            stopGeneration()
            val resolver = getApplication<Application>().contentResolver
            val files = withContext(Dispatchers.IO) { uris.map { it to queryFile(resolver, it) } }
            val (uri, file) = files.firstOrNull { it.second.name.endsWith(".zip", ignoreCase = true) }
                ?: files.firstOrNull { it.second.name.endsWith(".txt", ignoreCase = true) }
                ?: files.firstOrNull { resolver.getType(it.first)?.startsWith("image/") != true }
                ?: files.first()
            state = state.copy(scan = ScanStatus.Importing(file.name, if (file.size != null) 0 else null))

            val bytesRead = AtomicLong()
            val progress = file.size?.let { size ->
                launch {
                    while (true) {
                        delay(PROGRESS_INTERVAL_MS)
                        val pct = (bytesRead.get() * 100 / size).toInt().coerceIn(0, 99)
                        state = state.copy(scan = ScanStatus.Importing(file.name, pct))
                    }
                }
            }
            try {
                val report = withContext(Dispatchers.IO) {
                    val input = resolver.openInputStream(uri) ?: error("Cannot open ${file.name}")
                    CountingInputStream(input, bytesRead).use { BugReportParser.parse(it, file.name) }
                }
                progress?.cancel()
                showReport(report, save = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BugReportParser.NotABugReportException) {
                state = state.copy(scan = ScanStatus.Failed(e.message, import = true, notABugReport = true))
            } catch (e: Exception) {
                state = state.copy(scan = ScanStatus.Failed(e.message, import = true))
            } finally {
                progress?.cancel()
            }
        }
    }

    /** Shows a saved report, with its stored summary if it has one. */
    fun openSaved(id: Long) {
        if (state.scanning) return
        viewModelScope.launch {
            stopGeneration()
            val saved = try {
                repository.load(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            state = state.copy(
                scan = ScanStatus.Done(saved.report),
                summary = "",
                genStats = GenStats.None,
                currentReportId = saved.id,
            )
            loadStoredSummaryOrAnalyze()
        }
    }

    fun deleteSaved(id: Long) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }
            if (state.currentReportId == id) state = state.copy(currentReportId = null)
        }
    }

    /** Suggested file name for [exportCsv], e.g. `bugassistant-com.acme.field-20261112-1203.csv`. */
    fun exportFileName(): String {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US))
        return "bugassistant-${state.appFilter ?: "all-apps"}-$stamp.csv"
    }

    /** Writes saved issues (for the current app filter) as CSV to [uri], a file the user created in a folder they chose. */
    fun exportCsv(uri: Uri) {
        if (state.export == ExportStatus.Running) return
        val app = state.appFilter
        state = state.copy(export = ExportStatus.Running)
        viewModelScope.launch {
            state = state.copy(
                export = try {
                    val resolver = getApplication<Application>().contentResolver
                    val rows = withContext(Dispatchers.IO) {
                        resolver.openOutputStream(uri, "wt")?.use { repository.exportCsv(app, it) }
                    } ?: error("Cannot write to the selected file")
                    ExportStatus.Done(withContext(Dispatchers.IO) { queryFile(resolver, uri).name }, rows)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ExportStatus.Failed(e.message)
                },
            )
        }
    }

    /** Plain-text version of the report on screen (with the app filter applied), for sharing with developers. */
    fun shareText(): String? {
        val report = state.visibleReport ?: return null
        return ReportDigest.shareText(report, state.summary.ifBlank { null }, state.model?.name)
    }

    /** Puts [report] on screen, saves it if asked, and starts the summary unless one is already stored. */
    private suspend fun showReport(report: DeviceReport, save: Boolean) {
        val saved = if (save) {
            try {
                repository.save(report)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        state = state.copy(
            scan = ScanStatus.Done(report),
            summary = "",
            genStats = GenStats.None,
            currentReportId = saved?.id,
        )
        loadStoredSummaryOrAnalyze()
    }

    /** Shows the saved summary for the current report and app filter, or generates one. */
    private suspend fun loadStoredSummaryOrAnalyze() {
        val id = state.currentReportId
        val stored = if (id != null) {
            try {
                repository.summary(id, state.appFilter)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (!stored.isNullOrBlank()) {
            state = state.copy(summary = stored, genStats = GenStats.None)
            analyzeWhenReady = false
        } else {
            analyzeWhenReady = true
            maybeAutoAnalyze()
        }
    }

    private suspend fun stopGeneration() {
        job?.cancelAndJoin()
        job = null
    }

    private data class FileInfo(val name: String, val size: Long?)

    private fun queryFile(resolver: ContentResolver, uri: Uri): FileInfo {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si).takeIf { it > 0 }
                }
            }
        }
        return FileInfo(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "bugreport", size)
    }

    private class CountingInputStream(input: InputStream, private val count: AtomicLong) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) count.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count.addAndGet(it.toLong()) }
        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count.addAndGet(it) }
    }

    /** Asks the on-device model for a triage summary of the current report (for the selected app, if any). */
    fun analyze() {
        val report = state.visibleReport ?: return
        if (!state.canAnalyze || state.generating) return
        analyzeWhenReady = false
        generate(ReportDigest.build(report))
    }

    private fun maybeAutoAnalyze() {
        if (analyzeWhenReady && state.canAnalyze) analyze()
    }

    /** Picks the model the device can afford, downloads it if missing, then loads it. Also used by Retry. */
    fun prepareModel() {
        viewModelScope.launch {
            state = state.copy(busy = true)
            try {
                val choice = withContext(Dispatchers.IO) { ModelSelector.select(readResources()) }
                state = state.copy(
                    model = ModelInfo(choice.spec.displayName, (choice as? ModelChoice.Use)?.reason),
                )
                if (choice is ModelChoice.NotEnoughStorage) {
                    state = state.copy(modelStatus = ModelStatus.NotEnoughStorage(choice.neededBytes))
                    return@launch
                }
                val model = choice.spec
                val modelFile = File(modelDir, model.fileName)
                deleteStaleModels(keep = modelFile)
                if (!modelFile.exists()) {
                    state = state.copy(modelStatus = ModelStatus.Downloading(percent = null))
                    downloader.download(model, modelFile) { pct ->
                        state = state.copy(modelStatus = ModelStatus.Downloading(pct))
                    }
                }
                state = state.copy(modelStatus = ModelStatus.Loading)
                val start = SystemClock.elapsedRealtime()
                engine.load(modelFile.absolutePath)
                state = state.copy(modelStatus = ModelStatus.Ready(SystemClock.elapsedRealtime() - start))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(modelStatus = ModelStatus.Failed(e.message))
            } finally {
                state = state.copy(busy = false)
            }
            maybeAutoAnalyze()
        }
    }

    private fun generate(digest: String) {
        state = state.copy(summary = "", genStats = GenStats.None)
        val reportId = state.currentReportId
        val app = state.appFilter
        job = viewModelScope.launch {
            state = state.copy(busy = true, generating = true)
            val start = SystemClock.elapsedRealtime()
            var firstTokenAt = 0L
            try {
                engine.generate(SYSTEM_PROMPT, digest, maxTokens = MAX_SUMMARY_TOKENS).collect { ev ->
                    when (ev) {
                        is GenEvent.Token -> {
                            if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()
                            state = state.copy(summary = state.summary + ev.text)
                        }
                        is GenEvent.Done -> {
                            if (firstTokenAt == 0L) {
                                state = state.copy(genStats = GenStats.NoOutput)
                                return@collect
                            }
                            val decodeSec = (SystemClock.elapsedRealtime() - firstTokenAt)
                                .coerceAtLeast(1) / 1000.0
                            val tps = (ev.tokens - 1).coerceAtLeast(0) / decodeSec
                            state = state.copy(genStats = GenStats.Done(firstTokenAt - start, ev.tokens, tps))
                            if (reportId != null) {
                                runCatching { repository.saveSummary(reportId, app, state.summary.trim(), state.model?.name) }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                state = state.copy(genStats = GenStats.Stopped)
                throw e
            } catch (e: Exception) {
                state = state.copy(genStats = GenStats.Error(e.message))
            } finally {
                state = state.copy(busy = false, generating = false)
            }
        }
    }

    fun stop() { job?.cancel() }

    /** Frees space taken by models other than [keep] (e.g. the 3B file on a device that now gets 1B). */
    private suspend fun deleteStaleModels(keep: File) = withContext(Dispatchers.IO) {
        modelDir.listFiles()?.forEach { f ->
            if (isModelFile(f.name) && f.name != keep.name && f.name != keep.name + ".part") f.delete()
        }
    }

    private fun readResources(): DeviceResources {
        val am = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val files = modelDir.listFiles()?.filter { isModelFile(it.name) }?.associate { it.name to it.length() }
        return DeviceResources(
            totalRamBytes = mem.totalMem,
            isLowRamDevice = am.isLowRamDevice,
            usableStorageBytes = modelDir.usableSpace,
            modelFiles = files.orEmpty(),
        )
    }

    private fun isModelFile(name: String) = name.endsWith(".gguf") || name.endsWith(".gguf.part")

    override fun onCleared() { engine.close() }

    companion object {
        // Model input, not UI text: kept in code so it is never translated
        // (the prompt the model sees should not change with the device language).
        const val SYSTEM_PROMPT =
            "You are an Android reliability engineer. You receive a digest of crashes, ANRs, native crashes, " +
                "memory and performance data collected from one Android device. Write a short triage summary " +
                "in plain text with these sections:\n" +
                "Overview: one or two sentences on the overall health of the device.\n" +
                "Top issues: up to 5 bullets, most impactful first. For each give the app or process, what " +
                "happened, the likely cause based on the error and stack frames, and a suggested fix or next step.\n" +
                "Memory: assess memory pressure from the memory snapshot and low-memory kills. For each memory " +
                "leak suspect (OutOfMemoryError, Java heap at or near its limit, many Activity objects kept in " +
                "memory) say which app, why it suggests a leak, and suggest capturing a heap dump or adding " +
                "LeakCanary to find the leaking object.\n" +
                "Performance: only if the digest has device health data, explain likely causes of slowness " +
                "(high CPU load or busy processes, thermal throttling, low free storage, large memory users, " +
                "UI jank) and what to do.\n" +
                "Use only facts from the digest. Do not invent apps, classes or numbers. " +
                "If the data is limited, say so."

        // ~1.8k-token digest + 768 output tokens stays well inside the 4096-token context.
        private const val MAX_SUMMARY_TOKENS = 768
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val PREFS = "settings"
        private const val KEY_APP_FILTER = "app_filter"
    }
}
