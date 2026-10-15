package dev.glorioustr.mtzstudio

import dev.glorioustr.mtzstudio.tester.InstalledThemeManager
import dev.glorioustr.mtzstudio.tester.ThemeManagerBehavior
import dev.glorioustr.mtzstudio.tester.ThemeManagerContract
import dev.glorioustr.mtzstudio.tester.ThemeManagerRuntimeProfile
import kotlin.test.Test
import kotlin.test.assertFalse

class ThemeManagerCompatibilityCardTest {
    @Test
    fun `root module mode hides legacy downgrade for controlled 3.4.1.23`() {
        assertFalse(
            shouldShowThemeManagerDowngradeButton(
                allowRootDowngrade = true,
                current = installed("3.4.1.23-global"),
                runtimeProfile = incompatibleProfile("3.4.1.23-global"),
                rootModuleMode = true,
            ),
        )
    }

    @Test
    fun `root downgrade stays hidden without root access`() {
        assertFalse(
            shouldShowThemeManagerDowngradeButton(
                allowRootDowngrade = false,
                current = installed("3.4.1.23-global"),
                runtimeProfile = incompatibleProfile("3.4.1.23-global"),
                rootModuleMode = true,
            ),
        )
    }

    @Test
    fun `compatible legacy version does not show redundant downgrade`() {
        assertFalse(
            shouldShowThemeManagerDowngradeButton(
                allowRootDowngrade = true,
                current = installed("3.0.5.6-global"),
                runtimeProfile = ThemeManagerRuntimeProfile(
                    packageInstalled = true,
                    versionName = "3.0.5.6-global",
                    knownBehavior = ThemeManagerBehavior.LOCAL_THEME_IMPORT,
                    legacyTesterResolvable = true,
                    modernLocalLibraryResolvable = false,
                    publicMtzImportResolvable = false,
                    splitApkCount = 0,
                    exportedThemeActivityCandidates = emptyList(),
                ),
                rootModuleMode = false,
            ),
        )
    }

    private fun installed(version: String) = InstalledThemeManager(
        installed = true,
        packageName = ThemeManagerContract.PACKAGE_NAME,
        versionName = version,
        versionCode = null,
        behavior = ThemeManagerContract.behavior(version),
    )

    private fun incompatibleProfile(version: String) = ThemeManagerRuntimeProfile(
        packageInstalled = true,
        versionName = version,
        knownBehavior = ThemeManagerBehavior.UNKNOWN,
        legacyTesterResolvable = false,
        modernLocalLibraryResolvable = false,
        publicMtzImportResolvable = false,
        splitApkCount = 0,
        exportedThemeActivityCandidates = emptyList(),
    )
}
