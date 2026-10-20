package dev.glorioustr.mtzstudio

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import dev.glorioustr.mtzstudio.tester.RootTargetApk
import dev.glorioustr.mtzstudio.tester.ThemeManagerContract
import dev.glorioustr.mtzstudio.tester.ThemeManagerInspector
import dev.glorioustr.mtzstudio.tester.ThemeManagerCapabilityProbe
import dev.glorioustr.mtzstudio.tester.ThemeManagerFamily
import dev.glorioustr.mtzstudio.tester.VerifiedThemeManagerApk
import dev.glorioustr.mtzstudio.tester.ThemeManagerRuntimeProfile
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeArtifacts
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeTarget
import dev.glorioustr.mtzstudio.shevery.SheveryAccess
import dev.glorioustr.mtzstudio.shevery.SheveryAuthorizationStatus
import dev.glorioustr.mtzstudio.shevery.PreferredPrivilegedCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ThemeManagerCompatibilityCard(
    inspector: ThemeManagerInspector,
    updater: RootThemeManagerUpdater,
    allowRootDowngrade: Boolean,
    neonStyle: Boolean = false,
) {
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
    val runtimeManager = remember {
        RootThemeRuntimeManager(
            context = context.applicationContext,
            updater = updater,
            moduleInstaller = rootModuleInstaller,
            onEvent = { event, message, details ->
                LiveDiagnosticsRecorder.get(context.applicationContext).record(event, message, details)
            },
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
    var showRootModuleInfo by remember { mutableStateOf(false) }
    var downgradeFailed by remember { mutableStateOf(false) }
    var rootModuleState by remember { mutableStateOf<RootThemeImportModuleInstaller.State?>(null) }
    var detectedRuntimeTarget by remember { mutableStateOf<ThemeRuntimeTarget?>(null) }
    var selectedRuntimeTarget by remember { mutableStateOf<ThemeRuntimeTarget?>(null) }
    var rootModuleCheckComplete by remember { mutableStateOf(!allowRootDowngrade) }
    var rootModuleUpdateRequested by remember { mutableStateOf(false) }
    val cyanAccent = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFF006A78) else Color(0xFF00DAF3)
    val selectedTarget = selectedRuntimeTarget ?: detectedRuntimeTarget ?: ThemeRuntimeTarget.GLOBAL
    val selectedArtifact = ThemeRuntimeArtifacts.forTarget(selectedTarget)
    val recommendedApk = if (allowRootDowngrade) {
        ThemeManagerContract.rootTargetApk(
            when (selectedTarget) {
                ThemeRuntimeTarget.GLOBAL -> ThemeManagerFamily.GLOBAL
                ThemeRuntimeTarget.NON_GLOBAL -> ThemeManagerFamily.CHINA
            },
        ) ?: error("No verified Xiaomi Themes APK is registered for $selectedTarget")
    } else {
        RootTargetApk(
            version = ThemeManagerContract.RECOMMENDED_VERSION,
            apkName = "Xiaomi_Themes_3.0.5.6-global.apk",
            sha256 = "24b99f995bf5f8509e591bdb1d36ce6f95260ec95648d13f9ddedc4e7d8edceb",
        )
    }
    val recommendedVersion = recommendedThemeManagerVersion(allowRootDowngrade, selectedTarget)
    val recommendedDownloadUrl =
        "https://raw.githubusercontent.com/GloriousApps/HyperOS-MTZ-Studio/main/requirements/${recommendedApk.apkName}"
    val adoptedExternalRuntime = rootModuleState?.adoptedExternalRuntime == true
    val experimentalRootlessRecovery = !allowRootDowngrade && BuildConfig.VERSION_NAME.contains("rootless-test")

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
            downgradeFailed = false
            status = resources.getString(R.string.tm_verifying_apk)
            var stagedApk: VerifiedThemeManagerApk? = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val downloadName = "mtzstudio-${System.currentTimeMillis()}-${recommendedApk.apkName}"
                    val request = DownloadManager.Request(Uri.parse(recommendedDownloadUrl))
                        .setTitle(recommendedApk.apkName)
                        .setDescription("Xiaomi Themes $recommendedVersion")
                        .setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, downloadName)
                    val downloadId = manager.enqueue(request)
                    try {
                        awaitDownload(manager, downloadId)
                        val descriptor = manager.openDownloadedFile(downloadId)
                        val verified = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                            updater.stageAndVerify(input, current, expectedVersion = recommendedVersion)
                        }
                        stagedApk = verified
                        check(verified.sha256.equals(recommendedApk.sha256, ignoreCase = true)) {
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
                    } finally {
                        // The installer consumes this public path; removing it before install races pm.
                        runCatching { manager.remove(downloadId) }
                    }
                }
            }.onSuccess { result ->
                installed = result.installedAfter
                runtimeProfile = withContext(Dispatchers.IO) {
                    ThemeManagerCapabilityProbe(context).probe(result.installedAfter)
                }
                status = result.message
            }.onFailure { error ->
                downgradeFailed = true
                status = resources.getString(R.string.tm_apk_rejected, error.message ?: error::class.simpleName)
                ThemeManagerWarningNotifier.notifyDowngradeFailure(
                    context = context,
                    targetVersion = recommendedVersion,
                    installedVersion = installed?.versionName
                        ?: resources.getString(R.string.tm_version_unknown),
                    reason = error.message ?: error::class.simpleName.orEmpty(),
                )
            }
            stagedApk?.let { runCatching { withContext(Dispatchers.IO) { updater.discard(it) } } }
        }
    }

    fun refresh() {
        scope.launch {
            var refreshedTarget = selectedTarget
            val detected = withContext(Dispatchers.IO) { inspector.inspect() }
            installed = detected
            val profile = withContext(Dispatchers.IO) { ThemeManagerCapabilityProbe(context).probe(detected) }
            runtimeProfile = profile
            rootModuleState = if (allowRootDowngrade) {
                runCatching { withContext(Dispatchers.IO) { rootModuleInstaller.inspect() } }.getOrNull()
            } else null
            if (allowRootDowngrade) {
                runCatching { withContext(Dispatchers.IO) { rootModuleInstaller.detectTarget() } }
                    .onSuccess { detected ->
                        detectedRuntimeTarget = detected
                        if (selectedRuntimeTarget == null) {
                            selectedRuntimeTarget = detected
                            refreshedTarget = detected
                        }
                    }
                runCatching { withContext(Dispatchers.IO) { runtimeManager.healthCheck() } }
            }
            rootModuleCheckComplete = true
            status = if (profile.compatibleLocalMtzPath) {
                resources.getString(R.string.tm_recommended_active)
            } else {
                resources.getString(
                    R.string.tm_recommendation_notice,
                    recommendedThemeManagerVersion(allowRootDowngrade, refreshedTarget),
                )
            }
        }
    }

    fun downloadRecommendedApk() {
        scope.launch {
            val current = installed ?: withContext(Dispatchers.IO) { inspector.inspect() }.also { installed = it }
            status = resources.getString(R.string.tm_verifying_apk)
            runCatching {
                withContext(Dispatchers.IO) {
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val downloadName = "mtzstudio-${System.currentTimeMillis()}-${recommendedApk.apkName}"
                    val request = DownloadManager.Request(Uri.parse(recommendedDownloadUrl))
                        .setTitle(recommendedApk.apkName)
                        .setDescription("Xiaomi Themes $recommendedVersion")
                        .setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, downloadName)
                    val downloadId = manager.enqueue(request)
                    try {
                        awaitDownload(manager, downloadId)
                        val descriptor = manager.openDownloadedFile(downloadId)
                        var staged: VerifiedThemeManagerApk? = null
                        try {
                            val verified = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                                updater.stageAndVerify(input, current, expectedVersion = recommendedVersion)
                            }
                            staged = verified
                            check(verified.sha256.equals(recommendedApk.sha256, ignoreCase = true)) {
                                "Downloaded Xiaomi Themes APK checksum does not match"
                            }
                            staged = null // ownership passes to the verifiedApk UI state
                            verified
                        } catch (failure: Exception) {
                            staged?.let { runCatching { updater.discard(it) } }
                            throw failure
                        }
                    } finally {
                        // Only the private verified copy is needed after staging.
                        runCatching { manager.remove(downloadId) }
                    }
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

    // Root capability is resolved asynchronously while the panel is first composed. Re-run the
    // module probe when that capability becomes available; otherwise a valid module can remain
    // displayed as "installable" from the initial non-root pass.
    LaunchedEffect(allowRootDowngrade) {
        rootModuleCheckComplete = !allowRootDowngrade
        refresh()
    }
    DisposableEffect(verifiedApk) {
        val staged = verifiedApk
        onDispose { staged?.let { runCatching { updater.discard(it) } } }
    }

    StudioCard(Modifier.fillMaxWidth(), neon = neonStyle) {
        val compatibleLocalMtzPath = runtimeProfile?.compatibleLocalMtzPath == true
        val applyActivityUnavailable = runtimeProfile != null && installed?.installed == true && !compatibleLocalMtzPath
        // Root mode is reserved for the exact runtime artifacts verified by the bundled module;
        // other builds stay on the normal compatibility path.
        val rootModuleMode = allowRootDowngrade && (
            adoptedExternalRuntime || supportsRootMtzImportModule(installed?.versionName)
        )
        val selectedRuntimeMatchesInstalled = isRootRuntimeVersionForTarget(installed?.versionName, selectedTarget)
        val rootRuntimeVersionMismatch = allowRootDowngrade && installed?.installed == true &&
            !selectedRuntimeMatchesInstalled && !adoptedExternalRuntime
        // The module can be installed and current while its runtime bridge is still waiting for
        // the next boot. Do not interpret a non-active bridge as a request to reinstall the module.
        val rootModuleCurrent = selectedRuntimeMatchesInstalled &&
            rootModuleInstaller.isBundledVersion(rootModuleState, selectedTarget)
        // The post-boot service verifies the live APK through runtime-active. The bridge marker
        // is a separate, stricter health signal and can remain unavailable until Themes starts;
        // it must not make an already-running module look as if it needs another reboot.
        val rootRuntimeReady = selectedRuntimeMatchesInstalled && (adoptedExternalRuntime ||
            (rootModuleCurrent &&
                rootModuleState?.let { state ->
                    state.runtimeActive &&
                        state.runtimeVerified &&
                        isRootRuntimeVersionForTarget(state.runtimeVersion, selectedTarget)
                } == true)
        )
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
                            adoptedExternalRuntime && rootRuntimeReady -> "Harici v7 runtime benimsendi"
                            rootModuleMode && rootRuntimeReady -> "Modül güncel"
                            rootModuleMode && rootModuleCurrent -> "Modül kurulu"
                            rootModuleMode && rootModuleState?.active == true -> "Güncelleme var"
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
                    if (adoptedExternalRuntime) {
                        "Xiaomi Temalar: ${current.versionName ?: stringResource(R.string.tm_version_unknown)} · Mods-Center V7"
                    } else if (rootModuleMode) "Xiaomi Temalar: ${current.versionName ?: stringResource(R.string.tm_version_unknown)}"
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
                if (rootRuntimeVersionMismatch) {
                    Text(
                        stringResource(
                            R.string.tm_root_runtime_version_mismatch,
                            recommendedVersion,
                            current.versionName ?: stringResource(R.string.tm_version_unknown),
                        ),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
                    if (!allowRootDowngrade && !experimentalRootlessRecovery) {
                        Text(
                            stringResource(R.string.tm_rootless_import_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (rootModuleMode) {
                    val module = rootModuleState
                    val compactReady = rootRuntimeReady
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Root çalışma zamanı", fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    adoptedExternalRuntime && rootRuntimeReady -> "Harici V7 runtime hazır"
                                    rootRuntimeReady -> "${targetLabel(selectedTarget)} runtime etkin"
                                    rootModuleCurrent -> "${targetLabel(selectedTarget)} modülü kurulu; çalışma durumu denetleniyor"
                                    module?.active == true -> "Başka bir runtime etkin"
                                    module?.installed == true -> "Yeniden başlatma bekleniyor"
                                    else -> "Kurulum bekleniyor"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (compactReady) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = cyanAccent.copy(alpha = 0.14f),
                                contentColor = cyanAccent,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text("Hazır", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        IconButton(onClick = { showRootModuleInfo = true }) {
                            Icon(Icons.Filled.Info, contentDescription = "Runtime bilgileri")
                        }
                    }
                    if (compactReady) {
                        Text(
                            if (rootModuleState?.activeTarget == selectedTarget) {
                                "Tema aktarımı için güvenli yol hazır."
                            } else {
                                "Modül etkin; Xiaomi Temalar köprüsü ilk kullanımda denetlenir. Yeniden kurulum gerekmez."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (rootModuleCurrent) {
                        Text(
                            "${targetLabel(selectedTarget)} root modülü kurulu. Modülü yeniden kurmanız gerekmez; çalışma durumu yeniden denetleniyor.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (!adoptedExternalRuntime) {
                    Text("Root çalışma zamanı hedefi", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Önerilen algılama: ${targetLabel(detectedRuntimeTarget)}. Seçim otomatik uygulanmaz; hedefi elle onaylayın.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Column {
                        ThemeRuntimeTarget.values().forEach { target ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = selectedTarget == target,
                                    onClick = { selectedRuntimeTarget = target },
                                )
                                Text(targetLabel(target))
                            }
                        }
                    }
                    Text(
                        "Seçili: ${targetLabel(selectedTarget)} · APK ${selectedArtifact.versionName} · kod ${selectedArtifact.versionCode} · API ${selectedArtifact.supportedAndroidMin}+",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (rootModuleCheckComplete) {
                        when {
                            module?.installed != true || !rootModuleCurrent -> Button(onClick = {
                                rootModuleUpdateRequested = false
                                showRootModuleConfirmation = true
                            }) {
                                Text(if (module?.installed == true) "Seçilen çalışma zamanını yükle" else "Root çalışma zamanı modülünü kur")
                            }

                            rootModuleCurrent -> Surface(
                                shape = RoundedCornerShape(50),
                                color = cyanAccent.copy(alpha = 0.14f),
                                contentColor = cyanAccent,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("Root MTZ Import modülü güncel", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            else -> Button(onClick = {
                                rootModuleUpdateRequested = true
                                showRootModuleConfirmation = true
                            }) {
                                Text("Seçilen çalışma zamanını güncelle")
                            }
                        }
                    } else {
                        Text("Root MTZ Import modülü denetleniyor…", style = MaterialTheme.typography.bodySmall)
                    }
                    }
                }

                // Compatibility requires a runtime-resolvable Xiaomi route. Legacy Global builds
                // use ApplyThemeForScreenshot; 10.8.7.6+ builds use the native local library.
                // The modern version family alone is not enough if its component is missing.
                if (applyActivityUnavailable && !allowRootDowngrade && !experimentalRootlessRecovery) {
                    Button(onClick = ::startShizukuDowngrade) {
                        Text(stringResource(R.string.tm_btn_shizuku_downgrade, ThemeManagerContract.RECOMMENDED_VERSION))
                    }
                }
            }
             if ((runtimeProfile != null && !compatibleLocalMtzPath && !rootModuleMode) || downgradeFailed) {
                 Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
             }

            if (experimentalRootlessRecovery) {
                RootlessThemeRecoveryCard(neonStyle = neonStyle, onChanged = ::refresh)
            }

            val current = installed
            if (shouldShowThemeManagerDowngradeButton(allowRootDowngrade, current, runtimeProfile, rootModuleMode)) {
                OutlinedButton(
                    onClick = {
                        downloadRecommendedApk()
                    },
                ) { Text(stringResource(R.string.tm_btn_download_apk, recommendedVersion)) }

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
                    ) { Text(stringResource(R.string.tm_btn_downgrade_to_version, recommendedVersion)) }
                }
            }
        }
    }

    if (showRootModuleInfo) {
        val module = rootModuleState
        AlertDialog(
            onDismissRequest = { showRootModuleInfo = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = cyanAccent)
                    Text("Runtime bilgileri")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (adoptedExternalRuntime) "Harici Mods-Center V7 runtime" else "MTZ Studio Root MTZ Import runtime",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("Modül: ${module?.externalModuleVersion ?: module?.version ?: "-"} · hedef: ${targetLabel(module?.activeTarget ?: selectedTarget)}")
                    Text("Themes: ${module?.runtimeVersion ?: installed?.versionName ?: "-"} · code ${module?.runtimeVersionCode ?: "-"}")
                    Text(
                        if (adoptedExternalRuntime) {
                            "Mevcut V7 kurulumu korunur; MTZ Studio üzerine APK yazmaz."
                        } else {
                            "APK doğrulaması ve root köprüsü hazır olduğunda tema aktarımı kullanılabilir."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showRootModuleInfo = false }) { Text("Kapat") }
            },
        )
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
                            downgradeFailed = false
                            status = resources.getString(R.string.tm_status_waiting_root)
                            runCatching {
                                withContext(Dispatchers.IO) { updater.installVerifiedDowngrade(apk) }
                            }.onSuccess { result ->
                                installed = result.installedAfter
                                runtimeProfile = withContext(Dispatchers.IO) {
                                    ThemeManagerCapabilityProbe(context).probe(result.installedAfter)
                                }
                                status = buildString {
                                    append(result.message).append(" · ").append(result.authorizationSource)
                                    if (result.commandOutput.isNotBlank()) append(" · ").append(result.commandOutput)
                                }
                                if (result.success) {
                                    downgradeFailed = false
                                    withContext(Dispatchers.IO) { updater.discard(apk) }
                                    verifiedApk = null
                                    riskAccepted = false
                                } else {
                                    downgradeFailed = true
                                    val currentVersion = result.installedAfter.versionName
                                        ?: resources.getString(R.string.tm_version_unknown)
                                    val reason = listOf(result.message, result.commandOutput)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · ")
                                    status = resources.getString(
                                        R.string.tm_downgrade_failed,
                                        recommendedVersion,
                                        currentVersion,
                                        reason,
                                    )
                                    ThemeManagerWarningNotifier.notifyDowngradeFailure(
                                        context = context,
                                        targetVersion = recommendedVersion,
                                        installedVersion = currentVersion,
                                        reason = reason,
                                    )
                                }
                            }.onFailure { error ->
                                downgradeFailed = true
                                status = resources.getString(R.string.tm_status_root_failed, error.message ?: error::class.simpleName)
                                ThemeManagerWarningNotifier.notifyDowngradeFailure(
                                    context = context,
                                    targetVersion = recommendedVersion,
                                    installedVersion = installed?.versionName
                                        ?: resources.getString(R.string.tm_version_unknown),
                                    reason = error.message ?: error::class.simpleName.orEmpty(),
                                )
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
                    "${targetLabel(selectedTarget)} için doğrulanmış Xiaomi Temalar APK'sı Package Manager ile değiştirilecek; veriler korunur. " +
                        "Paket yöneticisi düşürme işlemini reddedebilir. Global köprü için yeniden başlatma gerekir; sistem mount işlemi sessizce yapılmaz. " +
                        "APK'yi geri almak için modülü kaldırmak yeterli değildir; önceki uyumlu APK ayrıca kurulmalıdır. " +
                        if (selectedTarget == ThemeRuntimeTarget.NON_GLOBAL) {
                            "Non-Global sistem APK'sı modül kaldırılsa bile kurulu kalır."
                        } else {
                            ""
                        },
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
                            withContext(Dispatchers.IO) { rootModuleInstaller.installOrUpdate(selectedTarget) }
                        }.onSuccess { result ->
                            // PM may have replaced the package even when the module swap later
                            // fails; refresh both views from the live device after completion.
                            val refreshedInstalled = withContext(Dispatchers.IO) { inspector.inspect() }
                            installed = refreshedInstalled
                            runtimeProfile = withContext(Dispatchers.IO) {
                                ThemeManagerCapabilityProbe(context).probe(refreshedInstalled)
                            }
                            rootModuleState = withContext(Dispatchers.IO) { rootModuleInstaller.inspect() }
                            status = if (result.target == ThemeRuntimeTarget.GLOBAL) {
                                "${targetLabel(result.target)} çalışma zamanı ${result.version} kuruldu. Köprüyü etkinleştirmek için telefonu yeniden başlatın."
                            } else {
                                "${targetLabel(result.target)} çalışma zamanı ${result.version} kuruldu ve canlı olarak doğrulandı."
                            }
                            if (result.target == ThemeRuntimeTarget.GLOBAL) showRootModuleRestartDialog = true
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
            title = { Text("Global köprü için yeniden başlatma gerekli") },
            text = {
                Text(
                    "Global çalışma zamanı kurulu. Xiaomi Temalar köprüsünün etkinleşmesi için telefonu şimdi yeniden başlatın.",
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

internal fun shouldShowThemeManagerDowngradeButton(
    allowRootDowngrade: Boolean,
    current: InstalledThemeManager?,
    runtimeProfile: ThemeManagerRuntimeProfile?,
    rootModuleMode: Boolean,
): Boolean {
    if (!allowRootDowngrade || current?.installed != true || runtimeProfile == null) return false
    if (runtimeProfile.compatibleLocalMtzPath) return false
    return !rootModuleMode
}

private fun targetLabel(target: ThemeRuntimeTarget?): String = when (target) {
    ThemeRuntimeTarget.GLOBAL -> "Global 3.4.1.23"
    ThemeRuntimeTarget.NON_GLOBAL -> "Non-Global 11.5.3.1"
    null -> "algılanamadı"
}

internal fun supportsRootMtzImportModule(versionName: String?): Boolean {
    val canonicalVersion = ThemeManagerContract.canonicalVersion(versionName) ?: return false
    // The bundled bridge is verified only for the exact artifact selected for the detected
    // Theme Manager family; an unknown newer build must stay on the incompatible path.
    val target = when (ThemeManagerContract.familyOf(versionName)) {
        ThemeManagerFamily.GLOBAL -> ThemeRuntimeTarget.GLOBAL
        ThemeManagerFamily.CHINA -> ThemeRuntimeTarget.NON_GLOBAL
        ThemeManagerFamily.UNKNOWN -> return false
    }
    return canonicalVersion == ThemeRuntimeArtifacts.forTarget(target).version
}

internal fun isRootRuntimeVersionForTarget(
    versionName: String?,
    target: ThemeRuntimeTarget,
): Boolean = ThemeManagerContract.canonicalVersion(versionName) ==
    ThemeManagerContract.canonicalVersion(ThemeRuntimeArtifacts.forTarget(target).versionName)

internal fun recommendedThemeManagerVersion(
    allowRootDowngrade: Boolean,
    target: ThemeRuntimeTarget,
): String = if (allowRootDowngrade) {
    ThemeRuntimeArtifacts.forTarget(target).version
} else {
    ThemeManagerContract.RECOMMENDED_VERSION
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
