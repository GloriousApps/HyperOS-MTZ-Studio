package dev.glorioustr.mtzstudio

import android.content.ComponentName
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.glorioustr.mtzstudio.core.Hashing
import dev.glorioustr.mtzstudio.library.LibraryTheme
import dev.glorioustr.mtzstudio.shevery.PreferredPrivilegedCommandRunner
import dev.glorioustr.mtzstudio.tester.ThemeManagerContract
import java.util.UUID

data class PreparedThemeApply(
    val themeId: String,
    val themeName: String,
    val stagedPath: String,
    val intent: Intent,
    val protocol: ThemeApplyProtocol = ThemeApplyProtocol.LEGACY_TESTER,
    val manualImportPath: String? = null,
    val operation: ThemeManagerOperation = ThemeManagerOperation.APPLY,
    val themeManagerLocalId: String? = null,
    val themeManagerLocalIdsBefore: Set<String>? = null,
)

enum class ThemeManagerOperation { APPLY, IMPORT_ONLY, DELETE }

enum class ThemeApplyProtocol {
    LEGACY_TESTER,
    MODERN_THEME_MANAGER_BRIDGE,
    ROOT_GLOBAL_THEME_MANAGER_BRIDGE,
    MODERN_THEME_MANAGER_DIRECT_APPLY,
    MODERN_THEME_MANAGER_MANUAL_IMPORT,
    ROOTLESS_MANUAL_IMPORT,
    ROOTLESS_FILE_MANAGER_HANDOFF,
    ROOTLESS_LEGACY_TESTER,
    ROOTLESS_BACKUP_RESTORE,
}

