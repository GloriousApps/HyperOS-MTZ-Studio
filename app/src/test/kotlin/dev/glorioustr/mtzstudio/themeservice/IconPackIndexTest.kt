package dev.glorioustr.mtzstudio.themeservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPackIndexTest {

    @Test
    fun findsTheIconMapEntryWhateverItIsCalled() {
        assertEquals("icon_map", IconPackIndex.iconMapEntry(listOf("res/a.png", "icon_map")))
        assertEquals("ICON_MAP.xml", IconPackIndex.iconMapEntry(listOf("ICON_MAP.xml")))
        assertEquals("a/b/iconmap", IconPackIndex.iconMapEntry(listOf("a/b/iconmap")))
        assertNull(IconPackIndex.iconMapEntry(listOf("res/drawable-xxhdpi/com.miui.home.png")))
    }

    @Test
    fun anIconMapCollapsesActivityEntriesOntoTheirPackage() {
        val names = listOf(
            "icon_map",
            "res/drawable-xxhdpi/home_icon.png",
            "res/drawable-xxhdpi/settings_icon.png",
        )
        val xml = """
            <MIUI_Icon_Map>
              <item name="com.miui.home/com.miui.home.launcher.Launcher">home_icon</item>
              <item name="com.android.settings/com.android.settings.Settings">settings_icon</item>
            </MIUI_Icon_Map>
        """.trimIndent()

        val index = IconPackIndex.build(names, xml)

        assertEquals(IconPackIndex.Source.ICON_MAP, index.source)
        assertEquals("res/drawable-xxhdpi/home_icon.png", index.byPackage["com.miui.home"])
        assertEquals("res/drawable-xxhdpi/settings_icon.png", index.byPackage["com.android.settings"])
    }

    @Test
    fun theFirstEntryForAPackageWins() {
        // A theme's launcher alias must not overwrite the main icon it already mapped.
        val names = listOf("icon_map", "res/first.png", "res/second.png")
        val xml = """
            <map>
              <item name="com.miui.home/com.miui.home.launcher.Launcher">first</item>
              <item name="com.miui.home/com.miui.home.launcher.Alias">second</item>
            </map>
        """.trimIndent()

        assertEquals("res/first.png", IconPackIndex.build(names, xml).byPackage["com.miui.home"])
    }

    @Test
    fun iconMapItemsPointingAtAMissingDrawableAreDropped() {
        val xml = """<map><item name="com.miui.home/X">nope</item></map>"""

        val index = IconPackIndex.build(listOf("icon_map", "res/other.png"), xml)

        // Nothing mapped, so the fallback layout is attempted and finds no package-named drawable.
        assertTrue(index.isEmpty)
        assertEquals(IconPackIndex.Source.NONE, index.source)
    }

    @Test
    fun fallsBackToPackageNamedDrawables() {
        val names = listOf(
            "res/drawable-xxhdpi/com.miui.home.png",
            "res/drawable-xxhdpi/com.android.settings.webp",
            "res/drawable-xxhdpi/ic_generic_background.png",
            "description.xml",
        )

        val index = IconPackIndex.build(names, null)

        assertEquals(IconPackIndex.Source.PACKAGE_NAMED_DRAWABLES, index.source)
        assertEquals("res/drawable-xxhdpi/com.miui.home.png", index.byPackage["com.miui.home"])
        assertEquals("res/drawable-xxhdpi/com.android.settings.webp", index.byPackage["com.android.settings"])
        // A decorative drawable is not a package name and must not be sent as one.
        assertFalse(index.byPackage.containsKey("ic_generic_background"))
        assertEquals(2, index.byPackage.size)
    }

    @Test
    fun anUnrecognisableArchiveYieldsNothing() {
        val index = IconPackIndex.build(listOf("res/drawable/a.png", "res/drawable/b.png"), null)

        assertTrue(index.isEmpty)
        assertEquals(IconPackIndex.Source.NONE, index.source)
    }

    @Test
    fun packageLikeNamesNeedADottedForm() {
        assertTrue(IconPackIndex.isPackageLike("com.miui.home"))
        assertTrue(IconPackIndex.isPackageLike("a.b"))
        assertFalse(IconPackIndex.isPackageLike("icons"))
        assertFalse(IconPackIndex.isPackageLike("9com.miui.home"))
        assertFalse(IconPackIndex.isPackageLike("com.miui.home/Launcher"))
        assertFalse(IconPackIndex.isPackageLike("../../etc/passwd"))
        assertFalse(IconPackIndex.isPackageLike(""))
    }
}
