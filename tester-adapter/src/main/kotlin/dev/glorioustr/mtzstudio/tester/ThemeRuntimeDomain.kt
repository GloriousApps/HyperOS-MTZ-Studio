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
)

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
) {
    val canApplyAutomatically: Boolean
        get() = (hasRootRuntime && runtimeHealthy) ||
            hasRootGlobalBridge ||
            (canApplyModernLocalTheme && canUseMiuiBackup) ||
            (hasLegacyApplyThemeForScreenshot && canResolveLegacyTester)
}

/**
 * Domain-level result for theme application and persistence lifecycle stages.
 */
sealed class ThemeApplyResult {
    data class Success(
        val themeId: String,
        val themeName: String,
        val localId: String? = null,
        val strategyName: String,
        val persistenceArmed: Boolean = false,
    ) : ThemeApplyResult()

    data class CapabilityMissing(
        val missingCapability: String,
        val explanation: String,
    ) : ThemeApplyResult()

    data class ThemeManagerIncompatible(
        val installedVersion: String?,
        val reason: String,
    ) : ThemeApplyResult()

    data class RuntimeNotInstalled(
        val target: ThemeRuntimeTarget,
        val requiredVersion: String,
    ) : ThemeApplyResult()

    data class RuntimeInstallFailed(
        val target: ThemeRuntimeTarget,
        val error: String,
    ) : ThemeApplyResult()

    data class ImportFailed(
        val reason: String,
        val error: Throwable? = null,
    ) : ThemeApplyResult()

    data class PreviewPublishFailed(
        val reason: String,
        val error: Throwable? = null,
    ) : ThemeApplyResult()

    data class ApplyFailed(
        val stage: String,
        val reason: String,
        val error: Throwable? = null,
    ) : ThemeApplyResult()

    data class PersistenceFailed(
        val reason: String,
        val error: Throwable? = null,
    ) : ThemeApplyResult()

    data class VerificationFailed(
        val reason: String,
        val expectedFingerprint: String? = null,
        val actualFingerprint: String? = null,
    ) : ThemeApplyResult()

    data class PermissionDenied(
        val permission: String,
        val message: String,
    ) : ThemeApplyResult()

    data class RootUnavailable(
        val message: String,
    ) : ThemeApplyResult()

    data class ShizukuUnavailable(
        val message: String,
    ) : ThemeApplyResult()

    data class SheveryUnavailable(
        val message: String,
    ) : ThemeApplyResult()

    data class RollbackPerformed(
        val target: ThemeRuntimeTarget,
        val message: String,
    ) : ThemeApplyResult()
}
