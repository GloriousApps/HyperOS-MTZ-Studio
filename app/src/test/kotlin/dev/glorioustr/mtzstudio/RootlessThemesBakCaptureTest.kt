package dev.glorioustr.mtzstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class RootlessThemesBakCaptureTest {
    private val hash = "a".repeat(64)
    private val path = "/sdcard/Download/MTZ Studio/Backups/mtzstudio-themes-id.tar"

    @Test fun `accepts standard sha256sum output with two spaces`() {
        assertEquals(hash, parseThemesArchiveHash("$hash  $path\n", path))
    }

    @Test fun `accepts sha256sum binary marker and ignores diagnostic lines`() {
        assertEquals(hash, parseThemesArchiveHash("warning\n$hash *$path\n", path))
    }

    @Test fun `rejects a checksum for another path`() {
        assertNull(parseThemesArchiveHash("$hash  /sdcard/other.tar", path))
    }

    @Test fun `accepts Xiaomi root metadata without resourceCode`() {
        assertTrue(rootThemeMetadataMatches(JSONObject("{\"localId\":\"theme-1\"}"), "theme-1"))
        assertTrue(rootThemeMetadataMatches(JSONObject("{\"localId\":\"theme-1\",\"resourceCode\":\"theme\"}"), "theme-1"))
        assertFalse(rootThemeMetadataMatches(JSONObject("{\"localId\":\"theme-1\",\"resourceCode\":\"icons\"}"), "theme-1"))
    }
}
