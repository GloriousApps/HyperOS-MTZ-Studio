package dev.glorioustr.mtzstudio.tester

/**
 * Domain-level representation of runtime targeting for Xiaomi Theme Manager.
 */
enum class ThemeRuntimeTarget {
    GLOBAL,
    NON_GLOBAL,
}

/**
 * Immutable metadata for verified Theme Manager runtime artifacts.
 *
 * The optional [artifactVersion] is populated by the Root artifact view. Keeping the pinned
 * fields in the primary constructor preserves the original local artifact registry exactly,
 * while the Root-oriented secondary constructor keeps the updater API intact.
 */
data class RuntimeArtifact(
    val id: String,
    val target: ThemeRuntimeTarget,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sha256: String,
    val certificateDigest: String,
    val supportedAndroidMin: Int,
    val supportedAndroidMax: Int,
    val assetFileName: String? = null,
    internal val artifactVersion: String? = null,
    internal val artifactFamily: ThemeManagerFamily? = null,
) {
    val family: ThemeManagerFamily
        get() = artifactFamily ?: when (target) {
            ThemeRuntimeTarget.GLOBAL -> ThemeManagerFamily.GLOBAL
            ThemeRuntimeTarget.NON_GLOBAL -> ThemeManagerFamily.CHINA
        }

    val version: String
        get() = artifactVersion ?: versionName.removeSuffix("-global")

    val apkName: String
        get() = assetFileName.orEmpty()

    val minSdk: Int
        get() = supportedAndroidMin

    val downloadUrl: String
        get() = "https://raw.githubusercontent.com/GloriousApps/HyperOS-MTZ-Studio/main/requirements/$apkName"

    fun asRootTargetApk(): RootTargetApk = RootTargetApk(
        version = version,
        apkName = apkName,
        sha256 = sha256,
        minSdk = minSdk,
    )

    /** Root-oriented constructor retained from the remote runtime artifact API. */
    constructor(
        family: ThemeManagerFamily,
        version: String,
        apkName: String,
        sha256: String,
        minSdk: Int,
    ) : this(
        id = "xiaomi-themes-${family.name.lowercase()}-$version",
        target = family.toRuntimeTarget(),
        packageName = ThemeManagerContract.PACKAGE_NAME,
        versionName = if (family == ThemeManagerFamily.GLOBAL) "$version-global" else version,
        versionCode = version.filter { it.isDigit() }.toLongOrNull() ?: 0L,
        sha256 = sha256,
        certificateDigest = "",
        supportedAndroidMin = minSdk,
        supportedAndroidMax = Int.MAX_VALUE,
        assetFileName = apkName,
        artifactVersion = version,
        artifactFamily = family,
    )
}

private fun ThemeManagerFamily.toRuntimeTarget(): ThemeRuntimeTarget = when (this) {
    ThemeManagerFamily.GLOBAL -> ThemeRuntimeTarget.GLOBAL
    ThemeManagerFamily.CHINA -> ThemeRuntimeTarget.NON_GLOBAL
    // The Root artifact API historically accepted UNKNOWN; retain construction compatibility while
    // exposing the original family through [RuntimeArtifact.family].
    ThemeManagerFamily.UNKNOWN -> ThemeRuntimeTarget.GLOBAL
}

/**
 * Fine-grained operational capabilities for Xiaomi Theme Manager on the current device.
 * Replaces simplistic binary flags with a verified capability matrix.
 */
