package dev.glorioustr.mtzstudio.strategy

import android.content.Context
import dev.glorioustr.mtzstudio.PreparedThemeApply
import dev.glorioustr.mtzstudio.library.LibraryTheme
import dev.glorioustr.mtzstudio.tester.InstalledThemeManager
import dev.glorioustr.mtzstudio.StudioAccessMode
import dev.glorioustr.mtzstudio.tester.ThemeManagerCapabilities
import dev.glorioustr.mtzstudio.tester.ThemeManagerCapabilityProbe
import dev.glorioustr.mtzstudio.tester.ThemeManagerInspector

/**
 * High-level engine that evaluates access mode, capabilities, and target profile
 * to select the appropriate apply strategy.
 */
class ThemeApplyEngine(
    private val context: Context,
    private val legacyGlobalStrategy: LegacyGlobalPreviewApplyStrategy = LegacyGlobalPreviewApplyStrategy(context),
    private val modernLocalStrategy: ModernLocalLibraryApplyStrategy = ModernLocalLibraryApplyStrategy(context),
) {
    private val inspector = ThemeManagerInspector(context)
    private val capabilityProbe = ThemeManagerCapabilityProbe(context)

    fun detectCapabilities(
        installed: InstalledThemeManager = inspector.inspect(),
        hasRootGlobalBridge: Boolean = false,
        hasRootRuntime: Boolean = false,
        runtimeHealthy: Boolean = false,
        canUseMiuiBackup: Boolean = false,
    ): ThemeManagerCapabilities {
        return capabilityProbe.probeCapabilities(
            installed = installed,
            hasRootGlobalBridge = hasRootGlobalBridge,
            hasRootRuntime = hasRootRuntime,
            runtimeHealthy = runtimeHealthy,
            canUseMiuiBackup = canUseMiuiBackup,
        )
    }

    fun selectRootlessStrategy(
        capabilities: ThemeManagerCapabilities,
    ): ThemeApplyStrategy? {
        return when {
            capabilities.canApplyModernLocalTheme && capabilities.canUseMiuiBackup && modernLocalStrategy.isAvailable() -> {
                modernLocalStrategy
            }
            capabilities.hasLegacyApplyThemeForScreenshot && legacyGlobalStrategy.isAvailable() -> {
                legacyGlobalStrategy
            }
            else -> null
        }
    }
}
