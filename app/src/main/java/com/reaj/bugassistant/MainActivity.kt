package com.reaj.bugassistant

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.reaj.bugassistant.ui.theme.BugAssistantTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on first launch: after a rotation the shared file was already imported.
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            BugAssistantTheme {
                Surface(Modifier.fillMaxSize()) { MainRoute(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** A bug report shared from Developer options ("Take bug report" > Share) or a file manager. */
    private fun handleShare(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamListExtra()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.importBugReport(uris)
    }

    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun Intent.streamListExtra(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }.orEmpty()
}

/**
 * Stateful entry point: connects [MainViewModel] to the stateless [MainScreen].
 * Previews live in MainScreen.kt, since this composable needs a real ViewModel (JNI + download).
 */
@Composable
fun MainRoute(vm: MainViewModel) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share_title)
    val pickBugReport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBugReport(listOf(uri))
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri)
    }

    MainScreen(
        state = vm.state,
        packageName = context.packageName,
        onRetryModel = vm::prepareModel,
        onScan = vm::scan,
        onImport = { pickBugReport.launch(BUG_REPORT_MIME_TYPES) },
        onAnalyze = vm::analyze,
        onStop = vm::stop,
        onShare = {
            vm.shareText()?.let { text ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, shareTitle)
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, shareTitle))
            }
        },
        onOpenSaved = vm::openSaved,
        onDeleteSaved = vm::deleteSaved,
        onAppFilter = vm::setAppFilter,
        onExport = { exportCsv.launch(vm.exportFileName()) },
        onOpenUsageAccess = {
            try {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        },
    )
}

/** Bug reports are zips (or plain text from `adb bugreport` on old devices); providers label them inconsistently. */
private val BUG_REPORT_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/vnd.android.bugreport",
    "text/plain",
    "application/octet-stream",
)
