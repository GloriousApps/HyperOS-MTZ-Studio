package dev.glorioustr.mtzstudio.tester

/**
 * Abstraction over the Root-side Theme Manager runtime lifecycle.
 *
 * The Root module installs a controlled Theme Manager runtime (Global: 3.4.1.23-global,
 * China: 11.5.3.1). This manager owns the lifecycle of that runtime: detect, install,
 * verify, rollback, remove and health-check. Implementations wrap the actual root command
 * execution (RootThemeManagerUpdater) and the module installer.
 *
 * The state machine is:
 *   STOCK -> INSTALLING -> ACTIVE (verified) | FAILED -> ROLLED_BACK -> STOCK
 *   ACTIVE -> UPDATING -> ACTIVE (re-verified) | FAILED -> ROLLED_BACK
 */
interface ThemeRuntimeManager {

    /** Current lifecycle state of the managed runtime. */
    val state: RuntimeState

    /** The target family resolved for this device (may be UNKNOWN). */
    val target: ThemeRuntimeTargetDetector.Target

    /** Resolves the runtime target from device properties. */
    fun detectTarget(): ThemeRuntimeTargetDetector.Target

    /**
     * Installs the target runtime. Returns the installed version on success, or a
     * [RuntimeInstallFailure] describing why the install failed.
     */
    fun installRuntime(): RuntimeInstallResult

    /**
     * Updates an already-installed runtime to the target version. Returns the updated
     * version on success.
     */
    fun updateRuntime(): RuntimeInstallResult

    /**
     * Verifies the installed runtime: package present, version matches target, and the
     * module bridge is ready. Returns a [RuntimeHealth] report.
     */
    fun verifyRuntime(): RuntimeHealth

    /** Current runtime status (installed version, target, health). */
    fun getRuntimeStatus(): RuntimeStatus

    /**
     * Rolls the runtime back to the previously recorded version. Only meaningful after a
     * failed install/update; otherwise returns a no-op result.
     */
    fun rollbackRuntime(): RollbackResult

    /** Removes the managed runtime, returning the device to its stock Theme Manager. */
    fun removeRuntime(): RemoveResult

    /** Full health check: package, version, module bridge, apply capability. */
    fun healthCheck(): RuntimeHealth
}

/** Lifecycle state of the managed runtime. */
enum class RuntimeState {
    /** No managed runtime installed yet; device runs its stock Theme Manager. */
    STOCK,

    /** A runtime install/update is in progress. */
    INSTALLING,

    /** The managed runtime is installed and verified. */
    ACTIVE,

    /** The last install/update failed. */
    FAILED,

    /** A failed install was rolled back to the previous runtime. */
    ROLLED_BACK,
}

/** Result of an install/update attempt. */
sealed class RuntimeInstallResult {
    data class Installed(val version: String) : RuntimeInstallResult()
    data class Failed(val reason: String) : RuntimeInstallResult()
}

/** Result of a rollback attempt. */
sealed class RollbackResult {
    data class RolledBack(val restoredVersion: String?) : RollbackResult()
    data class NothingToRollBack(val reason: String) : RollbackResult()
    data class Failed(val reason: String) : RollbackResult()
}

/** Result of a remove attempt. */
sealed class RemoveResult {
    data class Removed(val restoredVersion: String?) : RemoveResult()
    data class Failed(val reason: String) : RemoveResult()
}

/** Health report for the managed runtime. */
data class RuntimeHealth(
    val packageInstalled: Boolean,
    val versionName: String?,
    val versionMatchesTarget: Boolean,
    val moduleBridgeReady: Boolean,
    val applyCapability: ThemeManagerBehavior?,
    val healthy: Boolean,
) {
    val summary: String
        get() = buildString {
            append("paket=").append(if (packageInstalled) "var" else "yok")
            append(", sürüm=").append(versionName ?: "-")
            append(", hedef=").append(if (versionMatchesTarget) "uyumlu" else "uyumsuz")
            append(", modül=").append(if (moduleBridgeReady) "hazır" else "hazır değil")
            append(", uygulama=").append(applyCapability?.name ?: "-")
            append(", sağlık=").append(if (healthy) "iyi" else "bozuk")
        }
}

/** Snapshot of the runtime status. */
data class RuntimeStatus(
    val state: RuntimeState,
    val target: ThemeRuntimeTargetDetector.Target,
    val installedVersion: String?,
    val health: RuntimeHealth?,
)
