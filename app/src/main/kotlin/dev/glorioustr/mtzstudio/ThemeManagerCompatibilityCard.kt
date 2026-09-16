package dev.glorioustr.mtzstudio

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.glorioustr.mtzstudio.tester.InstalledThemeManager
import dev.glorioustr.mtzstudio.tester.PrivilegedCommandRunner
import dev.glorioustr.mtzstudio.tester.RootThemeManagerUpdater
import dev.glorioustr.mtzstudio.tester.ThemeManagerContract
import dev.glorioustr.mtzstudio.tester.ThemeManagerInspector
import dev.glorioustr.mtzstudio.tester.ThemeManagerCapabilityProbe
import dev.glorioustr.mtzstudio.tester.VerifiedThemeManagerApk
import dev.glorioustr.mtzstudio.shevery.SheveryAccess
import dev.glorioustr.mtzstudio.shevery.SheveryAuthorizationStatus
import dev.glorioustr.mtzstudio.shevery.PreferredPrivilegedCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

@Composable
internal fun ThemeManagerCompatibilityCard(
    inspector: ThemeManagerInspector,
    updater: RootThemeManagerUpdater,
    openInput: (Uri) -> InputStream?,
    allowRootDowngrade: Boolean,
) {
    val recommendedApkName = "Xiaomi_Themes_3.0.5.6-global.apk"
    val recommendedDownloadUrl =
        "https://github.com/GloriousApps/HyperOS-MTZ-Studio/releases/download/v4.0.0/$recommendedApkName"
    val recommendedApkSha256 = "24b99f995bf5f8509e591bdb1d36ce6f95260ec95648d13f9ddedc4e7d8edceb"
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheveryAccess = remember { SheveryAccess(context.applicationContext) }
    val rootModuleInstaller = remember {
        RootThemeImportModuleInstaller(
            context.applicationContext,
            PreferredPrivilegedCommandRunner(context.applicationContext),
        )
    }
    var installed by remember { mutableStateOf<InstalledThemeManager?>(null) }
    var runtimeProfile by remember { mutableStateOf<dev.glorioustr.mtzstudio.tester.ThemeManagerRuntimeProfile?>(null) }
    var verifiedApk by remember { mutableStateOf<VerifiedThemeManagerApk?>(null) }
    var status by remember { mutableStateOf(resources.getString(R.string.tm_checking_version)) }
    var riskAccepted by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    var showRootModuleConfirmation by remember { mutableStateOf(false) }
    var showRootModuleRestartDialog by remember { mutableStateOf(false) }
    var rootModuleState by remember { mutableStateOf<RootThemeImportModuleInstaller.State?>(null) }
    var rootModuleCheckComplete by remember { mutableStateOf(!allowRootDowngrade) }
    var rootModuleUpdateRequested by remember { mutableStateOf(false) }
    val cyanAccent = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFF006A78) else Color(0xFF00DAF3)

    fun startShizukuDowngrade() {
        when (sheveryAccess.status()) {
            SheveryAuthorizationStatus.PERMISSION_REQUIRED -> {
                sheveryAccess.requestPermission(52048)
                status = resources.getString(R.string.privileged_access_permission_required)
                return
            }
            SheveryAuthorizationStatus.ADB_READY -> Unit
            else -> {
                status = resources.getString(R.string.privileged_access_unavailable)
                return
            }
        }
        val current = installed ?: return
        scope.launch {
            status = resources.getString(R.string.tm_verifying_apk)
            var stagedApk: VerifiedThemeManagerApk? = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val downloadName = "mtzstudio-${System.currentTimeMillis()}-$recommendedApkName"
                    val request = DownloadManager.Request(Uri.parse(recommendedDownloadUrl))
                        .setTitle(recommendedApkName)
                        .setDescription("Xiaomi Themes ${ThemeManagerContract.RECOMMENDED_VERSION}")
                        .setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, downloadName)
                    val downloadId = manager.enqueue(request)
                    awaitDownload(manager, downloadId)
                    val descriptor = manager.openDownloadedFile(downloadId)
                    val verified = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        updater.stageAndVerify(input, current)
                    }
                    stagedApk = verified
                    check(verified.sha256.equals(recommendedApkSha256, ignoreCase = true)) {
                        "Downloaded Xiaomi Themes APK checksum does not match"
                    }
                    val shellRunner = PrivilegedCommandRunner { command, timeout ->
                        sheveryAccess.executeShell(command, timeout)
                    }
                    updater.installVerifiedDowngradeFromDownload(
                        apk = verified,
                        shellReadablePath = "/sdcard/Download/$downloadName",
                        installRunner = shellRunner,
                    ).also { result ->
                        check(result.success) {
                            listOf(result.message, result.commandOutput).filter { it.isNotBlank() }.joinToString(" · ")
                        }
                    }
                }
            }.onSuccess { result ->
                installed = result.installedAfter
                runtimeProfile = withContext(Dispatchers.IO) {
                    ThemeManagerCapabilityProbe(context).probe(result.installedAfter)
                }
                status = result.message
            }.onFailure { error ->
                status = resources.getString(R.string.tm_apk_rejected, error.message ?: error::class.simpleName)
            }
            stagedApk?.let { runCatching { withContext(Dispatchers.IO) { updater.discard(it) } } }
        }
    }

    fun refresh() {
        scope.launch {
            val detected = withContext(Dispatchers.IO) { inspector.inspect() }
            installed = detected
            val profile = withContext(Dispatchers.IO) { ThemeManagerCapabilityProbe(context).probe(detected) }
            runtimeProfile = profile
            rootModuleState = if (allowRootDowngrade) {
                runCatching { withContext(Dispatchers.IO) { rootModuleInstaller.inspect() } }.getOrNull()
            } else null
            rootModuleCheckComplete = true
            status = if (profile.compatibleLocalMtzPath) {
                resources.getString(R.string.tm_recommended_active)
            } else {
                resources.getString(R.string.tm_recommendation_notice, ThemeManagerContract.RECOMMENDED_VERSION)
            }
        }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val current = installed ?: withContext(Dispatchers.IO) { inspector.inspect() }.also { installed = it }
                status = resources.getString(R.string.tm_verifying_apk)
                runCatching {
                    withContext(Dispatchers.IO) {
                        openInput(uri)?.use { updater.stageAndVerify(it, current) }
                            ?: error(resources.getString(R.string.tm_error_apk_open))
                    }
                }.onSuccess { apk ->
                    verifiedApk?.let { previous -> withContext(Dispatchers.IO) { updater.discard(previous) } }
                    verifiedApk = apk
                    riskAccepted = false
                    status = resources.getString(R.string.tm_apk_verified, apk.versionName, apk.sha256)
                }.onFailure { error ->
                    status = resources.getString(R.string.tm_apk_rejected, error.message ?: error::class.simpleName)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }
    DisposableEffect(verifiedApk) {
        val staged = verifiedApk
        onDispose { staged?.let { runCatching { updater.discard(it) } } }
    }

    StudioCard(Modifier.fillMaxWidth()) {
        val compatibleLocalMtzPath = runtimeProfile?.compatibleLocalMtzPath == true
        val applyActivityUnavailable = runtimeProfile != null && installed?.installed == true && !compatibleLocalMtzPath
        val rootModuleMode = allowRootDowngrade && applyActivityUnavailable
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(50)).background(Color(0xFF00BCD4).copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = cyanAccent, modifier = Modifier.size(24.dp))
                }
                Text(
                    if (rootModuleMode) "Root MTZ Import modülü" else stringResource(R.string.tm_card_title),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        when {
                            rootModuleMode && rootModuleState?.active == true -> "Etkin"
                            rootModuleMode && rootModuleState?.installed == true -> "Yeniden başlatın"
                            rootModuleMode -> "Etkinleştirilebilir"
                            runtimeProfile == null -> stringResource(R.string.tm_profile_checking)
                            compatibleLocalMtzPath -> stringResource(R.string.tm_profile_active)
                            else -> stringResource(R.string.tm_profile_checking)
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            installed?.let { current ->
                Text(
                    if (rootModuleMode) "Xiaomi Temalar: ${current.versionName ?: stringResource(R.string.tm_version_unknown)}"
                    else if (!current.installed) stringResource(R.string.tm_device_not_found)
                    else if (compatibleLocalMtzPath) stringResource(
                        R.string.tm_device_installed_compatible,
                        current.versionName ?: stringResource(R.string.tm_version_unknown),
                    ) else stringResource(
                        R.string.tm_device_installed_incompatible,
                        current.versionName ?: stringResource(R.string.tm_version_unknown),
                    ),
                    color = if (compatibleLocalMtzPath || rootModuleMode) cyanAccent else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (compatibleLocalMtzPath) {
                    Text(
                        stringResource(R.string.tm_panel_compatible_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(
                                if (runtimeProfile?.legacyTesterResolvable == true ||
                                    runtimeProfile?.modernLocalLibraryResolvable == true ||
                                    runtimeProfile?.publicMtzImportResolvable == true
                                ) {
                                    R.string.tm_panel_native_ready
                                } else {
                                    R.string.tm_panel_bridge_ready
                                },
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }
                if (applyActivityUnavailable && !rootModuleMode) {
                    Text(
                        stringResource(R.string.tm_incompatibility_reason_missing_activity),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.tm_rootless_import_unavailable),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (rootModuleMode) {
                    val module = rootModuleState
                    val moduleText = when {
                        module?.active == true -> "Root MTZ Import modülü etkin. Xiaomi Temalar importer'ı yeniden başlatma sonrasında hazır."
                        module?.installed == true -> "Root MTZ Import modülü kurulu. Etkinleşmesi için telefonu yeniden başlatın."
                        else -> "Bu Global Temalar sürümünde dışa açık MTZ Import yok. Root modülü, Xiaomi Temalar'ın kendi importer'ını güvenli biçimde etkinleştirir."
                    }
                    Text(moduleText, style = MaterialTheme.typography.bodySmall)
                    if (rootModuleCheckComplete) {
                        when {
                            module?.installed != true -> Button(onClick = {
                                rootModuleUpdateRequested = false
                                showRootModuleConfirmation = true
                            }) {
                                Text("Root MTZ Import modülünü kur")
                            }

                            module.active != true -> Button(onClick = {
                                showRootModuleRestartDialog = true
                            }) {
                                Text("Telefonu yeniden başlat")
                            }

                            else -> Button(onClick = {
                                rootModuleUpdateRequested = true
                                showRootModuleConfirmation = true
                            }) {
                                Text("Root MTZ Import modülünü güncelle")
                            }
                        }
                    } else {
                        Text("Root MTZ Import modülü denetleniyor…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Compatibility requires a runtime-resolvable Xiaomi route. Legacy Global builds
                // use ApplyThemeForScreenshot; 10.8.7.6+ builds use the native local library.
                // The modern version family alone is not enough if its component is missing.
                if (applyActivityUnavailable && !allowRootDowngrade) {
                    Button(onClick = ::startShizukuDowngrade) {
                        Text(stringResource(R.string.tm_btn_shizuku_downgrade, ThemeManagerContract.RECOMMENDED_VERSION))
                    }
                }
            }
            if (runtimeProfile != null && !compatibleLocalMtzPath && !rootModuleMode) Text(status, style = MaterialTheme.typography.bodySmall)

            val current = installed
            if (allowRootDowngrade && current != null && current.installed && runtimeProfile != null && !compatibleLocalMtzPath && !rootModuleMode) {
                OutlinedButton(
                    onClick = {
                        apkPicker.launch(
                            arrayOf(
                                "application/vnd.android.package-archive",
                                "application/octet-stream",
                                "*/*",
                            ),
                        )
                    },
                ) { Text(stringResource(R.string.tm_btn_select_apk_version, ThemeManagerContract.RECOMMENDED_VERSION)) }

                verifiedApk?.let { apk ->
                    Text(
                        stringResource(R.string.tm_verified_apk_info, apk.packageName, apk.versionName, apk.sha256),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = riskAccepted, onCheckedChange = { riskAccepted = it })
                        Text(
                            stringResource(R.string.tm_risk_checkbox),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        enabled = riskAccepted,
                        onClick = { showConfirmation = true },
                    ) { Text(stringResource(R.string.tm_btn_downgrade_to_version, ThemeManagerContract.RECOMMENDED_VERSION)) }
                }
            }
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text(stringResource(R.string.tm_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.tm_dialog_text,
                        verifiedApk?.packageName ?: "",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation = false
                        val apk = verifiedApk ?: return@TextButton
                        scope.launch {
                            status = resources.getString(R.string.tm_status_waiting_root)
                            runCatching {
                                withContext(Dispatchers.IO) { updater.installVerifiedDowngrade(apk) }
                            }.onSuccess { result ->
                                installed = result.installedAfter
                                status = buildString {
                                    append(result.message).append(" · ").append(result.authorizationSource)
                                    if (result.commandOutput.isNotBlank()) append(" · ").append(result.commandOutput)
                                }
                                if (result.success) {
                                    withContext(Dispatchers.IO) { updater.discard(apk) }
                                    verifiedApk = null
                                    riskAccepted = false
                                }
                            }.onFailure { error ->
                                status = resources.getString(R.string.tm_status_root_failed, error.message ?: error::class.simpleName)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.tm_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showRootModuleConfirmation) {
        AlertDialog(
            onDismissRequest = { showRootModuleConfirmation = false },
            title = {
                Text(
                    if (rootModuleUpdateRequested) {
                        "Root MTZ Import modülünü güncelle"
                    } else {
                        "Root MTZ Import modülünü kur"
                    },
                )
            },
            text = {
                Text(
                    "MTZ Studio, yalnızca kendi Zygisk modülünü root yöneticinizin standart modül dizinine kuracak. " +
                        "Xiaomi Temalar APK'sı, imzası ve verileri değiştirilmez. İşlemden sonra modülün yüklenmesi için telefon yeniden başlatılmalıdır.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRootModuleConfirmation = false
                    scope.launch {
                        status = if (rootModuleUpdateRequested) {
                            "Root MTZ Import modülü güncelleniyor…"
                        } else {
                            "Root MTZ Import modülü kuruluyor…"
                        }
                        runCatching {
                            withContext(Dispatchers.IO) { rootModuleInstaller.installOrUpdate() }
                        }.onSuccess { result ->
                            rootModuleState = withContext(Dispatchers.IO) { rootModuleInstaller.inspect() }
                            status = if (rootModuleUpdateRequested) {
                                "Root MTZ Import modülü ${result.version} güncellendi. Etkinleştirmek için telefonu yeniden başlatın."
                            } else {
                                "Root MTZ Import modülü ${result.version} kuruldu. Etkinleştirmek için telefonu yeniden başlatın."
                            }
                            showRootModuleRestartDialog = true
                        }.onFailure { error ->
                            status = "Root MTZ Import modülü kurulamadı: ${error.message ?: error::class.simpleName}"
                        }
                    }
                }) { Text(if (rootModuleUpdateRequested) "Güncelle" else "Kur") }
            },
            dismissButton = {
                TextButton(onClick = { showRootModuleConfirmation = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showRootModuleRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRootModuleRestartDialog = false },
            title = { Text("Yeniden başlatma gerekli") },
            text = {
                Text(
                    "Root MTZ Import modülü kurulu. Xiaomi Temalar importer'ının etkinleşmesi için telefonu şimdi yeniden başlatın.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRootModuleRestartDialog = false
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val result = PreferredPrivilegedCommandRunner(context.applicationContext)
                                    .run("setprop sys.powerctl reboot", 10)
                                if (result.exitCode != 0) {
                                    error(result.output.ifBlank { "exit ${'$'}{result.exitCode}" })
                                }
                            }
                        }.onFailure { error ->
                            status = "Telefon yeniden başlatılamadı: ${'$'}{error.message ?: error::class.simpleName}"
                        }
                    }
                }) { Text("Yeniden başlat") }
            },
            dismissButton = {
                TextButton(onClick = { showRootModuleRestartDialog = false }) { Text("Daha sonra") }
            },
        )
    }
}

private fun awaitDownload(manager: DownloadManager, downloadId: Long) {
    val deadline = System.currentTimeMillis() + 5 * 60_000L
    while (System.currentTimeMillis() < deadline) {
        manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            check(cursor.moveToFirst()) { "Xiaomi Themes download could not be found" }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> return
                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    error("Xiaomi Themes download failed: $reason")
                }
            }
        }
        Thread.sleep(500)
    }
    error("Xiaomi Themes download timed out")
}
