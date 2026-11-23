package dev.glorioustr.mtzstudio.tester

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeServiceAvailabilityTest {

    private fun declaration(
        declared: Boolean = true,
        exported: Boolean = true,
        enabled: Boolean = true,
        requiredPermission: String? = null,
        actionResolves: Boolean = true,
        interfaceAvailable: Boolean = true,
    ) = ThemeServiceDeclaration(
        declared = declared,
        exported = exported,
        enabled = enabled,
        requiredPermission = requiredPermission,
        actionResolves = actionResolves,
        interfaceAvailable = interfaceAvailable,
    )

    @Test
    fun `the 3_4_8_36 shape is usable`() {
        // Exported, enabled, no android:permission, and the ROM ships the MIUI interface.
        assertTrue(declaration().usable)
    }

    @Test
    fun `a permission on the service makes it unusable for an unprivileged caller`() {
        assertFalse(declaration(requiredPermission = "miui.permission.USE_INTERNAL_GENERAL_API").usable)
    }

    @Test
    fun `a missing, unexported or disabled service is never usable`() {
        assertFalse(declaration(declared = false).usable)
        assertFalse(declaration(exported = false).usable)
        assertFalse(declaration(enabled = false).usable)
    }

    @Test
    fun `a ROM without the hidden MIUI interface is not usable`() {
        // Transaction codes are never guessed, so without IThemeService there is no call to make.
        assertFalse(declaration(interfaceAvailable = false).usable)
    }

    @Test
    fun `intent resolution alone does not make the service usable`() {
        // Some HyperOS PackageManager builds resolve an explicit component the APK never declares.
        assertFalse(declaration(declared = false, actionResolves = true).usable)
    }

    @Test
    fun `the contract stays on the component verified in the APK`() {
        assertEquals("com.android.thememanager", ThemeServiceContract.PACKAGE_NAME)
        assertEquals("com.android.thememanager.service.ThemeService", ThemeServiceContract.SERVICE_CLASS)
        assertEquals("com.miui.service.THEMESERVICE", ThemeServiceContract.ACTION)
        assertEquals("miui.content.res.IThemeService", ThemeServiceContract.INTERFACE_CLASS)
        assertEquals("miui.content.res.IThemeService\$Stub", ThemeServiceContract.STUB_CLASS)
        assertEquals("saveWallpaper", ThemeServiceContract.SAVE_WALLPAPER)
        assertEquals("saveLockWallpaper", ThemeServiceContract.SAVE_LOCK_WALLPAPER)
        assertEquals("saveCustomizedIcon", ThemeServiceContract.SAVE_CUSTOMIZED_ICON)
    }

    @Test
    fun `the unavailable constant is never usable`() {
        assertFalse(ThemeServiceDeclaration.UNAVAILABLE.usable)
    }
}
