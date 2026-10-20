package dev.glorioustr.mtzstudio

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-owned work: leaving this screen never cancels a package operation or starts a retry. */
private object RootlessRecoveryWork {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = mutableSetOf<() -> Unit>()
    var snapshot by mutableStateOf<RootlessThemeRecovery.Snapshot?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }

    fun refresh(context: Context) = run(context, notify = false) { snapshot() }
    fun prepare(context: Context) = run(context) { prepare() }
    fun install(context: Context) = run(context) { install(true) }
    fun recover(context: Context) = run(context) { recoverExisting() }

    private fun run(context: Context, notify: Boolean = true, action: RootlessThemeRecovery.() -> RootlessThemeRecovery.Snapshot) {
        if (busy) return
        busy = true
        error = null
        val app = context.applicationContext
        scope.launch {
            try {
                snapshot = withContext(Dispatchers.IO) { RootlessThemeRecovery(app).action() }
            } catch (failure: Exception) {
                error = failure.message ?: failure.javaClass.simpleName
                // Read the journal after a failed mutation: it may now require explicit recovery.
                snapshot = runCatching {
                    withContext(Dispatchers.IO) { RootlessThemeRecovery(app).snapshot() }
                }.getOrElse { snapshot }
            } finally {
                busy = false
                if (notify) listeners.toList().forEach { it() }
            }
        }
    }
}

/** Only the backend's Studio ZIP inside its app-private recovery folder can be exported. */
private fun studioZip(context: Context, backupPath: String?): File? {
    if (backupPath == null) return null
    val root = File(context.filesDir, "rootless-recovery").canonicalFile
    val folder = File(backupPath).canonicalFile
    if (folder.parentFile != root || !folder.isDirectory) return null
    val zip = File(folder, "studio-library.zip")
    return zip.takeIf { it.isFile && it.canonicalFile.parentFile == folder }
}

@Composable
internal fun RootlessThemeRecoveryCard(neonStyle: Boolean = false, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val latestOnChanged by rememberUpdatedState(onChanged)
    var accepted by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }
    var confirmRecovery by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportBundle by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val state = RootlessRecoveryWork.snapshot
    val busy = RootlessRecoveryWork.busy
    val zip = remember(state?.backupPath) { studioZip(context, state?.backupPath) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val source = studioZip(context, RootlessRecoveryWork.snapshot?.backupPath)
            val includeApks = exportBundle
            if (source == null) localError = context.getString(R.string.rootless_export_unavailable)
            else {
                exporting = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                                if (!includeApks) source.inputStream().use { it.copyTo(output) }
                                else ZipOutputStream(output).use { archive ->
                                    // Only this operation's generated files, not arbitrary app data.
                                    val folder = source.parentFile!!.canonicalFile
                                    val allowed = Regex("(?:base\\.apk|split-[0-9]+\\.apk|backup\\.properties|studio-library\\.zip)")
                                    val files = folder.listFiles()?.filter { allowed.matches(it.name) }.orEmpty()
                                    check(files.any { it.name == "base.apk" }) { "APK backup unavailable" }
                                    files.sortedBy { it.name }.forEach { file ->
                                        check(file.isFile && file.canonicalFile.parentFile == folder)
                                        archive.putNextEntry(ZipEntry(file.name))
                                        file.inputStream().use { it.copyTo(archive) }
                                        archive.closeEntry()
                                    }
                                }
                            } ?: error("Could not open export destination")
                        }
                        localError = null
                    } catch (failure: Exception) {
                        localError = failure.message ?: context.getString(R.string.rootless_export_unavailable)
                    } finally { exporting = false }
                }
            }
        }
    }

    DisposableEffect(lifecycle) {
        val listener: () -> Unit = { latestOnChanged() }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) RootlessRecoveryWork.refresh(context)
        }
        RootlessRecoveryWork.subscribe(listener)
        lifecycle.addObserver(observer)
        RootlessRecoveryWork.refresh(context)
        onDispose {
            lifecycle.removeObserver(observer)
            RootlessRecoveryWork.unsubscribe(listener)
        }
    }
    LaunchedEffect(state?.phase, state?.backupPath, RootlessRecoveryWork.error) { accepted = false }

    StudioCard(Modifier.fillMaxWidth(), neon = neonStyle) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.rootless_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.rootless_experimental), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.rootless_scope), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.rootless_target), style = MaterialTheme.typography.bodySmall)
            Text(state?.message ?: stringResource(R.string.rootless_loading), style = MaterialTheme.typography.bodyMedium)
            state?.backupPath?.let { Text(stringResource(R.string.rootless_backup_path, it), style = MaterialTheme.typography.bodySmall) }
            RootlessRecoveryWork.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = {
                accepted = false
                RootlessRecoveryWork.prepare(context)
            }, enabled = !busy && !exporting && state?.needsRecovery != true) {
                Text(stringResource(R.string.rootless_prepare))
            }
            if (zip != null) {
                OutlinedButton(onClick = { exportBundle = false; export.launch("mtzstudio-library.zip") }, enabled = !busy && !exporting) {
                    Text(stringResource(R.string.rootless_export_studio))
                }
                OutlinedButton(onClick = { exportBundle = true; export.launch("mtzstudio-themes-recovery.zip") }, enabled = !busy && !exporting) {
                    Text(stringResource(R.string.rootless_export_bundle))
                }
            }
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS)) }
                    .onFailure { localError = it.message ?: context.getString(R.string.rootless_settings_unavailable) }
            }) { Text(stringResource(R.string.rootless_manual_backup)) }
            Text(stringResource(R.string.rootless_manual_backup_note), style = MaterialTheme.typography.bodySmall)

            if (state?.canInstall == true && !state.needsRecovery) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it }, enabled = !busy && !exporting)
                    Text(stringResource(R.string.rootless_exact_warning), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.rootless_install_limitations), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { confirmInstall = true }, enabled = accepted && !busy && !exporting) {
                    Text(stringResource(R.string.rootless_install))
                }
            }
            if (state?.needsRecovery == true) {
                Text(stringResource(R.string.rootless_recovery_note), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { confirmRecovery = true }, enabled = !busy && !exporting) {
                    Text(stringResource(R.string.rootless_recover))
                }
            }
            if (busy) Text(stringResource(R.string.rootless_working))
        }
    }
    if (confirmInstall) AlertDialog(
        onDismissRequest = { confirmInstall = false },
        title = { Text(stringResource(R.string.rootless_install)) },
        text = { Text(stringResource(R.string.rootless_install_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmInstall = false
            if (accepted && !RootlessRecoveryWork.busy && RootlessRecoveryWork.snapshot?.canInstall == true) {
                accepted = false
                RootlessRecoveryWork.install(context)
            }
        }) { Text(stringResource(R.string.rootless_install)) } },
        dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirmRecovery) AlertDialog(
        onDismissRequest = { confirmRecovery = false },
        title = { Text(stringResource(R.string.rootless_recover)) },
        text = { Text(stringResource(R.string.rootless_recover_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmRecovery = false
            if (!RootlessRecoveryWork.busy && RootlessRecoveryWork.snapshot?.needsRecovery == true) RootlessRecoveryWork.recover(context)
        }) { Text(stringResource(R.string.rootless_recover)) } },
        dismissButton = { TextButton(onClick = { confirmRecovery = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
