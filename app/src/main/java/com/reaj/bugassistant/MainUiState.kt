package com.reaj.bugassistant

import com.reaj.bugassistant.diagnostics.DeviceReport
import com.reaj.bugassistant.model.ModelChoice
import com.reaj.bugassistant.storage.AppCount
import com.reaj.bugassistant.storage.ReportListItem

/**
 * UI state as typed values, not display strings. The UI turns them into localized text
 * with string resources, so the ViewModel stays free of Context and text.
 */
data class MainUiState(
    val modelStatus: ModelStatus,
    /** The model picked for this device, once known. */
    val model: ModelInfo? = null,
    val scan: ScanStatus = ScanStatus.Idle,
    /** LLM triage summary of [ScanStatus.Done.report], streamed token by token. */
    val summary: String = "",
    val genStats: GenStats = GenStats.None,
    /** Model download/load or generation in progress. */
    val busy: Boolean = false,
    val generating: Boolean = false,
    /** Saved reports, newest first. */
    val history: List<ReportListItem> = emptyList(),
    /** Database id of the report on screen, once saved. */
    val currentReportId: Long? = null,
    /** App package the screen is narrowed to; null = all apps. */
    val appFilter: String? = null,
    /** Apps found in saved reports, for the filter menu. */
    val savedApps: List<AppCount> = emptyList(),
    val export: ExportStatus = ExportStatus.Idle,
) {
    val loaded: Boolean get() = modelStatus is ModelStatus.Ready
    val report: DeviceReport? get() = (scan as? ScanStatus.Done)?.report
    /** [report] narrowed to [appFilter]. Everything on screen, the summary and sharing use this. */
    val visibleReport: DeviceReport? get() = report?.forApp(appFilter)

    /** Filter choices: apps in the current report first, then other saved apps, plus the current filter. */
    val appChoices: List<String>
        get() = (report?.apps.orEmpty() + savedApps.map { it.packageName } + listOfNotNull(appFilter)).distinct()
    /** A live scan or an import is running. */
    val scanning: Boolean get() = scan is ScanStatus.Scanning || scan is ScanStatus.Importing
    val canScan: Boolean get() = !scanning
    val canShare: Boolean get() = report != null && !generating
    val canAnalyze: Boolean get() = loaded && !busy && !scanning && visibleReport?.hasIssues == true
}

sealed interface ModelStatus {
    data object Starting : ModelStatus
    /** [percent] is null until the size is known. */
    data class Downloading(val percent: Int?) : ModelStatus
    data object Loading : ModelStatus
    data class Ready(val loadMs: Long) : ModelStatus
    /** [reason] is a technical detail (exception message) and is not localized. */
    data class Failed(val reason: String?) : ModelStatus
    /** Even the small model does not fit; [neededBytes] more free space is required. */
    data class NotEnoughStorage(val neededBytes: Long) : ModelStatus
}

/** [reason] is null when the device cannot fit any model. */
data class ModelInfo(val name: String, val reason: ModelChoice.Reason?)

sealed interface ScanStatus {
    data object Idle : ScanStatus
    data object Scanning : ScanStatus
    /** Reading an imported bug report. [percent] is null when the file size is unknown. */
    data class Importing(val fileName: String, val percent: Int?) : ScanStatus
    data class Done(val report: DeviceReport) : ScanStatus
    /** [notABugReport] is true when an imported file was not recognized as a bug report. */
    data class Failed(val reason: String?, val import: Boolean = false, val notABugReport: Boolean = false) : ScanStatus
}

sealed interface GenStats {
    data object None : GenStats
    data class Done(val ttftMs: Long, val tokens: Int, val tokensPerSec: Double) : GenStats
    data object NoOutput : GenStats
    data object Stopped : GenStats
    data class Error(val reason: String?) : GenStats
}

/** CSV export of saved issues to a file the user picked. */
sealed interface ExportStatus {
    data object Idle : ExportStatus
    data object Running : ExportStatus
    data class Done(val fileName: String?, val rows: Int) : ExportStatus
    data class Failed(val reason: String?) : ExportStatus
}