class ThemeApplyCoordinator(
    private val context: Context,
    private val commandRunner: PreferredPrivilegedCommandRunner,
) {
    private val diagnostics get() = LiveDiagnosticsRecorder.get(context)

    fun prepare(theme: LibraryTheme, themeManagerLocalId: String? = null): PreparedThemeApply {
        diagnostics.record("apply_source_check", "Tema kaynağı ve sürüm kontrol ediliyor", mapOf(
            "theme" to theme.displayName, "version" to installedThemeManagerVersion(), "localId" to themeManagerLocalId,
        ))
        check(Hashing.sha256(theme.archive.source) == theme.archive.sha256) { "Tema kaynağı doğrulama sonrası değişmiş" }
        diagnostics.record("apply_hash_verified", "Tema kaynak SHA-256 doğrulaması başarılı")
        val modern = ThemeManagerContract.behavior(installedThemeManagerVersion()) ==
            dev.glorioustr.mtzstudio.tester.ThemeManagerBehavior.MODERN_NATIVE_LIBRARY
        // A modern local-library identity is authoritative. Some vendor/modded Themes 10.8/11
        // packages also expose the old screenshot tester alias, but that route needs root-only
        // staging and must not steal an already imported Shizuku theme from the native flow.
        return when {
            // The root bridge returns a stable Xiaomi Themes localId after the first import.
            // Reusing that record is essential: applying an already imported theme must never
            // enqueue the MTZ for a second import.
            rootGlobalModuleBridgeReady() && themeManagerLocalId != null ->
                prepareRootGlobalModuleExistingTheme(theme, themeManagerLocalId)
            rootGlobalModuleBridgeReady() -> prepareRootGlobalModuleImport(theme, ThemeManagerOperation.APPLY)
            modern && themeManagerLocalId != null -> prepareModernExistingTheme(theme, themeManagerLocalId)
            modern && legacyTesterAvailable() -> {
                diagnostics.record(
                    "modern_legacy_tester_fallback",
                    "Yerel tema kimliği bulunamadığı için Temalar tester geçidi yedek yol olarak kullanılacak",
                )
                prepareLegacyTester(theme)
            }
            modern -> prepareModernImport(theme, ThemeManagerOperation.APPLY)
            else -> prepareLegacyTester(theme)
        }
    }

    fun prepareModernImportOnly(theme: LibraryTheme): PreparedThemeApply =
        prepareModernImport(theme, ThemeManagerOperation.IMPORT_ONLY)

    fun prepareModernImportAndApply(theme: LibraryTheme): PreparedThemeApply =
        prepareModernImport(theme, ThemeManagerOperation.APPLY)

    /**
     * Global Themes builds have their importer but do not expose it to other applications.
     * Our active root module receives this verified request inside Themes, then uses Xiaomi's
     * native import queue and apply implementation.  The MTZ is never copied into Themes data
     * by Studio itself.
     */
    fun prepareRootGlobalModuleImportOnly(theme: LibraryTheme): PreparedThemeApply =
        prepareRootGlobalModuleImport(theme, ThemeManagerOperation.IMPORT_ONLY)

    private fun prepareRootGlobalModuleExistingTheme(
        theme: LibraryTheme,
        localId: String,
    ): PreparedThemeApply {
        check(rootGlobalModuleBridgeReady()) {
            "Root MTZ Import modülü etkin değil veya güncel değil. Modülü güncelleyip telefonu yeniden başlatın."
        }
        require(localId.matches(SAFE_LOCAL_ID)) { "Geçersiz Xiaomi Temalar yerel kimliği" }
        val themeName = theme.archive.metadata?.name ?: theme.displayName
        val intent = Intent(ThemeManagerBridgeContract.ACTION_APPLY_EXISTING).apply {
            component = ComponentName(THEME_MANAGER_PACKAGE, ROOT_GLOBAL_THEME_ACTIVITY)
            putExtra(ThemeManagerBridgeContract.EXTRA_THEME_LOCAL_ID, localId)
        }
        check(intent.resolveActivity(context.packageManager) != null) {
            "Xiaomi Temalar yerel tema uygulama ekranı bulunamadı"
        }
        diagnostics.record(
            "root_global_existing_apply_prepared",
            "Xiaomi Temalar'daki mevcut yerel tema yeniden içe aktarılmadan uygulamaya hazırlandı",
            mapOf("theme" to theme.displayName, "localId" to localId),
        )
        return PreparedThemeApply(
            themeId = theme.id.value,
            themeName = themeName,
            stagedPath = "",
            intent = intent,
            protocol = ThemeApplyProtocol.ROOT_GLOBAL_THEME_MANAGER_BRIDGE,
            operation = ThemeManagerOperation.APPLY,
            themeManagerLocalId = localId,
        )
    }

    fun rootGlobalModuleBridgeReady(): Boolean {
        val result = runRecordedRootOrShell(
            "root_global_bridge_check",
            // The module is injected when this intent launches Xiaomi Themes.  Its marker
            // cannot exist before that first launch, so it must not gate the request itself.
            "test \"\$(id -u)\" = 0 && test -f '$ROOT_GLOBAL_MODULE_PROP' && " +
                "/system/bin/grep -qx 'version=$ROOT_GLOBAL_MODULE_VERSION' '$ROOT_GLOBAL_MODULE_PROP'",
            5,
        )
        return result.exitCode == 0
    }

    private fun prepareRootGlobalModuleImport(theme: LibraryTheme, operation: ThemeManagerOperation): PreparedThemeApply {
        check(rootGlobalModuleBridgeReady()) {
            "Root MTZ Import modülü etkin değil veya güncel değil. Modülü güncelleyip telefonu yeniden başlatın."
        }
        check(Hashing.sha256(theme.archive.source) == theme.archive.sha256) {
            "Tema kaynağı doğrulama sonrası değişmiş"
        }
        val themeName = theme.archive.metadata?.name ?: theme.displayName
        val exported = checkNotNull(MtzPublicExporter.exportToPublicDownloads(context, theme.archive.source, themeName)) {
            "Tema, Xiaomi Temalar içe aktarma akışı için hazırlanamadı"
        }
        val stagedPath = "$THEME_MANAGER_MODERN_DOWNLOAD_ROOT/${UUID.randomUUID()}.mtz"
        val stage = "/system/bin/mkdir -p ${shellQuote(THEME_MANAGER_MODERN_DOWNLOAD_ROOT)} && " +
            "/system/bin/cp ${shellQuote(exported.absolutePath)} ${shellQuote(stagedPath)} && " +
            // Android 15/16 FUSE may reject chmod inside another app's external-files
            // domain even after a successful root copy.  The copied file is already
            // readable by Themes; validate its presence instead of turning that benign
            // storage-layer limitation into an import failure.
            "/system/bin/test -s ${shellQuote(stagedPath)}"
        val result = runRecordedRootOrShell("root_global_mtz_staging", stage, 120)
        check(result.exitCode == 0) {
            "Tema Xiaomi Temalar içe aktarma alanına hazırlanamadı: ${result.output.takeLast(500)}"
        }
        val intent = Intent(
            if (operation == ThemeManagerOperation.APPLY) {
                ThemeManagerBridgeContract.ACTION_APPLY_MODERN
            } else {
                ThemeManagerBridgeContract.ACTION_IMPORT_MODERN
            },
        ).apply {
            component = ComponentName(THEME_MANAGER_PACKAGE, ROOT_GLOBAL_THEME_ACTIVITY)
            putExtra(ThemeManagerBridgeContract.EXTRA_THEME_PATH, stagedPath)
            putExtra(ThemeManagerBridgeContract.EXTRA_THEME_SHA256, theme.archive.sha256)
            putExtra(ThemeManagerBridgeContract.EXTRA_THEME_NAME, themeName.take(180))
        }
        check(intent.resolveActivity(context.packageManager) != null) {
            "Xiaomi Temalar içe aktarma ekranı bulunamadı"
        }
        diagnostics.record(
            "root_global_bridge_prepared",
            "Root modülü için Xiaomi Temalar yerleşik içe aktarma isteği hazırlandı",
            mapOf("theme" to theme.displayName, "operation" to operation.name),
        )
        return PreparedThemeApply(
            themeId = theme.id.value,
            themeName = themeName,
            stagedPath = stagedPath,
            intent = intent,
            protocol = ThemeApplyProtocol.ROOT_GLOBAL_THEME_MANAGER_BRIDGE,
            operation = operation,
        )
    }

    /**
     * Adds an MTZ to Xiaomi Themes through HyperOS' own backup service while running with
     * Shizuku/Shevery. This is the only stock-system path that does not require us to invoke
     * Xiaomi's private Java importer or automate screen coordinates.
     *
     * The caller must resolve and persist the newly-created Theme Manager localId after this
     * returns. Keeping that lookup outside this class also makes duplicate detection use the
     * same catalog implementation as normal device-theme imports.
     */
    fun importModernThroughShizukuBackup(theme: LibraryTheme): String {
        check(
            ThemeManagerContract.behavior(installedThemeManagerVersion()) ==
                dev.glorioustr.mtzstudio.tester.ThemeManagerBehavior.MODERN_NATIVE_LIBRARY,
        ) { "Bu işlem yalnızca yerleşik MTZ kitaplığı bulunan Xiaomi Temalar sürümlerinde kullanılabilir" }
        check(Hashing.sha256(theme.archive.source) == theme.archive.sha256) {
            "Tema kaynağı doğrulama sonrası değişmiş"
        }
        check(SheveryBackupRestorer.state() == SheveryBackupRestorer.State.READY) {
            "Shizuku/Shevery hazır değil veya izin verilmemiş"
        }

        val source = theme.archive.source.toFile()
        val localId = MtzToBakConverter.restoredThemeLocalId(source)
        val backup = java.io.File(context.cacheDir, "native-import-${UUID.randomUUID()}.bak")
        return try {
            diagnostics.record(
                "dual_import_backup_build",
                "MTZ, Xiaomi Temalar kitaplığı için hazırlanıyor",
                mapOf("theme" to theme.displayName),
            )
            MtzToBakConverter.convert(
                source,
                backup,
                MtzToBakConverter.deviceInfo(context),
            )
            val bytes = SheveryBackupRestorer.restore(backup)
            diagnostics.record(
                "dual_import_backup_restored",
                "MTZ, Studio kitaplığına ek olarak Xiaomi Temalar kitaplığına aktarıldı",
                mapOf("theme" to theme.displayName, "bytes" to bytes, "localId" to localId),
            )
            localId
        } finally {
            backup.delete()
        }
    }

    /**
     * Rootless hand-off: retain a public MTZ copy and open the best public Theme Manager surface.
     * No claim is made that Xiaomi accepted or applied the file; the user completes the import.
     */
    fun prepareRootlessManualImport(theme: LibraryTheme): PreparedThemeApply {
        check(Hashing.sha256(theme.archive.source) == theme.archive.sha256) {
            "Tema kaynağı doğrulama sonrası değişmiş"
        }
        val themeName = theme.archive.metadata?.name ?: theme.displayName
        val publicFile = checkNotNull(
            MtzPublicExporter.exportToPublicDownloads(context, theme.archive.source, themeName),
        ) { "MTZ, İndirilenler/MTZ Studio klasörüne kaydedilemedi" }
        val installedVersion = installedThemeManagerVersion()
        val behavior = ThemeManagerContract.behavior(installedVersion)
        if (SheveryBackupRestorer.state() == SheveryBackupRestorer.State.READY) {
            val backup = java.io.File(context.cacheDir, "rootless-theme-${UUID.randomUUID()}.bak")
            val restoredThemePath = MtzToBakConverter.restoredThemePath(theme.archive.source.toFile())
            val restored = runCatching {
                diagnostics.record("rootless_mtz_backup_build", "MTZ, cihazın Temalar sürümüne uygun BAK paketine dönüştürülüyor")
                MtzToBakConverter.convert(theme.archive.source.toFile(), backup, MtzToBakConverter.deviceInfo(context))
                val bytes = SheveryBackupRestorer.restore(backup)
                diagnostics.record("rootless_mtz_backup_restored", "MTZ, HyperOS yedekleme servisi üzerinden Temalar kitaplığına aktarıldı", mapOf("bytes" to bytes, "theme" to themeName))
                true
            }.onFailure {
                diagnostics.record("rootless_mtz_backup_fallback", "Doğrudan rootsuz aktarım tamamlanamadı; güvenli elle içe aktarma kullanılacak", error = it)
            }.getOrDefault(false)
            backup.delete()
            if (restored) {
                // Downloads is not directly readable by Themes under scoped storage. The BAK
                // restore places the MTZ in Themes' own external-files domain, so the Zyper
                // local-apply contract can consume it without a picker or online rights check.
                val tester = ThemeManagerContract.localRestoredThemeRequest(restoredThemePath)
                val testerIntent = Intent().apply {
                    component = ComponentName(THEME_MANAGER_PACKAGE, tester.componentClassName)
                    tester.stringExtras.forEach(::putExtra)
                    tester.longExtras.forEach(::putExtra)
                }.takeIf { it.resolveActivity(context.packageManager) != null }
                diagnostics.record(
                    "rootless_local_apply_ready",
                    if (testerIntent != null) {
                        "Tema Xiaomi'nin yerel uygulama geçidine hazırlandı"
                    } else {
                        "Tema kitaplığa aktarıldı; bu Temalar sürümünde doğrudan uygulama geçidi olmadığı için güvenli ana ekran açılacak"
                    },
                    mapOf(
                        "restoredPath" to restoredThemePath,
                        "caller" to THEME_MANAGER_PACKAGE,
                        "directApplyAvailable" to (testerIntent != null),
                    ),
                )
                return PreparedThemeApply(
                    themeId = theme.id.value,
                    themeName = themeName,
                    stagedPath = "",
                    intent = testerIntent ?: safeThemeManagerLauncherIntent(),
                    protocol = ThemeApplyProtocol.ROOTLESS_BACKUP_RESTORE,
                    manualImportPath = restoredThemePath,
                )
            }
        }
        val legacyIntent = if (behavior ==
            dev.glorioustr.mtzstudio.tester.ThemeManagerBehavior.LOCAL_THEME_IMPORT
        ) {
            val request = ThemeManagerContract.legacyTesterRequest(publicFile.absolutePath, context.packageName)
            Intent(request.action).apply {
                component = ComponentName(THEME_MANAGER_PACKAGE, request.componentClassName)
                request.stringExtras.forEach(::putExtra)
                request.longExtras.forEach(::putExtra)
            }.takeIf { it.resolveActivity(context.packageManager) != null }
        } else null
        diagnostics.record(
            "rootless_manual_handoff",
            if (legacyIntent != null) {
                "MTZ rootsuz Global içe aktarma için Temalar tester geçidine hazırlandı"
            } else {
                "MTZ rootsuz elle içe aktarma için hazırlandı"
            },
            mapOf(
                "theme" to themeName,
                "file" to publicFile.name,
                "themeManagerVersion" to installedVersion,
                "themeManagerBehavior" to behavior.name,
                "legacyTesterResolved" to (legacyIntent != null),
            ),
        )
        return PreparedThemeApply(
            themeId = theme.id.value,
            themeName = themeName,
            stagedPath = "",
            intent = legacyIntent ?: publicThemeManagerIntent(theme.archive.source),
            protocol = if (legacyIntent != null) {
                ThemeApplyProtocol.ROOTLESS_LEGACY_TESTER
            } else {
                ThemeApplyProtocol.ROOTLESS_FILE_MANAGER_HANDOFF
            },
            manualImportPath = publicFile.absolutePath,
        )
    }

    fun requireModernPrivilegedAccess() {
        diagnostics.record("privileged_preflight_started", "Root veya Shizuku uyumlu yetki denetleniyor")
        val result = runRecordedRootOrShell("privileged_preflight", "id -u", 10)
        val uid = result.output.lineSequence().firstOrNull()?.trim()
        check(result.exitCode == 0 && uid in setOf("0", "2000")) {
            context.getString(R.string.privileged_access_unavailable)
        }
        diagnostics.record(
            "privileged_preflight_completed",
            "Yetkili işlem kanalı hazır",
            mapOf("source" to result.authorizationSource),
        )
    }

    private fun prepareModernImport(theme: LibraryTheme, operation: ThemeManagerOperation): PreparedThemeApply {
        diagnostics.record("modern_import_prepare", "Yerleşik MTZ aktarımı hazırlanıyor", mapOf("operation" to operation, "theme" to theme.displayName))
        val themeName = theme.archive.metadata?.name ?: theme.displayName
        val bridgeReady = ensureModernThemeManagerBridgeScope()
        val manualImportFile = checkNotNull(
            MtzPublicExporter.exportToPublicDownloads(context, theme.archive.source, themeName),
        ) { "Tema, Xiaomi Temalar içe aktarma akışı için İndirilenler/MTZ Studio klasörüne hazırlanamadı" }
        val stagedPath = "$THEME_MANAGER_MODERN_DOWNLOAD_ROOT/${UUID.randomUUID()}.mtz"
        val command = buildString {
            append("/system/bin/mkdir -p ").append(shellQuote(THEME_MANAGER_MODERN_DOWNLOAD_ROOT))
            // The app-private library path cannot be read by the ADB shell UID. The verified
            // public export is readable by both root and Shizuku and contains the same MTZ.
            append(" && /system/bin/cp ").append(shellQuote(manualImportFile.absolutePath))
            append(' ').append(shellQuote(stagedPath))
            append(" && /system/bin/chmod 0644 ").append(shellQuote(stagedPath))
        }
        val result = runRecordedRootOrShell("modern_staging", command, 120)
        check(result.exitCode == 0) { "Tema, Temalar 10.8 içe aktarma alanına hazırlanamadı: ${result.output.takeLast(500)}" }

        val intent = Intent().apply {
            component = ComponentName(THEME_MANAGER_PACKAGE, THEME_MANAGER_MODERN_LOCAL_ACTIVITY)
            putExtra("REQUEST_RESOURCE_CODE", "theme")
            if (bridgeReady) {
                action = if (operation == ThemeManagerOperation.IMPORT_ONLY) {
                    ThemeManagerBridgeContract.ACTION_IMPORT_MODERN
                } else {
                    ThemeManagerBridgeContract.ACTION_APPLY_MODERN
                }
                putExtra(ThemeManagerBridgeContract.EXTRA_THEME_PATH, stagedPath)
                putExtra(ThemeManagerBridgeContract.EXTRA_THEME_SHA256, theme.archive.sha256)
                putExtra(ThemeManagerBridgeContract.EXTRA_THEME_NAME, themeName.take(180))
            }
        }
        check(intent.resolveActivity(context.packageManager) != null) { "Xiaomi Temalar yerel tema ekranı bulunamadı" }
        return PreparedThemeApply(
            themeId = theme.id.value,
            themeName = themeName,
            stagedPath = stagedPath,
            intent = intent,
            protocol = if (bridgeReady) {
                ThemeApplyProtocol.MODERN_THEME_MANAGER_BRIDGE
            } else {
                ThemeApplyProtocol.MODERN_THEME_MANAGER_MANUAL_IMPORT
            },
            manualImportPath = manualImportFile.absolutePath,
            operation = operation,
        )
    }

    private fun prepareModernExistingTheme(theme: LibraryTheme, localId: String): PreparedThemeApply {
        require(localId.matches(SAFE_LOCAL_ID)) { "Geçersiz Tema Mağazası yerel kimliği" }
        val bridgeReady = ensureModernThemeManagerBridgeScope()
        val themeName = theme.archive.metadata?.name ?: theme.displayName
        val intent = if (bridgeReady) {
            modernThemeManagerIntent(ThemeManagerBridgeContract.ACTION_APPLY_EXISTING).apply {
                putExtra(ThemeManagerBridgeContract.EXTRA_THEME_LOCAL_ID, localId)
            }
        } else {
            // Xiaomi Themes 10.8/11 internally uses REQUEST_APPLY_EVENT to restore/apply a
            // local resource as soon as its exported detail activity resolves the localId.
            // This is coordinate-free and leaves all validation inside Xiaomi Themes.
            Intent(Intent.ACTION_VIEW, modernLocalThemeUri(localId)).apply {
                setPackage(THEME_MANAGER_PACKAGE)
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra("REQUEST_RESOURCE_CODE", "theme")
                putExtra("REQUEST_APPLY_EVENT", true)
            }.also {
                check(it.resolveActivity(context.packageManager) != null) {
                    "Xiaomi Temalar yerel tema uygulama ekranı bulunamadı"
                }
            }
        }
        return PreparedThemeApply(
            themeId = theme.id.value,
            themeName = themeName,
            stagedPath = "",
            intent = intent,
            protocol = if (bridgeReady) {
                ThemeApplyProtocol.MODERN_THEME_MANAGER_BRIDGE
            } else {
                ThemeApplyProtocol.MODERN_THEME_MANAGER_DIRECT_APPLY
            },
            operation = ThemeManagerOperation.APPLY,
            themeManagerLocalId = localId,
        )
    }

    fun prepareModernDelete(theme: LibraryTheme, localId: String): PreparedThemeApply {
        diagnostics.record("native_delete_prepare", "Yerleşik tema silme hazırlanıyor", mapOf("theme" to theme.displayName, "localId" to localId))
        require(localId.matches(SAFE_LOCAL_ID)) { "Geçersiz Tema Mağazası yerel kimliği" }
        check(ensureModernThemeManagerBridgeScope()) { "Tema Mağazası köprüsü etkin değil" }
        return PreparedThemeApply(
            themeId = theme.id.value,
            themeName = theme.archive.metadata?.name ?: theme.displayName,
            stagedPath = "",
            intent = modernThemeManagerIntent(ThemeManagerBridgeContract.ACTION_DELETE_EXISTING).apply {
                putExtra(ThemeManagerBridgeContract.EXTRA_THEME_LOCAL_ID, localId)
            },
            protocol = ThemeApplyProtocol.MODERN_THEME_MANAGER_BRIDGE,
            operation = ThemeManagerOperation.DELETE,
            themeManagerLocalId = localId,
        )
    }

    fun prepareModernManualFallback(prepared: PreparedThemeApply): PreparedThemeApply {
        check(prepared.manualImportPath != null) { "Yerleşik içe aktarma için hazırlanmış MTZ bulunamadı" }
        return prepared.copy(
            intent = Intent().apply {
                component = ComponentName(THEME_MANAGER_PACKAGE, THEME_MANAGER_MODERN_LOCAL_ACTIVITY)
                putExtra("REQUEST_RESOURCE_CODE", "theme")
            },
            protocol = ThemeApplyProtocol.MODERN_THEME_MANAGER_MANUAL_IMPORT,
        )
    }

    fun nativeLibraryIntent(): Intent = Intent().apply {
        component = ComponentName(THEME_MANAGER_PACKAGE, THEME_MANAGER_MODERN_LOCAL_ACTIVITY)
        putExtra("REQUEST_RESOURCE_CODE", "theme")
        check(resolveActivity(context.packageManager) != null) { "Theme Manager local library is unavailable" }
    }

    private fun publicThemeManagerIntent(source: java.nio.file.Path): Intent {
        val sourceUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            source.toFile(),
        )
        // Xiaomi changes the names of its internal local-theme screens between builds.  Never
        // open a guessed explicit activity here: on 2.15.6.12-global the former
        // MineResourceTabActivity no longer exists and causes an ActivityNotFoundException.
        // An implicit file intent only proceeds when this installed Themes build advertises it.
        val mimeTypes = listOf(
            "application/vnd.miui.mtz",
            "application/x-mtz",
            "application/zip",
            "application/octet-stream",
        )
        mimeTypes.forEach { mimeType ->
            val directFileOpen = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(sourceUri, mimeType)
                setPackage(THEME_MANAGER_PACKAGE)
                clipData = ClipData.newRawUri("MTZ", sourceUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (directFileOpen.resolveActivity(context.packageManager) != null) {
                diagnostics.record(
                    "rootless_public_file_handoff",
                    "Temalar uygulamasının ilan ettiği MTZ dosya açma yolu bulundu",
                    mapOf("mimeType" to mimeType),
                )
                return directFileOpen
            }
        }

        // These Global branches removed their former public Themes import activity.  Xiaomi File
        // Manager is installed on the supported ROM families and can receive a public MTZ URI,
        // then hand it to the version-specific Themes importer.  Do not restrict this intent to
        // Themes: that restriction is exactly what left 2.15.6.12 and 3.0.5.19 at the launcher.
        val fileManagerIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(sourceUri, "application/vnd.miui.mtz")
            clipData = ClipData.newRawUri("MTZ", sourceUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val fileManagerTargets = context.packageManager.queryIntentActivities(fileManagerIntent, 0)
            .filter { it.activityInfo.packageName != THEME_MANAGER_PACKAGE }
        if (fileManagerTargets.isNotEmpty()) {
            diagnostics.record(
                "rootless_file_manager_handoff",
                "Temalar bu sürümde dış MTZ içe aktarmayı ilan etmiyor; MTZ Dosya Yöneticisi aktarımına hazırlanıyor",
                mapOf(
                    "version" to installedThemeManagerVersion(),
                    "targets" to fileManagerTargets.joinToString { it.activityInfo.packageName },
                ),
            )
            return Intent.createChooser(fileManagerIntent, "MTZ Import")
        }

        diagnostics.record(
            "rootless_public_file_handoff_unavailable",
            "Bu Temalar sürümü dış MTZ dosyası için ilan edilmiş bir içe aktarma etkinliği sunmuyor; MTZ Dosya Yöneticisi klasöründe hazır bırakıldı",
            mapOf("version" to installedThemeManagerVersion()),
        )
        throw IllegalStateException(context.getString(R.string.tm_rootless_import_unavailable))
    }

    fun openModernLocalThemeDetail(localId: String) {
        require(localId.matches(SAFE_LOCAL_ID)) { "Geçersiz Tema Mağazası yerel kimliği" }
        val uri = modernLocalThemeUri(localId).toString()
        val command = "/system/bin/am start -W -a android.intent.action.VIEW " +
            "-c android.intent.category.DEFAULT -d ${shellQuote(uri)} -p $THEME_MANAGER_PACKAGE " +
            "--es REQUEST_RESOURCE_CODE theme --ez REQUEST_APPLY_EVENT true"
        val result = runRecordedRootOrShell("modern_theme_detail", command, 30)
        check(result.exitCode == 0 && !result.output.contains("Error:", ignoreCase = true) &&
            !result.output.contains("Exception", ignoreCase = true)
        ) { "Xiaomi Temalar tema ayrıntı ekranı açılamadı: ${result.output.takeLast(500)}" }
    }

    private fun safeThemeManagerLauncherIntent(): Intent =
        checkNotNull(context.packageManager.getLaunchIntentForPackage(THEME_MANAGER_PACKAGE)) {
            "Xiaomi Temalar uygulamasının açılabilir bir ekranı bulunamadı"
        }

    private fun modernLocalThemeUri(localId: String): Uri =
        Uri.parse("ViewLocalResource://view.local.resource#$localId")

    private fun prepareLegacyTester(theme: LibraryTheme): PreparedThemeApply {
        val themeName = theme.archive.metadata?.name ?: theme.displayName
        val readableSource = MtzPublicExporter.exportToPublicDownloads(context, theme.archive.source, themeName)
            ?.absolutePath ?: theme.archive.source.toString()
        val stagedPath = "$THEME_MANAGER_STAGING_ROOT/${UUID.randomUUID()}.mtz"
        val command = buildString {
            append("/system/bin/mkdir -p ").append(shellQuote(THEME_MANAGER_STAGING_ROOT))
            append(" && /system/bin/cp ").append(shellQuote(readableSource))
            append(' ').append(shellQuote(stagedPath))
            append(" && /system/bin/chmod 777 ").append(shellQuote(stagedPath))
            append(" && /system/bin/mkdir -p /sdcard/MIUI/theme")
            append(" && /system/bin/cp ").append(shellQuote(readableSource))
            append(" /sdcard/MIUI/theme/${UUID.randomUUID()}.mtz 2>/dev/null || true")
        }
        val result = runRecorded("legacy_staging", command, 120)
        check(result.exitCode == 0) { "Tema, Tema Yöneticisine hazırlanamadı: ${result.output.takeLast(500)}" }

        val request = ThemeManagerContract.legacyTesterRequest(
            stagedPath,
            context.packageName,
        )
        val intent = Intent(request.action).apply {
            component = ComponentName(THEME_MANAGER_PACKAGE, request.componentClassName)
            request.stringExtras.forEach(::putExtra)
            request.longExtras.forEach(::putExtra)
        }
        check(intent.resolveActivity(context.packageManager) != null) { "Uyumlu Tema Yöneticisi tester aktivitesi bulunamadı" }
        return PreparedThemeApply(
            themeId = theme.id.value,
            themeName = themeName,
            stagedPath = stagedPath,
            intent = intent,
            protocol = ThemeApplyProtocol.LEGACY_TESTER,
        )
    }

    fun cleanup(prepared: PreparedThemeApply) {
        // Retain file temporarily or clean asynchronously so background services aren't starved during application
        if (prepared.stagedPath.isNotBlank()) {
            val command = "/system/bin/rm -f ${shellQuote(prepared.stagedPath)}"
            if (prepared.protocol == ThemeApplyProtocol.MODERN_THEME_MANAGER_BRIDGE ||
                prepared.protocol == ThemeApplyProtocol.ROOT_GLOBAL_THEME_MANAGER_BRIDGE ||
                prepared.protocol == ThemeApplyProtocol.MODERN_THEME_MANAGER_MANUAL_IMPORT
            ) {
                runRecordedRootOrShell("staging_cleanup", command, 30)
            } else {
                runRecorded("staging_cleanup", command, 30)
            }
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun installedThemeManagerVersion(): String? = runCatching {
        context.packageManager.getPackageInfo(THEME_MANAGER_PACKAGE, 0).versionName
    }.getOrNull()

    private fun isImportBridgeReady(): Boolean {
        val command = BRIDGE_MARKER_FILES.joinToString(separator = " || ") { path ->
            "( /system/bin/grep -q '^ready=true' ${shellQuote(path)} 2>/dev/null && " +
                "/system/bin/grep -q '^version=${BuildConfig.VERSION_CODE}$' ${shellQuote(path)} 2>/dev/null )"
        }
        // An ADB-mode Shizuku process cannot read the root-only bridge markers. That is an
        // expected "bridge unavailable" result and must not be converted into a root prompt.
        return runRecordedRootOrShell("bridge_marker_check", command, 5).exitCode == 0
    }

    private fun ensureModernThemeManagerBridgeScope(): Boolean {
        // Scope approval only shows that Vector/LSPosed accepted the selection. It does not prove
        // the module was injected into the currently running Themes process. Require the marker
        // emitted by onPackageReady before dispatching a bridge request; otherwise use the manual
        // native-library screen instead of leaving the operation waiting for a callback forever.
        val scopeApproved = ThemeProtectionServiceClient.isModernBridgeScopeApproved()
        val markerReady = isImportBridgeReady()
        if (markerReady) {
            diagnostics.record(
                "bridge_runtime_ready",
                "Temalar köprüsü çalışan süreçte doğrulandı; ayarlar değiştirilmedi",
                mapOf("scopeApproved" to scopeApproved),
            )
            return true
        }
        diagnostics.record(
            "bridge_runtime_unavailable",
            "Temalar kapsamı seçili olsa da köprü çalışan süreçte hazır değil; güvenli el ile içe aktarma açılacak",
            mapOf("scopeApproved" to scopeApproved),
        )
        return false
    }

    private fun modernThemeManagerIntent(actionName: String): Intent = Intent(actionName).apply {
        component = ComponentName(THEME_MANAGER_PACKAGE, THEME_MANAGER_MODERN_LOCAL_ACTIVITY)
        putExtra("REQUEST_RESOURCE_CODE", "theme")
        check(resolveActivity(context.packageManager) != null) { "Temalar yerel tema ekranı bulunamadı" }
    }

    private fun runRecorded(stage: String, command: String, timeoutSeconds: Long) = try {
        diagnostics.record("privileged_step_started", "Yetkili işlem başladı", mapOf("stage" to stage))
        commandRunner.run(command, timeoutSeconds).also { result ->
            diagnostics.record("privileged_step_result", "Yetkili işlem sonucu", mapOf(
                "stage" to stage, "exitCode" to result.exitCode,
                "output" to result.output.takeLast(1500),
            ))
        }
    } catch (error: Exception) {
        diagnostics.record("privileged_step_failed", "Yetkili işlem tamamlanamadı", mapOf("stage" to stage), error)
        throw error
    }

    private fun legacyTesterAvailable(): Boolean {
        val request = ThemeManagerContract.legacyTesterRequest("/dev/null", context.packageName)
        return Intent(request.action).apply {
            component = ComponentName(THEME_MANAGER_PACKAGE, request.componentClassName)
        }.resolveActivity(context.packageManager) != null
    }

    private fun runRecordedRootOrShell(stage: String, command: String, timeoutSeconds: Long) = try {
        diagnostics.record(
            "privileged_step_started",
            "Root veya Shizuku kabuk işlemi başladı",
            mapOf("stage" to stage),
        )
        commandRunner.runRootOrAdbShell(command, timeoutSeconds).also { result ->
            diagnostics.record(
                "privileged_step_result",
                "Root veya Shizuku kabuk işlemi sonucu",
                mapOf(
                    "stage" to stage,
                    "exitCode" to result.exitCode,
                    "source" to result.authorizationSource,
                    "output" to result.output.takeLast(1500),
                ),
            )
        }
    } catch (error: Exception) {
        diagnostics.record(
            "privileged_step_failed",
            "Root veya Shizuku kabuk işlemi tamamlanamadı",
            mapOf("stage" to stage),
            error,
        )
        throw error
    }

    /** Only inspect recent crash records for the two packages involved in this request. */
    fun captureFailureDiagnostics(startedAt: Long) {
        runCatching {
            val since = java.time.Instant.ofEpochMilli(maxOf(startedAt, System.currentTimeMillis() - 300_000))
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", java.util.Locale.US))
            val result = commandRunner.runRootOrAdbShell(
                "logcat -b crash -d -v threadtime -T ${shellQuote(since)} | tail -n 200",
                10,
            )
            check(result.exitCode == 0) { "Crash log unavailable (${result.exitCode}): ${result.output}" }
            val lines = result.output.lines()
            val ownPids = lines.filter { it.contains("Process: $THEME_MANAGER_PACKAGE,") || it.contains("Process: ${context.packageName},") }
                .mapNotNull { Regex("PID: (\\d+)").find(it)?.groupValues?.get(1) }.toSet()
            val relevant = lines.filter { line -> line.trim().split(Regex("\\s+"), limit = 5).getOrNull(2) in ownPids }
            diagnostics.record("host_crash_check", if (relevant.isEmpty()) "Bu işlem aralığında ilgili çökme kaydı bulunamadı; iptal veya yanıtsız dönüş olabilir" else "Temalar işlemine ait çökme ayrıntısı bulundu",
                mapOf("crash" to relevant.joinToString("\n").takeLast(6000)))
        }.onFailure { diagnostics.record("host_crash_check_unavailable", "Ek çökme kaydı okunamadı", error = it) }
        runCatching {
            val filter = "thememanager|ThemeImport|ResourceImport|action_resource_import|MTZStudioProtection"
            val result = commandRunner.runRootOrAdbShell(
                "logcat -b main -b system -d -v threadtime -t 1000 " +
                    "| /system/bin/grep -E ${shellQuote(filter)} | /system/bin/tail -n 250",
                30,
            )
            check(result.exitCode == 0) { "Import log unavailable (${result.exitCode}): ${result.output}" }
            diagnostics.record(
                "host_import_log",
                if (result.output.isBlank()) "Tema Yöneticisi içe aktarma ayrıntısı üretmedi" else "Tema Yöneticisi içe aktarma çalışma kaydı alındı",
                mapOf("exitCode" to result.exitCode, "log" to result.output.takeLast(6_000)),
            )
        }.onFailure {
            diagnostics.record("host_import_log_unavailable", "Tema Yöneticisi çalışma kaydı okunamadı", error = it)
        }
    }

    private companion object {
        const val THEME_MANAGER_PACKAGE = "com.android.thememanager"
        const val THEME_MANAGER_STAGING_ROOT = "/sdcard/Android/data/com.android.thememanager/files/mtzstudio"
        const val THEME_MANAGER_MODERN_DOWNLOAD_ROOT =
            "/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/.download"
        const val THEME_MANAGER_MODERN_LOCAL_ACTIVITY =
            ThemeManagerContract.MODERN_LOCAL_LIBRARY_COMPONENT
        const val ROOT_GLOBAL_THEME_ACTIVITY = "com.android.thememanager.activity.ThemeTabActivity"
        const val ROOT_GLOBAL_READY_MARKER = "/data/user/0/com.android.thememanager/files/mtz_import_module_ready"
        const val ROOT_GLOBAL_MODULE_PROP = "/data/adb/modules/xiaomi_themes_global_import/module.prop"
        const val ROOT_GLOBAL_MODULE_VERSION = "0.1.8-alpha"
        val SAFE_LOCAL_ID = Regex("[A-Za-z0-9._-]{1,128}")
        val BRIDGE_MARKER_FILES = listOf(
            "/data/system/theme/${ThemeManagerBridgeContract.BRIDGE_MARKER}",
            "/data/data/com.android.thememanager/files/${ThemeManagerBridgeContract.BRIDGE_MARKER}",
            "/data/user/0/com.android.thememanager/files/${ThemeManagerBridgeContract.BRIDGE_MARKER}",
            "/data/local/tmp/${ThemeManagerBridgeContract.BRIDGE_MARKER}",
        )
    }
}
