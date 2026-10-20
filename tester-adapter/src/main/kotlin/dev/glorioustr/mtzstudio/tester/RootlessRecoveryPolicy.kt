package dev.glorioustr.mtzstudio.tester

/** Deliberately narrow command policy. No root, global uninstall, reset, or downgrade options. */
object RootlessRecoveryCommands {
    const val PACKAGE = "com.android.thememanager"
    const val UNINSTALL = "/system/bin/pm uninstall --user 0 com.android.thememanager"
    const val RECOVER = "/system/bin/pm install-existing --user 0 com.android.thememanager"
    fun install(staged: String): String {
        require(Regex("/data/local/tmp/mtzstudio-rootless-[a-f0-9-]+\\.apk").matches(staged))
        return "/system/bin/pm install --user 0 '$staged'"
    }
}

data class RecoveryIdentity(val version: String, val code: Long, val certificates: Set<String>) {
    init { require(version.isNotBlank() && code >= 0 && certificates.isNotEmpty()) }
}

data class RecoveryFile(val path: String, val sha256: String, val sourcePath: String)

data class RecoveryJournal(
    val phase: String,
    val message: String,
    val backupPath: String?,
    val baseline: RecoveryIdentity?,
    val target: RecoveryIdentity?,
    val files: List<RecoveryFile>,
)

/** Gateway operations either return verified facts or throw; command output is never propagated. */
interface RootlessRecoveryGateway {
    fun requireReady()
    fun installed(): RecoveryIdentity?
    fun backup(baseline: RecoveryIdentity): Pair<String, List<RecoveryFile>>
    fun factoryObservation(): String = "Factory metadata unavailable; install-existing does not guarantee factory version."
    fun verifiedTarget(baseline: RecoveryIdentity): RecoveryIdentity
    fun recheck(journal: RecoveryJournal)
    fun stage(): String
    fun cleanupStaged(staged: String) {}
    fun command(command: String): Boolean
    fun legacyResolvable(): Boolean
}

interface RootlessRecoveryStore {
    fun load(): RecoveryJournal
    /** Must durably commit before returning, or throw. */
    fun save(journal: RecoveryJournal)
}

/** Only controlled failure codes may be surfaced; never raw shell output or device dumps. */
class RootlessRecoveryFailure(val reason: String) : Exception(reason) {
    init { require(Regex("[A-Z][A-Z0-9_]{0,99}").matches(reason)) }
}

/** Persisted state machine; caller serializes access across all instances. */
class RootlessRecoveryPolicy(private val store: RootlessRecoveryStore, private val gateway: RootlessRecoveryGateway) {
    fun snapshot(): RecoveryJournal = store.load()

    fun prepare(): RecoveryJournal {
        val old = store.load()
        if (old.phase !in setOf("IDLE", "FAILED", "RECOVERED", "INSTALLED")) return old
        return try {
            gateway.requireReady()
            val baseline = requireNotNull(gateway.installed())
            val target = gateway.verifiedTarget(baseline)
            val (path, files) = gateway.backup(baseline)
            check(path.isNotBlank() && files.isNotEmpty())
            val prepared = RecoveryJournal(
                "PREPARED",
                "Ready. Studio library and all installed APK splits saved. Themes private data is NOT backed up and uninstall will erase it. ${gateway.factoryObservation()}",
                path, baseline, target, files,
            )
            gateway.recheck(prepared)
            store.save(prepared)
            prepared
        } catch (error: Exception) {
            save(old.copy(phase = "FAILED", message = "Preparation failed; no package mutation was requested.${reason(error)}"))
        }
    }

    fun install(dataLossAccepted: Boolean): RecoveryJournal {
        val old = store.load()
        if (old.phase != "PREPARED") return old
        if (!dataLossAccepted) return old.copy(message = "Confirm loss of Xiaomi Themes private data before installation.")
        var current = old
        return try {
            gateway.requireReady()
            gateway.recheck(old)
            val staged = gateway.stage() // copy and validate BEFORE recording the uninstall intent
            gateway.requireReady()
            gateway.recheck(old)
            current = save(old.copy(phase = "UNINSTALL_PENDING", message = "Uninstall requested; recovery is available."))
            check(gateway.command(RootlessRecoveryCommands.UNINSTALL))
            check(gateway.installed() == null) // do not proceed if removal was uncertain
            current = save(current.copy(phase = "ABSENT", message = "Themes removed for user 0; recovery is available."))
            current = save(current.copy(phase = "INSTALL_PENDING", message = "Target install requested; recovery is available."))
            check(gateway.command(RootlessRecoveryCommands.install(staged)))
            check(gateway.installed() == old.target && gateway.legacyResolvable())
            val done = save(current.copy(phase = "INSTALLED", message = "Verified legacy Themes and tester resolution. Private data was erased; backups retained."))
            runCatching { gateway.cleanupStaged(staged) }
            done
        } catch (error: Exception) {
            val uncertain = current.phase in setOf("UNINSTALL_PENDING", "ABSENT", "INSTALL_PENDING")
            save(current.copy(
                phase = if (uncertain) "RECOVERY_NEEDED" else "FAILED",
                message = (if (uncertain) "Package operation interrupted or failed. Inspect Themes and use explicit recovery; do not retry uninstall. Private data cannot be restored."
                else "Preflight failed; no uninstall was requested. Verify service, files and installed version, then prepare again.") + reason(error),
            ))
        }
    }

    fun recoverExisting(): RecoveryJournal {
        val old = store.load()
        if (old.phase !in setOf("UNINSTALL_PENDING", "ABSENT", "INSTALL_PENDING", "RECOVERY_PENDING", "RECOVERY_NEEDED")) return old
        var current = old
        return try {
            gateway.requireReady() // does not require Themes to be installed
            var installed = gateway.installed()
            if (installed == null) {
                current = save(old.copy(phase = "RECOVERY_PENDING", message = "Install-existing requested; checking package state."))
                check(gateway.command(RootlessRecoveryCommands.RECOVER))
                installed = gateway.installed()
            }
            check(installed != null && installed.certificates == old.baseline?.certificates)
            save(current.copy(phase = "RECOVERED", message = "Themes package visible again (${installed.version}). No private data restoration or factory version guarantee; backups retained."))
        } catch (error: Exception) {
            save(current.copy(phase = "RECOVERY_NEEDED", message = "Recovery not verified. Reconnect ADB and inspect Themes before requesting recovery again; private data cannot be restored.${reason(error)}"))
        }
    }

    private fun save(journal: RecoveryJournal): RecoveryJournal { store.save(journal); return journal }
    private fun reason(error: Exception): String = " Reason: " +
        ((error as? RootlessRecoveryFailure)?.reason ?: "CHECK_FAILED")
}
