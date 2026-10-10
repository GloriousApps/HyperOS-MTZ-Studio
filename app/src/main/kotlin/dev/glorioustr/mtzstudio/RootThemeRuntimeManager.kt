package dev.glorioustr.mtzstudio

import android.content.Context
import dev.glorioustr.mtzstudio.tester.RemoveResult
import dev.glorioustr.mtzstudio.tester.RollbackResult
import dev.glorioustr.mtzstudio.tester.RootThemeManagerUpdater
import dev.glorioustr.mtzstudio.tester.RuntimeHealth
import dev.glorioustr.mtzstudio.tester.RuntimeInstallResult
import dev.glorioustr.mtzstudio.tester.RuntimeState
import dev.glorioustr.mtzstudio.tester.RuntimeStatus
import dev.glorioustr.mtzstudio.tester.ThemeManagerCapabilityProbe
import dev.glorioustr.mtzstudio.tester.ThemeManagerContract
import dev.glorioustr.mtzstudio.tester.ThemeManagerFamily
import dev.glorioustr.mtzstudio.tester.ThemeManagerInspector
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeManager
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeTarget
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeTargetDetector

/**
 * Root-side [ThemeRuntimeManager] implementation. The legacy updater dependency remains in the
 * constructor for caller compatibility, but the bundled module installer exclusively owns the
 * verified runtime APK and Package Manager mutation. This class owns the runtime state machine
 * and diagnostics.
 *
 * Diagnostics are forwarded through [onEvent] so the app's LiveDiagnostics recorder can
 * capture install/verify/rollback events without this class depending on the app module.
 */
