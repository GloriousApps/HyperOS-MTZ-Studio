package dev.glorioustr.mtzstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import java.nio.file.Path

@Composable
internal fun LiveDiagnosticsCard(
    recorder: LiveDiagnosticsRecorder,
    shareDiagnostics: (Path) -> Unit,
    shareThemeManagerApk: (Path) -> Unit,
    openAppShareForThemesExport: () -> Unit,
    neonStyle: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val state by recorder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var actionStatus by remember { mutableStateOf<String?>(null) }
    val logListState = rememberLazyListState()
    var followLiveTail by remember { mutableStateOf(true) }
    val isLive = state.activeSessionId != null

    LaunchedEffect(logListState) {
        snapshotFlow {
            logListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to
                logListState.layoutInfo.totalItemsCount
        }.collectLatest { (lastVisible, totalItems) ->
            followLiveTail = lastVisible == null || totalItems == 0 || lastVisible >= totalItems - 1
        }
    }
    LaunchedEffect(state.recentEvents.size) {
        if (followLiveTail && state.recentEvents.isNotEmpty()) {
            logListState.animateScrollToItem(state.recentEvents.lastIndex)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StudioCard(Modifier.fillMaxWidth(), neon = neonStyle) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier.size(42.dp).background(Color(0xFF00BCD4).copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.MonitorHeart, contentDescription = null, tint = Color(0xFF00DAF3), modifier = Modifier.size(23.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.diag_live_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.diag_local_only), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF00BCD4).copy(alpha = 0.14f),
                        contentColor = Color(0xFF00DAF3),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(8.dp).background(if (isLive) Color(0xFF00E5A8) else Color(0xFF00BCD4), RoundedCornerShape(50)))
                            Text(stringResource(if (isLive) R.string.diag_live_active else R.string.diag_live_ready), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xFF0D111A)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("›", color = Color(0xFF00DAF3), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text(
                                LiveDiagnosticsRecorder.phaseLabel(state.phase, context),
                                modifier = Modifier.weight(1f),
                                color = Color(0xFFE4E8F2),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (state.activeSessionId != null) {
                            val source = state.sourceBytes
                            if (source != null && source > 0) {
                                val progress = (state.bytesCopied.toFloat() / source).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF00DAF3),
                                    trackColor = Color(0xFF252B38),
                                )
                                Text(
                                    "${(progress * 100).toInt()}%  ·  ${formatDiagnosticBytes(state.bytesCopied)} / ${formatDiagnosticBytes(source)}",
                                    color = Color(0xFF98A2B7),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF00DAF3), trackColor = Color(0xFF252B38))
                                Text(
                                    stringResource(R.string.diag_copied, formatDiagnosticBytes(state.bytesCopied)),
                                    color = Color(0xFF98A2B7),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        if (state.recentEvents.isEmpty()) {
                            Text(stringResource(R.string.diag_no_events), color = Color(0xFF98A2B7), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        } else {
                            LazyColumn(
                                state = logListState,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(state.recentEvents, key = { index, _ -> index }) { index, event ->
                                    SelectionContainer {
                                        Text(
                                            event,
                                            color = if (index == state.recentEvents.lastIndex) Color(0xFFE4E8F2) else Color(0xFFAAB2C4),
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                state.recoveredSession?.let { DiagnosticWarning(stringResource(R.string.diag_interrupted_session, it)) }
                if (state.orphanStagingCount > 0) {
                    DiagnosticWarning(stringResource(R.string.diag_orphan_staging, state.orphanStagingCount, formatDiagnosticBytes(state.orphanStagingBytes)))
                }
            }
        }

        Text(stringResource(R.string.diag_tools_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        DiagnosticsActionCard(
            title = stringResource(R.string.btn_export_diagnostics),
            description = stringResource(R.string.diag_export_action_desc),
            icon = Icons.Filled.Description,
            accent = Color(0xFF0066FF),
            onClick = {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { recorder.createExport() } }
                        .onSuccess(shareDiagnostics)
                        .onFailure { actionStatus = resources.getString(R.string.diag_export_failed, it.message ?: "") }
                }
            },
        )
        DiagnosticsActionCard(
            title = stringResource(R.string.btn_export_themes_apk),
            description = stringResource(R.string.diag_themes_apk_desc),
            icon = Icons.Filled.Android,
            accent = Color(0xFF7C4DFF),
            onClick = {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { recorder.exportInstalledThemeManagerApk() } }
                        .onSuccess(shareThemeManagerApk)
                        .onFailure {
                            recorder.record(
                                "themes_apk_export_appshare_fallback",
                                "Rootsuz APK kopyası açılamadı; App Share dışa aktarma akışına yönlendiriliyor",
                                error = it,
                            )
                            actionStatus = resources.getString(R.string.diag_themes_apk_export_failed, it.message ?: "")
                            openAppShareForThemesExport()
                        }
                }
            },
        )
        DiagnosticsActionCard(
            title = stringResource(R.string.btn_report_problem_telegram),
            description = stringResource(R.string.diag_telegram_support_desc),
            icon = Icons.Filled.Send,
            accent = Color(0xFF00BCD4),
            onClick = { uriHandler.openUri(DEVELOPER_TELEGRAM_URL) },
        )

        actionStatus?.let {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(it, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun DiagnosticWarning(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DiagnosticsActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
) {
    StudioCard(Modifier.fillMaxWidth(), neon = neonStyle) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp).background(accent.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onClick) { Text(stringResource(R.string.diag_action_open)) }
        }
    }
}

internal const val DEVELOPER_TELEGRAM_URL = "https://t.me/Glorioustr"

private fun formatDiagnosticBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
