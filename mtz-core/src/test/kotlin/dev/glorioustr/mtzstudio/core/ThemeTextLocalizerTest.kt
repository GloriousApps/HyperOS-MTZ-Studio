package dev.glorioustr.mtzstudio.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class ThemeTextLocalizerTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out -> entries.forEach { (name, data) ->
            out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry()
        } }
    }.toByteArray()
    private fun archive(bytes: ByteArray): Pair<Path, Path> {
        val dir = Files.createTempDirectory("translation-test")
        return Files.write(dir.resolve("source.mtz"), bytes) to dir.resolve("output.mtz")
    }
    private fun nested(path: Path, name: String): ByteArray = ZipFile(path.toFile()).use { z -> z.getInputStream(z.getEntry(name)).use { it.readBytes() } }
    private fun entry(bytes: ByteArray, name: String): ByteArray = ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val item = zip.nextEntry ?: error("Missing $name")
            if (item.name == name) return zip.readBytes()
        }
        error("Unreachable")
    }

    @Test fun `translates nested extensionless packages and preserves scripts and images`() {
        val xml = """<Root><Text name="中文标识" text="壁纸设置" textExp="'壁纸'+#number"/><Var name="id" expression="'中文代码'"/><Image src="中文.png"/><Group text="壁纸设置"/></Root>"""
        val image = byteArrayOf(1, 4, 7, 9)
        val (source, output) = archive(zip("lockscreen" to zip("advance/manifest.xml" to xml.toByteArray(), "中文.png" to image)))
        var calls = 0
        val result = ThemeTextLocalizer().rewrite(source, output) { calls++; "Translated & \"quoted\"" }
        assertEquals(3, result.translatedNodes)
        assertEquals(2, calls)
        assertEquals(listOf("lockscreen!/advance/manifest.xml"), result.changedFiles)
        val lock = nested(output, "lockscreen")
        assertContentEquals(image, entry(lock, "中文.png"))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(entry(lock, "advance/manifest.xml").inputStream())
        val text = doc.getElementsByTagName("Text").item(0) as org.w3c.dom.Element
        assertEquals("中文标识", text.getAttribute("name"))
        assertEquals("Translated & \"quoted\"", text.getAttribute("text"))
        assertTrue(text.getAttribute("textExp").endsWith("+#number"))
        assertEquals("'中文代码'", (doc.getElementsByTagName("Var").item(0) as org.w3c.dom.Element).getAttribute("expression"))
    }

    @Test fun `unchanged nested packages remain byte identical`() {
        val original = zip("manifest.xml" to "<Text text='Hello'/>".toByteArray())
        val (source, output) = archive(zip("lockscreen" to original))
        val result = ThemeTextLocalizer().rewrite(source, output) { error("No download or translation needed") }
        assertEquals(0, result.translatedNodes)
        assertContentEquals(original, nested(output, "lockscreen"))
    }

    @Test fun `translates deep extensionless left and right component resources`() {
        val component = """<Root><Text text="完成"/><Text text="自定义"/><Text text="添加小组件"/></Root>""".toByteArray()
        val deep = zip("left_component" to zip("right_component" to zip("editor_component" to zip("widget_shell" to zip("resource_pack" to zip("layout" to component))))))
        val (source, output) = archive(zip("lockscreen" to deep))
        val seen = mutableListOf<String>()
        val result = ThemeTextLocalizer().rewrite(source, output) { seen += it; "çeviri" }

        assertEquals(listOf("完成", "自定义", "添加小组件"), seen)
        assertEquals(3, result.translatedNodes)
        val lockscreen = nested(output, "lockscreen")
        val left = entry(lockscreen, "left_component")
        val right = entry(left, "right_component")
        val editor = entry(right, "editor_component")
        val shell = entry(editor, "widget_shell")
        val resources = entry(shell, "resource_pack")
        assertTrue(entry(resources, "layout").toString(Charsets.UTF_8).contains("çeviri"))
    }

    @Test fun `replaces lock screen bitmap labels with safe MAML text overlays`() {
        val xml = """<Root>
            <Image x="50" y="160" w="190" h="90" src="menu/exit_btn.png" touchable="true"/>
            <Image x="#screen_width-50" y="160" w="190" h="90" align="right" src="menu/setting_btn.png"/>
            <Image x="#screen_width/2" y="0" align="center" alignV="center" src="menu/add_widget.webp" visibility="#widget_on==0"/>
        </Root>""".toByteArray()
        val (source, output) = archive(zip("lockscreen" to zip("advance/manifest.xml" to xml)))

        val result = ThemeTextLocalizer().rewrite(source, output) { value ->
            mapOf("完成" to "Tamam", "自定义" to "Özelleştir", "添加小组件" to "Bileşen ekle").getValue(value)
        }

        assertEquals(3, result.translatedNodes)
        val lockscreen = nested(output, "lockscreen")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(entry(lockscreen, "advance/manifest.xml").inputStream())
        val images = doc.getElementsByTagName("Image")
        assertEquals("menu/exit_btn_mask.png", (images.item(0) as org.w3c.dom.Element).getAttribute("src"))
        assertEquals("menu/exit_btn_mask.png", (images.item(1) as org.w3c.dom.Element).getAttribute("src"))
        assertEquals("0", (images.item(2) as org.w3c.dom.Element).getAttribute("visibility"))
        val labels = doc.getElementsByTagName("Text")
        assertEquals("Tamam", (labels.item(0) as org.w3c.dom.Element).getAttribute("text"))
        assertEquals("Özelleştir", (labels.item(1) as org.w3c.dom.Element).getAttribute("text"))
        assertEquals("Bileşen ekle", (labels.item(2) as org.w3c.dom.Element).getAttribute("text"))
        assertEquals("#widget_on==0", (labels.item(2) as org.w3c.dom.Element).getAttribute("visibility"))
        val backing = doc.getElementsByTagName("Rectangle").item(0) as org.w3c.dom.Element
        assertEquals("#cc202020", backing.getAttribute("fillColor"))
    }

    @Test fun `hides translated text occluded by baked button artwork and fits editor labels`() {
        val xml = """<Root><Button w="205"><Normal>
            <Text x="100" size="55" text="小组件样式"/><Image src="anniu/sz.png"/>
            </Normal></Button><Var name="select_bg_light_ani"/><Var name="select_text_color" expression="'#ffffffff'"/>
            <Image src="menu/add_widget.webp" visibility="#widget_on==0"/></Root>""".toByteArray()
        val (source, output) = archive(zip("lockscreen" to zip("advance/manifest.xml" to xml)))
        ThemeTextLocalizer().rewrite(source, output) {
            when (it) { "小组件样式" -> "Widget'lar"; "添加小组件" -> "Bileşen ekle"; else -> it }
        }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(entry(nested(output, "lockscreen"), "advance/manifest.xml").inputStream())
        val label = doc.getElementsByTagName("Text").item(0) as org.w3c.dom.Element
        assertEquals("0", label.getAttribute("visibility"))
        assertTrue(label.getAttribute("size").toInt() < 55)
        val variables = doc.getElementsByTagName("Var")
        assertTrue((0 until variables.length).any { index ->
            val variable = variables.item(index) as org.w3c.dom.Element
            variable.getAttribute("name") == "select_text_color" && variable.getAttribute("expression").contains("select_bg_light_ani")
        })
    }

    @Test fun `preserves MAML printf placeholders in translated format expressions`() {
        val xml = """<Root><Text formatExp="'已使用主题%d天 | 版本号：20260910'" paras="#days"/></Root>"""
        val (source, output) = archive(zip("manifest.xml" to xml.toByteArray()))

        ThemeTextLocalizer().rewrite(source, output) { value ->
            value.replace("已使用主题", "Tema kullanım süresi: ")
                .replace("天 | 版本号：20260910", " gün | sürüm: 20260910")
        }

        val rewritten = nested(output, "manifest.xml").toString(Charsets.UTF_8)
        assertTrue(rewritten.contains("Tema kullanımı: %d gün | Sürüm: 20260910"), rewritten)
        assertFalse(rewritten.contains("% D"), rewritten)
    }

    @Test fun `multilingual mode translates safe display text from different scripts`() {
        val xml = """<Root><Text text="Customize"/><Text text="Настройки"/><Text text="إعدادات"/><Text text="カスタマイズ"/><Var name="code" expression="'Настройки'"/><Image src="Настройки.png"/></Root>"""
        val (source, output) = archive(zip("manifest.xml" to xml.toByteArray()))
        val seen = mutableListOf<String>()
        val result = ThemeTextLocalizer(translateAllDisplayText = true).rewrite(source, output) {
            seen += it
            "localized"
        }
        assertEquals(4, result.translatedNodes)
        assertEquals(listOf("Customize", "Настройки", "إعدادات", "カスタマイズ"), seen)
        val rewritten = nested(output, "manifest.xml").toString(Charsets.UTF_8)
        assertTrue(rewritten.contains("expression=\"'Настройки'\""))
        assertTrue(rewritten.contains("src=\"Настройки.png\""))
    }

    @Test fun `multilingual mode ignores paths colors numbers and ordinary date token patterns`() {
        val xml = """<Root><Text text="https://example.com/theme"/><Text text="#AABBCC"/><Text text="24dp"/><DateTime format="dd/MM/yyyy"/></Root>"""
        val (source, output) = archive(zip("manifest.xml" to xml.toByteArray()))
        val result = ThemeTextLocalizer(translateAllDisplayText = true).rewrite(source, output) {
            error("Non-display values must not reach translation")
        }
        assertEquals(0, result.translatedNodes)
    }

    @Test fun `handles encoded XML text and translates dynamic display expressions`() {
        val xml = """<Root><!-- 中文注释 --><string name="id">&#x58C1;&#x7EB8;</string><Text textExp="formatDate('M月d日',#time)"/><Text textExp="ifelse(eqs(@a,'中文'),'是','否')"/></Root>"""
        val (source, output) = archive(zip("config.xml" to xml.toByteArray()))
        val result = ThemeTextLocalizer().rewrite(source, output) { "Duvar kağıdı" }
        assertTrue(result.translatedNodes >= 3)
        val rewritten = nested(output, "config.xml").toString(Charsets.UTF_8)
        assertTrue(rewritten.contains("中文注释"))
        assertFalse(rewritten.contains("M月d日"))
        assertTrue(rewritten.contains("formatDate('MMMM'"))
        assertTrue(rewritten.contains("eqs(@a,'中文')"))
    }

    @Test fun `translates description xml and json configuration files`() {
        val descXml = """<theme><title>经典主题</title><description>精美壁纸</description></theme>"""
        val configJson = """{"title": "时钟样式", "options": ["简约", "数字"]}"""
        val (source, output) = archive(zip("description.xml" to descXml.toByteArray(), "config.json" to configJson.toByteArray()))
        val result = ThemeTextLocalizer().rewrite(source, output) { "Translated" }
        assertEquals(3, result.translatedNodes)
        val desc = nested(output, "description.xml").toString(Charsets.UTF_8)
        assertTrue(desc.contains("<title>Translated</title>"))
        assertTrue(desc.contains("<description>Translated</description>"))
        val json = nested(output, "config.json").toString(Charsets.UTF_8)
        assertTrue(json.contains("\"title\": \"Translated\""))
        assertTrue(json.contains("\"简约\", \"数字\"")) // Unknown option values may be control IDs.
    }

    @Test fun `rejects external entities without changing source`() {
        val original = zip("manifest.xml" to """<!DOCTYPE r [<!ENTITY x SYSTEM "file:///private">]><r text="中文"/>""".toByteArray())
        val (source, output) = archive(original)
        assertFails { ThemeTextLocalizer().rewrite(source, output) { "translated" } }
        assertContentEquals(original, Files.readAllBytes(source))
        assertFalse(Files.exists(output))
    }

    @Test fun `limits expansion and nesting`() {
        val (source, output) = archive(zip("lockscreen" to zip("manifest.xml" to "<Text text='中文'/>".toByteArray())))
        assertFails { ThemeTextLocalizer(maxDepth = 0).rewrite(source, output) { "x" } }
        assertFails { ThemeTextLocalizer(maxExpandedBytes = 10).rewrite(source, output) { "x" } }
        assertFalse(Files.exists(output))
        Files.list(output.parent).use { paths -> assertEquals(1L, paths.count()) }
    }

    @Test fun `failure does not modify original archive`() {
        val original = zip("manifest.xml" to "<Text text='中文'/>".toByteArray())
        val (source, output) = archive(original)
        assertFails { ThemeTextLocalizer().rewrite(source, output) { error("Model download failed") } }
        assertContentEquals(original, Files.readAllBytes(source))
        assertFalse(Files.exists(output))
    }

    @Test fun `optional real device fixture includes wallpaper settings in nested lockscreen`() {
        val fixture = System.getenv("MTZ_TRANSLATION_FIXTURE") ?: return
        val output = Files.createTempDirectory("real-theme-translation").resolve("output.mtz")
        // Also exercise the production import limits. Some current themes contain a single
        // extensionless icons component larger than the historical 128 MiB ceiling.
        MtzParser().parse(Path.of(fixture))
        val unknown = sortedSetOf<String>()
        val result = ThemeTextLocalizer().rewrite(Path.of(fixture), output) {
            ThemeGlossary.resolve(it, "tr") ?: "Translated".also { _ -> unknown += it }
        }
        assertTrue(result.translatedNodes > 100, result.toString())
        assertTrue(result.changedFiles.contains("lockscreen!/advance/manifest.xml"), result.toString())
        val lock = nested(output, "lockscreen")
        val xml = entry(lock, "advance/manifest.xml").toString(Charsets.UTF_8)
        assertFalse(xml.contains("text=\"壁纸设置\""))
        println("Real theme: ${result.translatedNodes} translated nodes, ${result.changedFiles.size} changed XML files")
        println("Needs model: " + unknown.joinToString("\n"))
    }

    @Test fun `JSON preserves keys IDs paths and embedded quoted text`() {
        val json = """{"中文键":"中文值","path":"中文.png","payload":"\"title\":\"中文\"","text":"\u58c1\u7eb8","labels":["中文"]}"""
        val (source, output) = archive(zip("config.json" to json.toByteArray()))
        ThemeTextLocalizer().rewrite(source, output) { "Duvar\nkağıdı" }
        val rewritten = nested(output, "config.json").toString(Charsets.UTF_8)
        assertTrue(rewritten.contains("\"path\":\"中文.png\""))
        assertTrue(rewritten.contains("\"中文键\":\"中文值\""))
        assertTrue(rewritten.contains("\\\"title\\\":\\\"中文\\\""))
        assertTrue(rewritten.contains("Duvar\\u000akağıdı"))
    }
}
