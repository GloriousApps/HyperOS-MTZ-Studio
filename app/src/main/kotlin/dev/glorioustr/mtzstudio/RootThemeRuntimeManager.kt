package dev.glorioustr.mtzstudio

import android.content.Context
import dev.glorioustr.mtzstudio.tester.InstalledThemeManager
import dev.glorioustr.mtzstudio.tester.PrivilegedCommandRunner
import dev.glorioustr.mtzstudio.tester.RemoveResult
import dev.glorioustr.mtzstudio.tester.RollbackResult
import dev.glorioustr.mtzstudio.tester.RootThemeManagerUpdater
import dev.glorioustr.mtzstudio.tester.RuntimeHealth
import dev.glorioustr.mtzstudio.tester.RuntimeInstallResult
import dev.glorioustr.mtzstudio.tester.RuntimeState
import dev.glorioustr.mtzstudio.tester.RuntimeStatus
import dev.glorioustr.mtzstudio.tester.ThemeManagerBehavior
import dev.glorioustr.mtzstudio.tester.ThemeManagerCapabilityProbe
import dev.glorioustr.mtzstudio.tester.ThemeManagerContract
import dev.glorioustr.mtzstudio.tester.ThemeManagerFamily
import dev.glorioustr.mtzstudio.tester.ThemeManagerInspector
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeManager
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeTargetDetector
import dev.glorioustr.mtzstudio.tester.VerifiedThemeManagerApk
import java.io.InputStream

/**
 * Root-side [ThemeRuntimeManager] implementation. Wraps [RootThemeManagerUpdater] (Theme
 * Manager APK install) and [RootThemeImportModuleInstaller] (Zygisk module) and owns the
 * runtime state machine with rollback support.
 *
 * Diagnostics are forwarded through [onEvent] so the app's LiveDiagnostics recorder can
 * capture install/verify/rollback events without this class depending on the app module.
 */
internal class RootThemeRuntimeManager(
    context: Context,
    private val updater: RootThemeManagerUpdater,
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
        if (resolved == ThemeRuntimeTargetDetector.Target.UNKNOWN) {
            return RuntimeInstallResult.Failed("Cihazın Theme Manager ailesi belirlenemedi (Global/Çin)")
        }
        val targetApk = ThemeManagerContract.rootTargetApk(
            when (resolved) {
                ThemeRuntimeTargetDetector.Target.GLOBAL -> ThemeManagerFamily.GLOBAL
                ThemeRuntimeTargetDetector.Target.CHINA -> ThemeManagerFamily.CHINA
                ThemeRuntimeTargetDetector.Target.UNKNOWN -> ThemeManagerFamily.UNKNOWN
            },
        ) ?: return RuntimeInstallResult.Failed("Hedef Theme Manager APK'sı bulunamadı")
        val installed = runCatching { inspector.inspect() }.getOrNull()
        if (installed == null || !installed.installed) {
            return RuntimeInstallResult.Failed("Xiaomi Temalar paketi kurulu değil")
        }
        if (ThemeManagerContract.canonicalVersion(installed.versionName) == targetApk.version) {
            currentState = RuntimeState.ACTIVE
            return RuntimeInstallResult.Installed(installed.versionName ?: targetApk.version)
        }
        return RuntimeInstallResult.Failed(
            "Hedef sürüm ${targetApk.version} kurulu değil (mevcut: ${installed.versionName}). " +
                "APK indirme + kurulum akışı için ThemeManagerCompatibilityCard kullanılır.",
        )
    }

    override fun updateRuntime(): RuntimeInstallResult = installRuntime()

    override fun verifyRuntime(): RuntimeHealth {
        val installed = runCatching { inspector.inspect() }.getOrNull()
        val moduleState = runCatching { moduleInstaller.inspect() }.getOrNull()
        val targetApk = ThemeManagerContract.rootTargetApk(
            when (detectTarget()) {
                ThemeRuntimeTargetDetector.Target.GLOBAL -> ThemeManagerFamily.GLOBAL
                ThemeRuntimeTargetDetector.Target.CHINA -> ThemeManagerFamily.CHINA
                ThemeRuntimeTargetDetector.Target.UNKNOWN -> ThemeManagerFamily.UNKNOWN
            },
        )
        val versionMatches = installed?.versionName != null &&
            targetApk != null &&
            ThemeManagerContract.canonicalVersion(installed.versionName) == targetApk.version
        val moduleBridgeReady = moduleState?.active == true && moduleInstaller.isBundledVersion(moduleState)
        val behavior = installed?.behavior
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
                "target" to detectTarget().name,
                "installed" to (installed?.versionName ?: "-"),
                "versionMatches" to versionMatches,
                "moduleBridgeReady" to moduleBridgeReady,
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

    /** Records the pre-install version so a failed install can be rolled back. */
    internal fun recordPreviousVersion(version: String?) {
        previousVersion = version
    }

    private fun readSystemProperty(name: String): String? = runCatching {
        val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
