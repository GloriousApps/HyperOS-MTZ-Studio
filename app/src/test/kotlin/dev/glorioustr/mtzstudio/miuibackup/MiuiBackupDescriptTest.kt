package dev.glorioustr.mtzstudio.miuibackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiuiBackupDescriptTest {

    private val device = MiuiBackupThemeStager.DeviceDescriptor(
        device = "veux",
        miuiVersion = "OS3.0.301.0.WNRINXM",
        date = 1788815000000L,
        storageLeft = 223834632192L,
    )

    @Test
    fun descriptNamesTheThemePackageAndBakFile() {
        val xml = MiuiBackupThemeStager.buildDescript(64_837_707L, device)

        assertTrue(xml.contains("<packageName>com.android.thememanager</packageName>"))
        assertTrue(xml.contains("<bakFile>Themes(com.android.thememanager).bak</bakFile>"))
        assertTrue(xml.contains("<device>veux</device>"))
        assertTrue(xml.contains("<miuiVersion>OS3.0.301.0.WNRINXM</miuiVersion>"))
        assertTrue(xml.contains("<bakType>1</bakType>"))
    }

    @Test
    fun everySizeFieldCarriesTheBakLength() {
        val size = 64_837_707L
        val xml = MiuiBackupThemeStager.buildDescript(size, device)

        for (tag in listOf("size", "pkgSize", "completedSize", "bakFileSize", "transingTotalSize")) {
            assertTrue("$tag should carry the bak size", xml.contains("<$tag>$size</$tag>"))
        }
    }

    @Test
    fun carriesThePruneMarkerButIsStillWellFormedXmlHeader() {
        val xml = MiuiBackupThemeStager.buildDescript(1L, device)

        assertTrue(xml.startsWith("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"))
        assertTrue(xml.contains("mtzstudio-miui-backup-stage"))
        assertTrue(xml.trimEnd().endsWith("</MIUI-backup>"))
    }

    @Test
    fun escapesXmlSpecialCharactersInDeviceFields() {
        val tricky = device.copy(device = "a<b&c\"d", miuiVersion = "x>y")
        val xml = MiuiBackupThemeStager.buildDescript(1L, tricky)

        assertTrue(xml.contains("<device>a&lt;b&amp;c&quot;d</device>"))
        assertTrue(xml.contains("<miuiVersion>x&gt;y</miuiVersion>"))
        // The raw, unescaped forms must not leak into the element body.
        assertEquals(-1, xml.indexOf("<device>a<b"))
    }
}
