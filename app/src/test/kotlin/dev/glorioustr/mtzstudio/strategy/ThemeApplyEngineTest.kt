package dev.glorioustr.mtzstudio.strategy

import dev.glorioustr.mtzstudio.tester.ThemeManagerCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeApplyEngineTest {

    @Test
    fun selectRootlessStrategyReturnsModernWhenCapabilitiesMatch() {
        val caps = ThemeManagerCapabilities(
            packageInstalled = true,
            versionName = "10.8.7.6",
            hasLegacyApplyThemeForScreenshot = false,
            canResolveLegacyTester = false,
            canLaunchLegacyTester = false,
            canImportModernLocalLibrary = true,
            canApplyModernLocalTheme = true,
            canPersistAppliedTheme = true,
            canUseMiuiBackup = true,
            hasRootGlobalBridge = false,
            hasRootRuntime = false,
            runtimeHealthy = false,
        )

        // Mock modern strategy
        val mockModern = object : ThemeApplyStrategy {
            override val strategyName = "ModernLocalLibrary"
            override fun isAvailable() = true
            override suspend fun prepare(theme: dev.glorioustr.mtzstudio.library.LibraryTheme, themeManagerLocalId: String?, replacedLocalIds: Set<String>) = error("")
            override suspend fun verifyApplied(theme: dev.glorioustr.mtzstudio.library.LibraryTheme, prepared: dev.glorioustr.mtzstudio.PreparedThemeApply) = error("")
            override suspend fun cleanup(prepared: dev.glorioustr.mtzstudio.PreparedThemeApply) {}
        }
        val mockLegacy = object : ThemeApplyStrategy {
            override val strategyName = "LegacyGlobalPreview"
            override fun isAvailable() = false
            override suspend fun prepare(theme: dev.glorioustr.mtzstudio.library.LibraryTheme, themeManagerLocalId: String?, replacedLocalIds: Set<String>) = error("")
            override suspend fun verifyApplied(theme: dev.glorioustr.mtzstudio.library.LibraryTheme, prepared: dev.glorioustr.mtzstudio.PreparedThemeApply) = error("")
            override suspend fun cleanup(prepared: dev.glorioustr.mtzstudio.PreparedThemeApply) {}
        }

        val selected = when {
            caps.canApplyModernLocalTheme && caps.canUseMiuiBackup && mockModern.isAvailable() -> mockModern
            caps.hasLegacyApplyThemeForScreenshot && mockLegacy.isAvailable() -> mockLegacy
            else -> null
        }

        assertEquals("ModernLocalLibrary", selected?.strategyName)
    }

    @Test
    fun selectRootlessStrategyReturnsNullWhenNoAutomaticPathAvailable() {
        val caps = ThemeManagerCapabilities(
            packageInstalled = true,
            versionName = "3.0.6.8",
            hasLegacyApplyThemeForScreenshot = false,
            canResolveLegacyTester = false,
            canLaunchLegacyTester = false,
            canImportModernLocalLibrary = false,
            canApplyModernLocalTheme = false,
            canPersistAppliedTheme = false,
            canUseMiuiBackup = false,
            hasRootGlobalBridge = false,
            hasRootRuntime = false,
            runtimeHealthy = false,
        )

        val selected = when {
            caps.canApplyModernLocalTheme && caps.canUseMiuiBackup -> "Modern"
            caps.hasLegacyApplyThemeForScreenshot -> "Legacy"
            else -> null
        }

        assertNull(selected)
    }
}
