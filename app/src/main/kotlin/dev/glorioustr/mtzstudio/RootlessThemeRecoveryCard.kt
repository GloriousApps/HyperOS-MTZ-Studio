package dev.glorioustr.mtzstudio

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    fun installWithoutBackup(context: Context) = run(context) { installWithoutBackup() }
    fun recover(context: Context) = run(context) { recoverExisting() }
    fun restoreThemes(context: Context) = run(context) { restoreThemesBackup() }

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

@Composable
internal fun RootlessThemeRecoveryCard(neonStyle: Boolean = false, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestOnChanged by rememberUpdatedState(onChanged)
    var accepted by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }
    var confirmNoBackup by remember { mutableStateOf(false) }
    var confirmRecovery by remember { mutableStateOf(false) }
    var showPrepareInfo by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val state = RootlessRecoveryWork.snapshot
    val busy = RootlessRecoveryWork.busy
    val error = RootlessRecoveryWork.error
    val action = recoveryAction(state?.phase, state?.canInstall == true, state?.needsRecovery == true)
    val reason = controlledRecoveryReason(error) ?: controlledRecoveryReason(state?.message)
    val themeCount = state?.themeCount ?: 0
    val themesStatus = when (state?.themesBackupStatus) {
        "PARTIAL" -> null
        "EMPTY" -> R.string.rootless_themes_empty
        "FAILED" -> R.string.rootless_themes_failed
        else -> R.string.rootless_themes_unavailable
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
    LaunchedEffect(state?.phase, state?.publicBackupPath, error) {
        accepted = false
        confirmInstall = false
        confirmNoBackup = false
    }

    StudioCard(Modifier.fillMaxWidth(), neon = neonStyle) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.rootless_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = { showInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.rootless_info_title))
                }
            }
            val summary = when {
                busy -> R.string.rootless_working
                action == RecoveryAction.RECOVER -> R.string.rootless_recovery_summary
                error != null || state?.phase == "FAILED" -> R.string.rootless_failed
                state?.phase == "THEMES_RESTORED" -> R.string.rootless_themes_restored
                state?.phase == "INSTALLED" -> R.string.rootless_installed
                state?.phase == "RECOVERED" -> R.string.rootless_recovered
                action == RecoveryAction.INSTALL -> R.string.rootless_prepared
                state == null -> R.string.rootless_loading
                else -> R.string.rootless_intro
            }
            Text(stringResource(summary), style = MaterialTheme.typography.bodySmall)
            if (error != null || state?.phase == "FAILED" || state?.needsRecovery == true) {
                reason?.let { Text(stringResource(R.string.rootless_reason, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            state?.publicBackupPath?.takeIf(String::isNotBlank)?.let { path ->
                Text(
                    stringResource(R.string.rootless_saved_path, path),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (themesStatus == null) {
                        pluralStringResource(R.plurals.rootless_themes_partial, themeCount, themeCount)
                    } else stringResource(themesStatus),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when (action) {
                RecoveryAction.PREPARE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showPrepareInfo = true }, enabled = !busy) {
                        Text(stringResource(R.string.rootless_prepare))
                    }
                    OutlinedButton(onClick = { accepted = false; confirmNoBackup = true }, enabled = !busy) {
                        Text(stringResource(R.string.rootless_install_without_backup))
                    }
                }
                RecoveryAction.INSTALL -> Button(onClick = { accepted = false; confirmInstall = true }, enabled = !busy) {
                    Text(stringResource(R.string.rootless_install))
                }
                RecoveryAction.RECOVER -> Button(onClick = { confirmRecovery = true }, enabled = !busy) {
                    Text(stringResource(R.string.rootless_recover))
                }
                RecoveryAction.NONE -> Unit
            }
        }
    }

    if (showPrepareInfo) AlertDialog(
        onDismissRequest = { showPrepareInfo = false },
        title = { Text(stringResource(R.string.rootless_prepare_info_title)) },
        text = { Text(stringResource(R.string.rootless_prepare_info_text)) },
        confirmButton = {
            TextButton(onClick = {
                showPrepareInfo = false
                if (!busy) RootlessRecoveryWork.prepare(context)
            }, enabled = !busy) { Text(stringResource(R.string.rootless_ok_continue)) }
        },
        dismissButton = { TextButton(onClick = { showPrepareInfo = false }) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (showInfo) AlertDialog(
        onDismissRequest = { showInfo = false },
        title = { Text(stringResource(R.string.rootless_info_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rootless_info_detail))
                Text(stringResource(R.string.rootless_info_limitations))
                state?.publicBackupPath?.takeIf(String::isNotBlank)?.let {
                    Text(stringResource(R.string.rootless_saved_path, it))
                    Text(
                        if (themesStatus == null) {
                            pluralStringResource(R.plurals.rootless_themes_partial, themeCount, themeCount)
                        } else stringResource(themesStatus),
                    )
                }
                reason?.let { Text(stringResource(R.string.rootless_reason, it)) }
            }
        },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.rootless_close)) } },
    )
    if (confirmInstall) AlertDialog(
        onDismissRequest = { accepted = false; confirmInstall = false },
        title = { Text(stringResource(R.string.rootless_install)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rootless_exact_warning))
                Text(stringResource(R.string.rootless_install_limitations))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text(stringResource(R.string.rootless_risk_accept), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            confirmInstall = false
            if (accepted && !RootlessRecoveryWork.busy &&
                recoveryAction(RootlessRecoveryWork.snapshot?.phase, RootlessRecoveryWork.snapshot?.canInstall == true,
                    RootlessRecoveryWork.snapshot?.needsRecovery == true) == RecoveryAction.INSTALL
            ) RootlessRecoveryWork.install(context)
            accepted = false
        }, enabled = accepted && !busy) { Text(stringResource(R.string.rootless_install)) } },
        dismissButton = { TextButton(onClick = { accepted = false; confirmInstall = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirmNoBackup) AlertDialog(
        onDismissRequest = { accepted = false; confirmNoBackup = false },
        title = { Text(stringResource(R.string.rootless_install_without_backup)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rootless_no_backup_warning))
                Text(stringResource(R.string.rootless_no_backup_limitations))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text(stringResource(R.string.rootless_risk_accept), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            confirmNoBackup = false
            if (accepted && !busy) RootlessRecoveryWork.installWithoutBackup(context)
            accepted = false
        }, enabled = accepted && !busy) { Text(stringResource(R.string.rootless_install_without_backup)) } },
        dismissButton = { TextButton(onClick = { accepted = false; confirmNoBackup = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirmRecovery) AlertDialog(
        onDismissRequest = { confirmRecovery = false },
        title = { Text(stringResource(R.string.rootless_recover)) },
        text = { Text(stringResource(R.string.rootless_recover_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmRecovery = false
            if (!RootlessRecoveryWork.busy && RootlessRecoveryWork.snapshot?.needsRecovery == true) RootlessRecoveryWork.recover(context)
        }, enabled = !busy) { Text(stringResource(R.string.rootless_recover)) } },
        dismissButton = { TextButton(onClick = { confirmRecovery = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun RootlessThemeRecoveryBackupCard(neonStyle: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = RootlessRecoveryWork.snapshot
    val busy = RootlessRecoveryWork.busy
    val error = RootlessRecoveryWork.error
    val reason = controlledRecoveryReason(error) ?: controlledRecoveryReason(state?.message)
    var confirmRestore by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) RootlessRecoveryWork.refresh(context)
        }
        lifecycle.addObserver(observer)
        RootlessRecoveryWork.refresh(context)
        onDispose { lifecycle.removeObserver(observer) }
    }

    StudioCard(Modifier.fillMaxWidth(), neon = neonStyle) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rootless_restore_backup_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.rootless_restore_backup_desc), style = MaterialTheme.typography.bodySmall)
            if (busy) Text(stringResource(R.string.rootless_working))
            if (state?.phase in setOf("THEMES_RESTORE_FAILED", "THEMES_RESTORE_PENDING") || error != null) {
                Text(stringResource(R.string.rootless_restore_backup_incomplete), color = MaterialTheme.colorScheme.error)
                reason?.let { Text(stringResource(R.string.rootless_reason, it), style = MaterialTheme.typography.bodySmall) }
            }
            when {
                state?.phase == "THEMES_RESTORED" -> Text(stringResource(R.string.rootless_restore_backup_done), color = MaterialTheme.colorScheme.primary)
                state?.canRestoreThemes == true -> Button(onClick = { confirmRestore = true }, enabled = !busy) {
                    Text(stringResource(R.string.rootless_restore_backup_button))
                }
                state?.phase in setOf("INSTALLED", "RECOVERED") && state?.themesBackupStatus != "PARTIAL" ->
                    Text(stringResource(R.string.rootless_restore_backup_unavailable), style = MaterialTheme.typography.bodySmall)
                else -> Text(stringResource(R.string.rootless_restore_backup_waiting), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text(stringResource(R.string.rootless_restore_backup_title)) },
        text = { Text(stringResource(R.string.rootless_restore_backup_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmRestore = false
            if (!RootlessRecoveryWork.busy && RootlessRecoveryWork.snapshot?.canRestoreThemes == true) {
                RootlessRecoveryWork.restoreThemes(context)
            }
        }, enabled = !busy) { Text(stringResource(R.string.rootless_restore_backup_button)) } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
