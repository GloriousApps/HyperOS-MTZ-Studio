package dev.glorioustr.mtzstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootlessRecoveryPresentationTest {
    @Test fun `old prepared journal without public copies must prepare again`() {
        assertEquals(RecoveryAction.PREPARE, recoveryAction("PREPARED", canInstall = false, needsRecovery = false))
        assertEquals(RecoveryAction.INSTALL, recoveryAction("PREPARED", canInstall = true, needsRecovery = false))
    }

    @Test fun `recovery takes priority and completed states do not propose another action`() {
        assertEquals(RecoveryAction.RECOVER, recoveryAction("RECOVERY_NEEDED", canInstall = false, needsRecovery = true))
        assertEquals(RecoveryAction.RECOVER, recoveryAction("INSTALL_PENDING", canInstall = false, needsRecovery = true))
        assertEquals(RecoveryAction.RECOVER, recoveryAction("FAILED", canInstall = false, needsRecovery = true))
        assertEquals(RecoveryAction.NONE, recoveryAction("INSTALLED", canInstall = false, needsRecovery = false))
        assertEquals(RecoveryAction.NONE, recoveryAction("RECOVERED", canInstall = false, needsRecovery = false))
        assertEquals(RecoveryAction.NONE, recoveryAction(null, canInstall = false, needsRecovery = false))
        assertEquals(RecoveryAction.PREPARE, recoveryAction("IDLE", canInstall = false, needsRecovery = false))
        assertEquals(RecoveryAction.PREPARE, recoveryAction("FAILED", canInstall = false, needsRecovery = false))
    }

    @Test fun `only controlled reason codes appear in compact summary`() {
        assertEquals("ADB_SERVICE_PERMISSION_REQUIRED", controlledRecoveryReason("Preparation failed. Reason: ADB_SERVICE_PERMISSION_REQUIRED"))
        assertEquals("BACKUP_PUBLIC_VERIFY_PATH", controlledRecoveryReason("Preparation failed. Reason: BACKUP_PUBLIC_VERIFY_PATH"))
        assertNull(controlledRecoveryReason("Detailed internal path /data/user/0/private"))
    }
}
