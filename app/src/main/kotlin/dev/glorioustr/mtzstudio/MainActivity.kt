package dev.glorioustr.mtzstudio

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import dev.glorioustr.mtzstudio.composer.ComponentSelection
import dev.glorioustr.mtzstudio.composer.CompositionMetadata
import dev.glorioustr.mtzstudio.composer.CompositionRequest
import dev.glorioustr.mtzstudio.composer.CompositionResult
import dev.glorioustr.mtzstudio.composer.CompositionSource
import dev.glorioustr.mtzstudio.composer.MtzComposer
import dev.glorioustr.mtzstudio.core.ComponentCategory
import dev.glorioustr.mtzstudio.core.ThemeId
import dev.glorioustr.mtzstudio.library.LibraryTheme
import dev.glorioustr.mtzstudio.library.StudioBackupManager
import dev.glorioustr.mtzstudio.library.ThemeLibrary
import dev.glorioustr.mtzstudio.tester.RootThemeManagerUpdater
import dev.glorioustr.mtzstudio.tester.ThemeManagerInspector
import dev.glorioustr.mtzstudio.tester.ThemeManagerBehavior
import dev.glorioustr.mtzstudio.tester.ThemeManagerCapabilityProbe
import dev.glorioustr.mtzstudio.tester.StudioCapabilityPolicy
import dev.glorioustr.mtzstudio.shevery.PreferredPrivilegedCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

private const val MAX_SAFE_FULL_CATALOG_THEMES = 24
private val SHIZUKU_MANAGER_PACKAGES = listOf(
    "moe.shizuku.privileged.api",
    "com.hamondev.shevery",
)
private val ROOT_MANAGER_PACKAGES = listOf(
    "me.yuki.folk",
    "com.topjohnwu.magisk",
    "me.weishu.kernelsu",
    "me.bmax.apatch",
    "com.rifsxd.ksunext",
    "com.sukisu.ultra",
    "io.github.vvb2060.magisk",
)

private data class AuthorizationManagerApp(val packageName: String, val displayName: String)

private fun Context.installedAuthorizationManager(): AuthorizationManagerApp? =
    SHIZUKU_MANAGER_PACKAGES.firstNotNullOfOrNull { packageName ->
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            AuthorizationManagerApp(packageName, packageManager.getApplicationLabel(info).toString())
        }.getOrNull()
    }

private fun Context.installedRootManager(): AuthorizationManagerApp? =
    ROOT_MANAGER_PACKAGES.firstNotNullOfOrNull { packageName ->
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            AuthorizationManagerApp(packageName, packageManager.getApplicationLabel(info).toString())
        }.getOrNull()
    }

class MainActivity : ComponentActivity() {
    private var shizukuSetupRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeShizukuSetupIntent(intent)
        // Run the launch check through JobScheduler so network waiting and downloading can
        // continue even when Studio leaves the foreground.
        AppUpdateScheduler.schedule(applicationContext, checkNow = false)
        val library = ThemeLibrary(applicationContext)
        val backupManager = StudioBackupManager(applicationContext)
        val composer = MtzComposer()
        val themeManagerInspector = ThemeManagerInspector(applicationContext)
        val privilegedRunner = PreferredPrivilegedCommandRunner(applicationContext)
        val themeManagerUpdater = RootThemeManagerUpdater(
            context = applicationContext,
            inspector = themeManagerInspector,
            commandRunner = privilegedRunner,
        )
        val themeApplyCoordinator = ThemeApplyCoordinator(applicationContext, privilegedRunner)
        val bakImporter = ThemeManagerBakImporter(applicationContext, privilegedRunner)
        val themeLanguageTool = ThemeLanguageTool(applicationContext, library)
        val deviceThemeImporter = DeviceThemeImporter(
            context = applicationContext,
            library = library,
            composer = composer,
            commandRunner = privilegedRunner,
        )
        val diagnostics = LiveDiagnosticsRecorder.get(applicationContext)
        val appearanceStore = AppearanceStore(applicationContext)
        val installedThemeManager = themeManagerInspector.inspect()
        val capabilityProfile = ThemeManagerCapabilityProbe(applicationContext).probe(installedThemeManager)
        val globalThemeProtectionRequired = installedThemeManager.requiresGlobalThemeProtection
        val modernThemeManagerMode = installedThemeManager.usesModernNativeLibrary
        diagnostics.record("activity_started", "Uygulama ekranı açıldı", mapOf(
            "themeManagerVersion" to installedThemeManager.versionName,
            "provider" to if (modernThemeManagerMode) "modern" else "global",
            "restored" to (savedInstanceState != null),
            "knownBehavior" to capabilityProfile.knownBehavior,
            "legacyTesterResolvable" to capabilityProfile.legacyTesterResolvable,
            "modernLocalLibraryResolvable" to capabilityProfile.modernLocalLibraryResolvable,
            "compatibleLocalMtzPath" to capabilityProfile.compatibleLocalMtzPath,
            "splitApkCount" to capabilityProfile.splitApkCount,
            "exportedThemeActivityCandidates" to capabilityProfile.exportedThemeActivityCandidates.joinToString(),
        ))
        setContent {
            var appearance by remember { mutableStateOf(appearanceStore.load()) }
            var contentStyle by remember { mutableStateOf(appearanceStore.loadContentStyle()) }
            StudioAppTheme(appearance, contentStyle) {
                StudioScreen(
                    library = library,
                    backupManager = backupManager,
                    composer = composer,
                    themeManagerInspector = themeManagerInspector,
                    themeManagerUpdater = themeManagerUpdater,
                    privilegedRunner = privilegedRunner,
                    themeApplyCoordinator = themeApplyCoordinator,
                    bakImporter = bakImporter,
                    themeLanguageTool = themeLanguageTool,
                    deviceThemeImporter = deviceThemeImporter,
                    diagnostics = diagnostics,
                    documentDiagnostics = ::documentDiagnostics,
                    openInput = contentResolver::openInputStream,
                    openOutput = { uri -> contentResolver.openOutputStream(uri) },
                    shareMtz = { share(it, "application/zip", "Export MTZ") },
                    shareDiagnostics = { share(it, "text/plain", "Export diagnostics") },
                    shareThemeManagerApk = { share(it, "application/vnd.android.package-archive", "Export Xiaomi Themes APK") },
                    openAppShareForThemesExport = ::openAppShareForThemesExport,
                    appearance = appearance,
                    onAppearanceChange = { selected ->
                        appearanceStore.save(selected)
                        appearance = selected
                    },
                    contentStyle = contentStyle,
                    onContentStyleChange = { selected ->
                        appearanceStore.saveContentStyle(selected)
                        contentStyle = selected
                    },
                    globalThemeProtectionRequired = globalThemeProtectionRequired,
                    modernThemeManagerMode = modernThemeManagerMode,
                    themeManagerBehavior = installedThemeManager.behavior,
                    shizukuSetupRequest = shizukuSetupRequest,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A unique short-lived job performs a quiet check whenever Studio returns to foreground.
        AppUpdateScheduler.schedule(applicationContext, checkNow = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeShizukuSetupIntent(intent)
    }

    private fun consumeShizukuSetupIntent(intent: Intent?) {
        if (intent?.action != ShizukuSetupSession.ACTION_RETURN_TO_SETUP) return
        intent.action = null
        ShizukuSetupSession.start(applicationContext)
        shizukuSetupRequest++
    }

    private fun documentDiagnostics(uri: Uri): SelectedDocumentDiagnostics {
        var name: String? = null
        var size: Long? = null
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor: Cursor? = contentResolver.query(uri, columns, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex).takeIf { value -> value >= 0 }
            }
        }
        return SelectedDocumentDiagnostics(name, size, uri.scheme, uri.authority)
    }

    private fun share(path: Path, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", path.toFile())
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(chooserTitle, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, chooserTitle))
    }

    private fun openAppShareForThemesExport() {
        val appShare = packageManager.getLaunchIntentForPackage("com.software41.appshare")
        if (appShare != null) {
            startActivity(appShare)
        } else {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.software41.appshare"),
                ),
            )
        }
    }
}

