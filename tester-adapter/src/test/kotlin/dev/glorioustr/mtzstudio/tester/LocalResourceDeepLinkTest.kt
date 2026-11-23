package dev.glorioustr.mtzstudio.tester

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalResourceDeepLinkTest {

    private fun declaration(
        declared: Boolean = true,
        exported: Boolean = true,
        enabled: Boolean = true,
        requiredPermission: String? = null,
        schemeFilterPresent: Boolean = true,
    ) = LocalResourceDetailDeclaration(
        declared = declared,
        exported = exported,
        enabled = enabled,
        requiredPermission = requiredPermission,
        schemeFilterPresent = schemeFilterPresent,
    )

    @Test
    fun `the 3_4_8_36 shape is usable`() {
        assertTrue(declaration().usable)
    }

    @Test
    fun `a permission or a missing declaration blocks the deep link`() {
        assertFalse(declaration(requiredPermission = "miui.permission.USE_INTERNAL_GENERAL_API").usable)
        assertFalse(declaration(declared = false).usable)
        assertFalse(declaration(exported = false).usable)
        assertFalse(declaration(enabled = false).usable)
    }

    @Test
    fun `the explicit component does not need the published scheme filter`() {
        // The filter is reported for diagnostics; starting an explicit component never matches it.
        assertTrue(declaration(schemeFilterPresent = false).usable)
    }

    @Test
    fun `an unsafe local id is refused before any intent is built`() {
        assertNull(LocalResourceDetailContract.detailIntent(""))
        assertNull(LocalResourceDetailContract.detailIntent("../../etc/passwd"))
        assertNull(LocalResourceDetailContract.detailIntent("has space"))
        assertNull(LocalResourceDetailContract.detailIntent("a/b"))
        assertNull(LocalResourceDetailContract.detailIntent("x".repeat(129)))
    }

    @Test
    fun `the contract stays on the surface verified in the APK`() {
        assertEquals("com.android.thememanager", LocalResourceDetailContract.PACKAGE_NAME)
        assertEquals(
            "com.android.thememanager.activity.ThemeDetailActivity",
            LocalResourceDetailContract.DETAIL_COMPONENT,
        )
        assertEquals("ViewLocalResource", LocalResourceDetailContract.SCHEME)
        assertEquals("view.local.resource", LocalResourceDetailContract.HOST)
        assertEquals("REQUEST_RESOURCE_CODE", LocalResourceDetailContract.EXTRA_RESOURCE_CODE)
        assertEquals("REQUEST_APPLY_EVENT", LocalResourceDetailContract.EXTRA_APPLY_EVENT)
        assertEquals("theme", LocalResourceDetailContract.RESOURCE_CODE_THEME)
    }
}
