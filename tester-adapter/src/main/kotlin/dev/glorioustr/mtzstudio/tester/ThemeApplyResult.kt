package dev.glorioustr.mtzstudio.tester

/**
 * Single result vocabulary shared by the legacy/modern strategies and the Root runtime manager.
 * The primary fields retain the remote strategy API; compatibility aliases and constructors keep
 * the earlier local domain API source-compatible as well.
 */
sealed class ThemeApplyResult {
    data class Success(
        val themeId: String,
        val protocol: String,
        val themeManagerLocalId: String? = null,
        val persistenceArmed: Boolean = false,
    ) : ThemeApplyResult() {
        val themeName: String get() = themeId
        val localId: String? get() = themeManagerLocalId
        val strategyName: String get() = protocol

        /** Earlier local-domain constructor. */
        constructor(
            themeId: String,
            themeName: String,
            localId: String?,
            strategyName: String,
            persistenceArmed: Boolean = false,
        ) : this(
            themeId = themeId,
            protocol = strategyName,
            themeManagerLocalId = localId,
            persistenceArmed = persistenceArmed,
        )
    }

    data class CapabilityMissing(
        val missing: String,
        val detail: String? = null,
    ) : ThemeApplyResult() {
        val missingCapability: String get() = missing
        val explanation: String get() = detail.orEmpty()
    }

    data class ThemeManagerIncompatible(
        val versionName: String?,
        val reason: String,
    ) : ThemeApplyResult() {
        val installedVersion: String? get() = versionName
    }

    data class RuntimeNotInstalled(
        val target: ThemeRuntimeTargetDetector.Target?,
        val detail: String? = null,
    ) : ThemeApplyResult() {
        val requiredVersion: String get() = detail.orEmpty()

        constructor(target: ThemeRuntimeTarget, requiredVersion: String) : this(
            target = target.toDetectorTarget(),
            detail = requiredVersion,
        )
    }

    data class RuntimeInstallFailed(
        val target: ThemeRuntimeTargetDetector.Target?,
        val reason: String,
    ) : ThemeApplyResult() {
        val error: String get() = reason

        constructor(target: ThemeRuntimeTarget, error: String) : this(
            target = target.toDetectorTarget(),
            reason = error,
        )
    }

    data class ImportFailed(
        val reason: String,
        val error: Throwable? = null,
    ) : ThemeApplyResult()

    data class PreviewPublishFailed(
        val reason: String,
        val error: Throwable? = null,
    ) : ThemeApplyResult()

    data class ApplyFailed(
        val reason: String,
        val stage: String? = null,
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
        val message: String? = null,
    ) : ThemeApplyResult() {
        val detail: String get() = message ?: permission
    }

    data class RootUnavailable(
        val detail: String? = null,
    ) : ThemeApplyResult() {
        val message: String get() = detail.orEmpty()
    }

    data class ShizukuUnavailable(
        val detail: String? = null,
    ) : ThemeApplyResult() {
        val message: String get() = detail.orEmpty()
    }

    data class SheveryUnavailable(
        val detail: String? = null,
    ) : ThemeApplyResult() {
        val message: String get() = detail.orEmpty()
    }

    data class RollbackPerformed(
        val restoredVersion: String?,
        val reason: String,
        internal val legacyTarget: ThemeRuntimeTarget? = null,
    ) : ThemeApplyResult() {
        val message: String get() = reason
        val target: ThemeRuntimeTarget? get() = legacyTarget

        constructor(target: ThemeRuntimeTarget, message: String) : this(
            restoredVersion = null,
            reason = message,
            legacyTarget = target,
        )
    }
}

private fun ThemeRuntimeTarget.toDetectorTarget(): ThemeRuntimeTargetDetector.Target = when (this) {
    ThemeRuntimeTarget.GLOBAL -> ThemeRuntimeTargetDetector.Target.GLOBAL
    ThemeRuntimeTarget.NON_GLOBAL -> ThemeRuntimeTargetDetector.Target.CHINA
}
