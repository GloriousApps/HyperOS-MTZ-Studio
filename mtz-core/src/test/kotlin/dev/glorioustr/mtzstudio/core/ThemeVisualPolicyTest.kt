package dev.glorioustr.mtzstudio.core

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeVisualPolicyTest {
    private fun entry(path: String, size: Long = 32, directory: Boolean = false) =
        MtzEntry(path, size, size, 0, directory, false)

    private fun archive(vararg categories: ComponentCategory) = MtzArchive(
        source = java.nio.file.Path.of("fixture.mtz"),
        sha256 = "fixture",
        metadata = null,
        entries = emptyList(),
        components = categories.map { ThemeComponent(it, it.name, listOf(it.name), 1) },
        rightsEntries = emptyList(),
        expandedBytes = categories.size.toLong(),
    )

    @Test fun `font only package is separated from the complete theme gallery`() {
        assertTrue(ThemeVisualPolicy.isFontOnly(archive(ComponentCategory.FONT)))
        assertFalse(ThemeVisualPolicy.isFontOnly(archive(ComponentCategory.FONT, ComponentCategory.ICONS)))
        assertFalse(ThemeVisualPolicy.isFontOnly(archive()))
    }

    @Test fun `localized statusbar images outrank all launcher and default covers`() {
        val entries = listOf(entry("preview/preview_launcher_0.jpg"), entry("preview/en_US_launcher_0.jpg"),
            entry("preview/en_US_statusbar_1.jpg"), entry("preview/en_US_statusbar_0.jpg"),
            entry("wallpaper/default_wallpaper.jpg"))
        assertEquals(listOf("preview/en_US_statusbar_0.jpg", "preview/en_US_statusbar_1.jpg"),
            ThemeVisualPolicy.categoryWithFallback(entries, ComponentCategory.SYSTEM_UI).take(2))
    }

    @Test fun `localized MMS image outranks launcher even without MMS component`() {
        val entries = listOf(entry("preview/preview_launcher_0.jpg"), entry("preview/en_US_mms_0.jpg"))
        assertEquals("preview/en_US_mms_0.jpg", ThemeVisualPolicy.categoryWithFallback(entries, ComponentCategory.MMS).first())
        assertTrue(ThemeVisualPolicy.isPreviewOnly(emptyList(), entries, ComponentCategory.MMS))
    }

    @Test fun `generic cover alone does not create a preview-only source`() {
        val entries = listOf(entry("preview/en_US_launcher_0.jpg"), entry("wallpaper/default_wallpaper.jpg"))
        assertFalse(ThemeVisualPolicy.isPreviewOnly(emptyList(), entries, ComponentCategory.MMS))
        assertTrue(ThemeVisualPolicy.categoryPreviewPaths(entries, ComponentCategory.SYSTEM_UI).isEmpty())
    }

    @Test fun `real component remains selectable with or without image`() {
        val components = listOf(ThemeComponent(ComponentCategory.MMS, "com.android.mms", listOf("com.android.mms"), 10))
        assertFalse(ThemeVisualPolicy.isPreviewOnly(components, listOf(entry("preview/en_US_mms_0.jpg")), ComponentCategory.MMS))
        assertFalse(ThemeVisualPolicy.isPreviewOnly(components, emptyList(), ComponentCategory.MMS))
    }

    @Test fun `category names must be distinct filename tokens`() {
        val entries = listOf(entry("preview/en_US_phonewallpaper_0.jpg"), entry("preview/en_US_contact_0.jpg"))
        assertEquals(listOf("preview/en_US_contact_0.jpg"), ThemeVisualPolicy.categoryPreviewPaths(entries, ComponentCategory.CONTACTS))
    }

    @Test fun `all nine sections exist independently of preview files`() {
        assertEquals(9, ThemeVisualPolicy.personalizationCategories.distinct().size)
        assertTrue(ComponentCategory.FONT in ThemeVisualPolicy.personalizationCategories)
        assertTrue(ComponentCategory.AOD in ThemeVisualPolicy.personalizationCategories)
        assertTrue(ComponentCategory.WIDGET in ThemeVisualPolicy.personalizationCategories)
        assertFalse(ComponentCategory.WALLPAPER in ThemeVisualPolicy.personalizationCategories)
        assertTrue(ThemeVisualPolicy.defaultPreviewPaths(emptyList()).isEmpty())
    }

    @Test fun `specific image takes priority with default cover retained for decode fallback`() {
        val images = listOf(entry("preview/icons.png"), entry("wallpaper/default_wallpaper.jpg"))
        assertEquals(listOf("preview/icons.png", "wallpaper/default_wallpaper.jpg"),
            ThemeVisualPolicy.previewPaths(images, listOf("preview/icons.png"), listOf("icons")))
    }

    @Test fun `missing category image falls back to general wallpaper`() {
        assertEquals(listOf("wallpaper/default_wallpaper.jpg"), ThemeVisualPolicy.previewPaths(
            listOf(entry("wallpaper/default_wallpaper.jpg")), listOf("preview/icons.jpg"), listOf("icons")))
    }

    @Test fun `alternate cover names are accepted and deduplicated`() {
        val entries = listOf(entry("Cover.PNG"), entry("previews/cover.webp"))
        assertEquals(listOf("Cover.PNG", "previews/cover.webp"),
            ThemeVisualPolicy.previewPaths(entries, listOf("cover.png"), listOf("cover")))
    }

    @Test fun `empty oversized directory and nonimage entries are ignored`() {
        assertTrue(ThemeVisualPolicy.defaultPreviewPaths(listOf(
            entry("preview/empty.jpg", 0), entry("preview/huge.png", 17L * 1024 * 1024),
            entry("preview/folder.jpg", directory = true), entry("preview/not-image.txt"),
        )).isEmpty())
    }

    @Test fun `Jiyan shaped archive keeps six actual components despite no preview directory`() {
        val path = Files.createTempFile("mtz-no-previews", ".mtz")
        try {
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                listOf("icons", "lockscreen", "com.android.systemui", "clock_2x4", "miui.systemui.plugin",
                    "com.android.contacts", "com.android.incallui", "com.android.mms", "com.miui.home",
                    "wallpaper/default_wallpaper.jpg", "wallpaper/default_lock_wallpaper.jpg").forEach {
                    zip.putNextEntry(ZipEntry(it)); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
                }
            }
            val archive = MtzParser().parse(path)
            val actual = archive.components.map { it.category }.toSet()
                .intersect(ThemeVisualPolicy.personalizationCategories.toSet())
            assertEquals(setOf(ComponentCategory.ICONS, ComponentCategory.LOCKSCREEN,
                ComponentCategory.SYSTEM_UI, ComponentCategory.WIDGET, ComponentCategory.CONTACTS, ComponentCategory.MMS,
                ComponentCategory.LAUNCHER), actual)
            assertEquals("wallpaper/default_wallpaper.jpg", ThemeVisualPolicy.defaultPreviewPaths(archive.entries).first())
        } finally { Files.deleteIfExists(path) }
    }
}
