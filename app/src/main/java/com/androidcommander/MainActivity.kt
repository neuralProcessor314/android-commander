package com.androidcommander

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.FileProvider
import com.androidcommander.explore.ExplorerLauncher
import com.androidcommander.fs.MediaScanHook
import com.androidcommander.fs.treeUriToPath
import com.androidcommander.model.SideEffect
import com.androidcommander.ui.AndroidCommanderTheme
import com.androidcommander.ui.AppScreen
import java.io.File
import kotlinx.coroutines.flow.collectLatest

/**
 * Locked portrait, adjustNothing (declared in the manifest) -- instruction section 1: the layout
 * must never resize for the IME or relayout on configuration changes.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.setAllFilesAccess(hasAllFilesAccess())
        }

    private val safTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                val path = treeUriToPath(uri)
                if (path != null) viewModel.onSafGrantReceived(uri, path)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MediaScanHook.scanner = { path ->
            MediaScannerConnection.scanFile(applicationContext, arrayOf(path), null, null)
        }

        viewModel.setAllFilesAccess(hasAllFilesAccess())

        setContent {
            val state by viewModel.state.collectAsState()

            AndroidCommanderTheme {
                AppScreen(
                    state = state,
                    dispatch = viewModel::dispatch,
                    onMetricsReady = { viewModel.lockPageSize(it.pageSize) },
                )
            }

            LaunchedEffect(Unit) {
                viewModel.sideEffects.collectLatest { effect -> handleSideEffect(effect) }
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(lifecycleOwner) {
                lifecycleOwner.lifecycle.addObserver(
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.setAllFilesAccess(hasAllFilesAccess())
                        }
                    },
                )
            }
        }
    }

    private fun handleSideEffect(effect: SideEffect) {
        when (effect) {
            is SideEffect.ViewFile -> viewFile(effect.file)
            is SideEffect.ExploreDir -> {
                val ok = ExplorerLauncher.launch(this, effect.dir)
                viewModel.onExploreResult(ok)
            }
            SideEffect.RequestAllFilesAccess -> requestAllFilesAccess()
            is SideEffect.RequestSafGrant -> {
                val hintUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:" + effect.forPath.path.removePrefix("/storage/emulated/0").trim('/'),
                )
                try {
                    safTreeLauncher.launch(hintUri)
                } catch (_: ActivityNotFoundException) {
                    // No document-tree picker on this device; silently drop rather than crash --
                    // the pane just stays empty for that path, consistent with an unmet
                    // permission (instruction section 2).
                }
            }
            SideEffect.FinishActivity -> finish()
        }
    }

    private fun viewFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "com.androidcommander.fileprovider", file)
        val mime = contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }

    private fun requestAllFilesAccess() {
        // The per-app deep link is ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION (takes the
        // package: URI); ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION (no "APP") is the *global*
        // list screen and isn't meant to be targeted at one app. Falls back to the global list
        // if the per-app screen isn't resolvable, and never launches an intent that can't
        // resolve -- every intent launch in this app is guarded this way (see viewFile(),
        // ExplorerLauncher) so a missing system screen shows a stripe error instead of crashing.
        val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        val target = if (perApp.resolveActivity(packageManager) != null) {
            perApp
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        if (target.resolveActivity(packageManager) != null) {
            try {
                allFilesAccessLauncher.launch(target)
            } catch (_: ActivityNotFoundException) {
                viewModel.onAllFilesAccessRequestFailed()
            }
        } else {
            viewModel.onAllFilesAccessRequestFailed()
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
}
