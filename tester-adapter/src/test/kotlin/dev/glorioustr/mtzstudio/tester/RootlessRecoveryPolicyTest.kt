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
        val commands = mutableListOf<String>()
        override fun requireReady() { check(service) }
        override fun installed() = installed
        override fun backup(baseline: RecoveryIdentity) = "/private/rollback" to listOf(RecoveryFile("/private/rollback/base.apk", "sha", "/data/app/base.apk"))
        override fun verifiedTarget(baseline: RecoveryIdentity): RecoveryIdentity { check(targetValid); return target }
        override fun recheck(journal: RecoveryJournal) { check(backupsValid && installed == journal.baseline && journal.target == target) }
        override fun stage(): String { check(stageSucceeds); return "/data/local/tmp/mtzstudio-rootless-aabb.apk" }
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
        policy.install(false)
        assertTrue(gateway.commands.isEmpty())
        assertEquals("PREPARED", policy.snapshot().phase)
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
