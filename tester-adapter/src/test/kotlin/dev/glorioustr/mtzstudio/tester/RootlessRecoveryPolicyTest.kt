package dev.glorioustr.mtzstudio.tester

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RootlessRecoveryPolicyTest {
    private val original = RecoveryIdentity("3.4.1.23", 34123, setOf("cert"))
    private val target = RecoveryIdentity("3.0.5.6", 30506, setOf("cert"))

    private class MemoryStore : RootlessRecoveryStore {
        var state = RecoveryJournal("IDLE", "", null, null, null, emptyList())
        var rejectPhase: String? = null
        override fun load() = state
        override fun save(journal: RecoveryJournal) { check(journal.phase != rejectPhase); state = journal }
    }

    private inner class FakeGateway : RootlessRecoveryGateway {
        var installed: RecoveryIdentity? = original
        var service = true
        var backupsValid = true
        var targetValid = true
        var legacy = true
        var installSucceeds = true
        var stageSucceeds = true
        var uninstallThrows = false
        var stillInstalledAfterUninstall = false
        var exportFails = false
        var publicReadable = true
        var published = 0
        var publicChecks = 0
        var cleanups = 0
        var backupStatus = "UNAVAILABLE"
        var themeCount = 0
        var schemaVersion = 2
        var includeThemesBak = false
        var stages = 0
        var backupFails = false
        var restoredThemes = 0
        var restoreFails = false
        var changeDuringStage = false
        val commands = mutableListOf<String>()
        override fun requireReady() { check(service) }
        override fun installed() = installed
        override fun backup(baseline: RecoveryIdentity): Pair<String, List<RecoveryFile>> {
            if (backupFails) error("Local backup failed")
            return "/private/rollback" to listOf(RecoveryFile("/private/rollback/base.apk", "sha", "/data/app/base.apk"))
        }
        override fun publishPublic(backupPath: String, files: List<RecoveryFile>): PublicBackupReceipt {
            if (exportFails) error("MediaStore export failed")
            published++
            val names = listOf("studio-library.zip", "themes-apks.zip", "backup.properties", "README.txt") +
                (if (includeThemesBak) listOf("themes.bak") else emptyList())
            return PublicBackupReceipt("Download/MTZ Studio/Backups/unique/",
                names.mapIndexed { index, name ->
                    PublicRecoveryFile(name, "content://media/external/downloads/$index", "a".repeat(64), 123)
                }, backupStatus, themeCount, schemaVersion)
        }
        override fun recheckPublic(receipt: PublicBackupReceipt) {
            publicChecks++
            check(publicReadable && receipt.isComplete())
        }
        override fun restoreThemesBackup(receipt: PublicBackupReceipt) {
            check(receipt.themesBackupStatus == "PARTIAL")
            restoredThemes++
            check(!restoreFails)
        }
        override fun cleanupPublic(receipt: PublicBackupReceipt) { cleanups++ }
        override fun verifiedTarget(baseline: RecoveryIdentity): RecoveryIdentity { check(targetValid); return target }
        override fun recheck(journal: RecoveryJournal) { check(backupsValid && installed == journal.baseline && journal.target == target) }
        override fun stage(): String {
            stages++; check(stageSucceeds)
            if (changeDuringStage) installed = target
            return "/data/local/tmp/mtzstudio-rootless-aabb.apk"
        }
        override fun legacyResolvable() = legacy
        override fun command(command: String): Boolean {
            commands += command
            if (command == RootlessRecoveryCommands.UNINSTALL) {
                if (uninstallThrows) throw RootlessRecoveryFailure("DELETE_FAILED_INTERNAL_ERROR")
                if (!stillInstalledAfterUninstall) installed = null
            }
            if (command.startsWith("/system/bin/pm install --user 0")) {
                if (!installSucceeds) return false
                installed = target
            }
            if (command == RootlessRecoveryCommands.RECOVER) installed = original
            return true
        }
    }

    @Test fun `preparation and acknowledgement cannot uninstall`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        assertEquals("PREPARED", policy.prepare().phase)
        assertEquals("/private/rollback", policy.snapshot().backupPath)
        assertEquals("UNAVAILABLE", policy.snapshot().publicBackup?.themesBackupStatus)
        policy.install(false)
        assertTrue(gateway.commands.isEmpty())
        assertEquals("PREPARED", policy.snapshot().phase)
    }

    @Test fun `mandatory public export failure blocks install without uninstall`() {
        val store = MemoryStore(); val gateway = FakeGateway(); gateway.exportFails = true
        val policy = RootlessRecoveryPolicy(store, gateway)
        assertEquals("FAILED", policy.prepare().phase)
        assertEquals(null, policy.snapshot().publicBackup)
        assertEquals("FAILED", policy.install(true).phase)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test fun `ordinary preparation failures expose controlled stage reasons`() {
        val cases = listOf<Pair<String, (FakeGateway) -> Unit>>(
            "READY_CHECK" to { it.service = false },
            "TARGET_CHECK" to { it.targetValid = false },
            "BACKUP_LOCAL" to { it.backupFails = true },
            "BACKUP_PUBLIC" to { it.exportFails = true },
            "BACKUP_PUBLIC_VERIFY" to { it.publicReadable = false },
            "BACKUP_RECHECK" to { it.backupsValid = false },
        )
        cases.forEach { (reason, fail) ->
            val store = MemoryStore(); val gateway = FakeGateway().apply { fail(this) }
            val prepared = RootlessRecoveryPolicy(store, gateway).prepare()
            assertEquals("FAILED", prepared.phase, reason)
            assertTrue(prepared.message.contains("Reason: $reason"), prepared.message)
        }
    }

    @Test fun `public URI revalidation failure before install prevents uninstall`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        assertEquals("PREPARED", policy.prepare().phase)
        gateway.publicReadable = false
        assertEquals("FAILED", policy.install(true).phase)
        assertTrue(gateway.publicChecks >= 2)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test fun `publication verification failure cleans exported rows and fails preflight`() {
        val store = MemoryStore(); val gateway = FakeGateway(); gateway.publicReadable = false
        val policy = RootlessRecoveryPolicy(store, gateway)
        assertEquals("FAILED", policy.prepare().phase)
        assertEquals(1, gateway.cleanups)
        assertEquals(null, policy.snapshot().publicBackup)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test fun `old prepared journal without public receipt cannot install and prepare rebuilds`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        store.state = RecoveryJournal("PREPARED", "old journal", "/private/rollback", original, target,
            listOf(RecoveryFile("/private/rollback/base.apk", "sha", "/data/app/base.apk")))
        assertEquals(null, policy.snapshot().publicBackup)
        assertEquals("PREPARED", policy.install(true).phase)
        assertEquals(null, store.state.publicBackup)
        assertEquals(0, gateway.stages)
        assertTrue(gateway.commands.isEmpty())
        assertEquals("PREPARED", policy.prepare().phase)
        assertEquals(1, gateway.published)
        assertTrue(policy.snapshot().publicBackup!!.isComplete())
    }

    @Test fun `complete aggregate partial receipt permits preparation and installation`() {
        val store = MemoryStore(); val gateway = FakeGateway().apply {
            backupStatus = "PARTIAL"; themeCount = 2; includeThemesBak = true
        }
        val policy = RootlessRecoveryPolicy(store, gateway)
        val prepared = policy.prepare()
        assertEquals("PREPARED", prepared.phase)
        assertEquals(2, prepared.publicBackup?.themeCount)
        assertEquals(2, prepared.publicBackup?.schemaVersion)
        assertTrue(prepared.publicBackup!!.isComplete())
        assertEquals(1, prepared.publicBackup!!.files.count { it.name == "themes.bak" })
        assertEquals("INSTALLED", policy.install(true).phase)
        assertEquals(1, gateway.stages)
        assertEquals(listOf(RootlessRecoveryCommands.UNINSTALL,
            RootlessRecoveryCommands.install("/data/local/tmp/mtzstudio-rootless-aabb.apk")), gateway.commands)
    }

    @Test fun `failed Themes capture keeps prepared backup retryable`() {
        val store = MemoryStore(); val gateway = FakeGateway().apply {
            backupStatus = "FAILED"
        }
        val policy = RootlessRecoveryPolicy(store, gateway)
        val first = policy.prepare()
        assertEquals("PREPARED", first.phase)
        assertEquals("FAILED", first.publicBackup?.themesBackupStatus)
        assertTrue(first.publicBackup!!.isComplete())
        assertEquals(1, gateway.published)

        val second = policy.prepare()
        assertEquals("PREPARED", second.phase)
        assertEquals(2, gateway.published)
        assertEquals("FAILED", second.publicBackup?.themesBackupStatus)
        assertEquals(0, gateway.stages)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test fun `no-backup downgrade does not create a prepared receipt`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        assertEquals("INSTALLED", policy.installWithoutBackup(true).phase)
        assertEquals(null, policy.snapshot().publicBackup)
        assertEquals(1, gateway.stages)
        assertTrue(gateway.commands.isNotEmpty())
    }

    @Test fun `no-backup downgrade preserves an earlier verified public backup`() {
        val store = MemoryStore(); val gateway = FakeGateway().apply {
            backupStatus = "PARTIAL"; themeCount = 2; includeThemesBak = true
        }
        val policy = RootlessRecoveryPolicy(store, gateway)
        policy.prepare()
        val previous = policy.snapshot().publicBackup
        assertEquals("INSTALLED", policy.installWithoutBackup(true).phase)
        assertEquals(previous, policy.snapshot().publicBackup)
    }

    @Test fun `partial themes backup can be restored after verified target install`() {
        val store = MemoryStore(); val gateway = FakeGateway().apply {
            backupStatus = "PARTIAL"; themeCount = 2; includeThemesBak = true
        }
        val policy = RootlessRecoveryPolicy(store, gateway)
        policy.prepare()
        policy.install(true)
        assertEquals("THEMES_RESTORED", policy.restoreThemesBackup().phase)
        assertEquals(1, gateway.restoredThemes)
        assertEquals("THEMES_RESTORED", policy.snapshot().phase)
        policy.restoreThemesBackup()
        assertEquals(1, gateway.restoredThemes)
    }

    @Test fun `no-backup requires explicit consent and stable identity before mutation`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        policy.installWithoutBackup(false)
        assertEquals(0, gateway.stages)
        assertTrue(gateway.commands.isEmpty())
        gateway.changeDuringStage = true
        assertEquals("FAILED", policy.installWithoutBackup(true).phase)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test fun `no-backup interruption cannot uninstall twice after restart`() {
        val store = MemoryStore(); val gateway = FakeGateway().apply { uninstallThrows = true }
        val policy = RootlessRecoveryPolicy(store, gateway)
        assertEquals("RECOVERY_NEEDED", policy.installWithoutBackup(true).phase)
        RootlessRecoveryPolicy(store, gateway).installWithoutBackup(true)
        assertEquals(1, gateway.commands.size)
    }

    @Test fun `restore failure preserves receipt and requires explicit retry`() {
        val store = MemoryStore(); val gateway = FakeGateway().apply {
            backupStatus = "PARTIAL"; themeCount = 2; includeThemesBak = true
        }
        val policy = RootlessRecoveryPolicy(store, gateway)
        policy.prepare(); policy.install(true)
        val backup = store.state.publicBackup
        gateway.restoreFails = true
        assertEquals("THEMES_RESTORE_FAILED", policy.restoreThemesBackup().phase)
        assertEquals(backup, store.state.publicBackup)
        val restarted = RootlessRecoveryPolicy(store, gateway)
        restarted.snapshot()
        assertEquals(1, gateway.restoredThemes)
        gateway.restoreFails = false
        assertEquals("THEMES_RESTORED", restarted.restoreThemesBackup().phase)
        assertEquals(2, gateway.restoredThemes)
    }

    @Test fun `wrong target missing receipt and failed intent persistence cannot restore`() {
        val store = MemoryStore(); val gateway = FakeGateway().apply {
            backupStatus = "PARTIAL"; themeCount = 2; includeThemesBak = true
        }
        val policy = RootlessRecoveryPolicy(store, gateway)
        policy.prepare(); policy.install(true)
        gateway.installed = original
        assertEquals("THEMES_RESTORE_FAILED", policy.restoreThemesBackup().phase)
        assertEquals(0, gateway.restoredThemes)
        gateway.installed = target
        store.rejectPhase = "THEMES_RESTORE_PENDING"
        policy.restoreThemesBackup()
        assertEquals(0, gateway.restoredThemes)
        store.rejectPhase = null
        store.state = store.state.copy(publicBackup = null)
        policy.restoreThemesBackup()
        assertEquals(0, gateway.restoredThemes)
    }

    @Test fun `partial archive count and schema must all be present before prepared`() {
        val cases = listOf(
            "missing archive" to { gateway: FakeGateway -> gateway.includeThemesBak = false },
            "zero count" to { gateway: FakeGateway -> gateway.themeCount = 0 },
            "legacy schema" to { gateway: FakeGateway -> gateway.schemaVersion = 1 },
        )
        cases.forEach { (name, invalidate) ->
            val store = MemoryStore(); val gateway = FakeGateway().apply {
                backupStatus = "PARTIAL"; themeCount = 2; includeThemesBak = true
                invalidate(this)
            }
            val policy = RootlessRecoveryPolicy(store, gateway)
            assertEquals("FAILED", policy.prepare().phase, name)
            assertEquals(null, store.state.publicBackup, name)
            assertEquals(1, gateway.cleanups, name)
            assertEquals("FAILED", policy.install(true).phase, name)
            assertEquals(0, gateway.stages, name)
            assertTrue(gateway.commands.isEmpty(), name)
        }
    }

    @Test fun `persisted incomplete partial receipts cannot install and prepare republishes`() {
        val invalidReceipts = listOf<Pair<String, (PublicBackupReceipt) -> PublicBackupReceipt>>(
            "missing archive" to { receipt -> receipt.copy(files = receipt.files.filterNot { it.name == "themes.bak" }) },
            "zero count" to { receipt -> receipt.copy(themeCount = 0) },
            "legacy schema" to { receipt -> receipt.copy(schemaVersion = 1) },
        )
        invalidReceipts.forEach { (name, invalidate) ->
            val store = MemoryStore(); val gateway = FakeGateway().apply {
                backupStatus = "PARTIAL"; themeCount = 2; includeThemesBak = true
            }
            val policy = RootlessRecoveryPolicy(store, gateway)
            val prepared = policy.prepare()
            assertEquals("PREPARED", prepared.phase, name)
            store.state = prepared.copy(publicBackup = invalidate(prepared.publicBackup!!))
            assertEquals("PREPARED", RootlessRecoveryPolicy(store, gateway).install(true).phase, name)
            assertEquals(0, gateway.stages, name)
            assertTrue(gateway.commands.isEmpty(), name)
            assertEquals("PREPARED", policy.prepare().phase, name)
            assertEquals(2, gateway.published, name)
            assertTrue(store.state.publicBackup!!.isComplete(), name)
        }
    }

    @Test fun `unsupported theme content status never claims partial backup`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        val prepared = policy.prepare()
        assertEquals("UNAVAILABLE", prepared.publicBackup?.themesBackupStatus)
        assertFalse(prepared.message.contains("Themes private data saved"))
        assertTrue(prepared.message.contains("NOT backed up"))
        assertFalse(prepared.publicBackup!!.copy(themesBackupStatus = "PARTIAL").isComplete())
    }

    @Test fun `journal write failure removes newly published public rows`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        store.rejectPhase = "PREPARED"
        assertEquals("FAILED", policy.prepare().phase)
        assertEquals(1, gateway.cleanups)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test fun `service loss and changed baseline or backups fail before uninstall`() {
        listOf<(FakeGateway) -> Unit>(
            { it.service = false }, { it.installed = target }, { it.backupsValid = false },
        ).forEach { change ->
            val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
            policy.prepare(); change(gateway)
            assertEquals("FAILED", policy.install(true).phase)
            assertTrue(gateway.commands.isEmpty())
        }
    }

    @Test fun `unverified target never reaches a package command`() {
        val store = MemoryStore(); val gateway = FakeGateway(); gateway.targetValid = false
        val policy = RootlessRecoveryPolicy(store, gateway)
        assertEquals("FAILED", policy.prepare().phase)
        assertEquals("FAILED", policy.install(true).phase)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test fun `interrupted uninstall persists recovery and cannot be retried after restart`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        policy.prepare(); gateway.installSucceeds = false
        assertEquals("RECOVERY_NEEDED", policy.install(true).phase)
        assertEquals(listOf(RootlessRecoveryCommands.UNINSTALL, RootlessRecoveryCommands.install("/data/local/tmp/mtzstudio-rootless-aabb.apk")), gateway.commands)
        assertEquals("RECOVERY_NEEDED", RootlessRecoveryPolicy(store, gateway).install(true).phase)
        gateway.service = false
        assertEquals("RECOVERY_NEEDED", policy.recoverExisting().phase)
        gateway.service = true
        assertEquals("RECOVERED", RootlessRecoveryPolicy(store, gateway).recoverExisting().phase)
        assertEquals(1, gateway.commands.count { it == RootlessRecoveryCommands.UNINSTALL })
        assertEquals(1, gateway.commands.count { it == RootlessRecoveryCommands.RECOVER })
    }

    @Test fun `verified installed identity and legacy resolution are both required`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        policy.prepare()
        assertEquals("INSTALLED", policy.install(true).phase)
        assertEquals(target, gateway.installed)
        val otherStore = MemoryStore(); val otherGateway = FakeGateway(); otherGateway.legacy = false
        val otherPolicy = RootlessRecoveryPolicy(otherStore, otherGateway)
        otherPolicy.prepare()
        assertEquals("RECOVERY_NEEDED", otherPolicy.install(true).phase)
        assertEquals(target, otherGateway.installed)
    }

    @Test fun `recovery after intent committed before command is explicit and durable`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        val prepared = policy.prepare()
        store.save(prepared.copy(phase = "UNINSTALL_PENDING"))
        assertEquals("RECOVERED", RootlessRecoveryPolicy(store, gateway).recoverExisting().phase)
        assertTrue(gateway.commands.isEmpty()) // already installed; do not mutate
        store.save(prepared.copy(phase = "RECOVERY_PENDING")); gateway.installed = null
        assertEquals("RECOVERED", RootlessRecoveryPolicy(store, gateway).recoverExisting().phase)
    }

    @Test fun `command policy excludes global reset and downgrade operations`() {
        val commands = listOf(RootlessRecoveryCommands.UNINSTALL, RootlessRecoveryCommands.RECOVER,
            RootlessRecoveryCommands.install("/data/local/tmp/mtzstudio-rootless-abcd.apk"))
        assertTrue(commands[0].contains("uninstall --user 0"))
        assertTrue(commands[1].contains("install-existing --user 0"))
        assertFalse(commands.any { listOf(" -k", " --all", " -d", "uninstall-system-updates", "reset", "su ").any(it::contains) })
    }

    @Test fun `failed staging and journal commit prevent uninstall`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        policy.prepare(); gateway.stageSucceeds = false
        assertEquals("FAILED", policy.install(true).phase)
        assertTrue(gateway.commands.isEmpty())
        gateway.stageSucceeds = true; policy.prepare(); store.rejectPhase = "UNINSTALL_PENDING"
        assertEquals("FAILED", policy.install(true).phase)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test fun `uncertain uninstall never proceeds to install or repeats removal`() {
        listOf<(FakeGateway) -> Unit>(
            { it.uninstallThrows = true }, { it.stillInstalledAfterUninstall = true },
        ).forEach { change ->
            val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
            policy.prepare(); change(gateway)
            assertEquals("RECOVERY_NEEDED", policy.install(true).phase)
            policy.prepare(); policy.install(true)
            assertEquals(listOf(RootlessRecoveryCommands.UNINSTALL), gateway.commands)
        }
    }

    @Test fun `interrupted phases cannot prepare or uninstall on reopening`() {
        listOf("UNINSTALL_PENDING", "ABSENT", "INSTALL_PENDING", "RECOVERY_PENDING", "RECOVERY_NEEDED").forEach { phase ->
            val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
            store.state = policy.prepare().copy(phase = phase)
            assertEquals(phase, RootlessRecoveryPolicy(store, gateway).snapshot().phase)
            assertEquals(phase, policy.prepare().phase)
            assertEquals(phase, policy.install(true).phase)
            assertTrue(gateway.commands.isEmpty())
        }
    }

    @Test fun `recovery never accepts an unrelated signer`() {
        val store = MemoryStore(); val gateway = FakeGateway(); val policy = RootlessRecoveryPolicy(store, gateway)
        store.state = policy.prepare().copy(phase = "RECOVERY_NEEDED")
        gateway.installed = original.copy(certificates = setOf("other"))
        assertEquals("RECOVERY_NEEDED", policy.recoverExisting().phase)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test fun `unsafe installer paths and arbitrary failure payloads are rejected`() {
        listOf("/sdcard/a.apk", "/data/local/tmp/mtzstudio-rootless-a.apk;reboot", "/data/local/tmp/../a.apk").forEach { path ->
            assertFailsWith<IllegalArgumentException> { RootlessRecoveryCommands.install(path) }
        }
        assertFailsWith<IllegalArgumentException> { RootlessRecoveryFailure("private device dump\n") }
    }
}
