package dev.glorioustr.mtzstudio.tester

/**
 * Domain-level result of a theme apply attempt. Every failure carries the exact stage that
 * failed so the UI and diagnostics can reflect it instead of a generic "Apply failed".
 *
 * This is the single result vocabulary for the apply engine. Strategies map their internal
 * exceptions onto these outcomes; the UI renders a human-readable message per outcome.
 */
sealed class ThemeApplyResult {
    /** The theme was applied and, when applicable, persistence was armed. */
    data class Success(
        val themeId: String,
        val protocol: String,
        val themeManagerLocalId: String? = null,
        val persistenceArmed: Boolean = false,
    ) : ThemeApplyResult()

    /** The Theme Manager does not expose any capability needed for this apply path. */
    data class CapabilityMissing(
        val missing: String,
        val detail: String? = null,
    ) : ThemeApplyResult()

    /** The installed Theme Manager version is not compatible with the selected strategy. */
    data class ThemeManagerIncompatible(
        val versionName: String?,
        val reason: String,
    ) : ThemeApplyResult()

    /** The Root runtime is not installed (module missing or inactive). */
    data class RuntimeNotInstalled(
        val target: ThemeRuntimeTargetDetector.Target?,
        val detail: String? = null,
    ) : ThemeApplyResult()

    /** The Root runtime could not be installed or updated. */
    data class RuntimeInstallFailed(
        val target: ThemeRuntimeTargetDetector.Target?,
        val reason: String,
    ) : ThemeApplyResult()

    /** The MTZ could not be imported into Xiaomi Themes. */
    data class ImportFailed(
        val reason: String,
    ) : ThemeApplyResult()

    /** The legacy preview could not be published (staging succeeded, publish failed). */
    data class PreviewPublishFailed(
        val reason: String,
    ) : ThemeApplyResult()

    /** The apply intent was dispatched but the theme did not apply. */
    data class ApplyFailed(
        val reason: String,
    ) : ThemeApplyResult()

    /** The applied theme could not be persisted. */
    data class PersistenceFailed(
        val reason: String,
    ) : ThemeApplyResult()

    /** Post-apply verification did not confirm the theme. */
    data class VerificationFailed(
        val reason: String,
    ) : ThemeApplyResult()

    /** A required permission was denied. */
    data class PermissionDenied(
        val detail: String,
    ) : ThemeApplyResult()

    /** Root access was required but unavailable. */
    data class RootUnavailable(
        val detail: String? = null,
    ) : ThemeApplyResult()

    /** Shizuku access was required but unavailable. */
    data class ShizukuUnavailable(
        val detail: String? = null,
    ) : ThemeApplyResult()

    /** Shevery access was required but unavailable. */
    data class SheveryUnavailable(
        val detail: String? = null,
    ) : ThemeApplyResult()

    /** A failed runtime install was rolled back to the previous Theme Manager. */
    data class RollbackPerformed(
        val restoredVersion: String?,
        val reason: String,
    ) : ThemeApplyResult()
}
