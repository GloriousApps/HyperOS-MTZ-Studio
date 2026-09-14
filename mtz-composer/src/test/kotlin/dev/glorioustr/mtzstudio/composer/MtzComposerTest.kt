package dev.glorioustr.mtzstudio.composer

import dev.glorioustr.mtzstudio.core.ComponentCategory
import dev.glorioustr.mtzstudio.core.MtzParser
import dev.glorioustr.mtzstudio.core.ThemeId
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import dev.glorioustr.mtzstudio.core.ThemeVisualPolicy

class MtzComposerTest {
    @Test fun `default SMS drops base component and stale preview while retaining chosen name and image`() {
        val parser = MtzParser()
        val base = parser.parse(zip("icons" to byteArrayOf(1), "com.android.mms" to byteArrayOf(2),
            "preview/preview_mms_0.jpg" to byteArrayOf(3)))
        val preview = parser.parse(zip("description.xml" to "<theme><title>Jiyan &amp; Minimal</title></theme>".encodeToByteArray(),
            "preview/en_US_mms_0.jpg" to byteArrayOf(4)))
        val request = CompositionRequest(CompositionMetadata("My mix"), listOf(
            ComponentSelection(CompositionSource(ThemeId("preview"), "Jiyan", preview), ComponentCategory.MMS, "", useDefault = true)),
            baseSource = CompositionSource(ThemeId("base"), "Base", base))
        val output = Files.createTempDirectory("mtz-default-test").resolve("mixed.mtz")
        val result = MtzComposer().compose(request, output)
        assertFalse(result.verifiedArchive.components.any { it.category == ComponentCategory.MMS })
        assertTrue(result.verifiedArchive.components.any { it.category == ComponentCategory.ICONS })
        assertEquals("Jiyan & Minimal", ThemeVisualPolicy.defaultSourceName(result.verifiedArchive, ComponentCategory.MMS))
        assertTrue(result.provenance.last().useDefault)
        ZipFile(output.toFile()).use { zip ->
            assertEquals(null, zip.getEntry("com.android.mms"))
            assertEquals(null, zip.getEntry("preview/preview_mms_0.jpg"))
            assertTrue(zip.getInputStream(zip.getEntry("preview/en_US_mms_0.jpg")).readBytes().contentEquals(byteArrayOf(4)))
        }
        val second = MtzComposer().compose(CompositionRequest(CompositionMetadata("Second mix"), emptyList(),
            baseSource = CompositionSource(ThemeId("mixed"), "My mix", result.verifiedArchive)), output.resolveSibling("second.mtz"))
        assertEquals("Jiyan & Minimal", ThemeVisualPolicy.defaultSourceName(second.verifiedArchive, ComponentCategory.MMS))
    }

    @Test fun `default statusbar removes companion plugin but preserves other components`() {
        val parser = MtzParser()
        val base = parser.parse(zip("icons" to byteArrayOf(1), "com.android.systemui" to byteArrayOf(2),
            "miui.systemui.plugin" to byteArrayOf(3)))
        val preview = parser.parse(zip("preview/en_US_statusbar_0.jpg" to byteArrayOf(4)))
        val result = MtzComposer().compose(CompositionRequest(CompositionMetadata("Default bar"), listOf(
            ComponentSelection(CompositionSource(ThemeId("p"), "Preview source", preview), ComponentCategory.SYSTEM_UI, "", true)),
            baseSource = CompositionSource(ThemeId("b"), "Base", base)), Files.createTempDirectory("mtz-default-bar").resolve("bar.mtz"))
        assertEquals(setOf(ComponentCategory.ICONS), result.verifiedArchive.components.map { it.category }.toSet())
    }

    @Test fun `default mode cannot silently discard a real source component`() {
        val archive = MtzParser().parse(zip("com.android.mms" to byteArrayOf(1), "preview/en_US_mms_0.jpg" to byteArrayOf(2)))
        val source = CompositionSource(ThemeId("s"), "Source", archive)
        assertFailsWith<CompositionException> {
            MtzComposer().compose(CompositionRequest(CompositionMetadata("Invalid"), listOf(
                ComponentSelection(source, ComponentCategory.MMS, "", true)), baseSource = source),
                Files.createTempDirectory("mtz-default-invalid").resolve("invalid.mtz"))
        }
    }

