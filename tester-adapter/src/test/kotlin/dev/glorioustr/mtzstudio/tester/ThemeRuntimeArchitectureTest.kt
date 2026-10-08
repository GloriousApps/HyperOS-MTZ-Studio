package dev.glorioustr.mtzstudio.tester

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeRuntimeArchitectureTest {

    @Test
    fun `detector resolves china from miui region`() {
        assertEquals(
            ThemeRuntimeTargetDetector.Target.CHINA,
            ThemeRuntimeTargetDetector.resolve("CN", null, null),
        )
    }

    @Test
    fun `detector resolves global from non-cn miui region`() {
        assertEquals(
            ThemeRuntimeTargetDetector.Target.GLOBAL,
            ThemeRuntimeTargetDetector.resolve("TR", null, null),
        )
        assertEquals(
            ThemeRuntimeTargetDetector.Target.GLOBAL,
            ThemeRuntimeTargetDetector.resolve("global", null, null),
        )
    }

    @Test
    fun `detector falls back to mod_device when region missing`() {
        assertEquals(
            ThemeRuntimeTargetDetector.Target.CHINA,
            ThemeRuntimeTargetDetector.resolve(null, "ishtar_cn", null),
        )
        assertEquals(
            ThemeRuntimeTargetDetector.Target.GLOBAL,
            ThemeRuntimeTargetDetector.resolve(null, "venus_global", null),
        )
        assertEquals(
            ThemeRuntimeTargetDetector.Target.GLOBAL,
            ThemeRuntimeTargetDetector.resolve(null, "venus_eea", null),
        )
    }

    @Test
    fun `detector falls back to installed theme manager family`() {
        assertEquals(
            ThemeRuntimeTargetDetector.Target.GLOBAL,
            ThemeRuntimeTargetDetector.resolve(null, null, "3.4.1.23-global"),
        )
        assertEquals(
            ThemeRuntimeTargetDetector.Target.CHINA,
            ThemeRuntimeTargetDetector.resolve(null, null, "11.5.3.1"),
        )
        assertEquals(
            ThemeRuntimeTargetDetector.Target.UNKNOWN,
            ThemeRuntimeTargetDetector.resolve(null, null, "10.8.7.6"),
        )
        assertEquals(
            ThemeRuntimeTargetDetector.Target.UNKNOWN,
            ThemeRuntimeTargetDetector.resolve(null, null, null),
        )
    }

    @Test
    fun `detector prefers region over installed family`() {
        assertEquals(
            ThemeRuntimeTargetDetector.Target.GLOBAL,
            ThemeRuntimeTargetDetector.resolve("TR", "ishtar_cn", "11.5.3.1"),
        )
        assertEquals(
            ThemeRuntimeTargetDetector.Target.CHINA,
            ThemeRuntimeTargetDetector.resolve("CN", "venus_global", "3.4.1.23-global"),
        )
    }

    @Test
    fun `apply result success carries protocol and persistence`() {
        val success = ThemeApplyResult.Success(
            themeId = "theme-1",
            protocol = "ROOT_GLOBAL_THEME_MANAGER_BRIDGE",
            themeManagerLocalId = "local-1",
            persistenceArmed = true,
        )
        assertEquals("theme-1", success.themeId)
        assertEquals("ROOT_GLOBAL_THEME_MANAGER_BRIDGE", success.protocol)
        assertTrue(success.persistenceArmed)
    }

    @Test
    fun `apply result failure variants are distinguishable`() {
        val failures = listOf<ThemeApplyResult>(
            ThemeApplyResult.CapabilityMissing("legacyTester"),
            ThemeApplyResult.ThemeManagerIncompatible("3.0.6.8", "tester activity removed"),
            ThemeApplyResult.RuntimeNotInstalled(ThemeRuntimeTargetDetector.Target.GLOBAL),
            ThemeApplyResult.RuntimeInstallFailed(ThemeRuntimeTargetDetector.Target.CHINA, "pm rejected"),
            ThemeApplyResult.ImportFailed("no importer"),
            ThemeApplyResult.PreviewPublishFailed("publish rejected"),
            ThemeApplyResult.ApplyFailed("activity crashed"),
            ThemeApplyResult.PersistenceFailed("no local id"),
            ThemeApplyResult.VerificationFailed("theme not active"),
            ThemeApplyResult.PermissionDenied("storage denied"),
            ThemeApplyResult.RootUnavailable(),
            ThemeApplyResult.ShizukuUnavailable(),
            ThemeApplyResult.SheveryUnavailable(),
            ThemeApplyResult.RollbackPerformed("3.0.5.6", "install failed"),
        )
        assertEquals(14, failures.size)
        assertTrue(failures.none { it is ThemeApplyResult.Success })
    }

    @Test
    fun `runtime health summary reflects state`() {
        val healthy = RuntimeHealth(
            packageInstalled = true,
            versionName = "3.4.1.23-global",
            versionMatchesTarget = true,
            moduleBridgeReady = true,
            applyCapability = ThemeManagerBehavior.UNKNOWN,
            healthy = true,
        )
        assertTrue(healthy.healthy)
        assertTrue(healthy.summary.contains("sağlık=iyi"))

        val broken = healthy.copy(moduleBridgeReady = false, healthy = false)
        assertFalse(broken.healthy)
        assertTrue(broken.summary.contains("sağlık=bozuk"))
    }

    @Test
    fun `runtime status snapshot carries state target and health`() {
        val status = RuntimeStatus(
            state = RuntimeState.ACTIVE,
            target = ThemeRuntimeTargetDetector.Target.GLOBAL,
            installedVersion = "3.4.1.23-global",
            health = null,
        )
        assertEquals(RuntimeState.ACTIVE, status.state)
        assertEquals(ThemeRuntimeTargetDetector.Target.GLOBAL, status.target)
        assertEquals("3.4.1.23-global", status.installedVersion)
    }

    @Test
    fun `runtime artifact resolves global and china targets`() {
        val global = ThemeManagerContract.runtimeArtifact(ThemeManagerFamily.GLOBAL)
        assertTrue(global != null)
        assertEquals("3.4.1.23", global!!.version)
        assertEquals("Xiaomi_Themes_3.4.1.23-global.apk", global.apkName)
        assertEquals(27, global.minSdk)
        assertTrue(global.downloadUrl.endsWith("/requirements/Xiaomi_Themes_3.4.1.23-global.apk"))
        assertEquals(global.version, global.asRootTargetApk().version)

        val china = ThemeManagerContract.runtimeArtifact(ThemeManagerFamily.CHINA)
        assertTrue(china != null)
        assertEquals("11.5.3.1", china!!.version)
        assertEquals("Xiaomi_Themes_11.5.3.1.apk", china.apkName)
        assertEquals(34, china.minSdk)

        assertEquals(null, ThemeManagerContract.runtimeArtifact(ThemeManagerFamily.UNKNOWN))
    }

    @Test
    fun `legacy strategy plans success for local theme import`() {
        val installed = InstalledThemeManager(
            installed = true,
            packageName = ThemeManagerContract.PACKAGE_NAME,
            versionName = "3.0.5.6",
            versionCode = 3000506,
            behavior = ThemeManagerBehavior.LOCAL_THEME_IMPORT,
            family = ThemeManagerFamily.GLOBAL,
        )
        val result = LegacyGlobalPreviewApplyStrategy.plan(installed, "/data/theme.mtz", "dev.glorioustr.mtzstudio")
        assertTrue(result is ThemeApplyResult.Success)
        assertEquals(LegacyGlobalPreviewApplyStrategy.PROTOCOL, (result as ThemeApplyResult.Success).protocol)
    }

    @Test
    fun `legacy strategy rejects tester-activity-removed builds`() {
        val installed = InstalledThemeManager(
            installed = true,
            packageName = ThemeManagerContract.PACKAGE_NAME,
            versionName = "3.0.6.8",
            versionCode = 3000608,
            behavior = ThemeManagerBehavior.TESTER_ACTIVITY_REMOVED,
            family = ThemeManagerFamily.GLOBAL,
        )
        val result = LegacyGlobalPreviewApplyStrategy.plan(installed, "/data/theme.mtz", "dev.glorioustr.mtzstudio")
        assertTrue(result is ThemeApplyResult.ThemeManagerIncompatible)
    }

    @Test
    fun `legacy strategy reports capability missing when not installed`() {
        val installed = InstalledThemeManager(
            installed = false,
            packageName = ThemeManagerContract.PACKAGE_NAME,
            versionName = null,
            versionCode = null,
            behavior = ThemeManagerBehavior.UNKNOWN,
        )
        val result = LegacyGlobalPreviewApplyStrategy.plan(installed, "/data/theme.mtz", "dev.glorioustr.mtzstudio")
        assertTrue(result is ThemeApplyResult.CapabilityMissing)
    }

    @Test
    fun `modern strategy plans success for native library`() {
        val installed = InstalledThemeManager(
            installed = true,
            packageName = ThemeManagerContract.PACKAGE_NAME,
            versionName = "10.8.7.6",
            versionCode = 10080706,
            behavior = ThemeManagerBehavior.MODERN_NATIVE_LIBRARY,
            family = ThemeManagerFamily.UNKNOWN,
        )
        val result = ModernLocalLibraryApplyStrategy.plan(installed, "/data/theme.mtz")
        assertTrue(result is ThemeApplyResult.Success)
        assertEquals(ModernLocalLibraryApplyStrategy.PROTOCOL, (result as ThemeApplyResult.Success).protocol)
        assertTrue(result.persistenceArmed)
    }

    @Test
    fun `modern strategy rejects non-native-library builds`() {
        val installed = InstalledThemeManager(
            installed = true,
            packageName = ThemeManagerContract.PACKAGE_NAME,
            versionName = "3.4.1.23-global",
            versionCode = 3040123,
            behavior = ThemeManagerBehavior.UNKNOWN,
            family = ThemeManagerFamily.GLOBAL,
        )
        val result = ModernLocalLibraryApplyStrategy.plan(installed, "/data/theme.mtz")
        assertTrue(result is ThemeApplyResult.ThemeManagerIncompatible)
    }
}
