package dev.glorioustr.mtzstudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePersistenceGuardServiceTest {

    @Test
    fun doesNotReportInitialUnavailableCheckAsConnectionLoss() {
        assertFalse(shouldReportThemeGuardConnectionLost(null, false))
    }

    @Test
    fun reportsOnlyAConfirmedConnectionTransitionToUnavailable() {
        assertTrue(shouldReportThemeGuardConnectionLost(true, false))
        assertFalse(shouldReportThemeGuardConnectionLost(false, false))
        assertFalse(shouldReportThemeGuardConnectionLost(true, true))
    }
}