    @Test
    fun `packages an active font as a verified font MTZ`() {
        val directory = Files.createTempDirectory("mtz-font-export-test")
        val font = directory.resolve("Current Font.ttf")
        Files.write(font, byteArrayOf(0, 1, 0, 0, 7, 8, 9))
        val preview = directory.resolve("font-preview.png")
        Files.write(preview, byteArrayOf(1, 2, 3))

        val result = MtzComposer().composeFont(
            FontExportRequest(
                metadata = CompositionMetadata(name = "Active device font"),
                fontFile = font,
                archiveFileName = font.fileName.toString(),
                previewFile = preview,
            ),
            directory.resolve("active-font.mtz"),
        )

        assertEquals(setOf(ComponentCategory.FONT), result.verifiedArchive.components.map { it.category }.toSet())
        ZipFile(result.output.toFile()).use { zip ->
            assertTrue(zip.getEntry("fonts/Current-Font.ttf") != null)
            assertTrue(zip.getEntry("preview/preview_fonts_0.png") != null)
            val xml = zip.getInputStream(zip.getEntry("description.xml")).bufferedReader().readText()
            assertTrue("<fontWeight>" in xml)
        }
    }

    @Test
    fun `composes one selected component for a single-source custom theme`() {
        val parser = MtzParser()
        val source = parser.parse(zip("icons" to byteArrayOf(1, 2, 3)))
        val request = CompositionRequest(
            metadata = CompositionMetadata(name = "Tek Kaynak"),
            selections = listOf(selection("one", source, ComponentCategory.ICONS)),
        )

        val result = MtzComposer(parser).compose(
            request,
            Files.createTempDirectory("mtz-single-compose-test").resolve("single.mtz"),
        )

        assertEquals(setOf(ComponentCategory.ICONS), result.verifiedArchive.components.map { it.category }.toSet())
    }

    @Test
    fun `stores generated gallery preview inside composed MTZ`() {
        val parser = MtzParser()
        val source = parser.parse(zip("icons" to byteArrayOf(1, 2, 3)))
        val preview = byteArrayOf(9, 8, 7, 6)
        val request = CompositionRequest(
            metadata = CompositionMetadata(name = "Preview theme"),
            selections = listOf(selection("one", source, ComponentCategory.ICONS)),
            generatedPreviewBytes = preview,
        )

        val result = MtzComposer(parser).compose(
            request,
            Files.createTempDirectory("mtz-preview-compose-test").resolve("preview.mtz"),
        )

        ZipFile(result.output.toFile()).use { zip ->
            val entry = zip.getEntry("preview/preview_launcher_0.jpg")
            assertTrue(entry != null)
            assertTrue(zip.getInputStream(entry).readBytes().contentEquals(preview))
            val markerEntry = zip.getEntry("preview/mtz_studio_generated.jpg")
            assertTrue(markerEntry != null)
            assertTrue(zip.getInputStream(markerEntry).readBytes().contentEquals(preview))
        }
    }

    @Test
    fun `base theme keeps every unchanged root while selected categories override it`() {
        val parser = MtzParser()
        val base = parser.parse(
            zip(
                "description.xml" to """
                    <theme>
                      <title>Circle</title>
                      <uiVersion>17</uiVersion>
                      <author>VedaT</author>
                      <designer>Circle Designer</designer>
                      <miuiAdapterVersion>4.2</miuiAdapterVersion>
                    </theme>
                """.trimIndent().encodeToByteArray(),
                "icons" to byteArrayOf(1),
                "com.android.systemui" to byteArrayOf(2),
                "miui.systemui.plugin" to byteArrayOf(3),
                "framework-miui-res" to byteArrayOf(4),
                "com.android.settings" to byteArrayOf(5),
                "rights/rights.xml" to byteArrayOf(9),
            ),
        )
        val icons = parser.parse(zip("icons" to byteArrayOf(7)))
        val font = parser.parse(zip("fonts/Roboto-Regular.ttf" to byteArrayOf(8)))
        val request = CompositionRequest(
            metadata = CompositionMetadata(name = "Inherited mix"),
            baseSource = CompositionSource(ThemeId("base"), "Circle", base),
            selections = listOf(
                selection("icons", icons, ComponentCategory.ICONS),
                selection("font", font, ComponentCategory.FONT),
            ),
        )

        val result = MtzComposer(parser).compose(
            request,
            Files.createTempDirectory("mtz-base-compose-test").resolve("inherited.mtz"),
        )

        ZipFile(result.output.toFile()).use { output ->
            assertTrue(output.getInputStream(output.getEntry("icons")).readBytes().contentEquals(byteArrayOf(7)))
            assertTrue(output.getInputStream(output.getEntry("fonts/Roboto-Regular.ttf")).readBytes().contentEquals(byteArrayOf(8)))
            assertTrue(output.getInputStream(output.getEntry("com.android.systemui")).readBytes().contentEquals(byteArrayOf(2)))
            assertTrue(output.getInputStream(output.getEntry("miui.systemui.plugin")).readBytes().contentEquals(byteArrayOf(3)))
            assertTrue(output.getInputStream(output.getEntry("framework-miui-res")).readBytes().contentEquals(byteArrayOf(4)))
            assertTrue(output.getInputStream(output.getEntry("com.android.settings")).readBytes().contentEquals(byteArrayOf(5)))
            assertTrue(output.getEntry("rights/rights.xml") == null)
            val description = output.getInputStream(output.getEntry("description.xml")).bufferedReader().readText()
            assertTrue("<uiVersion>17</uiVersion>" in description)
            assertTrue("<miuiAdapterVersion>4.2</miuiAdapterVersion>" in description)
            assertTrue("<author>VedaT</author>" in description)
            assertTrue("<designer>Circle Designer</designer>" in description)
        }
    }

