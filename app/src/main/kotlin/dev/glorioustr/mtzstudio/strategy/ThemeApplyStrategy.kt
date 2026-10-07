package dev.glorioustr.mtzstudio.strategy

import dev.glorioustr.mtzstudio.PreparedThemeApply
import dev.glorioustr.mtzstudio.library.LibraryTheme
import dev.glorioustr.mtzstudio.tester.ThemeApplyResult

/**
 * Common abstraction for all theme application strategies.
 * Provides explicit separation between preparation, execution, verification, and persistence.
 */
interface ThemeApplyStrategy {
    val strategyName: String

    /**
     * Checks if the required runtime/device capabilities exist for this strategy.
     */
    fun isAvailable(): Boolean

    /**
     * Stages and prepares the theme apply request and intent.
     */
    suspend fun prepare(
        theme: LibraryTheme,
        themeManagerLocalId: String? = null,
        replacedLocalIds: Set<String> = emptySet(),
    ): PreparedThemeApply

    /**
     * Verifies that the theme has been successfully applied to the system.
     */
    suspend fun verifyApplied(theme: LibraryTheme, prepared: PreparedThemeApply): ThemeApplyResult

    /**
     * Cleans up any staged artifacts or temporary files.
     */
    suspend fun cleanup(prepared: PreparedThemeApply)
}
