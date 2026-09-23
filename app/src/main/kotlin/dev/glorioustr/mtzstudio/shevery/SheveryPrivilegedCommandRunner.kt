package dev.glorioustr.mtzstudio.shevery

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.glorioustr.mtzstudio.BuildConfig
import dev.glorioustr.mtzstudio.LiveDiagnosticsRecorder
import dev.glorioustr.mtzstudio.StudioAccessMode
import dev.glorioustr.mtzstudio.tester.VerifiedRootCommandRunner
import dev.glorioustr.mtzstudio.tester.CommandQueueGate
import dev.glorioustr.mtzstudio.tester.BoundedRemoteCall
import dev.glorioustr.mtzstudio.tester.RootAccessUnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.glorioustr.mtzstudio.tester.PrivilegedCommandResult
import dev.glorioustr.mtzstudio.tester.PrivilegedCommandRunner
import dev.glorioustr.mtzstudio.tester.SuPrivilegedCommandRunner
import dev.glorioustr.mtzstudio.tester.ThemeManagerUpdateException
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class SheveryAuthorizationStatus {
    ROOT_READY,
    ADB_READY,
    PERMISSION_REQUIRED,
    SERVICE_NOT_RUNNING,
}

class SheveryAccess(private val context: Context) {
    fun status(): SheveryAuthorizationStatus {
        return try {
            if (!Shizuku.pingBinder()) return SheveryAuthorizationStatus.SERVICE_NOT_RUNNING
            val serviceUid = runCatching { Shizuku.getUid() }.getOrNull()
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return SheveryAuthorizationStatus.PERMISSION_REQUIRED
            }
            if (serviceUid == 0 || Shizuku.getUid() == 0) {
                SheveryAuthorizationStatus.ROOT_READY
            } else {
                SheveryAuthorizationStatus.ADB_READY
            }
        } catch (_: Exception) {
            SheveryAuthorizationStatus.SERVICE_NOT_RUNNING
        }
    }

    fun requestPermission(requestCode: Int) {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(requestCode)
    }

    fun verifyRootService(): Boolean = runCatching {
        val result = executeRoot("id -u", 10)
        result.exitCode == 0 && result.output.lineSequence().firstOrNull()?.trim() == "0"
    }.getOrDefault(false)

    fun executeRoot(command: String, timeoutSeconds: Long): PrivilegedCommandResult = serviceGate.run {
        if (status() != SheveryAuthorizationStatus.ROOT_READY) {
            throw ThemeManagerUpdateException("Shizuku-compatible root authorization is not ready")
        }
        val args = Shizuku.UserServiceArgs(ComponentName(context, SheveryRootCommandService::class.java))
            .processNameSuffix("shevery_root")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .daemon(false)
        val latch = CountDownLatch(1)
        val service = AtomicReference<IRootCommandService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service.set(IRootCommandService.Stub.asInterface(binder))
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service.set(null)
            }
        }
        Shizuku.bindUserService(args, connection)
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw ThemeManagerUpdateException("Shizuku-compatible root service connection timed out")
            }
            val remote = service.get() ?: throw ThemeManagerUpdateException("Shizuku-compatible root service is unavailable")
            val encoded = BoundedRemoteCall.await((timeoutSeconds.coerceIn(1, 300) + 2) * 1_000) {
                if (remote.serviceUid() != 0) throw ThemeManagerUpdateException("Shizuku-compatible service is not running as root")
                remote.execute(command, timeoutSeconds.coerceIn(1, 300).toInt())
            }
            val separator = encoded.indexOf('\u0000')
            val exitCode = encoded.substring(0, separator.coerceAtLeast(0)).toIntOrNull() ?: -1
            val output = if (separator >= 0) encoded.substring(separator + 1) else encoded
            PrivilegedCommandResult(exitCode, output, "Shizuku-compatible root service")
        } finally {
            // Some Vector/Shevery builds keep a non-daemon user service alive after unbind.
            // Explicitly ask the remote process to exit before removing its binding; otherwise
            // every short root probe leaves another :shevery_root process behind.
            service.get()?.let { remote ->
                runCatching { BoundedRemoteCall.await(2_000) { remote.destroy(); Unit } }
            }
            runCatching { BoundedRemoteCall.await(2_000) { Shizuku.unbindUserService(args, connection, true) } }
        }
    }

    /** Executes an allow-listed diagnostic/bridge command with the current Shizuku identity. */
    fun executeShell(command: String, timeoutSeconds: Long): PrivilegedCommandResult = serviceGate.run {
        val current = status()
        if (current != SheveryAuthorizationStatus.ROOT_READY && current != SheveryAuthorizationStatus.ADB_READY) {
            throw ThemeManagerUpdateException("Shizuku authorization is not ready")
        }
        val args = Shizuku.UserServiceArgs(ComponentName(context, SheveryRootCommandService::class.java))
            .processNameSuffix("shevery_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .daemon(false)
        val latch = CountDownLatch(1)
        val service = AtomicReference<IRootCommandService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service.set(IRootCommandService.Stub.asInterface(binder))
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service.set(null) }
        }
        Shizuku.bindUserService(args, connection)
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw ThemeManagerUpdateException("Shizuku service connection timed out")
            val remote = service.get() ?: throw ThemeManagerUpdateException("Shizuku service is unavailable")
            val encoded = BoundedRemoteCall.await((timeoutSeconds.coerceIn(1, 60) + 2) * 1_000) {
                remote.execute(command, timeoutSeconds.coerceIn(1, 60).toInt())
            }
            val separator = encoded.indexOf('\u0000')
            val exitCode = encoded.substring(0, separator.coerceAtLeast(0)).toIntOrNull() ?: -1
            val output = if (separator >= 0) encoded.substring(separator + 1) else encoded
            PrivilegedCommandResult(exitCode, output, "Shizuku shell service")
        } finally {
            service.get()?.let { remote ->
                runCatching { BoundedRemoteCall.await(2_000) { remote.destroy(); Unit } }
            }
            runCatching { BoundedRemoteCall.await(2_000) { Shizuku.unbindUserService(args, connection, true) } }
        }
    }

    private companion object {
        // All instances use the same Shizuku user service. One request must not destroy another's binder.
        val serviceGate = CommandQueueGate()
    }
}

