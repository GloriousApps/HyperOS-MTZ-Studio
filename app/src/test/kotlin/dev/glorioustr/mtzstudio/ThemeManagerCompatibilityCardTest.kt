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
}