    @Test
    fun `custom maker replaces inherited author and designer metadata`() {
        val parser = MtzParser()
        val base = parser.parse(
            zip(
                "description.xml" to """
                    <theme>
                      <title>Base</title>
                      <author>Original Author</author>
                      <designer>Original Designer</designer>
                    </theme>
                """.trimIndent().encodeToByteArray(),
                "icons" to byteArrayOf(1),
            ),
        )
        val request = CompositionRequest(
            metadata = CompositionMetadata(
                name = "Renamed",
                author = "New Maker",
                designer = "New Maker",
            ),
            baseSource = CompositionSource(ThemeId("base"), "Base", base),
            selections = emptyList(),
        )

        val result = MtzComposer(parser).compose(
            request,
            Files.createTempDirectory("mtz-maker-compose-test").resolve("maker.mtz"),
        )

        ZipFile(result.output.toFile()).use { output ->
            val description = output.getInputStream(output.getEntry("description.xml")).bufferedReader().readText()
            assertTrue("<author>New Maker</author>" in description)
            assertTrue("<designer>New Maker</designer>" in description)
            assertFalse("Original Author" in description)
            assertFalse("Original Designer" in description)
        }
    }

    @Test
    fun `composes two themes deterministically excludes rights and reopens output`() {
        val parser = MtzParser()
        val first = parser.parse(zip("icons" to byteArrayOf(1, 2), "rights/rights.xml" to byteArrayOf(9)))
        val second = parser.parse(zip("wallpaper/home.jpg" to byteArrayOf(3, 4)))
        val request = CompositionRequest(
            metadata = CompositionMetadata(name = "My <Theme>"),
            selections = listOf(
                selection("one", first, ComponentCategory.ICONS),
                selection("two", second, ComponentCategory.WALLPAPER),
            ),
        )
        val directory = Files.createTempDirectory("mtz-compose-test")

        val one = MtzComposer(parser).compose(request, directory.resolve("one.mtz"))
        val two = MtzComposer(parser).compose(request, directory.resolve("two.mtz"))

        assertEquals(one.outputSha256, two.outputSha256)
        assertEquals(setOf(ComponentCategory.ICONS, ComponentCategory.WALLPAPER), one.verifiedArchive.components.map { it.category }.toSet())
        assertTrue(one.verifiedArchive.rightsEntries.isEmpty())
        ZipFile(one.output.toFile()).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.contains("rights", ignoreCase = true) })
            val xml = zip.getInputStream(zip.getEntry("description.xml")).bufferedReader().readText()
            assertTrue("My &lt;Theme&gt;" in xml)
        }
    }

    @Test
    fun `copies a clock 2x4 widget from its selected source`() {
        val parser = MtzParser()
        val widgetTheme = parser.parse(zip("clock_2x4" to byteArrayOf(80, 75, 3, 4, 9, 8, 7)))
        val request = CompositionRequest(
            metadata = CompositionMetadata(name = "Widget test"),
            selections = listOf(selection("widget", widgetTheme, ComponentCategory.WIDGET)),
        )

        val result = MtzComposer(parser).compose(
            request,
            Files.createTempDirectory("mtz-widget-compose-test").resolve("widget.mtz"),
        )

        ZipFile(result.output.toFile()).use { output ->
            assertTrue(output.getEntry("clock_2x4") != null)
        }
        assertTrue(result.verifiedArchive.components.any { it.category == ComponentCategory.WIDGET })
    }

    private fun selection(id: String, archive: dev.glorioustr.mtzstudio.core.MtzArchive, category: ComponentCategory): ComponentSelection {
        val component = archive.components.single { it.category == category }
        return ComponentSelection(
            source = CompositionSource(ThemeId(id), id, archive),
            category = category,
            rootPath = component.rootPath,
        )
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): Path {
        val path = Files.createTempFile("mtz-compose-source", ".mtz")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return path
    }
}
