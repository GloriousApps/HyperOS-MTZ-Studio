package dev.glorioustr.mtzstudio.themeservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeServiceOutcomeTest {

    @Test
    fun everyIconAcceptedCountsAsFullyAccepted() {
        val outcome = ThemeServiceOutcome(
            customizedIcons = mapOf(
                "com.miui.home" to ThemeServiceCall.ACCEPTED,
                "com.android.settings" to ThemeServiceCall.ACCEPTED,
            ),
            bound = true,
        )

        assertEquals(2, outcome.requested.size)
        assertTrue(outcome.anyAccepted)
        assertTrue(outcome.allAccepted)
    }

    @Test
    fun aRejectionIsReportedAsARejection() {
        // Themes returning false means the request arrived and was declined. It must never be
        // smoothed into success: that icon was not applied.
        val outcome = ThemeServiceOutcome(
            customizedIcons = mapOf(
                "com.miui.home" to ThemeServiceCall.ACCEPTED,
                "com.android.settings" to ThemeServiceCall.REJECTED,
            ),
            bound = true,
        )

        assertTrue(outcome.anyAccepted)
        assertFalse(outcome.allAccepted)
    }

    @Test
    fun anEmptyOutcomeClaimsNothing() {
        val outcome = ThemeServiceOutcome(failureMessage = "unavailable")

        assertTrue(outcome.requested.isEmpty())
        assertFalse(outcome.anyAccepted)
        assertFalse(outcome.allAccepted)
    }

    @Test
    fun aFailedCallIsNotAnAcceptance() {
        val outcome = ThemeServiceOutcome(
            customizedIcons = mapOf("com.miui.home" to ThemeServiceCall.FAILED),
            bound = true,
        )

        assertFalse(outcome.anyAccepted)
        assertFalse(outcome.allAccepted)
    }

    @Test
    fun describeReportsCountsNotBitmaps() {
        val described = ThemeServiceOutcome(
            customizedIcons = mapOf(
                "com.miui.home" to ThemeServiceCall.ACCEPTED,
                "com.android.settings" to ThemeServiceCall.REJECTED,
            ),
            bound = true,
        ).describe()

        assertEquals(2, described["iconsRequested"])
        assertEquals(1, described["iconsAccepted"])
    }
}