class PreferredPrivilegedCommandRunner(context: Context) : PrivilegedCommandRunner {
    private val commandGate = CommandQueueGate()
    private val appContext = context.applicationContext
    private val shevery = SheveryAccess(appContext)
    private val su = SuPrivilegedCommandRunner()
    private val accessFailure = MutableStateFlow<String?>(null)
    val authorizationFailure = accessFailure.asStateFlow()
    private val verifiedRunner = VerifiedRootCommandRunner(
        service = {
            if (shevery.status() == SheveryAuthorizationStatus.ROOT_READY) {
                PrivilegedCommandRunner { command, timeout -> shevery.executeRoot(command, timeout) }
            } else null
        },
        direct = su,
    )

    override fun run(command: String, timeoutSeconds: Long): PrivilegedCommandResult = commandGate.run {
        val serviceStatus = shevery.status()
        try {
            verifiedRunner.run(command, timeoutSeconds)
        } catch (error: RootAccessUnavailableException) {
            val message = when (serviceStatus) {
                SheveryAuthorizationStatus.PERMISSION_REQUIRED ->
                    appContext.getString(dev.glorioustr.mtzstudio.R.string.privileged_access_permission_required)
                SheveryAuthorizationStatus.ADB_READY ->
                    appContext.getString(dev.glorioustr.mtzstudio.R.string.privileged_access_adb_only)
                else -> appContext.getString(dev.glorioustr.mtzstudio.R.string.privileged_access_unavailable)
            }
            LiveDiagnosticsRecorder.get(appContext).record(
                "root_access_unavailable", "Yetkili işlem kanalı kullanılamıyor",
                mapOf("compatibleService" to serviceStatus), error,
            )
            accessFailure.value = message
            throw ThemeManagerUpdateException(message, error)
        }
    }

    /**
     * Runs a command with root when available, otherwise with the authorized Shizuku ADB shell.
     * This is only for operations whose target is accessible to Android's shell user; callers
     * that need private app data or package installation privileges must continue to use [run].
     */
    fun runRootOrAdbShell(command: String, timeoutSeconds: Long): PrivilegedCommandResult {
        return if (shevery.status() == SheveryAuthorizationStatus.ADB_READY) {
            commandGate.run {
                shevery.executeShell(command, timeoutSeconds).also { accessFailure.value = null }
            }
        } else {
            run(command, timeoutSeconds)
        }
    }

    fun dismissAuthorizationFailure() { accessFailure.value = null }

    /** Capability probe that never opens the authorization-error dialog on a rootless device. */
    fun isRootReadySilently(): Boolean = runCatching { commandGate.run {
        val result = verifiedRunner.run("id -u", ROOT_PROBE_TIMEOUT_SECONDS)
        result.exitCode == 0 && result.output.lineSequence().firstOrNull()?.trim() == "0"
    } }.getOrDefault(false)

    fun accessModeSilently(): StudioAccessMode {
        // Do not start a Shizuku user service just to classify a normally rooted device.
        // This check is short and read-only; all actual work is still gated by VerifiedRootCommandRunner.
        val directRoot = runCatching {
            val result = su.run("id -u", ROOT_PROBE_TIMEOUT_SECONDS)
            result.exitCode == 0 && result.output.lineSequence().firstOrNull()?.trim() == "0"
        }.getOrDefault(false)
        if (directRoot) return StudioAccessMode.ROOT
        val serviceStatus = shevery.status()
        if (serviceStatus == SheveryAuthorizationStatus.ROOT_READY && shevery.verifyRootService()) {
            return StudioAccessMode.ROOT
        }
        if (serviceStatus == SheveryAuthorizationStatus.ADB_READY) return StudioAccessMode.SHIZUKU
        return if (isRootReadySilently()) StudioAccessMode.ROOT else StudioAccessMode.STANDARD
    }

    fun shizukuThemeManagerProbe(): PrivilegedCommandResult {
        check(shevery.status() == SheveryAuthorizationStatus.ADB_READY) { "Shizuku ADB mode is not ready" }
        return shevery.executeShell(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.android.thememanager",
            15,
        )
    }

    fun requireRootReady() {
        run("id -u", ROOT_PROBE_TIMEOUT_SECONDS).also { result ->
            if (result.exitCode != 0 || result.output.lineSequence().firstOrNull()?.trim() != "0") {
                throw ThemeManagerUpdateException(
                    appContext.getString(dev.glorioustr.mtzstudio.R.string.privileged_access_unavailable),
                )
            }
        }
    }

    private companion object {
        const val ROOT_PROBE_TIMEOUT_SECONDS = 10L
    }
}
