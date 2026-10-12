package dev.glorioustr.mtzstudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootThemeBridgeReadinessTest {

    @Test
    fun acceptsExpectedVersionWithOrWithoutTrailingLf() {
        assertTrue(RootThemeBridgeReadiness.isExpectedMarker("1.0.0"))
        assertTrue(RootThemeBridgeReadiness.isExpectedMarker("1.0.0\n"))
    }

    @Test
    fun rejectsReadyFlagAndOtherVersions() {
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker("ready=true"))
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker("2.0.0"))
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker(""))
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker("0.0.0"))
    }

    @Test
    fun rejectsExtraLinesAndStructuredReadyFields() {
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker("1.0.0\nready=true"))
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker("ready=true\nversion=1.0.0"))
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker("version=1.0.0\nready=true"))
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker("1.0.0\n\n"))
    }

    @Test
    fun rejectsWhitespaceGarbage() {
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker(" 1.0.0"))
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker("1.0.0 "))
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker("\t1.0.0"))
        assertFalse(RootThemeBridgeReadiness.isExpectedMarker("1.0.0\r\n"))
    }
}
