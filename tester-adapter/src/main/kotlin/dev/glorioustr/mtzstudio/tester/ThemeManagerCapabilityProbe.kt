package dev.glorioustr.mtzstudio.tester

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager

/**
 * A runtime profile supplements version rules with observable package capabilities.  It never
 * upgrades an unknown build to an automatic import path: a resolved activity alone cannot prove
 * Xiaomi's semantic behaviour.  The profile is instead recorded in diagnostics and paired with a
 * user-exported base APK when a new ROM branch needs a verified compatibility rule.
 */
data class ThemeManagerRuntimeProfile(
    val packageInstalled: Boolean,
    val versionName: String?,
    val knownBehavior: ThemeManagerBehavior,
    val legacyTesterResolvable: Boolean,
    /** True when the 10.8.7.6+ Theme Manager family exposes its native local-theme library. */
    val modernLocalLibraryResolvable: Boolean,
    /** True only when Themes itself advertises a public content-URI MTZ entry point. */
    val publicMtzImportResolvable: Boolean,
    val splitApkCount: Int,
    val exportedThemeActivityCandidates: List<String>,
) {
    /** A verified legacy apply activity or a verified modern native import library is sufficient. */
    val compatibleLocalMtzPath: Boolean
        get() = legacyTesterResolvable ||
            (knownBehavior == ThemeManagerBehavior.MODERN_NATIVE_LIBRARY && modernLocalLibraryResolvable)
}

class ThemeManagerCapabilityProbe(private val context: Context) {
    fun probe(installed: InstalledThemeManager): ThemeManagerRuntimeProfile {
        if (!installed.installed) {
            return ThemeManagerRuntimeProfile(false, null, ThemeManagerBehavior.UNKNOWN, false, false, false, 0, emptyList())
        }
        val legacyTester = Intent(ThemeManagerContract.LEGACY_TESTER_ACTION).apply {
            component = ComponentName(ThemeManagerContract.PACKAGE_NAME, ThemeManagerContract.LEGACY_TESTER_COMPONENT)
        }
        val resolvable = runCatching {
            context.packageManager.resolveActivity(legacyTester, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
        val modernLocalLibrary = Intent().apply {
            component = ComponentName(
                ThemeManagerContract.PACKAGE_NAME,
                ThemeManagerContract.MODERN_LOCAL_LIBRARY_COMPONENT,
            )
            putExtra("REQUEST_RESOURCE_CODE", "theme")
        }
        val modernLocalLibraryResolvable = runCatching {
            context.packageManager.resolveActivity(modernLocalLibrary, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
        val publicMtzImport = listOf(
            "application/vnd.miui.mtz",
            "application/x-mtz",
            "application/zip",
            "application/octet-stream",
        ).any { mimeType ->
            runCatching {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse("content://dev.glorioustr.mtzstudio.probe/theme.mtz"), mimeType)
                    `package` = ThemeManagerContract.PACKAGE_NAME
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.resolveActivity(context.packageManager) != null
            }.getOrDefault(false)
        }
        val splits = runCatching {
            context.packageManager.getApplicationInfo(ThemeManagerContract.PACKAGE_NAME, 0).splitSourceDirs?.size ?: 0
        }.getOrDefault(0)
        val candidates = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                ThemeManagerContract.PACKAGE_NAME,
                PackageManager.GET_ACTIVITIES,
            ).activities.orEmpty()
                .asSequence()
                .filter { it.exported }
                .map { it.name }
                .filter { name ->
                    name.contains("theme", ignoreCase = true) ||
                        name.contains("resource", ignoreCase = true) ||
                        name.contains("local", ignoreCase = true)
                }
                .distinct()
                .take(24)
                .toList()
        }.getOrDefault(emptyList())
        return ThemeManagerRuntimeProfile(
            packageInstalled = true,
            versionName = installed.versionName,
            knownBehavior = installed.behavior,
            legacyTesterResolvable = resolvable,
            modernLocalLibraryResolvable = modernLocalLibraryResolvable,
            publicMtzImportResolvable = publicMtzImport,
            splitApkCount = splits,
            exportedThemeActivityCandidates = candidates,
        )
    }

    fun probeCapabilities(
        installed: InstalledThemeManager,
        hasRootGlobalBridge: Boolean = false,
        hasRootRuntime: Boolean = false,
        runtimeHealthy: Boolean = false,
        canUseMiuiBackup: Boolean = false,
    ): ThemeManagerCapabilities {
        val profile = probe(installed)
        return ThemeManagerCapabilities(
            packageInstalled = profile.packageInstalled,
            versionName = profile.versionName,
            hasLegacyApplyThemeForScreenshot = profile.legacyTesterResolvable,
            canResolveLegacyTester = profile.legacyTesterResolvable,
            canLaunchLegacyTester = profile.legacyTesterResolvable,
            canImportModernLocalLibrary = profile.modernLocalLibraryResolvable,
            canApplyModernLocalTheme = profile.modernLocalLibraryResolvable,
            canPersistAppliedTheme = hasRootGlobalBridge || hasRootRuntime || canUseMiuiBackup || profile.legacyTesterResolvable,
            canUseMiuiBackup = canUseMiuiBackup,
            hasRootGlobalBridge = hasRootGlobalBridge,
            hasRootRuntime = hasRootRuntime,
            runtimeHealthy = runtimeHealthy,
        )
    }
}