data class ThemeManagerCapabilities(
    val packageInstalled: Boolean,
    val versionName: String?,
    val hasLegacyApplyThemeForScreenshot: Boolean,
    val canResolveLegacyTester: Boolean,
    val canLaunchLegacyTester: Boolean,
    val canImportModernLocalLibrary: Boolean,
    val canApplyModernLocalTheme: Boolean,
    val canPersistAppliedTheme: Boolean,
    val canUseMiuiBackup: Boolean,
    val hasRootGlobalBridge: Boolean,
    val hasRootRuntime: Boolean,
    val runtimeHealthy: Boolean,
    private val profileSnapshot: ThemeManagerRuntimeProfile? = null,
    private val rootModuleReadySnapshot: Boolean = false,
    private val rootModuleVersionSnapshot: String? = null,
    private val rootModuleTargetFamilySnapshot: ThemeManagerFamily = ThemeManagerFamily.UNKNOWN,
    private val rootModuleTargetVersionSnapshot: String? = null,
    private val shizukuReadySnapshot: Boolean = false,
    private val sheveryReadySnapshot: Boolean = false,
    private val persistenceReadySnapshot: Boolean = false,
) {
    val canApplyAutomatically: Boolean
        get() = (hasRootRuntime && runtimeHealthy) ||
            hasRootGlobalBridge ||
            (canApplyModernLocalTheme && canUseMiuiBackup) ||
            (hasLegacyApplyThemeForScreenshot && canResolveLegacyTester)

    /** Remote runtime-probe view of this same capability snapshot. */
    val profile: ThemeManagerRuntimeProfile
        get() = profileSnapshot ?: ThemeManagerRuntimeProfile(
            packageInstalled = packageInstalled,
            versionName = versionName,
            knownBehavior = ThemeManagerContract.behavior(versionName),
            legacyTesterResolvable = canResolveLegacyTester,
            modernLocalLibraryResolvable = canImportModernLocalLibrary,
            publicMtzImportResolvable = false,
            splitApkCount = 0,
            exportedThemeActivityCandidates = emptyList(),
        )

    val rootModuleReady: Boolean get() = rootModuleReadySnapshot
    val rootModuleVersion: String? get() = rootModuleVersionSnapshot
    val rootModuleTargetFamily: ThemeManagerFamily get() = rootModuleTargetFamilySnapshot
    val rootModuleTargetVersion: String? get() = rootModuleTargetVersionSnapshot
    val shizukuReady: Boolean get() = shizukuReadySnapshot
    val sheveryReady: Boolean get() = sheveryReadySnapshot
    val persistenceReady: Boolean get() = persistenceReadySnapshot

    val behavior: ThemeManagerBehavior
        get() = profile.knownBehavior

    val rootApplyAvailable: Boolean
        get() = rootModuleReady && rootModuleTargetVersion != null

    val stockAutomaticImportAvailable: Boolean
        get() = profile.compatibleLocalMtzPath && (shizukuReady || sheveryReady)

    /** Constructor used by the runtime probe API added by the remote branch. */
    constructor(
        profile: ThemeManagerRuntimeProfile,
        rootModuleReady: Boolean,
        rootModuleVersion: String?,
        rootModuleTargetFamily: ThemeManagerFamily,
        rootModuleTargetVersion: String?,
        shizukuReady: Boolean,
        sheveryReady: Boolean,
        persistenceReady: Boolean,
    ) : this(
        packageInstalled = profile.packageInstalled,
        versionName = profile.versionName,
        hasLegacyApplyThemeForScreenshot = profile.legacyTesterResolvable,
        canResolveLegacyTester = profile.legacyTesterResolvable,
        canLaunchLegacyTester = profile.legacyTesterResolvable,
        canImportModernLocalLibrary = profile.modernLocalLibraryResolvable,
        canApplyModernLocalTheme = profile.modernLocalLibraryResolvable,
        canPersistAppliedTheme = persistenceReady,
        canUseMiuiBackup = persistenceReady,
        hasRootGlobalBridge = rootModuleReady,
        hasRootRuntime = rootModuleReady,
        runtimeHealthy = rootModuleReady,
        profileSnapshot = profile,
        rootModuleReadySnapshot = rootModuleReady,
        rootModuleVersionSnapshot = rootModuleVersion,
        rootModuleTargetFamilySnapshot = rootModuleTargetFamily,
        rootModuleTargetVersionSnapshot = rootModuleTargetVersion,
        shizukuReadySnapshot = shizukuReady,
        sheveryReadySnapshot = sheveryReady,
        persistenceReadySnapshot = persistenceReady,
    )
}