internal class RootThemeRuntimeManager(
    context: Context,
    @Suppress("UNUSED_PARAMETER") updater: RootThemeManagerUpdater,
    private val moduleInstaller: RootThemeImportModuleInstaller,
    private val inspector: ThemeManagerInspector = ThemeManagerInspector(context),
    private val capabilityProbe: ThemeManagerCapabilityProbe = ThemeManagerCapabilityProbe(context),
    private val onEvent: (event: String, message: String, details: Map<String, Any?>) -> Unit = { _, _, _ -> },
) : ThemeRuntimeManager {

    private var currentState: RuntimeState = RuntimeState.STOCK
    private var previousVersion: String? = null

    override val state: RuntimeState get() = currentState
    override val target: ThemeRuntimeTargetDetector.Target get() = detectTarget()

    override fun detectTarget(): ThemeRuntimeTargetDetector.Target {
        val region = readSystemProperty("ro.miui.region")
        val modDevice = readSystemProperty("ro.product.mod_device")
        val installed = runCatching { inspector.inspect() }.getOrNull()
        return ThemeRuntimeTargetDetector.resolve(region, modDevice, installed?.versionName)
    }

    override fun installRuntime(): RuntimeInstallResult {
        val resolved = detectTarget()
        val installed = runCatching { inspector.inspect() }.getOrNull()
        val target = resolved.toRuntimeTarget()
            ?: return RuntimeInstallResult.Failed("Cihazın Theme Manager ailesi belirlenemedi (Global/Çin)")
        if (installed == null || !installed.installed) {
            return RuntimeInstallResult.Failed("Xiaomi Temalar paketi kurulu değil")
        }

        // RootThemeImportModuleInstaller is the single owner of the bundled runtime ZIP,
        // verified payload APK, and Package Manager mutation. Do not use RootThemeManagerUpdater
        // here: a separate APK download/install would perform a second PM mutation before the
        // module swap and could leave the selected target out of sync with the installed module.
        currentState = RuntimeState.INSTALLING
        onEvent("runtime_install_started", "Bundled Theme Manager runtime kuruluyor", mapOf("target" to target.name))
        return runCatching {
            val result = moduleInstaller.installOrUpdate(target)
            currentState = RuntimeState.ACTIVE
            onEvent(
                "runtime_install_completed",
                "Bundled Theme Manager runtime kuruldu",
                mapOf("target" to result.target.name, "version" to result.version),
            )
            RuntimeInstallResult.Installed(result.version)
        }.getOrElse { error ->
            currentState = RuntimeState.FAILED
            onEvent(
                "runtime_install_failed",
                "Bundled Theme Manager runtime kurulamadı",
                mapOf("target" to target.name, "error" to (error.message ?: error::class.simpleName)),
            )
            RuntimeInstallResult.Failed(error.message ?: error::class.simpleName.orEmpty())
        }
    }

    override fun updateRuntime(): RuntimeInstallResult = installRuntime()

    override fun verifyRuntime(): RuntimeHealth {
        val installed = runCatching { inspector.inspect() }.getOrNull()
        val moduleState = runCatching { moduleInstaller.inspect() }.getOrNull()
        val resolved = detectTarget()
        val runtimeTarget = resolved.toRuntimeTarget()
        val targetApk = ThemeManagerContract.rootTargetApk(
            when (resolved) {
                ThemeRuntimeTargetDetector.Target.GLOBAL -> ThemeManagerFamily.GLOBAL
                ThemeRuntimeTargetDetector.Target.CHINA -> ThemeManagerFamily.CHINA
                ThemeRuntimeTargetDetector.Target.UNKNOWN -> ThemeManagerFamily.UNKNOWN
            },
        )
        val versionMatches = installed?.versionName != null &&
            targetApk != null &&
            ThemeManagerContract.canonicalVersion(installed.versionName) == targetApk.version
        val moduleBridgeReady = runtimeTarget != null && moduleState?.activeTarget == runtimeTarget &&
            moduleInstaller.isBundledVersion(moduleState, runtimeTarget)
        val profile = installed?.let { runCatching { capabilityProbe.probe(it) }.getOrNull() }
        val behavior = profile?.knownBehavior ?: installed?.behavior
        val healthy = installed?.installed == true && versionMatches && moduleBridgeReady
        val health = RuntimeHealth(
            packageInstalled = installed?.installed == true,
            versionName = installed?.versionName,
            versionMatchesTarget = versionMatches,
            moduleBridgeReady = moduleBridgeReady,
            applyCapability = behavior,
            healthy = healthy,
        )
        onEvent(
            "runtime_health_check",
            "Root Theme Manager runtime sağlık kontrolü",
            mapOf(
                "target" to resolved.name,
                "installed" to (installed?.versionName ?: "-"),
                "versionMatches" to versionMatches,
                "moduleBridgeReady" to moduleBridgeReady,
                "moduleTarget" to (moduleState?.activeTarget?.name ?: "-"),
                "applyCapability" to (behavior?.name ?: "-"),
                "legacyTesterResolvable" to (profile?.legacyTesterResolvable ?: false),
                "modernLocalLibraryResolvable" to (profile?.modernLocalLibraryResolvable ?: false),
                "healthy" to healthy,
            ),
        )
        return health
    }

    override fun getRuntimeStatus(): RuntimeStatus = RuntimeStatus(
        state = currentState,
        target = detectTarget(),
        installedVersion = runCatching { inspector.inspect() }.getOrNull()?.versionName,
        health = runCatching { verifyRuntime() }.getOrNull(),
    )

    override fun rollbackRuntime(): RollbackResult {
        val previous = previousVersion
        if (previous == null) {
            return RollbackResult.NothingToRollBack("Geri alınacak önceki sürüm kaydı yok")
        }
        onEvent("runtime_rollback", "Theme Manager runtime geri alınıyor", mapOf("previous" to previous))
        currentState = RuntimeState.ROLLED_BACK
        previousVersion = null
        return RollbackResult.RolledBack(previous)
    }

    override fun removeRuntime(): RemoveResult {
        val installed = runCatching { inspector.inspect() }.getOrNull()
        currentState = RuntimeState.STOCK
        return RemoveResult.Removed(installed?.versionName)
    }

    override fun healthCheck(): RuntimeHealth = verifyRuntime()

    private fun ThemeRuntimeTargetDetector.Target.toRuntimeTarget(): ThemeRuntimeTarget? = when (this) {
        ThemeRuntimeTargetDetector.Target.GLOBAL -> ThemeRuntimeTarget.GLOBAL
        ThemeRuntimeTargetDetector.Target.CHINA -> ThemeRuntimeTarget.NON_GLOBAL
        ThemeRuntimeTargetDetector.Target.UNKNOWN -> null
    }

    /** Records the pre-install version so a failed install can be rolled back. */
    internal fun recordPreviousVersion(version: String?) {
        previousVersion = version
    }

    private fun readSystemProperty(name: String): String? = runCatching {
        val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
