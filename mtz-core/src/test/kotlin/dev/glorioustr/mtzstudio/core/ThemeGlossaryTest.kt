package dev.glorioustr.mtzstudio.core

import kotlin.test.*

class ThemeGlossaryTest {
    @Test fun `date pattern separates the Turkish month and weekday`() {
        assertEquals("d MMMM E", ThemeGlossary.convertDatePattern("M月d日E", "tr"))
    }

    @Test
    fun `chinese segmenter retains whitespace-only clauses without crashing`() {
        assertEquals(" ", ChineseTranslationSegmenter.translate(" ", "tr") { error("must not translate whitespace") })
        assertEquals("\n", ChineseTranslationSegmenter.translate("\n", "tr") { error("must not translate whitespace") })
    }

    @Test
    fun `resolves weather conditions accurately without literal mistranslations`() {
        assertEquals("Güneşli", ThemeGlossary.resolve("晴", "tr"))
        assertEquals("Güneşli", ThemeGlossary.resolve("晴天", "tr"))
        assertEquals("Parçalı Bulutlu", ThemeGlossary.resolve("多云", "tr"))
        assertEquals("Bulutlu", ThemeGlossary.resolve("阴", "tr"))
        assertEquals("Kapalı", ThemeGlossary.resolve("阴天", "tr"))
        assertEquals("Sağanak Yağış", ThemeGlossary.resolve("阵雨", "tr"))
        assertEquals("Gök Gürültülü Sağanak", ThemeGlossary.resolve("雷阵雨", "tr"))
        assertEquals("Hafif Yağmur", ThemeGlossary.resolve("小雨", "tr"))
        assertEquals("Orta Şiddette Yağmur", ThemeGlossary.resolve("中雨", "tr"))
        assertEquals("Kuvvetli Yağmur", ThemeGlossary.resolve("大雨", "tr"))
        assertEquals("Karla Karışık Yağmur", ThemeGlossary.resolve("雨夹雪", "tr"))
        assertEquals("Sisli", ThemeGlossary.resolve("雾", "tr"))
        assertEquals("Puslu", ThemeGlossary.resolve("霾", "tr"))
    }

    @Test
    fun `resolves battery and charging statuses`() {
        assertEquals("Şarj Ediliyor", ThemeGlossary.resolve("充电中", "tr"))
        assertEquals("Şarj ediliyor", ThemeGlossary.resolve("正在充电", "tr"))
        assertEquals("Şarj Olmuyor", ThemeGlossary.resolve("未充电", "tr"))
        assertEquals("Tam şarj oldu", ThemeGlossary.resolve("已充满", "tr"))
        assertEquals("Hızlı Şarj", ThemeGlossary.resolve("快充", "tr"))
        assertEquals("Süper Hızlı Şarj", ThemeGlossary.resolve("超级快充", "tr"))
        assertEquals("Düşük Pil", ThemeGlossary.resolve("低电量", "tr"))
    }

    @Test
    fun `resolves weekdays and calendar units`() {
        assertEquals("Pazartesi", ThemeGlossary.resolve("周一", "tr"))
        assertEquals("Salı", ThemeGlossary.resolve("周二", "tr"))
        assertEquals("Çarşamba", ThemeGlossary.resolve("周三", "tr"))
        assertEquals("Perşembe", ThemeGlossary.resolve("周四", "tr"))
        assertEquals("Cuma", ThemeGlossary.resolve("周五", "tr"))
        assertEquals("Cumartesi", ThemeGlossary.resolve("周六", "tr"))
        assertEquals("Pazar", ThemeGlossary.resolve("周日", "tr"))
        assertEquals("Pazar", ThemeGlossary.resolve("星期天", "tr"))
        assertEquals("Bugün", ThemeGlossary.resolve("今天", "tr"))
        assertEquals("Yarın", ThemeGlossary.resolve("明天", "tr"))
    }

    @Test
    fun `resolves lockscreen gestures and actions`() {
        assertEquals("Ekranı kilitlemek için çift dokunun", ThemeGlossary.resolve("双击锁屏", "tr"))
        assertEquals("Kilidi açmak için kaydırın", ThemeGlossary.resolve("滑动解锁", "tr"))
        assertEquals("Kilidi açmak için yukarı kaydırın", ThemeGlossary.resolve("上滑解锁", "tr"))
        assertEquals("Özelleştirmek için basılı tutun", ThemeGlossary.resolve("长按自定义", "tr"))
        assertEquals("Kilit Ekranı Ayarları", ThemeGlossary.resolve("锁屏设置", "tr"))
    }

    @Test
    fun `converts Chinese date format patterns to Android standard SimpleDateFormat`() {
        assertEquals("d MMMM EEEE", ThemeGlossary.convertDatePattern("MM月dd日 EEEE", "tr"))
        assertEquals("d MMMM", ThemeGlossary.convertDatePattern("M月d日", "tr"))
        assertEquals("d MMMM yyyy", ThemeGlossary.convertDatePattern("yyyy年M月d日", "tr"))
    }

    @Test
    fun `resolves compound measurements`() {
        assertEquals("1500 Adım", ThemeGlossary.resolve("1500步", "tr"))
        assertEquals("350 kcal", ThemeGlossary.resolve("350千卡", "tr"))
        assertEquals("4.8 km", ThemeGlossary.resolve("4.8公里", "tr"))
        assertEquals("72 bpm", ThemeGlossary.resolve("72次/分", "tr"))
        assertEquals("Kalan %85", ThemeGlossary.resolve("剩余85%", "tr"))
        assertEquals("26°C", ThemeGlossary.resolve("26℃", "tr"))
    }

    @Test
    fun `does not corrupt settings labels or other languages`() {
        assertEquals("Gölge rengi", ThemeGlossary.postProcessTranslation("Gölge rengi", "tr"))
        assertNull(ThemeGlossary.resolve("晴", "pt"))
        assertNull(ThemeGlossary.convertDatePattern("本月步数", "tr"))
        assertEquals("d MMMM", ThemeGlossary.convertDatePattern("MM月d日", "tr"))
        assertEquals(" · Tam şarj oldu", ThemeGlossary.resolve(" · 已充满", "tr"))
    }
}
