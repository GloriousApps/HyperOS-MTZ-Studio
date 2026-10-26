package dev.glorioustr.mtzstudio

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ThemeManagerCompatibilityCardTest {
    @Test
    fun `root module support is limited to bundled runtime versions`() {
        assertTrue(supportsRootMtzImportModule("3.4.1.23"))
        assertTrue(supportsRootMtzImportModule("3.4.1.23-global"))
        assertTrue(supportsRootMtzImportModule("11.5.3.1"))
        assertFalse(supportsRootMtzImportModule("3.4.1.25"))
    }

    @Test
    fun `factory rollback is offered only for an older compatible factory build`() {
        assertTrue(factoryThemeManagerRollbackAvailable("3.4.1.23-global", "3.0.5.14"))
        assertTrue(factoryThemeManagerRollbackAvailable("3.0.6.8-global", "3.0.4.32"))
        assertFalse(factoryThemeManagerRollbackAvailable("3.0.5.14", "3.0.5.14"))
        assertFalse(factoryThemeManagerRollbackAvailable("3.4.1.23-global", "3.0.6.8"))
    }
}
