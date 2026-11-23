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
    /** True when the verified 3.4.x Themes account/local-resource shell is exported. */
    val themeResourceTabResolvable: Boolean = false,
    /**
     * The component that actually carries the tester contract on this build. It is the historical
     * class name on most Global builds, but an `<activity-alias>` when Themes republishes the
     * contract under a different name. Null when no tester entry point exists.
     */
    val legacyTesterComponent: String? = null,
    /**
     * True when the exported, permission-free `ThemeService` can be bound. It applies wallpapers
     * and icons only — never a whole MTZ — so it must not feed [compatibleLocalMtzPath].
     */
    val unprivilegedWallpaperService: Boolean = false,
) {
    /**
     * A manual handoff is not an importer and must never be reported as automatic apply support.
     * It only gives the user a known Themes surface from which Xiaomi's own local-resource flow
     * can be continued.
     */
    val manualLocalLibraryHandoffAvailable: Boolean
        get() = ThemeManagerContract.canonicalVersion(versionName) ==
            ThemeManagerContract.ROOT_GLOBAL_RECOMMENDED_VERSION && themeResourceTabResolvable

    /** A verified legacy apply activity or a verified modern native import library is sufficient. */
    val compatibleLocalMtzPath: Boolean
        get() = legacyTesterResolvable ||
            (knownBehavior == ThemeManagerBehavior.MODERN_NATIVE_LIBRARY && modernLocalLibraryResolvable)
}

/**
 * How the legacy tester contract was found on the installed Theme Manager, and which component
 * carries it. [available] stays false only when no entry point exists at all.
 */
data class LegacyTesterResolution(
    val componentClassName: String?,
    val declaredInManifest: Boolean,
    val declaredAliasComponent: String?,
    val resolvedByIntent: Boolean,
) {
    val available: Boolean get() = componentClassName != null
}

class ThemeManagerCapabilityProbe(private val context: Context) {
    fun probe(installed: InstalledThemeManager): ThemeManagerRuntimeProfile {
        if (!installed.installed) {
            return ThemeManagerRuntimeProfile(false, null, ThemeManagerBehavior.UNKNOWN, false, false, false, 0, emptyList())
        }
        val legacyTester = resolveLegacyTester(context)
        val resolvable = legacyTester.available
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
        val themeResourceTabResolvable = runCatching {
            Intent().apply {
                component = ComponentName(
                    ThemeManagerContract.PACKAGE_NAME,
                    ThemeManagerContract.THEME_RESOURCE_TAB_COMPONENT,
                )
                putExtra("extra_outer_tab_id", "mine")
                putExtra("EXTRA_TAB_ID", "theme")
            }.resolveActivity(context.packageManager) != null
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
            themeResourceTabResolvable = themeResourceTabResolvable,
            legacyTesterComponent = legacyTester.componentClassName,
            unprivilegedWallpaperService = ThemeServiceAvailability.resolve(context).usable,
        )
    }

    companion object {
        /**
         * Single source of truth for the legacy tester contract.
         *
         * Neither signal is reliable on its own:
         *  * Some HyperOS PackageManager builds return a non-null result for an explicit
         *    component even when the activity is not declared by the active APK, so intent
         *    resolution alone can report a tester that cannot be started.
         *  * Several Global builds publish the tester as an `<activity-alias>` instead of the
         *    historical class, so a strict name/enabled/exported match against
         *    `PackageInfo.activities` misses a contract that is in fact present. That strict
         *    match is what demoted builds such as 3.0.5.14 to "root required" even though their
         *    tester activity works.
         *
         * The manifest scan therefore also accepts an alias that targets the tester, and the
         * `support3.0` action resolution is kept as an independent confirmation. The returned
         * component is the entry point callers must dispatch to, which is the alias name when
         * the build renamed the published contract.
         */
        fun resolveLegacyTester(context: Context): LegacyTesterResolution {
            val activities = runCatching {
                val flags = PackageManager.GET_ACTIVITIES.toLong()
                val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        ThemeManagerContract.PACKAGE_NAME,
                        PackageManager.PackageInfoFlags.of(flags),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(
                        ThemeManagerContract.PACKAGE_NAME,
                        PackageManager.GET_ACTIVITIES,
                    )
                }
                packageInfo.activities.orEmpty().toList()
            }.getOrDefault(emptyList())
            val usable = activities.filter { it.enabled && it.exported }
            val declaredDirectly = usable.any { it.name == ThemeManagerContract.LEGACY_TESTER_COMPONENT }
            val declaredAlias = usable.firstOrNull { activity ->
                activity.targetActivity == ThemeManagerContract.LEGACY_TESTER_COMPONENT &&
                    activity.name != ThemeManagerContract.LEGACY_TESTER_COMPONENT
            }?.name
            // When several activities inside Themes match the action the platform answers with
            // its own resolver component. That is not a tester entry point, so only a component
            // that really belongs to Themes is accepted here.
            val resolvedByAction = runCatching {
                Intent(ThemeManagerContract.LEGACY_TESTER_ACTION)
                    .setPackage(ThemeManagerContract.PACKAGE_NAME)
                    .resolveActivity(context.packageManager)
                    ?.takeIf { it.packageName == ThemeManagerContract.PACKAGE_NAME }
                    ?.className
            }.getOrNull()
            val resolvedExplicitly = runCatching {
                Intent(ThemeManagerContract.LEGACY_TESTER_ACTION).apply {
                    component = ComponentName(
                        ThemeManagerContract.PACKAGE_NAME,
                        ThemeManagerContract.LEGACY_TESTER_COMPONENT,
                    )
                }.resolveActivity(context.packageManager) != null
            }.getOrDefault(false)
            val component = when {
                declaredDirectly -> ThemeManagerContract.LEGACY_TESTER_COMPONENT
                // The action-resolved component is what the platform would actually dispatch.
                resolvedByAction != null -> resolvedByAction
                declaredAlias != null -> declaredAlias
                resolvedExplicitly -> ThemeManagerContract.LEGACY_TESTER_COMPONENT
                else -> null
            }
            return LegacyTesterResolution(
                componentClassName = component,
                declaredInManifest = declaredDirectly,
                declaredAliasComponent = declaredAlias,
                resolvedByIntent = resolvedByAction != null || resolvedExplicitly,
            )
        }

        /**
         * Kept for callers that only need the yes/no answer. It is the full resolution, not the
         * historical manifest-only match, so an aliased tester is no longer reported as absent.
         */
        fun isLegacyTesterDeclared(context: Context): Boolean = resolveLegacyTester(context).available
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
