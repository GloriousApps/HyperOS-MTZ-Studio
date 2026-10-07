package dev.glorioustr.mtzstudio.tester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeRuntimeDomainTest {

    @Test
    fun targetDetectorIdentifiesChinaRegion() {
        val target = ThemeRuntimeTargetDetector.detect(
            getprop = { key ->
                when (key) {
                    "ro.miui.region" -> "CN"
                    "ro.product.mod_device" -> "ishtar_cn"
                    else -> null
                }
            },
            fingerprint = "Xiaomi/ishtar/ishtar:14/UKQ1.230804.001/V816.0.4.0.UMACNXM:user/release-keys",
        )
        assertEquals(ThemeRuntimeTarget.NON_GLOBAL, target)
    }

    @Test
    fun targetDetectorIdentifiesGlobalRegion() {
        val target = ThemeRuntimeTargetDetector.detect(
            getprop = { key ->
                when (key) {
                    "ro.miui.region" -> "TR"
                    "ro.product.mod_device" -> "garnet_global"
                    else -> null
                }
            },
            fingerprint = "Redmi/garnet_global/garnet:14/UKQ1.230917.001/V816.0.8.0.UNRMIXM:user/release-keys",
        )
        assertEquals(ThemeRuntimeTarget.GLOBAL, target)
    }

    @Test
    fun capabilitiesCanApplyAutomaticallyEvaluatesCorrectly() {
        // Root runtime healthy
        val rootHealthy = ThemeManagerCapabilities(
            packageInstalled = true,
            versionName = "3.4.1.23",
            hasLegacyApplyThemeForScreenshot = false,
            canResolveLegacyTester = false,
            canLaunchLegacyTester = false,
            canImportModernLocalLibrary = false,
            canApplyModernLocalTheme = false,
            canPersistAppliedTheme = true,
            canUseMiuiBackup = false,
            hasRootGlobalBridge = false,
            hasRootRuntime = true,
            runtimeHealthy = true,
        )
        assertTrue(rootHealthy.canApplyAutomatically)

        // Root runtime unhealthy
        val rootUnhealthy = rootHealthy.copy(runtimeHealthy = false)
        assertFalse(rootUnhealthy.canApplyAutomatically)

        // Rootless + Shizuku backup + modern local apply
        val modernShizuku = ThemeManagerCapabilities(
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
        assertTrue(modernShizuku.canApplyAutomatically)

        // Rootless legacy with valid ApplyThemeForScreenshot activity
        val legacyValid = ThemeManagerCapabilities(
            packageInstalled = true,
            versionName = "3.0.5.6",
            hasLegacyApplyThemeForScreenshot = true,
            canResolveLegacyTester = true,
            canLaunchLegacyTester = true,
            canImportModernLocalLibrary = false,
            canApplyModernLocalTheme = false,
            canPersistAppliedTheme = true,
            canUseMiuiBackup = false,
            hasRootGlobalBridge = false,
            hasRootRuntime = false,
            runtimeHealthy = false,
        )
        assertTrue(legacyValid.canApplyAutomatically)

        // Rootless new global with tester activity removed
        val legacyRemoved = legacyValid.copy(
            hasLegacyApplyThemeForScreenshot = false,
            canResolveLegacyTester = false,
        )
        assertFalse(legacyRemoved.canApplyAutomatically)
    }

    @Test
    fun studioCapabilityPolicyRespectsCapabilities() {
        val rootlessCaps = ThemeManagerCapabilities(
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
        val policy = StudioCapabilityPolicy(
            rootAvailable = false,
            themeManagerBehavior = ThemeManagerBehavior.MODERN_NATIVE_LIBRARY,
            capabilities = rootlessCaps,
        )
        // With modern capabilities, automated apply is possible even without root
        assertTrue(policy.canApplyAutomatically)
    }

    @Test
    fun runtimeArtifactsMetadataMatchesTarget() {
        val global = ThemeRuntimeArtifacts.forTarget(ThemeRuntimeTarget.GLOBAL)
        assertEquals(ThemeManagerContract.CONTROLLED_GLOBAL_VERSION, global.versionName)
        assertEquals(ThemeRuntimeTarget.GLOBAL, global.target)

        val nonGlobal = ThemeRuntimeArtifacts.forTarget(ThemeRuntimeTarget.NON_GLOBAL)
        assertEquals(ThemeManagerContract.CONTROLLED_NON_GLOBAL_VERSION, nonGlobal.versionName)
        assertEquals(ThemeRuntimeTarget.NON_GLOBAL, nonGlobal.target)
    }
}