internal data class UiSelection(
    val themeId: ThemeId,
    val category: ComponentCategory,
    val rootPath: String,
    val useDefault: Boolean = false,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StudioScreen(
    library: ThemeLibrary,
    backupManager: StudioBackupManager,
    composer: MtzComposer,
    themeManagerInspector: ThemeManagerInspector,
    themeManagerUpdater: RootThemeManagerUpdater,
    privilegedRunner: PreferredPrivilegedCommandRunner,
    themeApplyCoordinator: ThemeApplyCoordinator,
    bakImporter: ThemeManagerBakImporter,
    themeLanguageTool: ThemeLanguageTool,
    deviceThemeImporter: DeviceThemeImporter,
    diagnostics: LiveDiagnosticsRecorder,
    documentDiagnostics: (Uri) -> SelectedDocumentDiagnostics,
    openInput: (Uri) -> InputStream?,
    openOutput: (Uri) -> OutputStream?,
    shareMtz: (Path) -> Unit,
    shareDiagnostics: (Path) -> Unit,
    shareThemeManagerApk: (Path) -> Unit,
    openAppShareForThemesExport: () -> Unit,
    appearance: AppAppearance,
    onAppearanceChange: (AppAppearance) -> Unit,
    contentStyle: AppContentStyle,
    onContentStyleChange: (AppContentStyle) -> Unit,
    globalThemeProtectionRequired: Boolean,
    modernThemeManagerMode: Boolean,
    themeManagerBehavior: ThemeManagerBehavior,
    shizukuSetupRequest: Int,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var themes by remember { mutableStateOf<List<LibraryTheme>>(emptyList()) }
    var checkingImportAccess by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(resources.getString(R.string.status_loading_library)) }
    val defaultCompositionName = stringResource(R.string.default_composition_name)
    var compositionName by rememberSaveable { mutableStateOf(defaultCompositionName) }
    var compositionMakerName by rememberSaveable { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<CompositionResult?>(null) }
    val selections = remember { mutableStateMapOf<ComponentCategory, UiSelection>() }
    val diagnosticState by diagnostics.state.collectAsState()
    val translationProgress by ThemeTranslationProgressStore.state.collectAsState()
    val cloudTransferState by CloudTransferStore.state.collectAsState()
    val appUpdateState by AppUpdateStore.state.collectAsState()
    val themeProtectionState by ThemeProtectionServiceClient.state.collectAsState()
    var destination by rememberSaveable { mutableStateOf(StudioDestination.HOME) }
    var returnDestination by rememberSaveable { mutableStateOf(StudioDestination.HOME) }
    var bakImporting by remember { mutableStateOf(false) }
    var pendingBakArchive by remember { mutableStateOf<ThemeManagerBakArchive?>(null) }
    var translateBakToAppLanguage by remember { mutableStateOf(false) }
    var pendingApplyTheme by remember { mutableStateOf<LibraryTheme?>(null) }
    var pendingOcrTheme by remember { mutableStateOf<LibraryTheme?>(null) }
    var preparedApply by remember { mutableStateOf<PreparedThemeApply?>(null) }
    var themeOperationRunning by remember { mutableStateOf(false) }
    var mtzImportTotal by remember { mutableIntStateOf(0) }
    var mtzImportCompleted by remember { mutableIntStateOf(0) }
    var mtzImportSucceeded by remember { mutableIntStateOf(0) }
    var mtzImportFailed by remember { mutableIntStateOf(0) }
    var mtzImportCurrentName by remember { mutableStateOf<String?>(null) }
    var mtzBatchDocuments by remember { mutableStateOf<List<MtzFolderDocument>?>(null) }
    var mtzBatchSelection by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var showUpdateCheckResult by remember { mutableStateOf(false) }
    var dismissedUpdateEventId by rememberSaveable { mutableStateOf(0L) }
    var installUpdateWhenReady by rememberSaveable { mutableStateOf(false) }
    var modernImportObserverJob by remember { mutableStateOf<Job?>(null) }
    var backupStatus by remember { mutableStateOf(resources.getString(R.string.status_no_backup_yet)) }
    val cloudAccountStore = remember { CloudAccountStore(context) }
    val appUpdateManager = remember { AppUpdateManager(context.applicationContext) }
    var cloudAccount by remember { mutableStateOf(cloudAccountStore.load()) }
    var customHomeWallpaperUri by rememberSaveable { mutableStateOf<String?>(null) }
    var customLockWallpaperUri by rememberSaveable { mutableStateOf<String?>(null) }
    var baseThemeId by rememberSaveable { mutableStateOf<String?>(null) }
    var themeDeviceImportStatus by remember { mutableStateOf(resources.getString(R.string.device_import_idle)) }
    var fontDeviceImportStatus by remember { mutableStateOf(resources.getString(R.string.device_import_idle)) }
    var deviceImportRunning by remember { mutableStateOf(false) }
    val pauseCatalog = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val catalogProgress by deviceThemeImporter.catalogProgress.collectAsState()
    var availableDeviceThemes by remember { mutableStateOf<List<DeviceThemeSummary>>(emptyList()) }
    var isScanningDeviceThemes by remember { mutableStateOf(false) }
    var showDeviceThemePicker by remember { mutableStateOf(false) }
    var largeCatalogThemeCount by remember { mutableStateOf<Int?>(null) }
    var showThemeProtectionRestartDialog by remember { mutableStateOf(false) }
    val studioState = remember { context.getSharedPreferences("studio-ui-state", 0) }
    var rootAccessAvailable by remember { mutableStateOf<Boolean?>(null) }
    // Keep this unknown until the asynchronous root/Shizuku probe finishes. Rendering STANDARD
    // here caused a misleading rootless card to flash briefly on rooted devices.
    var accessMode by remember { mutableStateOf<StudioAccessMode?>(null) }
    var authorizationManager by remember { mutableStateOf(context.installedAuthorizationManager()) }
    var rootManager by remember { mutableStateOf(context.installedRootManager()) }
    val capabilities = StudioCapabilityPolicy(
        rootAvailable = rootAccessAvailable == true,
        themeManagerBehavior = themeManagerBehavior,
    )
    var catalogLoadFinished by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var activeThemeId by rememberSaveable {
        mutableStateOf(studioState.getString("last-applied-theme-id", null))
    }
    var previousThemeId by rememberSaveable {
        mutableStateOf(studioState.getString("previous-applied-theme-id", null))
    }
    var lastAppliedProtocol by rememberSaveable {
        mutableStateOf(studioState.getString("last-applied-protocol", null))
    }

    androidx.compose.runtime.LaunchedEffect(shizukuSetupRequest) {
        if (shizukuSetupRequest > 0) destination = StudioDestination.HOME
    }

    fun rememberAppliedTheme(themeId: String, protocol: ThemeApplyProtocol) {
        if (activeThemeId != null && activeThemeId != themeId) previousThemeId = activeThemeId
        activeThemeId = themeId
        lastAppliedProtocol = protocol.name
        studioState.edit()
            .putString("last-applied-theme-id", themeId)
            .putString("previous-applied-theme-id", previousThemeId)
            .putString("last-applied-protocol", protocol.name)
            .apply()
    }

    fun persistPreparedApply(prepared: PreparedThemeApply) {
        if (prepared.protocol == ThemeApplyProtocol.MODERN_THEME_MANAGER_BRIDGE ||
            prepared.protocol == ThemeApplyProtocol.ROOT_GLOBAL_THEME_MANAGER_BRIDGE
        ) {
            prepared.intent.putExtra(ThemeManagerBridgeContract.EXTRA_DIAGNOSTIC_RECEIVER, diagnostics.nativeStepReceiver())
        }
        diagnostics.record("theme_request_ready", "Temalar işlemine geçiliyor", mapOf(
            "operation" to prepared.operation, "theme" to prepared.themeName,
            "themeId" to prepared.themeId, "localId" to prepared.themeManagerLocalId,
            "protocol" to prepared.protocol,
        ))
        studioState.edit()
            .putLong("pending-started-at", System.currentTimeMillis())
            .putString("pending-theme-id", prepared.themeId)
            .putString("pending-theme-name", prepared.themeName)
            .putString("pending-staged-path", prepared.stagedPath)
            .putString("pending-protocol", prepared.protocol.name)
            .putString("pending-manual-path", prepared.manualImportPath)
            .putString("pending-operation", prepared.operation.name)
            .putString("pending-local-id", prepared.themeManagerLocalId)
            .putString(
                "pending-local-ids-before",
                prepared.themeManagerLocalIdsBefore?.joinToString("|") ?: "__not_captured__",
            )
            .putString("pending-intent-uri", prepared.intent.toUri(Intent.URI_INTENT_SCHEME))
            .apply()
    }

    fun restorePreparedApply(): PreparedThemeApply? = runCatching {
        val themeId = studioState.getString("pending-theme-id", null) ?: return@runCatching null
        val themeName = studioState.getString("pending-theme-name", null) ?: return@runCatching null
        PreparedThemeApply(
            themeId = themeId,
            themeName = themeName,
            stagedPath = studioState.getString("pending-staged-path", "").orEmpty(),
            intent = Intent.parseUri(
                studioState.getString("pending-intent-uri", null) ?: return@runCatching null,
                Intent.URI_INTENT_SCHEME,
            ),
            protocol = ThemeApplyProtocol.valueOf(
                studioState.getString("pending-protocol", ThemeApplyProtocol.LEGACY_TESTER.name).orEmpty(),
            ),
            manualImportPath = studioState.getString("pending-manual-path", null),
            operation = ThemeManagerOperation.valueOf(
                studioState.getString("pending-operation", ThemeManagerOperation.APPLY.name).orEmpty(),
            ),
            themeManagerLocalId = studioState.getString("pending-local-id", null),
            themeManagerLocalIdsBefore = studioState.getString("pending-local-ids-before", "__not_captured__")
                ?.takeUnless { it == "__not_captured__" }
                ?.split('|')
                ?.filter(String::isNotBlank)
                ?.toSet(),
        )
    }.getOrNull()

    fun clearPreparedApply() {
        studioState.edit()
            .remove("pending-theme-id")
            .remove("pending-theme-name")
            .remove("pending-staged-path")
            .remove("pending-protocol")
            .remove("pending-manual-path")
            .remove("pending-operation")
            .remove("pending-local-id")
            .remove("pending-local-ids-before")
            .remove("pending-intent-uri")
            .remove("pending-started-at")
            .apply()
    }

    val homeWallpaperPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            customHomeWallpaperUri = uri.toString()
            status = resources.getString(R.string.custom_home_wallpaper_selected)
        }
    }

    val lockWallpaperPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            customLockWallpaperUri = uri.toString()
            status = resources.getString(R.string.custom_lock_wallpaper_selected)
        }
    }

    fun saveGoogleDriveAuthorization(result: AuthorizationResult) {
        runCatching {
            check(!result.accessToken.isNullOrBlank()) { "Google Drive erişim anahtarı alınamadı" }
            cloudAccountStore.save(
                CloudAccount(
                    provider = CloudProvider.GOOGLE_DRIVE,
                    accountName = GoogleDriveAppDataStore.accountName(result),
                    oauthBacked = true,
                    isConnected = true,
                ),
            )
            cloudAccount = cloudAccountStore.load()
            backupStatus = resources.getString(R.string.status_cloud_connected, cloudAccount.accountName)
        }.onFailure { error ->
            backupStatus = resources.getString(R.string.status_backup_failed, error.message ?: error::class.simpleName)
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { appUpdateManager.restoreState() }
    }

    val googleDriveAuthorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK && activityResult.data != null) {
            runCatching {
                Identity.getAuthorizationClient(context)
                    .getAuthorizationResultFromIntent(activityResult.data!!)
            }.onSuccess(::saveGoogleDriveAuthorization)
                .onFailure { error ->
                    backupStatus = resources.getString(R.string.status_backup_failed, error.message ?: error::class.simpleName)
                }
        } else {
            backupStatus = resources.getString(R.string.status_backup_failed, "Google Drive bağlantısı iptal edildi")
        }
    }

    val onChooseGoogleAccount: () -> Unit = {
        backupStatus = resources.getString(R.string.status_backup_preparing)
        Identity.getAuthorizationClient(context)
            .authorize(GoogleDriveAppDataStore.authorizationRequest())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent != null) {
                        googleDriveAuthorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                        )
                    } else {
                        backupStatus = resources.getString(R.string.status_backup_failed, "Google Drive izin ekranı açılamadı")
                    }
                } else {
                    saveGoogleDriveAuthorization(result)
                }
            }
            .addOnFailureListener { error ->
                backupStatus = resources.getString(R.string.status_backup_failed, error.message ?: error::class.simpleName)
            }
    }

    suspend fun loadLibrarySnapshot(): Boolean {
        val snapshot = withContext(Dispatchers.IO) { library.load() }
        themes = snapshot.themes
        status = when {
            snapshot.warnings.isNotEmpty() -> resources.getString(R.string.status_library_warnings, snapshot.warnings.size)
            themes.isEmpty() -> resources.getString(R.string.status_library_empty)
            else -> resources.getString(R.string.status_library_ready)
        }
        return snapshot.warnings.isEmpty()
    }

    fun reload(openThemesAfter: Boolean = false) {
        scope.launch {
            val valid = loadLibrarySnapshot()
            if (openThemesAfter && valid) destination = StudioDestination.THEMES
        }
    }

    fun localizeTheme(theme: LibraryTheme, experimentalOcr: Boolean) {
        if (themeOperationRunning) return
        themeOperationRunning = true
        status = resources.getString(R.string.theme_language_tool_working)
        ThemeTranslationService.start(
            context,
            theme.id.value,
            theme.archive.metadata?.name ?: theme.displayName,
            experimentalOcr,
        )
    }

    androidx.compose.runtime.LaunchedEffect(translationProgress) {
        when {
            translationProgress.running -> {
                themeOperationRunning = true
                status = resources.getString(R.string.theme_language_tool_working) +
                    if (translationProgress.total > 0) " ${translationProgress.processed}/${translationProgress.total}" else ""
            }
            translationProgress.completed -> {
                themeOperationRunning = false
                if (translationProgress.error == null) {
                    loadLibrarySnapshot()
                    destination = StudioDestination.THEMES
                    status = resources.getString(R.string.theme_language_tool_complete)
                    val translatedTheme = translationProgress.themeId?.let { id ->
                        themes.firstOrNull { it.id.value == id }
                    }
                    if (translatedTheme != null) pendingOcrTheme = translatedTheme
                } else {
                    status = resources.getString(R.string.theme_language_tool_failed, translationProgress.error)
                    operationError = status
                }
            }
        }
    }

    var handledCloudCompletion by rememberSaveable { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(cloudTransferState) {
        if (cloudTransferState.running) {
            backupStatus = resources.getString(
                if (cloudTransferState.operation == CloudTransferOperation.BACKUP) R.string.status_backup_preparing
                else R.string.status_restore_preparing,
            )
        } else if (cloudTransferState.completed && cloudTransferState.completionId != handledCloudCompletion) {
            handledCloudCompletion = cloudTransferState.completionId
            if (cloudTransferState.error != null) {
                backupStatus = resources.getString(R.string.status_backup_failed, cloudTransferState.error)
            } else if (cloudTransferState.operation == CloudTransferOperation.BACKUP) {
                cloudAccount = cloudAccountStore.load()
                backupStatus = resources.getString(R.string.status_cloud_upload_success, cloudAccount.provider.displayName)
            } else {
                loadLibrarySnapshot()
                backupStatus = resources.getString(
                    R.string.status_restore_success,
                    cloudTransferState.themeCount,
                    cloudTransferState.fileCount,
                )
            }
        }
    }

    val applyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val prepared = preparedApply ?: restorePreparedApply()
        val requestStartedAt = studioState.getLong("pending-started-at", System.currentTimeMillis())
        diagnostics.record("theme_activity_returned", "Temalar ekranından yanıt geldi", mapOf(
            "operation" to prepared?.operation, "theme" to prepared?.themeName,
            "resultCode" to result.resultCode,
            "bridgeResult" to result.data?.getStringExtra(ThemeManagerBridgeContract.EXTRA_RESULT),
            "error" to result.data?.getStringExtra(ThemeManagerBridgeContract.EXTRA_ERROR),
            "durationMs" to (System.currentTimeMillis() - requestStartedAt),
        ))
        result.data?.getStringArrayListExtra(ThemeManagerBridgeContract.EXTRA_DIAGNOSTIC_TRACE)
            ?.take(40)?.forEach(diagnostics::recordNativeStep)
        preparedApply = null
        themeOperationRunning = false
        pauseCatalog.set(false)
        clearPreparedApply()
        if (prepared != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.delay(10_000)
                    runCatching { themeApplyCoordinator.cleanup(prepared) }
                }
            }
            when (prepared.protocol) {
                ThemeApplyProtocol.LEGACY_TESTER -> {
                    diagnostics.record("legacy_apply_unverified", "Global tester çağrısı döndü; bu protokol kesin uygulama sonucu bildirmiyor", mapOf("theme" to prepared.themeName))
                    status = resources.getString(R.string.status_legacy_apply_unverified, prepared.themeName)
                    rememberAppliedTheme(prepared.themeId, prepared.protocol)
                }

                ThemeApplyProtocol.MODERN_THEME_MANAGER_BRIDGE,
                ThemeApplyProtocol.ROOT_GLOBAL_THEME_MANAGER_BRIDGE -> {
                    val bridgeSucceeded = result.resultCode == Activity.RESULT_OK &&
                        result.data?.getStringExtra(ThemeManagerBridgeContract.EXTRA_RESULT) == ThemeManagerBridgeContract.RESULT_OK
                    if (bridgeSucceeded) {
                        diagnostics.record("theme_operation_completed", "Temalar işlemi tamamlandı", mapOf("operation" to prepared.operation, "theme" to prepared.themeName))
                        val localId = result.data?.getStringExtra(ThemeManagerBridgeContract.EXTRA_THEME_LOCAL_ID)
                        when (prepared.operation) {
                            ThemeManagerOperation.APPLY -> {
                                // Root Global Themes applies through its native importer. Each
                                // completed import receives a fresh local ID, so retain it here
                                // rather than leaving Studio linked to the older record.
                                if (!localId.isNullOrBlank()) {
                                    scope.launch(Dispatchers.IO) {
                                        library.load().themes.firstOrNull {
                                            it.id.value == prepared.themeId
                                        }?.let { applied ->
                                            deviceThemeImporter.rememberThemeManagerOrigin(localId, applied)
                                        }
                                    }
                                }
                                status = resources.getString(R.string.status_apply_success, prepared.themeName)
                                rememberAppliedTheme(prepared.themeId, prepared.protocol)
                            }
                            ThemeManagerOperation.IMPORT_ONLY -> {
                                scope.launch {
                                    // A Theme Manager operation can recreate this activity before
                                    // its initial library load completes. Resolve the persisted item.
                                    withContext(Dispatchers.IO) {
                                        library.load().themes.firstOrNull {
                                            it.id.value == prepared.themeId
                                        }?.let { imported ->
                                            if (!localId.isNullOrBlank()) {
                                                deviceThemeImporter.rememberThemeManagerOrigin(localId, imported)
                                            }
                                        }
                                    }
                                    status = resources.getString(R.string.status_modern_theme_imported, prepared.themeName)
                                    reload(openThemesAfter = true)
                                }
                            }
                            ThemeManagerOperation.DELETE -> {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        library.deleteTheme(ThemeId(prepared.themeId))
                                        prepared.themeManagerLocalId?.let(deviceThemeImporter::forgetThemeManagerOrigin)
                                    }
                                    if (activeThemeId == prepared.themeId) {
                                        activeThemeId = null
                                        studioState.edit().remove("last-applied-theme-id").apply()
                                    }
                                    status = resources.getString(R.string.status_theme_removed, prepared.themeName)
                                    reload()
                                }
                            }
                        }
                    } else {
                        diagnostics.record("theme_operation_unconfirmed", "Temalar işlemi başarısız veya sonuç doğrulanamadı", mapOf(
                            "operation" to prepared.operation, "theme" to prepared.themeName,
                            "error" to result.data?.getStringExtra(ThemeManagerBridgeContract.EXTRA_ERROR),
                        ))
                        scope.launch(Dispatchers.IO) { themeApplyCoordinator.captureFailureDiagnostics(requestStartedAt) }
                        // Do not bounce straight back to the host after a failed or cancelled request.
                        status = resources.getString(
                            R.string.status_apply_failed,
                            result.data?.getStringExtra(ThemeManagerBridgeContract.EXTRA_ERROR)
                                ?: resources.getString(R.string.error_modern_bridge_failed),
                        )
                        operationError = status
                    }
                }

                ThemeApplyProtocol.MODERN_THEME_MANAGER_DIRECT_APPLY -> {
                    // Xiaomi's exported 10.8/11 detail activity consumes REQUEST_APPLY_EVENT
                    // internally. Those builds do not return a structured result to the caller,
                    // so retain the selected theme and let the persistence monitor verify it.
                    diagnostics.record(
                        "modern_direct_apply_dispatched",
                        "Xiaomi Temalar yerel kaydı doğrudan uygulama isteğini aldı",
                        mapOf("theme" to prepared.themeName, "localId" to prepared.themeManagerLocalId),
                    )
                    status = resources.getString(R.string.status_apply_success, prepared.themeName)
                    rememberAppliedTheme(prepared.themeId, prepared.protocol)
                }

                ThemeApplyProtocol.MODERN_THEME_MANAGER_MANUAL_IMPORT -> {
                    status = resources.getString(
                        R.string.status_manual_import_ready,
                        prepared.manualImportPath.orEmpty(),
                    )
                }

                ThemeApplyProtocol.ROOTLESS_MANUAL_IMPORT -> {
                    diagnostics.record(
                        "rootless_manual_returned",
                        "Rootsuz Temalar yönlendirmesinden uygulamaya dönüldü",
                        mapOf("theme" to prepared.themeName),
                    )
                    status = resources.getString(
                        R.string.status_manual_import_ready,
                        prepared.manualImportPath.orEmpty(),
                    )
                }

                ThemeApplyProtocol.ROOTLESS_FILE_MANAGER_HANDOFF -> {
                    diagnostics.record(
                        "rootless_file_manager_returned",
                        "Dosya Yöneticisi MTZ aktarımından uygulamaya dönüldü; Temalar sonucu dış uygulama tarafından yönetilir",
                        mapOf("theme" to prepared.themeName),
                    )
                    status = resources.getString(
                        R.string.status_manual_import_ready,
                        prepared.manualImportPath.orEmpty(),
                    )
                }

                ThemeApplyProtocol.ROOTLESS_LEGACY_TESTER -> {
                    diagnostics.record(
                        "rootless_legacy_apply_unverified",
                        "Rootsuz Global tester çağrısı döndü; kesin uygulama sonucu ve kalıcılık doğrulanamaz",
                        mapOf("theme" to prepared.themeName),
                    )
                    status = resources.getString(R.string.status_legacy_apply_unverified, prepared.themeName)
                    rememberAppliedTheme(prepared.themeId, prepared.protocol)
                }

                ThemeApplyProtocol.ROOTLESS_BACKUP_RESTORE -> {
                    diagnostics.record("rootless_backup_returned", "Doğrudan aktarılan tema için Xiaomi Temalar ekranından dönüldü", mapOf("theme" to prepared.themeName))
                    status = resources.getString(R.string.status_legacy_apply_unverified, prepared.themeName)
                    rememberAppliedTheme(prepared.themeId, prepared.protocol)
                }
            }
            if (prepared.operation == ThemeManagerOperation.APPLY) {
                // Applying a full theme recreates the activity on some HyperOS builds. The
                // asynchronous access probe can still be pending when the result is delivered,
                // so do not let a transient null UI state skip persistence arming.
                scope.launch {
                    val confirmedMode = accessMode ?: withContext(Dispatchers.IO) {
                        privilegedRunner.accessModeSilently()
                    }
                    if (confirmedMode == StudioAccessMode.SHIZUKU || confirmedMode == StudioAccessMode.ROOT) runCatching {
                        ThemePersistenceGuardService.arm(context.applicationContext, prepared)
                    }.onFailure {
                        diagnostics.record(
                            "theme_watch_arm_failed",
                            "Shizuku tema izleyicisi etkinleştirilemedi",
                            error = it,
                        )
                    }
                }
            }
        }
    }

    val rootlessNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        diagnostics.record(
            "rootless_restore_notification_permission",
            if (granted) "Rootsuz yeniden uygulama bildirim izni verildi" else "Rootsuz yeniden uygulama bildirim izni verilmedi",
            mapOf("granted" to granted),
        )
    }

    fun launchThemeOperation(block: suspend () -> Unit) {
        pauseCatalog.set(true)
        scope.launch {
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostics.record("theme_operation_failed", "Tema işlemi tamamlanamadı", error = error)
                status = resources.getString(R.string.status_apply_failed, error.message ?: error::class.simpleName)
                operationError = status
            } finally {
                themeOperationRunning = preparedApply != null
                if (preparedApply == null) pauseCatalog.set(false)
            }
        }
    }

    fun observeModernNativeImport(prepared: PreparedThemeApply) {
        val previousIds = prepared.themeManagerLocalIdsBefore ?: return
        val importedTheme = themes.firstOrNull { it.id.value == prepared.themeId } ?: return
        modernImportObserverJob?.cancel()
        modernImportObserverJob = scope.launch {
            repeat(60) {
                delay(1_500)
                val localId = withContext(Dispatchers.IO) {
                    runCatching { deviceThemeImporter.resolveImportedLocalId(importedTheme, previousIds) }.getOrNull()
                }
                if (localId != null) {
                    val opened = withContext(Dispatchers.IO) {
                        runCatching {
                            deviceThemeImporter.rememberThemeManagerOrigin(localId, importedTheme)
                            themeApplyCoordinator.openModernLocalThemeDetail(localId)
                        }
                    }
                    opened.onSuccess {
                        diagnostics.record(
                            "modern_import_detail_opened",
                            "Yeni Xiaomi Temalar kaydı algılandı; tema ayrıntısı açıldı",
                            mapOf("theme" to prepared.themeName, "localId" to localId),
                        )
                        status = resources.getString(R.string.status_modern_theme_detail_opened, prepared.themeName)
                    }.onFailure { error ->
                        diagnostics.record("modern_import_detail_failed", "Yeni tema bulundu ancak ayrıntı ekranı açılamadı", error = error)
                    }
                    return@launch
                }
            }
            diagnostics.record(
                "modern_import_observer_timeout",
                "Xiaomi Temalar içe aktarımı için yeni kayıt zaman aşımı içinde görülmedi",
                mapOf("theme" to prepared.themeName),
            )
        }
    }

    /** Root bridge requests run outside Studio, so persist the native local ID by observing
     * the catalog after the import completes.  Without this, every later Apply re-imports the
     * same MTZ and leaves a duplicate in Xiaomi Themes. */
    fun observeRootBridgeImport(prepared: PreparedThemeApply) {
        val previousIds = prepared.themeManagerLocalIdsBefore ?: return
        val importedTheme = themes.firstOrNull { it.id.value == prepared.themeId } ?: return
        modernImportObserverJob?.cancel()
        modernImportObserverJob = scope.launch {
            repeat(60) {
                delay(1_500)
                val localId = withContext(Dispatchers.IO) {
                    runCatching { deviceThemeImporter.resolveImportedLocalId(importedTheme, previousIds) }.getOrNull()
                }
                if (localId != null) {
                    withContext(Dispatchers.IO) {
                        deviceThemeImporter.rememberThemeManagerOrigin(localId, importedTheme)
                    }
                    diagnostics.record(
                        "root_global_import_linked",
                        "Root köprüsünün oluşturduğu Xiaomi Temalar kaydı eşleştirildi; sonraki uygulamalarda yeniden içe aktarma yapılmayacak",
                        mapOf("theme" to prepared.themeName, "localId" to localId),
                    )
                    return@launch
                }
            }
            diagnostics.record(
                "root_global_import_link_timeout",
                "Root köprüsü tamamlandı ancak yeni Xiaomi Temalar kimliği zamanında bulunamadı",
                mapOf("theme" to prepared.themeName),
            )
        }
    }

    fun launchPreparedTheme(prepared: PreparedThemeApply) {
        if (prepared.protocol == ThemeApplyProtocol.MODERN_THEME_MANAGER_MANUAL_IMPORT &&
            prepared.themeManagerLocalIdsBefore == null
        ) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { deviceThemeImporter.localThemeIds() }
                }.onSuccess { ids ->
                    launchPreparedTheme(prepared.copy(themeManagerLocalIdsBefore = ids))
                }.onFailure { error ->
                    // This snapshot is advisory only. A transiently unavailable private
                    // catalog must not turn a valid MTZ import into a blocking apply error.
                    diagnostics.record(
                        "modern_import_snapshot_unavailable",
                        "İçe aktarma öncesi yerel tema listesi okunamadı; işlem yine de sürdürülecek",
                        error = error,
                    )
                    launchPreparedTheme(prepared.copy(themeManagerLocalIdsBefore = emptySet()))
                }
            }
            return
        }
        try {
            preparedApply = prepared
            persistPreparedApply(prepared)
            if (prepared.operation == ThemeManagerOperation.APPLY &&
                (accessMode == StudioAccessMode.SHIZUKU || accessMode == StudioAccessMode.ROOT)
            ) {
                // Xiaomi Themes does not reliably return a result on every build. Arm before
                // leaving Studio so monitoring still starts when that external screen remains open.
                ThemePersistenceGuardService.arm(context.applicationContext, prepared)
            }
            diagnostics.record(
                "theme_activity_launching",
                "Temalar etkinliği başlatılıyor",
                mapOf(
                    "protocol" to prepared.protocol,
                    "action" to prepared.intent.action,
                    "component" to prepared.intent.component?.flattenToShortString(),
                    "rootless" to prepared.protocol.name.startsWith("ROOTLESS_"),
                ),
            )
            if (prepared.protocol == ThemeApplyProtocol.ROOT_GLOBAL_THEME_MANAGER_BRIDGE) {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            themeApplyCoordinator.dispatchRootGlobalModuleBridge(prepared)
                        }
                    }.onSuccess {
                        // The root bridge completes Xiaomi Themes' native import/apply work in
                        // its own process, so there is no ActivityResult to wait for here.
                        preparedApply = null
                        clearPreparedApply()
                        themeOperationRunning = false
                        pauseCatalog.set(false)
                        diagnostics.record(
                            "root_global_bridge_dispatched",
                            "Xiaomi Temalar yerleşik uygulama isteği root köprüsüne gönderildi",
                            mapOf("theme" to prepared.themeName, "operation" to prepared.operation),
                        )
                        if (prepared.operation == ThemeManagerOperation.APPLY) {
                            status = resources.getString(R.string.status_apply_success, prepared.themeName)
                            rememberAppliedTheme(prepared.themeId, prepared.protocol)
                            if (prepared.themeManagerLocalId == null) observeRootBridgeImport(prepared)
                        } else {
                            status = resources.getString(R.string.status_modern_theme_imported, prepared.themeName)
                        }
                    }.onFailure { error ->
                        preparedApply = null
                        clearPreparedApply()
                        themeOperationRunning = false
                        pauseCatalog.set(false)
                        diagnostics.record("root_global_bridge_dispatch_failed", "Root köprüsü isteği gönderilemedi", error = error)
                        status = resources.getString(R.string.status_apply_failed, error.message ?: error::class.simpleName.orEmpty())
                        operationError = status
                    }
                }
                return
            }
            applyLauncher.launch(prepared.intent)
            if (prepared.protocol == ThemeApplyProtocol.MODERN_THEME_MANAGER_MANUAL_IMPORT) {
                observeModernNativeImport(prepared)
            }
            diagnostics.record(
                "theme_activity_launched",
                "Temalar etkinliği başlatıldı; dönüş bekleniyor",
                mapOf("protocol" to prepared.protocol, "theme" to prepared.themeName),
            )
        } catch (error: Exception) {
            preparedApply = null
            clearPreparedApply()
            diagnostics.record("theme_activity_launch_failed", "Temalar etkinliği başlatılamadı", error = error)
            throw error
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            rootlessNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun beginThemeApply(theme: LibraryTheme) {
        if (themeOperationRunning) return
        themeOperationRunning = true
        pendingApplyTheme = null
        launchThemeOperation {
            status = resources.getString(R.string.status_preparing_apply)
            diagnostics.record(
                "apply_requested",
                "Tema uygulama istendi",
                mapOf("theme" to theme.displayName, "themeId" to theme.id.value),
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    val modernShizukuImport =
                        accessMode == StudioAccessMode.SHIZUKU &&
                            themeManagerBehavior == ThemeManagerBehavior.MODERN_NATIVE_LIBRARY
                    if (rootAccessAvailable == true || modernShizukuImport) {
                        // Modern 10.8.7.6+ builds expose their own local import library. Shizuku
                        // can stage an MTZ for that screen even though it cannot read the private
                        // catalog. Never send this branch to the removed legacy tester activity.
                        val originNeedsRefresh = deviceThemeImporter.themeManagerOriginNeedsRefresh(theme)
                        val linkedLocalIds = deviceThemeImporter.linkedLocalIdsFor(theme)
                        val savedLocalIds = if (originNeedsRefresh) emptySet() else linkedLocalIds
                        val savedLocalId = savedLocalIds.firstOrNull()
                        val localId = when {
                            savedLocalId != null -> savedLocalId
                            modernShizukuImport -> {
                                // Older Studio releases did not persist a Xiaomi localId for
                                // themes that were already in the private Studio library. Do not
                                // call the root-only catalog reader here: mirror the verified MTZ
                                // through HyperOS' Shizuku backup channel, then persist its stable
                                // localId before dispatching the direct 10.8/11 apply request.
                                themeApplyCoordinator.importModernThroughShizukuBackup(theme).also { restoredLocalId ->
                                    deviceThemeImporter.rememberThemeManagerOrigin(restoredLocalId, theme)
                                    diagnostics.record(
                                        "modern_shizuku_apply_link_created",
                                        "Eski Studio teması Xiaomi Temalar kitaplığına aktarıldı ve eşleştirildi",
                                        mapOf("theme" to theme.displayName, "localId" to restoredLocalId),
                                    )
                                }
                            }
                            !originNeedsRefresh -> deviceThemeImporter.resolveExistingLocalId(theme)?.also { existingLocalId ->
                                deviceThemeImporter.rememberThemeManagerOrigin(existingLocalId, theme)
                            }
                            else -> null
                        }
                        if (rootAccessAvailable == true &&
                            themeApplyCoordinator.rootGlobalModuleBridgeReady()
                        ) {
                            // A translated or re-created Studio archive must enter Themes through
                            // the root module as well.  The former branch below bypassed the module
                            // whenever its prior local record was stale, then opened an activity
                            // removed from current Global Themes builds.
                            if (localId != null) {
                                themeApplyCoordinator.prepare(theme, localId, linkedLocalIds)
                            } else {
                                // The native importer runs asynchronously inside Xiaomi Themes.
                                // Keep a complete before-snapshot so its newly created local ID
                                // can be saved and reused by all following Apply actions.
                                // The private Themes catalog can be temporarily unavailable
                                // after a Themes update/restart. This snapshot is only used for
                                // duplicate cleanup, so it must not block the root import.
                                val allLocalIdsBefore = runCatching {
                                    deviceThemeImporter.localThemeIds()
                                }.onFailure { error ->
                                    diagnostics.record(
                                        "root_global_import_snapshot_unavailable",
                                        "İçe aktarma öncesi Tema kitaplığı okunamadı; işlem yine de sürdürülecek",
                                        error = error,
                                    )
                                }.getOrDefault(emptySet())
                                themeApplyCoordinator.prepareRootGlobalModuleImportAndApply(theme, linkedLocalIds)
                                    .copy(themeManagerLocalIdsBefore = allLocalIdsBefore)
                            }
                        } else if (originNeedsRefresh && localId == null) {
                            // The Studio source changed after it was mirrored (for example by
                            // translation). Import and apply the new archive; never reuse the old
                            // Xiaomi Themes record merely because its title still matches.
                            themeApplyCoordinator.prepareModernImportAndApply(theme)
                        } else {
                            themeApplyCoordinator.prepare(theme, localId, savedLocalIds)
                        }
                    } else {
                        themeApplyCoordinator.prepareRootlessManualImport(theme)
                    }
                }
            }.onSuccess { prepared ->
                if (prepared.protocol == ThemeApplyProtocol.ROOTLESS_MANUAL_IMPORT ||
                    prepared.protocol == ThemeApplyProtocol.ROOTLESS_FILE_MANAGER_HANDOFF ||
                    prepared.protocol == ThemeApplyProtocol.ROOTLESS_LEGACY_TESTER ||
                    prepared.protocol == ThemeApplyProtocol.ROOTLESS_BACKUP_RESTORE
                ) {
                    RootlessRestoreAssistant.remember(context, prepared)
                }
                if (prepared.protocol == ThemeApplyProtocol.MODERN_THEME_MANAGER_MANUAL_IMPORT ||
                    prepared.protocol == ThemeApplyProtocol.ROOTLESS_MANUAL_IMPORT ||
                    prepared.protocol == ThemeApplyProtocol.ROOTLESS_FILE_MANAGER_HANDOFF
                ) {
                    status = resources.getString(
                        R.string.status_manual_import_ready,
                        prepared.manualImportPath.orEmpty(),
                    )
                    Toast.makeText(
                        context,
                        resources.getString(
                            R.string.manual_import_toast,
                            prepared.manualImportPath.orEmpty().substringAfterLast('/'),
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                launchPreparedTheme(prepared)
            }.onFailure { error ->
                themeOperationRunning = false
                diagnostics.record("apply_prepare_failed", "Tema uygulama hazırlanamadı", error = error)
                status = resources.getString(
                    R.string.status_apply_failed,
                    error.message ?: error::class.simpleName,
                )
                operationError = status
            }
        }
    }

    fun deleteTheme(theme: LibraryTheme) {
        if (themeOperationRunning) return
        themeOperationRunning = true
        diagnostics.record("delete_requested", "Tema kaldırma istendi", mapOf("theme" to theme.displayName, "themeId" to theme.id.value))
        if (capabilities.usesNativeCatalog) {
            val localId = deviceThemeImporter.localIdFor(theme)
            if (localId != null) {
                launchThemeOperation {
                    runCatching {
                        withContext(Dispatchers.IO) { themeApplyCoordinator.prepareModernDelete(theme, localId) }
                    }.onSuccess { prepared ->
                        launchPreparedTheme(prepared)
                    }.onFailure { error ->
                        diagnostics.record("theme_request_prepare_failed", "Temalar işlemi hazırlanamadı", error = error)
                        status = resources.getString(R.string.status_apply_failed, error.message ?: error::class.simpleName)
                        operationError = status
                    }
                }
                return
            }
        }
        launchThemeOperation {
            val success = withContext(Dispatchers.IO) { library.deleteTheme(theme.id) }
            if (success) {
                val snapshot = withContext(Dispatchers.IO) { library.load() }
                themes = snapshot.themes
                if (baseThemeId == theme.id.value) {
                    baseThemeId = null
                }
                if (activeThemeId == theme.id.value) {
                    activeThemeId = null
                    studioState.edit().remove("last-applied-theme-id").apply()
                }
                status = resources.getString(R.string.status_theme_removed, theme.archive.metadata?.name ?: theme.displayName)
            }
        }
    }

    fun navigateTo(target: StudioDestination, returnTo: StudioDestination = StudioDestination.HOME) {
        returnDestination = returnTo
        destination = target
    }

    fun navigateBack() {
        destination = returnDestination
        returnDestination = StudioDestination.HOME
    }

    suspend fun mirrorImportedThemeToXiaomi(theme: LibraryTheme): Boolean {
        if (themeManagerBehavior != ThemeManagerBehavior.MODERN_NATIVE_LIBRARY) return false

        val originNeedsRefresh = deviceThemeImporter.themeManagerOriginNeedsRefresh(theme)
        val savedLocalId = withContext(Dispatchers.IO) { deviceThemeImporter.localIdFor(theme) }
        val alreadyImported = savedLocalId ?: if (
            accessMode == StudioAccessMode.ROOT && !originNeedsRefresh
        ) {
            withContext(Dispatchers.IO) {
                deviceThemeImporter.resolveExistingLocalId(theme)?.also { localId ->
                    deviceThemeImporter.rememberThemeManagerOrigin(localId, theme)
                }
            }
        } else null
        if (alreadyImported != null) {
            diagnostics.record(
                "dual_import_already_present",
                "MTZ Xiaomi Temalar kitaplığında zaten bulundu",
                mapOf("theme" to theme.displayName, "localId" to alreadyImported),
            )
            return true
        }

        // Stock Xiaomi Themes exposes no public Java import API. Shizuku uses HyperOS' own
        // backup service to create or refresh the native record without a second picker.
        if (accessMode != StudioAccessMode.SHIZUKU ||
            SheveryBackupRestorer.state() != SheveryBackupRestorer.State.READY
        ) return false

        val localId = withContext(Dispatchers.IO) {
            themeApplyCoordinator.importModernThroughShizukuBackup(theme).also { restoredLocalId ->
                deviceThemeImporter.rememberThemeManagerOrigin(restoredLocalId, theme)
            }
        }
        diagnostics.record(
            "dual_import_linked",
            "Studio ve Xiaomi Temalar kayıtları eşleştirildi",
            mapOf("theme" to theme.displayName, "localId" to localId),
        )
        return true
    }

    fun composeTheme() {
        if (themeOperationRunning) return
        themeOperationRunning = true
        launchThemeOperation {
            diagnostics.record("compose_started", "Tema oluşturma başladı", mapOf(
                "name" to compositionName, "baseThemeId" to baseThemeId,
                "selections" to selections.values.joinToString { "${it.category}=${it.themeId};default=${it.useDefault}" },
                "customHomeWallpaper" to (customHomeWallpaperUri != null),
                "customLockWallpaper" to (customLockWallpaperUri != null),
            ))
            status = resources.getString(R.string.status_composing)
            runCatching {
                withContext(Dispatchers.IO) {
                    val byId = themes.associateBy { it.id }
                    val homeBytes = customHomeWallpaperUri?.let { uriStr ->
                        openInput(Uri.parse(uriStr))?.use { it.readBytes() }
                    }
                    val lockBytes = customLockWallpaperUri?.let { uriStr ->
                        openInput(Uri.parse(uriStr))?.use { it.readBytes() }
                    }
                    val baseTheme = baseThemeId?.let { id -> themes.firstOrNull { it.id.value == id } }
                    val previewSource = homeBytes
                        ?: baseTheme?.let(::readHomePreviewSource)
                        ?: selections.values.asSequence()
                            .mapNotNull { selected -> byId[selected.themeId] }
                            .mapNotNull(::readHomePreviewSource)
                            .firstOrNull()
                    val generatedPreview = GeneratedThemePreviewFactory.create(
                        themeName = compositionName.trim(),
                        wallpaperBytes = previewSource,
                    )
                    val makerName = compositionMakerName.trim().takeIf(String::isNotEmpty)
                    diagnostics.record("compose_preview_ready", "Önizleme hazırlığı tamamlandı")
                    val request = CompositionRequest(
                        metadata = CompositionMetadata(
                            name = compositionName.trim(),
                            author = makerName,
                            designer = makerName,
                        ),
                        baseSource = baseTheme?.let { theme ->
                            CompositionSource(theme.id, theme.displayName, theme.archive)
                        },
                        selections = selections.values
                            .filter { it.category.isPersonalizationOption() }
                            .map { selected ->
                            val theme = byId.getValue(selected.themeId)
                            ComponentSelection(
                                source = CompositionSource(theme.id, theme.displayName, theme.archive),
                                category = selected.category,
                                rootPath = selected.rootPath,
                                useDefault = selected.useDefault,
                            )
                        },
                        customHomeWallpaperBytes = homeBytes,
                        customLockWallpaperBytes = lockBytes,
                        generatedPreviewBytes = generatedPreview,
                    )
                    val result = composer.compose(request, library.newExportPath(compositionName))
                    diagnostics.record("compose_archive_created", "MTZ bileşenleri birleştirildi", mapOf("file" to result.output.fileName))
                    library.recordComposition(result)
                    val importedTheme = java.nio.file.Files.newInputStream(result.output).use { input ->
                        library.importTheme(
                            input = input,
                            suggestedName = compositionName.trim(),
                            includeInThemeGallery = true,
                        )
                    }
                    // Export copy to device public Downloads/MTZ Studio folder
                    diagnostics.record("compose_library_saved", "Oluşturulan tema özel kitaplığa kaydedildi", mapOf("themeId" to importedTheme.id.value, "sha256" to importedTheme.archive.sha256))
                    val publicCopy = MtzPublicExporter.exportToPublicDownloads(context, result.output, compositionName.trim())
                    diagnostics.record("compose_public_export", if (publicCopy != null) "MTZ, İndirilenler/MTZ Studio klasörüne kaydedildi" else "MTZ genel klasöre kaydedilemedi; özel kopya korundu")
                    result to importedTheme
                }
            }.onSuccess { (result, importedTheme) ->
                diagnostics.record("compose_completed", "Tema oluşturma tamamlandı", mapOf("name" to compositionName))
                lastResult = result
                val snapshot = withContext(Dispatchers.IO) { library.load() }
                themes = snapshot.themes
                destination = StudioDestination.THEMES
                status = resources.getString(R.string.status_compose_success, compositionName.trim())
                if (capabilities.usesNativeCatalog) {
                    runCatching {
                        val mirrored = mirrorImportedThemeToXiaomi(importedTheme)
                        if (mirrored) null else withContext(Dispatchers.IO) {
                            themeApplyCoordinator.prepareModernImportOnly(importedTheme)
                        }
                    }.onSuccess { prepared ->
                        if (prepared != null) launchPreparedTheme(prepared)
                        else themeOperationRunning = false
                    }.onFailure { error ->
                        themeOperationRunning = false
                        diagnostics.record("theme_request_prepare_failed", "Temalar işlemi hazırlanamadı", error = error)
                        status = resources.getString(R.string.status_apply_failed, error.message ?: error::class.simpleName)
                        operationError = status
                    }
                } else if (themeApplyCoordinator.rootGlobalModuleBridgeReady()) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            themeApplyCoordinator.prepareRootGlobalModuleImportOnly(importedTheme)
                        }
                    }.onSuccess(::launchPreparedTheme)
                        .onFailure { error ->
                            themeOperationRunning = false
                            diagnostics.record("root_global_compose_import_failed", "Oluşturulan tema Xiaomi Temalar kitaplığına aktarılamadı", error = error)
                            status = resources.getString(R.string.status_apply_failed, error.message ?: error::class.simpleName)
                            operationError = status
                        }
                } else {
                    themeOperationRunning = false
                }
            }.onFailure { error ->
                themeOperationRunning = false
                status = resources.getString(R.string.status_compose_failed, error.message ?: error::class.simpleName)
                diagnostics.record("compose_failed", "Tema oluşturulamadı", error = error)
            }
        }
    }

    fun openDeviceThemePicker() {
        showDeviceThemePicker = true
        isScanningDeviceThemes = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { deviceThemeImporter.listAvailableDeviceThemes() }
            }.onSuccess { list ->
                availableDeviceThemes = list
                isScanningDeviceThemes = false
            }.onFailure { err ->
                isScanningDeviceThemes = false
                themeDeviceImportStatus = resources.getString(R.string.device_import_failed, err.message ?: "Failed to scan")
            }
        }
    }

    fun importSelectedDeviceThemes(selectedIds: Set<String>) {
        if (deviceImportRunning || selectedIds.isEmpty()) return
        scope.launch {
            deviceImportRunning = true
            themeDeviceImportStatus = resources.getString(R.string.device_import_working)
            runCatching {
                withContext(Dispatchers.IO) { deviceThemeImporter.importSelectedThemes(selectedIds) }
            }.onSuccess { summary ->
                reload(openThemesAfter = true)
                themeDeviceImportStatus = resources.getString(
                    R.string.device_theme_import_summary,
                    summary.found,
                    summary.added,
                    summary.duplicates,
                    summary.failed,
                )
                if (summary.errors.isNotEmpty()) status = summary.errors.take(3).joinToString("\n")
            }.onFailure { error ->
                themeDeviceImportStatus = resources.getString(
                    R.string.device_import_failed,
                    error.message ?: error::class.simpleName,
                )
            }
            deviceImportRunning = false
        }
    }

    fun importFromThemeManager(fontOnly: Boolean) {
        if (deviceImportRunning) return
        scope.launch {
            deviceImportRunning = true
            if (fontOnly) fontDeviceImportStatus = resources.getString(R.string.device_import_working)
            else themeDeviceImportStatus = resources.getString(R.string.device_import_working)
            if (fontOnly) {
                runCatching {
                    withContext(Dispatchers.IO) { deviceThemeImporter.importActiveFont() }
                }.onSuccess { result ->
                    destination = StudioDestination.FONTS
                    reload()
                    val name = result.theme.archive.metadata?.name ?: result.theme.displayName
                    fontDeviceImportStatus = resources.getString(
                        if (result.addedToLibrary) R.string.device_import_added else R.string.device_import_duplicate,
                        name,
                    )
                }.onFailure { error ->
                    fontDeviceImportStatus = resources.getString(
                        R.string.device_import_failed,
                        error.message ?: error::class.simpleName,
                    )
                }
            } else {
                openDeviceThemePicker()
            }
            deviceImportRunning = false
        }
    }

    fun refreshModernThemeManagerCatalog() {
        if (!capabilities.usesNativeCatalog || deviceImportRunning || preparedApply != null || themeOperationRunning || checkingImportAccess) return
        // Claim the refresh before launching so entry/resume cannot start two scans.
        deviceImportRunning = true
        pauseCatalog.set(false)
        scope.launch {
            catalogError = null
            themeDeviceImportStatus = resources.getString(R.string.device_import_working)
            android.util.Log.i("MtzCatalog", "Automatic catalog refresh started")
            diagnostics.record("catalog_sync_started", "Tema kitaplığı eşitleniyor")
            try {
                // Keep cached sources visible while a large Themes library is reconstructed in
                // small resumable batches.  This work remains off the UI thread.
                loadLibrarySnapshot()
                catalogLoadFinished = true
                var totalAdded = 0
                var totalFailed = 0
                var found = 0
                var completed = false
                while (!completed) {
                    if (pauseCatalog.get()) {
                        diagnostics.record("catalog_sync_waiting", "Öncelikli MTZ işlemi için katalog eşitlemesi bekletildi")
                        while (pauseCatalog.get()) delay(250)
                    }
                    val batch = try {
                        withContext(Dispatchers.IO) { deviceThemeImporter.synchronizeModernLibrary { pauseCatalog.get() } }
                    } catch (error: Exception) {
                        themeDeviceImportStatus = resources.getString(
                            R.string.device_import_failed, error.message ?: error::class.simpleName,
                        )
                        diagnostics.record("catalog_sync_failed", "Tema kitaplığı eşitlenemedi", error = error)
                        catalogError = themeDeviceImportStatus
                        break
                    }
                    loadLibrarySnapshot()
                    found = batch.found
                    totalAdded += batch.added
                    totalFailed += batch.failed
                    completed = batch.completed
                    themeDeviceImportStatus = resources.getString(
                        R.string.modern_catalog_sync_summary,
                        found, totalAdded, totalFailed,
                    )
                    android.util.Log.i("MtzCatalog", "Catalog batch: found=$found, added=$totalAdded, failed=$totalFailed, completed=$completed")
                    diagnostics.record(
                        if (completed) "catalog_sync_completed" else "catalog_sync_batch_completed",
                        if (completed) "Tema kitaplığı eşitlendi" else "Tema kitaplığı arka planda sonraki gruba geçiyor",
                        mapOf("found" to found, "added" to totalAdded, "failed" to totalFailed,
                            "completed" to completed, "batchErrors" to batch.errors.joinToString("\n")),
                    )
                    if (totalFailed > 0) {
                        catalogError = resources.getString(R.string.catalog_partial_failure)
                    }
                    if (!completed) delay(350)
                }
                if (completed) {
                    Toast.makeText(context, resources.getString(R.string.catalog_sync_all_complete), Toast.LENGTH_LONG).show()
                }
            } finally {
                catalogLoadFinished = true
                deviceImportRunning = false
            }
        }
    }

    fun requestFullThemeManagerCatalog() {
        if (!capabilities.usesNativeCatalog || deviceImportRunning || themeOperationRunning || checkingImportAccess) return
        scope.launch {
            isScanningDeviceThemes = true
            val count = runCatching {
                withContext(Dispatchers.IO) { deviceThemeImporter.availableThemeCount() }
            }.onFailure { error ->
                diagnostics.record("catalog_count_failed", "Tema Yöneticisi tema sayısı okunamadı", error = error)
                themeDeviceImportStatus = resources.getString(
                    R.string.device_import_failed, error.message ?: error::class.simpleName,
                )
            }.getOrNull()
            isScanningDeviceThemes = false
            if (count == null) return@launch
            diagnostics.record("catalog_full_access_requested", "Tema Yöneticisindeki tüm temalar istendi", mapOf("count" to count))
            if (count > MAX_SAFE_FULL_CATALOG_THEMES) {
                largeCatalogThemeCount = count
            } else {
                refreshModernThemeManagerCatalog()
            }
        }
    }

    fun exportTheme(theme: LibraryTheme) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { library.exportTheme(theme) }
            }.onSuccess(shareMtz).onFailure { error ->
                status = resources.getString(
                    R.string.status_export_failed,
                    error.message ?: error::class.simpleName,
                )
            }
        }
    }

    fun importMtzDocuments(selectedUris: List<Uri>) {
        checkingImportAccess = false
        if (selectedUris.isEmpty()) {
            pauseCatalog.set(false)
            return
        }
        if (selectedUris.size > 5) {
            status = resources.getString(R.string.mtz_import_too_many)
            operationError = status
            pauseCatalog.set(false)
            return
        }
        if (themeOperationRunning) return
        themeOperationRunning = true
        pauseCatalog.set(true)
        mtzImportTotal = selectedUris.size
        mtzImportCompleted = 0
        mtzImportSucceeded = 0
        mtzImportFailed = 0
        mtzImportCurrentName = null
        diagnostics.recordPickerResultReceived()
        scope.launch {
            selectedUris.forEach { uri ->
                status = resources.getString(R.string.status_copying_verifying)
                var diagnosticSession: ImportDiagnosticSession? = null
                runCatching {
                    withContext(Dispatchers.IO) {
                        val document = documentDiagnostics(uri)
                        withContext(Dispatchers.Main) { mtzImportCurrentName = document.displayName }
                        val session = diagnostics.beginImport(document)
                        diagnosticSession = session
                        openInput(uri)?.use { input ->
                            library.importTheme(input, document.displayName, session.observer)
                        } ?: run {
                            val openErr = resources.getString(R.string.error_open_document)
                            session.failBeforeImport(openErr)
                            error(openErr)
                        }
                    }
                }.onSuccess { importedTheme ->
                    themes = (themes.filterNot { it.id == importedTheme.id } + importedTheme)
                    runCatching {
                        // Global Themes exposes its importer only inside its own process.  When
                        // the active root module is present, immediately hand this verified copy
                        // to that importer instead of leaving the Studio-only archive orphaned.
                        if (themeApplyCoordinator.rootGlobalModuleBridgeReady()) {
                            val prepared = withContext(Dispatchers.IO) {
                                themeApplyCoordinator.prepareRootGlobalModuleImportOnly(importedTheme)
                            }
                            launchPreparedTheme(prepared)
                            true
                        } else {
                            mirrorImportedThemeToXiaomi(importedTheme)
                        }
                    }
                        .onFailure { error ->
                            // The Studio import remains valid when Xiaomi rejects its own native
                            // catalog copy. Applying later can retry the existing manual/native
                            // paths instead of discarding the user's successfully imported MTZ.
                            diagnostics.record(
                                "dual_import_failed",
                                "MTZ Studio'ya eklendi ancak Xiaomi Temalar kopyası oluşturulamadı",
                                mapOf("theme" to importedTheme.displayName),
                                error,
                            )
                        }
                    mtzImportSucceeded += 1
                    diagnostics.record("import_components", "Tema bileşenleri ve genel önizleme incelendi", mapOf(
                        "themeId" to importedTheme.id.value,
                        "components" to importedTheme.archive.components.joinToString { it.category.name },
                        "defaultPreviews" to dev.glorioustr.mtzstudio.core.ThemeVisualPolicy
                            .defaultPreviewPaths(importedTheme.archive.entries).joinToString(),
                    ))
                }.onFailure { error ->
                    mtzImportFailed += 1
                    diagnosticSession?.failBeforeImport(error.message ?: error::class.simpleName ?: "unknown error")
                    status = resources.getString(R.string.status_import_rejected, error.message ?: error::class.simpleName)
                }
                mtzImportCompleted += 1
            }
            themeOperationRunning = false
            pauseCatalog.set(false)
            loadLibrarySnapshot()
            status = resources.getString(R.string.mtz_import_result, mtzImportSucceeded, mtzImportFailed)
        }
    }

    fun deleteThemesFromLibrary(selected: List<LibraryTheme>) {
        if (themeOperationRunning || selected.isEmpty()) return
        themeOperationRunning = true
        launchThemeOperation {
            val removedIds = withContext(Dispatchers.IO) {
                selected.mapNotNull { theme ->
                    deviceThemeImporter.hideThemeManagerOriginFor(theme)
                    theme.id.value.takeIf { library.deleteTheme(theme.id) }
                }.toSet()
            }
            if (baseThemeId in removedIds) baseThemeId = null
            if (activeThemeId in removedIds) {
                activeThemeId = null
                studioState.edit().remove("last-applied-theme-id").apply()
            }
            themes = withContext(Dispatchers.IO) { library.load().themes }
            status = resources.getString(R.string.status_themes_removed, removedIds.size)
            diagnostics.record("library_bulk_delete", "Seçilen MTZ Studio temaları kaldırıldı", mapOf("count" to removedIds.size))
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importMtzDocuments(listOfNotNull(uri))
    }

    val mtzFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        checkingImportAccess = false
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { findMtzDocuments(context, treeUri) }
            }.onSuccess { documents ->
                if (documents.isEmpty()) {
                    operationError = resources.getString(R.string.mtz_import_folder_empty)
                } else {
                    mtzBatchSelection = emptySet()
                    mtzBatchDocuments = documents
                }
            }.onFailure { error ->
                operationError = resources.getString(
                    R.string.mtz_import_folder_error,
                    error.message ?: error::class.simpleName,
                )
            }
        }
    }

    val bakPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || bakImporting || themeOperationRunning) return@rememberLauncherForActivityResult
        bakImporting = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = documentDiagnostics(uri).displayName ?: "Themes.bak"
                    val stream = openInput(uri) ?: error(resources.getString(R.string.error_open_document))
                    bakImporter.stageAndInspect(stream, name)
                }
            }.onSuccess { archive ->
                pendingBakArchive = archive
                translateBakToAppLanguage = false
            }.onFailure { error ->
                status = resources.getString(R.string.bak_import_failed, error.message ?: error::class.simpleName)
                diagnostics.record("bak_inspect_failed", "BAK arşivi incelenemedi", error = error)
            }
            bakImporting = false
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch {
                backupStatus = resources.getString(R.string.status_backup_preparing)
                runCatching {
                    withContext(Dispatchers.IO) {
                        openOutput(uri)?.use { output -> backupManager.create(output) }
                            ?: error(resources.getString(R.string.error_backup_target))
                    }
                }.onSuccess { summary ->
                    backupStatus = resources.getString(R.string.status_backup_success, summary.themeCount, summary.fileCount)
                }.onFailure { error ->
                    backupStatus = resources.getString(R.string.status_backup_failed, error.message ?: error::class.simpleName)
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                backupStatus = resources.getString(R.string.status_restore_preparing)
                runCatching {
                    withContext(Dispatchers.IO) {
                        openInput(uri)?.use { input -> backupManager.restore(input) }
                            ?: error(resources.getString(R.string.error_restore_target))
                    }
                }.onSuccess { summary ->
                    reload()
                    backupStatus = resources.getString(R.string.status_restore_success, summary.themeCount, summary.fileCount)
                }.onFailure { error ->
                    backupStatus = resources.getString(R.string.status_restore_failed, error.message ?: error::class.simpleName)
                }
            }
        }
    }

    SheveryAuthorizationGate(privilegedRunner) {
        scope.launch(Dispatchers.IO) {
            val mode = privilegedRunner.accessModeSilently()
            withContext(Dispatchers.Main) {
                accessMode = mode
                rootAccessAvailable = mode == StudioAccessMode.ROOT
                when (mode) {
                    StudioAccessMode.SHIZUKU -> ThemePersistenceGuardService.resumeIfArmed(context.applicationContext)
                    StudioAccessMode.ROOT -> ThemePersistenceGuardService.resumeIfArmed(context.applicationContext)
                    StudioAccessMode.STANDARD -> ThemePersistenceGuardService.resumeIfArmed(context.applicationContext)
                }
            }
        }
    }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                authorizationManager = context.installedAuthorizationManager()
                rootManager = context.installedRootManager()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // The local library is always available, including while capability detection runs.
        reload()
        val mode = withContext(Dispatchers.IO) { privilegedRunner.accessModeSilently() }
        accessMode = mode
        rootAccessAvailable = mode == StudioAccessMode.ROOT
        when (mode) {
            StudioAccessMode.SHIZUKU -> ThemePersistenceGuardService.resumeIfArmed(context.applicationContext)
            StudioAccessMode.ROOT -> ThemePersistenceGuardService.resumeIfArmed(context.applicationContext)
            StudioAccessMode.STANDARD -> ThemePersistenceGuardService.resumeIfArmed(context.applicationContext)
        }
        val rootReady = mode == StudioAccessMode.ROOT
        diagnostics.record(
            "privilege_mode_selected",
            when (mode) {
                StudioAccessMode.ROOT -> "Rootlu tam erişim modu etkin"
                StudioAccessMode.SHIZUKU -> "Shizuku gelişmiş rootsuz modu etkin"
                StudioAccessMode.STANDARD -> "Shizuku veya Shevery bağlantısı bekleniyor"
            },
            mapOf("root" to rootReady, "mode" to mode.name, "themeManagerBehavior" to themeManagerBehavior),
        )
        if (rootReady) {
            ThemeProtectionServiceClient.initialize(context.applicationContext, globalThemeProtectionRequired)
            ThemeProtectionServiceClient.setCommandRunner { cmd ->
                runCatching { privilegedRunner.run(cmd, 3).output }.getOrNull()
            }
            ThemeProtectionServiceClient.refresh()
        }
    }
    androidx.compose.runtime.LaunchedEffect(capabilities.usesNativeCatalog) {
        // Xiaomi Themes is not a second authoritative Studio library. Importing every local
        // Themes record on resume caused previously applied themes to reappear in Studio.
        // Full catalog import remains available explicitly from Library management.
        catalogLoadFinished = true
    }
    androidx.compose.runtime.LaunchedEffect(catalogProgress) {
        if (catalogProgress.total > 0) {
            // Merge incremental catalog items without hiding a concurrently imported private source.
            themes = (themes + catalogProgress.themes).associateBy { it.id }.values.toList()
            if (deviceImportRunning) {
                themeDeviceImportStatus = resources.getString(R.string.device_import_working) +
                    " ${catalogProgress.processed}/${catalogProgress.total}"
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(destination, modernThemeManagerMode) {
        diagnostics.record("screen_opened", "Ekran açıldı", mapOf("screen" to destination))
    }
    androidx.compose.runtime.LaunchedEffect(status) {
        diagnostics.record("operation_status", status)
    }
    androidx.compose.runtime.LaunchedEffect(globalThemeProtectionRequired) {
        if (!globalThemeProtectionRequired && destination == StudioDestination.THEME_PROTECTION) {
            destination = StudioDestination.HOME
            returnDestination = StudioDestination.HOME
        }
    }
    BackHandler(enabled = destination != StudioDestination.HOME, onBack = ::navigateBack)

    // Keep private drafts visible even while native synchronization is incomplete or unavailable.
    val workspaceThemes = themes

    val primaryDestinations = setOf(
        StudioDestination.HOME,
        StudioDestination.THEMES,
        StudioDestination.PERSONALIZE,
        StudioDestination.TOOLS,
    )

    Scaffold(
        containerColor = if (contentStyle == AppContentStyle.LIQUID_GLASS) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.background
        },
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (destination in primaryDestinations) {
                        if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
                            Surface(
                                shape = RoundedCornerShape(13.dp),
                                color = Color(0xFF111722),
                                shadowElevation = 2.dp,
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.logo_banner),
                                    contentDescription = stringResource(R.string.app_name),
                                    modifier = Modifier.height(44.dp).padding(horizontal = 7.dp, vertical = 3.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        } else {
                            Image(
                                painter = painterResource(R.drawable.logo_banner),
                                contentDescription = stringResource(R.string.app_name),
                                modifier = Modifier.height(44.dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    } else {
                        Text(stringResource(destination.titleRes))
                    }
                },
                navigationIcon = {
                    if (destination !in primaryDestinations) {
                        IconButton(onClick = ::navigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                        }
                    }
                },
                actions = {
                    if (destination in primaryDestinations) {
                        Surface(
                            modifier = Modifier.padding(end = 12.dp),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Text(
                                text = when (accessMode) {
                                    StudioAccessMode.ROOT -> "ROOT"
                                    StudioAccessMode.SHIZUKU -> "SHIZUKU"
                                    StudioAccessMode.STANDARD -> stringResource(R.string.panel_required)
                                    null -> "…"
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (contentStyle == AppContentStyle.LIQUID_GLASS) {
                        Color.Transparent
                    } else {
                        MaterialTheme.colorScheme.background
                    },
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            if (destination in primaryDestinations) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 10.dp,
                    ) {
                        NavigationBar(
                            modifier = Modifier.height(80.dp),
                            containerColor = Color.Transparent,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            listOf(
                                Triple(StudioDestination.HOME, Icons.Filled.Dashboard, R.string.nav_panel),
                                Triple(StudioDestination.THEMES, Icons.Filled.ColorLens, R.string.nav_library),
                                Triple(StudioDestination.PERSONALIZE, Icons.Filled.Tune, R.string.dest_personalize),
                                Triple(StudioDestination.TOOLS, Icons.Filled.Build, R.string.dest_tools),
                            ).forEach { (target, icon, label) ->
                                NavigationBarItem(
                                    selected = destination == target,
                                    onClick = {
                                        destination = target
                                        returnDestination = StudioDestination.HOME
                                    },
                                    icon = { Icon(icon, contentDescription = null) },
                                    label = { Text(stringResource(label), maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when {
            destination == StudioDestination.HOME -> StudioPanelScreen(
                importing = diagnosticState.activeSessionId != null || checkingImportAccess || themeOperationRunning,
                accessMode = accessMode,
                themeManagerInspector = themeManagerInspector,
                themeManagerUpdater = themeManagerUpdater,
                openInput = openInput,
                onAddMtz = {
                    navigateTo(StudioDestination.MTZ_IMPORT)
                },
                showBakImport = accessMode == StudioAccessMode.SHIZUKU || accessMode == StudioAccessMode.ROOT,
                bakImporting = bakImporting,
                onAddBak = {
                    runCatching { bakPicker.launch(arrayOf("application/octet-stream", "application/x-tar", "*/*")) }
                        .onFailure { error -> status = resources.getString(R.string.bak_import_failed, error.message ?: "") }
                },
                onOpenBackup = { navigateTo(StudioDestination.BACKUP) },
                authorizationManagerName = (if (accessMode == StudioAccessMode.ROOT) rootManager else authorizationManager)?.displayName,
                onOpenAuthorizationManager = {
                    (if (accessMode == StudioAccessMode.ROOT) rootManager else authorizationManager)?.let { manager ->
                        runCatching {
                            val launchIntent = checkNotNull(context.packageManager.getLaunchIntentForPackage(manager.packageName))
                            context.startActivity(launchIntent)
                        }.onFailure { error ->
                            status = resources.getString(R.string.privileged_permission_request_failed)
                            diagnostics.record("authorization_manager_open_failed", "Shizuku uyumlu yönetici açılamadı", error = error)
                        }
                    }
                },
                allowRootDowngrade = rootAccessAvailable == true,
                shizukuSetupRequest = shizukuSetupRequest,
                modifier = contentModifier,
            )
            destination == StudioDestination.THEMES -> ThemesScreen(
                themes = workspaceThemes,
                activeThemeId = activeThemeId,
                previousThemeId = previousThemeId,
                accessMode = accessMode,
                appliedProtocol = lastAppliedProtocol,
                deviceImportStatus = themeDeviceImportStatus,
                deviceImportRunning = deviceImportRunning || (capabilities.usesNativeCatalog && !catalogLoadFinished),
                catalogError = catalogError,
                onRetryCatalog = ::refreshModernThemeManagerCatalog,
                onOpenDeviceThemePicker = ::openDeviceThemePicker,
                onDeleteThemes = ::deleteThemesFromLibrary,
                // Root can read Xiaomi Themes' private library on both legacy Global and
                // modern builds. Keep automatic native catalog sync restricted separately.
                showDeviceImport = capabilities.canReadPrivateThemeManagerData,
                nativeCatalogMode = capabilities.usesNativeCatalog,
                rootlessMode = accessMode == StudioAccessMode.STANDARD,
                translationProgress = translationProgress,
                onApplyTheme = { theme ->
                    if (!themeOperationRunning) {
                        when (accessMode) {
                            StudioAccessMode.ROOT -> pendingApplyTheme = theme
                            StudioAccessMode.SHIZUKU -> beginThemeApply(theme)
                            StudioAccessMode.STANDARD, null -> {
                                status = resources.getString(R.string.shizuku_recommended_desc)
                                operationError = status
                            }
                        }
                    }
                },
                onTranslateTheme = { localizeTheme(it, experimentalOcr = false) },
                onDeleteTheme = ::deleteTheme,
                onCustomizeTheme = { theme ->
                    baseThemeId = theme.id.value
                    selections.clear()
                    destination = StudioDestination.PERSONALIZE
                },
                onRollbackTheme = { theme ->
                    if (!themeOperationRunning) {
                        when (accessMode) {
                            StudioAccessMode.ROOT -> pendingApplyTheme = theme
                            StudioAccessMode.SHIZUKU -> beginThemeApply(theme)
                            StudioAccessMode.STANDARD, null -> operationError = resources.getString(R.string.shizuku_recommended_desc)
                        }
                    }
                },
                modifier = contentModifier,
            )
            destination == StudioDestination.MTZ_IMPORT -> MtzImportScreen(
                importing = themeOperationRunning || checkingImportAccess,
                completed = mtzImportCompleted,
                total = mtzImportTotal,
                succeeded = mtzImportSucceeded,
                failed = mtzImportFailed,
                currentName = mtzImportCurrentName,
                currentFileProgress = diagnosticState.sourceBytes?.takeIf { it > 0L }?.let {
                    diagnosticState.bytesCopied.toFloat() / it.toFloat()
                } ?: 0f,
                phase = diagnosticState.phase,
                onSelectSingle = {
                    checkingImportAccess = true
                    diagnostics.recordPickerLaunched()
                    runCatching { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
                        .onFailure { checkingImportAccess = false }
                },
                onSelectMultiple = {
                    checkingImportAccess = true
                    diagnostics.recordPickerLaunched()
                    runCatching { mtzFolderPicker.launch(null) }
                        .onFailure { checkingImportAccess = false }
                },
                modifier = contentModifier,
            )
            destination == StudioDestination.TOOLS -> StudioToolsScreen(
                showThemeProtection = globalThemeProtectionRequired && rootAccessAvailable == true,
                onNavigate = { target -> navigateTo(target, StudioDestination.TOOLS) },
                onCheckForUpdates = {
                    showUpdateCheckResult = true
                    if (appUpdateState.phase != AppUpdatePhase.CHECKING &&
                        appUpdateState.phase != AppUpdatePhase.DOWNLOADING
                    ) {
                        AppUpdateStore.update(
                            AppUpdateState(
                                phase = AppUpdatePhase.CHECKING,
                                eventId = System.currentTimeMillis(),
                            ),
                        )
                    }
                    scope.launch(Dispatchers.IO) {
                        runCatching { appUpdateManager.checkForUpdate(force = true) }
                            .onFailure {
                                AppUpdateStore.update(AppUpdateState(AppUpdatePhase.ERROR, error = it.message ?: it::class.simpleName, eventId = System.currentTimeMillis()))
                            }
                    }
                },
                modifier = contentModifier,
            )
            destination == StudioDestination.PERSONALIZE -> PersonalizeScreen(
                themes = workspaceThemes,
                selections = selections,
                compositionName = compositionName,
                compositionMakerName = compositionMakerName,
                operationRunning = themeOperationRunning,
                lastResult = lastResult,
                status = status,
                baseThemeId = baseThemeId,
                onSelectBaseTheme = { theme ->
                    baseThemeId = theme?.id?.value
                    // Switching the base must not retain components absent from the new theme.
                    selections.clear()
                    if (theme != null) {
                        theme.archive.components.forEach { comp ->
                            if (comp.category.isPersonalizationOption()) {
                                selections[comp.category] = UiSelection(theme.id, comp.category, comp.rootPath)
                            }
                        }
                        dev.glorioustr.mtzstudio.core.ThemeVisualPolicy.personalizationCategories.forEach { category ->
                            if (dev.glorioustr.mtzstudio.core.ThemeVisualPolicy.isPreviewOnly(
                                    theme.archive.components, theme.archive.entries, category)) {
                                selections[category] = UiSelection(theme.id, category, "", useDefault = true)
                            }
                        }
                        val themeName = theme.archive.metadata?.name ?: theme.displayName
                        compositionName = if (themeName.endsWith(" Karmam", ignoreCase = true)) {
                            themeName
                        } else {
                            "$themeName Karmam"
                        }
                    }
                },
                customHomeWallpaperUri = customHomeWallpaperUri,
                customLockWallpaperUri = customLockWallpaperUri,
                onPickHomeWallpaper = { homeWallpaperPickerLauncher.launch("image/*") },
                onRemoveHomeWallpaper = { customHomeWallpaperUri = null },
                onPickLockWallpaper = { lockWallpaperPickerLauncher.launch("image/*") },
                onRemoveLockWallpaper = { customLockWallpaperUri = null },
                onCompositionNameChange = { compositionName = it },
                onCompositionMakerNameChange = { compositionMakerName = it },
                onCompose = ::composeTheme,
                onShare = shareMtz,
                modifier = contentModifier,
            )
            destination == StudioDestination.DIAGNOSTICS -> LiveDiagnosticsCard(
                recorder = diagnostics,
                shareDiagnostics = shareDiagnostics,
                shareThemeManagerApk = shareThemeManagerApk,
                openAppShareForThemesExport = openAppShareForThemesExport,
                modifier = contentModifier,
            )
            destination == StudioDestination.BACKUP -> BackupRestoreScreen(
                themeCount = themes.size,
                status = backupStatus,
                cloudAccount = cloudAccount,
                onSelectGoogleDrive = onChooseGoogleAccount,
                onConnectCloud = { account ->
                    cloudAccountStore.save(account)
                    cloudAccount = cloudAccountStore.load()
                    backupStatus = if (cloudAccount.isConnected) {
                        resources.getString(R.string.status_cloud_connected, account.accountName)
                    } else {
                        resources.getString(R.string.status_backup_failed, "Geçerli bir HTTPS WebDAV adresi gerekli")
                    }
                },
                onDisconnectCloud = {
                    cloudAccountStore.disconnect()
                    cloudAccount = cloudAccountStore.load()
                    backupStatus = resources.getString(R.string.status_cloud_disconnected)
                },
                onBackupCloud = {
                    CloudTransferService.startBackup(context)
                },
                onRestoreCloud = {
                    CloudTransferService.startRestore(context)
                },
                cloudTransferRunning = cloudTransferState.running,
                onBackupLocal = {
                    backupLauncher.launch("hyperos-mtz-studio-backup-${System.currentTimeMillis()}.zip")
                },
                onRestoreLocal = {
                    restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                modifier = contentModifier,
            )
            destination == StudioDestination.APPEARANCE -> AppearanceScreen(
                selected = appearance,
                onSelect = onAppearanceChange,
                contentStyle = contentStyle,
                onContentStyleSelect = onContentStyleChange,
                modifier = contentModifier,
            )
            destination == StudioDestination.AI_TRANSLATION -> AiTranslationSettingsScreen(modifier = contentModifier)
            destination == StudioDestination.ABOUT -> AboutScreen(modifier = contentModifier)
            destination == StudioDestination.THEME_PROTECTION -> ThemeProtectionScreen(
                state = themeProtectionState,
                onEnable = ThemeProtectionServiceClient::requestActivation,
                onDisable = ThemeProtectionServiceClient::disable,
                onOpenFramework = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            XposedManagerLauncher(context.applicationContext, privilegedRunner).open()
                        }
                        if (!result.opened) {
                            status = resources.getString(R.string.theme_protection_manager_missing)
                            android.widget.Toast.makeText(
                                context,
                                resources.getString(R.string.theme_protection_manager_open_failed, result.detail),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                onRestartDevice = { showThemeProtectionRestartDialog = true },
                onRefresh = {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            privilegedRunner.run("am force-stop com.android.thememanager", 5)
                            privilegedRunner.run("am start -n com.android.thememanager/.ThemeResourceTabActivity", 5)
                            Thread.sleep(600)
                        }
                        ThemeProtectionServiceClient.refresh()
                    }
                },
                modifier = contentModifier,
            )
            destination.category != null -> CategoryScreen(
                destination = destination,
                themes = workspaceThemes,
                selections = selections,
                customHomeWallpaperUri = customHomeWallpaperUri,
                customLockWallpaperUri = customLockWallpaperUri,
                onPickHomeWallpaper = { homeWallpaperPickerLauncher.launch("image/*") },
                onRemoveHomeWallpaper = { customHomeWallpaperUri = null },
                onPickLockWallpaper = { lockWallpaperPickerLauncher.launch("image/*") },
                onRemoveLockWallpaper = { customLockWallpaperUri = null },
                deviceImportStatus = fontDeviceImportStatus,
                deviceImportRunning = deviceImportRunning,
                onImportActiveFont = { importFromThemeManager(fontOnly = true) },
                showDeviceFontImport = rootAccessAvailable == true,
                modifier = contentModifier,
            )
        }
    }

    androidx.compose.runtime.LaunchedEffect(appUpdateState.phase, appUpdateState.eventId, installUpdateWhenReady) {
        if (appUpdateState.phase == AppUpdatePhase.READY && installUpdateWhenReady) {
            installUpdateWhenReady = false
            dismissedUpdateEventId = appUpdateState.eventId
            showUpdateCheckResult = false
            context.startActivity(Intent(context, UpdateInstallActivity::class.java))
        }
    }

    if (appUpdateState.phase == AppUpdatePhase.AVAILABLE &&
        !showUpdateCheckResult && appUpdateState.eventId != dismissedUpdateEventId
    ) {
        AlertDialog(
            onDismissRequest = { dismissedUpdateEventId = appUpdateState.eventId },
            title = { Text(resources.getString(R.string.app_update_ready_title, appUpdateState.version.orEmpty())) },
            text = { Text(resources.getString(R.string.app_update_found_prompt, appUpdateState.version.orEmpty())) },
            confirmButton = {
                TextButton(onClick = { showUpdateCheckResult = true }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissedUpdateEventId = appUpdateState.eventId }) {
                    Text(stringResource(R.string.app_update_later))
                }
            },
        )
    } else if (appUpdateState.phase == AppUpdatePhase.READY &&
        !installUpdateWhenReady && appUpdateState.eventId != dismissedUpdateEventId
    ) {
        AlertDialog(
            onDismissRequest = {
                dismissedUpdateEventId = appUpdateState.eventId
                showUpdateCheckResult = false
            },
            title = { Text(resources.getString(R.string.app_update_ready_title, appUpdateState.version.orEmpty())) },
            text = { Text(stringResource(R.string.app_update_ready_text)) },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(context, UpdateInstallActivity::class.java))
                }) { Text(stringResource(R.string.app_update_install)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    dismissedUpdateEventId = appUpdateState.eventId
                    showUpdateCheckResult = false
                }) { Text(stringResource(R.string.app_update_later)) }
            },
        )
    } else if (showUpdateCheckResult && appUpdateState.phase != AppUpdatePhase.IDLE) {
        val updateDialogBusy = appUpdateState.phase == AppUpdatePhase.CHECKING ||
            appUpdateState.phase == AppUpdatePhase.DOWNLOADING
        val updateDialogTitle = when (appUpdateState.phase) {
            AppUpdatePhase.CHECKING -> stringResource(R.string.app_update_checking)
            AppUpdatePhase.AVAILABLE -> resources.getString(
                R.string.app_update_ready_title,
                appUpdateState.version.orEmpty(),
            )
            AppUpdatePhase.DOWNLOADING -> resources.getString(
                R.string.app_update_ready_title,
                appUpdateState.version.orEmpty(),
            )
            AppUpdatePhase.UP_TO_DATE -> stringResource(R.string.app_update_up_to_date)
            AppUpdatePhase.ERROR -> stringResource(R.string.app_update_check_failed)
            else -> ""
        }
        AlertDialog(
            onDismissRequest = { if (!updateDialogBusy) showUpdateCheckResult = false },
            title = { Text(updateDialogTitle) },
            text = when (appUpdateState.phase) {
                AppUpdatePhase.AVAILABLE -> ({
                    Text(resources.getString(R.string.app_update_available_text, appUpdateState.version.orEmpty()))
                })
                AppUpdatePhase.DOWNLOADING -> ({
                    val percent = appUpdateState.progressPercent
                    Column {
                        Text(stringResource(R.string.app_update_downloading))
                        ReadableProgressBar(
                            progress = percent?.div(100f),
                            label = percent?.let { "$it%" }.orEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .height(34.dp),
                        )
                    }
                })
                AppUpdatePhase.ERROR -> ({
                    Text(appUpdateState.error ?: stringResource(R.string.app_update_check_failed))
                })
                else -> null
            },
            confirmButton = {
                if (appUpdateState.phase == AppUpdatePhase.AVAILABLE) {
                    TextButton(onClick = {
                        installUpdateWhenReady = true
                        scope.launch(Dispatchers.IO) {
                            runCatching { appUpdateManager.downloadAvailable() }
                                .onFailure {
                                    AppUpdateStore.update(
                                        AppUpdateState(
                                            AppUpdatePhase.ERROR,
                                            error = it.message ?: it::class.simpleName,
                                            eventId = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                        }
                    }) { Text(stringResource(R.string.app_update_download_install)) }
                } else if (!updateDialogBusy) {
                    TextButton(onClick = { showUpdateCheckResult = false }) { Text(stringResource(R.string.action_close)) }
                }
            },
            dismissButton = {
                if (appUpdateState.phase == AppUpdatePhase.AVAILABLE) {
                    TextButton(onClick = {
                        dismissedUpdateEventId = appUpdateState.eventId
                        showUpdateCheckResult = false
                    }) { Text(stringResource(R.string.app_update_later)) }
                }
            },
        )
    }

    if (showThemeProtectionRestartDialog) {
        AlertDialog(
            onDismissRequest = { showThemeProtectionRestartDialog = false },
            title = { Text(stringResource(R.string.theme_protection_restart_title)) },
            text = { Text(stringResource(R.string.theme_protection_restart_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showThemeProtectionRestartDialog = false
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val result = privilegedRunner.run("setprop sys.powerctl reboot", 10)
                                if (result.exitCode != 0) error(result.output.ifBlank { "exit ${result.exitCode}" })
                            }
                        }.onFailure { error ->
                            status = resources.getString(
                                R.string.theme_protection_reboot_failed,
                                error.message ?: error::class.simpleName,
                            )
                        }
                    }
                }) {
                    Text(stringResource(R.string.theme_protection_restart_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showThemeProtectionRestartDialog = false }) {
                    Text(stringResource(R.string.theme_protection_restart_cancel))
                }
            },
        )
    }

    if (showDeviceThemePicker) {
        DeviceThemePickerDialog(
            availableThemes = availableDeviceThemes,
            isLoading = isScanningDeviceThemes,
            onDismiss = { showDeviceThemePicker = false },
            onImportSelected = { selectedIds ->
                showDeviceThemePicker = false
                importSelectedDeviceThemes(selectedIds)
            },
        )
    }

    largeCatalogThemeCount?.let { count ->
        AlertDialog(
            onDismissRequest = { largeCatalogThemeCount = null },
            title = { Text(stringResource(R.string.catalog_large_warning_title)) },
            text = { Text(stringResource(R.string.catalog_large_warning_desc, count, MAX_SAFE_FULL_CATALOG_THEMES)) },
            confirmButton = {
                TextButton(onClick = {
                    largeCatalogThemeCount = null
                    refreshModernThemeManagerCatalog()
                }) { Text(stringResource(R.string.catalog_large_warning_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { largeCatalogThemeCount = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    pendingBakArchive?.let { archive ->
        AlertDialog(
            onDismissRequest = {
                archive.discardStagedCopy()
                pendingBakArchive = null
            },
            title = { Text(stringResource(R.string.bak_import_title)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.bak_import_desc))
                    Text(
                        "${archive.displayName} · ${archive.entryCount}",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = translateBakToAppLanguage,
                            onCheckedChange = { translateBakToAppLanguage = it },
                        )
                        androidx.compose.foundation.layout.Column {
                            Text(stringResource(R.string.theme_language_tool_title))
                            Text(stringResource(R.string.theme_language_tool_desc), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (bakImporting) return@TextButton
                        bakImporting = true
                        pendingBakArchive = null
                        scope.launch {
                            status = resources.getString(R.string.bak_restore_working)
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val imported = BakToMtzImporter(context).importThemes(archive, library)
                                    if (translateBakToAppLanguage) imported.forEach(themeLanguageTool::translateTextToSystemLanguage)
                                    diagnostics.record(
                                        "bak_direct_import_completed",
                                        "BAK, Tema Yöneticisine dokunulmadan MTZ kitaplığına alındı",
                                        mapOf("count" to imported.size, "translated" to translateBakToAppLanguage, "mode" to accessMode?.name),
                                    )
                                }
                            }.onSuccess {
                                archive.discardStagedCopy()
                                reload(openThemesAfter = true)
                                status = resources.getString(R.string.bak_restore_success)
                                bakImporting = false
                            }.onFailure { error ->
                                archive.discardStagedCopy()
                                status = resources.getString(R.string.bak_restore_failed, error.message ?: error::class.simpleName)
                                diagnostics.record("bak_direct_import_failed", "BAK doğrudan MTZ'ye dönüştürülemedi", error = error)
                                bakImporting = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.bak_restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    archive.discardStagedCopy()
                    pendingBakArchive = null
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    mtzBatchDocuments?.let { documents ->
        MtzBatchPickerDialog(
            documents = documents,
            selectedUris = mtzBatchSelection,
            onToggle = { uri ->
                mtzBatchSelection = if (uri in mtzBatchSelection) {
                    mtzBatchSelection - uri
                } else if (mtzBatchSelection.size < 5) {
                    mtzBatchSelection + uri
                } else {
                    mtzBatchSelection
                }
            },
            onImport = {
                val selected = documents.map { it.uri }.filter { it in mtzBatchSelection }
                mtzBatchDocuments = null
                mtzBatchSelection = emptySet()
                importMtzDocuments(selected)
            },
            onDismiss = {
                mtzBatchDocuments = null
                mtzBatchSelection = emptySet()
            },
        )
    }

    operationError?.let { message ->
        AlertDialog(
            onDismissRequest = { operationError = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { operationError = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }

    pendingOcrTheme?.let { theme ->
        AlertDialog(
            onDismissRequest = { pendingOcrTheme = null },
            title = { Text(stringResource(R.string.experimental_ocr_title)) },
            text = { Text(stringResource(R.string.experimental_ocr_description)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingOcrTheme = null
                    localizeTheme(theme, experimentalOcr = true)
                }) { Text(stringResource(R.string.experimental_ocr_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingOcrTheme = null
                }) { Text(stringResource(R.string.experimental_ocr_skip)) }
            },
        )
    }

    pendingApplyTheme?.let { theme ->
        AlertDialog(
            onDismissRequest = { pendingApplyTheme = null },
            title = {
                Text(
                    stringResource(
                        if (rootAccessAvailable != true) R.string.rootless_apply_dialog_title
                        else R.string.apply_dialog_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (rootAccessAvailable != true) R.string.rootless_apply_dialog_desc
                        else R.string.apply_dialog_desc,
                        theme.archive.metadata?.name ?: theme.displayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { beginThemeApply(theme) },
                ) {
                    Text(
                        stringResource(
                            if (rootAccessAvailable != true) R.string.action_open_in_themes
                            else R.string.action_apply,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingApplyTheme = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

internal data class MtzFolderDocument(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
)

private fun findMtzDocuments(context: Context, treeUri: Uri): List<MtzFolderDocument> {
    val resolver = context.contentResolver
    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    return resolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        buildList {
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty()
                val mime = cursor.getString(mimeIndex).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR || !name.endsWith(".mtz", ignoreCase = true)) continue
                val documentId = cursor.getString(idIndex)
                add(
                    MtzFolderDocument(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        displayName = name,
                        sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                    ),
                )
            }
        }.sortedBy { it.displayName.lowercase() }
    }.orEmpty()
}
