package dev.glorioustr.mtzstudio.core

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class MamlTextTranslatorTest {
    private fun doc(xml: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    private fun translate(s: String) = ThemeGlossary.resolve(s, "tr") ?: s

    @Test fun `charging prefix is translated with the branch not as entering`() {
        val tool = MamlTextTranslator(doc("<Root/>"), "tr", ::translate)
        val result = tool.expression("'正在'+ifelse(#ChargeSpeed == 1,'快充',#ChargeSpeed == 2,'超级快充','充电')+' · '+#battery_level+'%'")
        assertTrue(result.contains("Şarj ediliyor"), result)
        assertTrue(result.contains("Hızlı şarj ediliyor"), result)
        assertFalse(result.contains("正在"), result)
        assertTrue(result.contains("#battery_level"))
    }

    @Test fun `conditional headings translated as phrases rather than joined words`() {
        val tool = MamlTextTranslator(doc("<Root/>"), "tr", ::translate)
        val result = tool.expression("ifelse(#wall == 0,'默认','自定义')+'壁纸'")
        assertEquals("ifelse(#wall == 0,'Varsayılan duvar kâğıdı','Özel duvar kâğıdı')", result)
        val settings = tool.expression("ifelse(#tab == 0,'时间','日期')+'设置'")
        assertEquals("ifelse(#tab == 0,'Saat ayarları','Tarih ayarları')", settings)
    }

    @Test fun `display arrays cloned while logic and predicates remain unchanged`() {
        val document = doc("""<Root><Var name="labels" type="string[]" values="'晴','阴'"/><Text/></Root>""")
        val tool = MamlTextTranslator(document, "tr", ::translate)
        val output = tool.expression("ifelse(eqs(@labels[0],'晴'),@labels[#choice],'阴')")
        assertTrue(output.contains("eqs(@labels[0],'晴')"), output)
        assertTrue(output.contains("@__mtz_locale_0[#choice]"), output)
        val vars = document.getElementsByTagName("Var")
        assertEquals("'晴','阴'", (vars.item(0) as org.w3c.dom.Element).getAttribute("values"))
        assertEquals("'Güneşli','Bulutlu'", (vars.item(1) as org.w3c.dom.Element).getAttribute("values"))
    }

    @Test fun `date arrays translate lunar suffix and provider weather separately`() {
        val document = doc("""<Root><Variable name="weather" type="string" column="description"/><Var name="dates" type="string[]" values="'M月d日 E · N月e',@weather+' '+#temp+'℃'"/></Root>""")
        val tool = MamlTextTranslator(document, "tr", ::translate)
        assertTrue(tool.expression("formatDate(@dates[#choice],#time_sys)").startsWith("@__mtz_locale_"))
        val clone = document.getElementsByTagName("Var").item(1) as org.w3c.dom.Element
        val output = clone.getAttribute("values")
        assertTrue(output.contains("#date_lunar"), output)
        assertTrue(output.contains("#month_lunar+1"), output)
        assertTrue(output.contains("'Çin takvimi '"), output)
        assertTrue(output.contains("eqs(@weather,'晴'),'Güneşli'"), output)
        assertFalse(output.contains("formatDate('Güneşli'"))
    }

    @Test fun `runtime variable writers are not changed`() {
        val document = doc("""<Root><VariableCommand name="quality" type="string" expression="ifelse(#aqi {= 50,'优','良')"/></Root>""")
        val tool = MamlTextTranslator(document, "tr", ::translate)
        assertTrue(tool.expression("@quality").contains("eqs(@quality,'优'),'Çok iyi'"))
        assertEquals("ifelse(#aqi {= 50,'优','良')", (document.getElementsByTagName("VariableCommand").item(0) as org.w3c.dom.Element).getAttribute("expression"))
    }

    @Test fun `runtime text written in another language is localized without changing the writer`() {
        val document = doc("""<Root><VariableCommand name="state" type="string" expression="ifelse(#online,'Подключено','Отключено')"/></Root>""")
        val translations = mapOf("Подключено" to "Connected", "Отключено" to "Disconnected")
        val tool = MamlTextTranslator(document, "en") { translations[it] ?: it }

        val output = tool.expression("@state")

        assertTrue(output.contains("eqs(@state,'Подключено'),'Connected'"), output)
        assertTrue(output.contains("eqs(@state,'Отключено'),'Disconnected'"), output)
        assertEquals(
            "ifelse(#online,'Подключено','Отключено')",
            (document.getElementsByTagName("VariableCommand").item(0) as org.w3c.dom.Element).getAttribute("expression"),
        )
    }

    @Test fun `hash referenced runtime strings are localized without changing the writer`() {
        val document = doc("""<Root><VariableCommand name="quality" type="string" expression="ifelse(#aqi {= 50,'优','良')"/></Root>""")
        val tool = MamlTextTranslator(document, "tr", ::translate)

        val output = tool.expression("#quality")

        assertTrue(output.contains("eqs(#quality,'优'),'Çok iyi'"), output)
        assertEquals(
            "ifelse(#aqi {= 50,'优','良')",
            (document.getElementsByTagName("VariableCommand").item(0) as org.w3c.dom.Element).getAttribute("expression"),
        )
    }

    @Test fun `dynamic gesture hints use deterministic glossary translations`() {
        val tool = MamlTextTranslator(doc("<Root/>"), "tr", ::translate)
        val output = tool.expression("ifelse(#open,'↓↓下划收起壁纸面板↓↓','↑↑上划呼出壁纸面板↑↑')")

        assertTrue(output.contains("Duvar kâğıdı panelini kapatmak için aşağı kaydırın"), output)
        assertTrue(output.contains("Duvar kâğıdı panelini açmak için yukarı kaydırın"), output)
        assertFalse(output.contains("下划"), output)
        assertFalse(output.contains("上划"), output)
    }

    @Test fun `compact Chinese weekday substring becomes localized branches`() {
        val tool = MamlTextTranslator(doc("<Root/>"), "tr", ::translate)
        val output = tool.expression("substr('日一二三四五六',#day,1)")

        assertTrue(output.contains("eq(#day,0),'Paz'"), output)
        assertTrue(output.contains("eq(#day,6),'Cmt'"), output)
        assertFalse(output.contains("日一二三四五六"), output)
    }

    @Test fun `duplicate static string definitions map their displayed values without mutating logic`() {
        val document = doc("""<Root><Var name="state" type="string" expression="ifelse(#a,'晴','阴')"/><Group><Var name="state" type="string" expression="ifelse(#b,'多云','晴')"/></Group></Root>""")
        val tool = MamlTextTranslator(document, "tr", ::translate)

        val output = tool.expression("@state")

        assertTrue(output.contains("eqs(@state,'晴'),'Güneşli'"), output)
        assertTrue(output.contains("eqs(@state,'阴'),'Bulutlu'"), output)
        assertTrue(output.contains("eqs(@state,'多云'),'Parçalı Bulutlu'"), output)
        assertEquals("ifelse(#a,'晴','阴')", (document.getElementsByTagName("Var").item(0) as org.w3c.dom.Element).getAttribute("expression"))
    }
}
