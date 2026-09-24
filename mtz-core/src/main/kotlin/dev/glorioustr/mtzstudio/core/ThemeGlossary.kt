package dev.glorioustr.mtzstudio.core

import java.util.Locale

/**
 * Domain-specific glossary and natural language processor for Xiaomi HyperOS / MIUI themes.
 * Provides high-accuracy, natural, native translations for UI widgets, weather conditions,
 * battery/charging statuses, calendar/date formats, health metrics, gestures, and settings.
 */
object ThemeGlossary {

    private val CHINESE_CHARS = Regex("[\\p{IsHan}]+")

    fun containsChinese(text: String): Boolean = CHINESE_CHARS.containsMatchIn(text)

    /** Protects Xiaomi/MAML terms before the Chinese -> English -> target-language route. */
    fun prepareChineseForEnglishPivot(text: String): String {
        var prepared = text
        CHINESE_PIVOT_TERMS.forEach { (source, replacement) -> prepared = prepared.replace(source, " $replacement ") }
        return prepared.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Resolves a Chinese phrase using the domain glossary for the target language (defaulting to Turkish).
     * Returns null if no exact or rule-based match is found, signaling that external ML translation is needed.
     */
    fun resolve(chineseText: String, targetLanguage: String = "tr"): String? {
        val trimmed = chineseText.trim()
        if (trimmed.isEmpty()) return null

        val lang = targetLanguage.lowercase(Locale.ROOT)
        if (lang.startsWith("tr")) {
            resolveTurkish(trimmed)?.let { return chineseText.replace(trimmed, it) }
        } else if (lang == "en") {
            resolveEnglish(trimmed)?.let { return chineseText.replace(trimmed, it) }
        }
        if (lang == "tr" || lang == "en") resolveCompound(trimmed, lang)?.let { return it }
        val phrase = Regex("^([^\\p{L}\\d]*)([\\p{IsHan}]+)([^\\p{L}\\d]*)$").matchEntire(chineseText)
        if (phrase != null && (lang == "tr" || lang == "en")) {
            val translated = if (lang == "tr") resolveTurkish(phrase.groupValues[2]) else resolveEnglish(phrase.groupValues[2])
            if (translated != null) return phrase.groupValues[1] + translated + phrase.groupValues[3]
        }

        // Try compound regex patterns (e.g. numbers + units)
        if (lang == "tr" || lang == "en") resolveCompound(trimmed, lang)?.let { return it }

        return null
    }

    /**
     * Converts Chinese date format pattern strings (commonly in formatDate / formatTime)
     * into localized, clean Android SimpleDateFormat patterns.
     */
    fun convertDatePattern(pattern: String, language: String = "tr"): String? {
        if (!containsChinese(pattern)) return null
        // Only token-attached units are syntax. Do not remove 月/日 from ordinary prose.
        var result = pattern
        val monthDay = if (language.startsWith("en")) "MMMM d" else "d MMMM"
        result = Regex("yyyy年M{1,2}月d{1,2}日").replace(result, "$monthDay yyyy")
        result = Regex("M{1,2}月d{1,2}日").replace(result, monthDay)
        result = Regex("yyyy年M{1,2}月").replace(result, "MMMM yyyy")
        result = result.replace("yyyy年", "yyyy")
        result = Regex("M{1,2}月").replace(result, "MMMM")
        result = Regex("d{1,2}日").replace(result, "d")
        // Chinese date patterns often omit the separator before a weekday.  A
        // localized month name must not run into the next token ("EylülPrş").
        result = Regex("(d MMMM)(?=E{1,4}\\b)").replace(result, "$1 ")
        result = Regex("H{1,2}[点时]m{1,2}分").replace(result, "HH:mm")
        result = Regex("H{1,2}[点时]").replace(result, "HH:00")
        return result.takeIf { it != pattern }
    }

    /**
     * Resolves compound patterns like "1000 步", "250 千卡", "剩余 80%"
     */
    private fun resolveCompound(text: String, language: String): String? {
        val isTr = language.startsWith("tr")

        // Weekday compound: 周一..周日, 星期一..星期日
        val weekdayMatch = Regex("""^(?:周|星期|礼拜)([一二三四五六日天])$""").matchEntire(text)
        if (weekdayMatch != null) {
            val d = weekdayMatch.groupValues[1]
            return if (isTr) when (d) {
                "一" -> "Pazartesi"
                "二" -> "Salı"
                "三" -> "Çarşamba"
                "四" -> "Perşembe"
                "五" -> "Cuma"
                "六" -> "Cumartesi"
                else -> "Pazar"
            } else when (d) {
                "一" -> "Monday"
                "二" -> "Tuesday"
                "三" -> "Wednesday"
                "四" -> "Thursday"
                "五" -> "Friday"
                "六" -> "Saturday"
                else -> "Sunday"
            }
        }

        // Steps: 1234 步, 1234步, 步数: 1234
        val stepMatch = Regex("""^(\d+)\s*步$""").matchEntire(text)
        if (stepMatch != null) {
            return if (isTr) "${stepMatch.groupValues[1]} Adım" else "${stepMatch.groupValues[1]} Steps"
        }

        // Calories: 500 千卡, 500卡路里
        val calMatch = Regex("""^(\d+)\s*(?:千卡|卡路里)$""").matchEntire(text)
        if (calMatch != null) {
            return "${calMatch.groupValues[1]} kcal"
        }

        // Distance: 5.2 公里, 5.2千米, 500米
        val kmMatch = Regex("""^([\d.]+)\s*(?:公里|千米)$""").matchEntire(text)
        if (kmMatch != null) {
            return "${kmMatch.groupValues[1]} km"
        }
        val mMatch = Regex("""^(\d+)\s*米$""").matchEntire(text)
        if (mMatch != null) {
            return "${mMatch.groupValues[1]} m"
        }

        // Heart rate: 75 次/分
        val hrMatch = Regex("""^(\d+)\s*次/(?:分|分钟)$""").matchEntire(text)
        if (hrMatch != null) {
            return "${hrMatch.groupValues[1]} bpm"
        }

        // Battery: 剩余 80%, 电量 80%
        val battMatch = Regex("""^(?:剩余|当前)?\s*电量\s*[:：]?\s*(\d+)\s*%$""").matchEntire(text)
        if (battMatch != null) {
            return if (isTr) "%${battMatch.groupValues[1]} Pil" else "${battMatch.groupValues[1]}% Battery"
        }
        val remBattMatch = Regex("""^剩余\s*[:：]?\s*(\d+)\s*%$""").matchEntire(text)
        if (remBattMatch != null) {
            return if (isTr) "Kalan %${remBattMatch.groupValues[1]}" else "Remaining ${remBattMatch.groupValues[1]}%"
        }
        val chgBattMatch = Regex("""^已充电\s*[:：]?\s*(\d+)\s*%$""").matchEntire(text)
        if (chgBattMatch != null) {
            return if (isTr) "Şarj Edildi %${chgBattMatch.groupValues[1]}" else "Charged ${chgBattMatch.groupValues[1]}%"
        }

        // Temperature: 25℃, 25度
        val tempMatch = Regex("""^([+-]?\d+)\s*(?:℃|度)$""").matchEntire(text)
        if (tempMatch != null) {
            return "${tempMatch.groupValues[1]}°C"
        }

        return null
    }

    /**
     * Post-processes machine-translated strings to fix awkward, literal translation artifacts.
     */
    fun postProcessTranslation(translatedText: String, targetLanguage: String = "tr"): String {
        // Broad substitutions such as “gölge -> bulutlu” corrupt legitimate settings labels.
        // Domain corrections belong to the source-language glossary, not arbitrary translated prose.
        return translatedText.trim()
    }

    private fun resolveTurkish(text: String): String? =
        TURKISH_THEME_UI[text] ?: TURKISH_DICTIONARY[text] ?: BakThemeGlossary.resolve(text, "tr")
    private fun resolveEnglish(text: String): String? = ENGLISH_DICTIONARY[text]

    private val TURKISH_THEME_UI = mapOf(
        "自定义" to "Özelleştirme",
        "完成" to "Tamam", "添加小组件" to "Bileşen ekle",
        "默认壁纸" to "Varsayılan duvar kâğıdı", "自定义壁纸" to "Özel duvar kâğıdı",
        "桌面壁纸" to "Ana ekran duvar kâğıdı", "文件夹壁纸" to "Klasördeki duvar kâğıdı", "内置壁纸" to "Hazır duvar kâğıdı",
        "正在使用默认壁纸" to "Varsayılan duvar kâğıdı kullanılıyor",
        "正在使用自定义壁纸" to "Özel duvar kâğıdı kullanılıyor",
        "正在使用桌面壁纸" to "Ana ekran duvar kâğıdı kullanılıyor",
        "正在使用文件夹壁纸" to "Klasördeki duvar kâğıdı kullanılıyor",
        "正在使用内置壁纸" to "Hazır duvar kâğıdı kullanılıyor",
        "专辑主体位置" to "Albüm görseli konumu", "内置小组件" to "Hazır widget'lar",
        "关于作者" to "Yapımcı hakkında", "实验室功能" to "Deneysel özellikler",
        "左下角功能" to "Sol alt köşe işlevi", "底部快捷功能颜色" to "Alt kısayol rengi",
        "锁屏壁纸设置" to "Kilit ekranı duvar kâğıdı ayarları", "锁屏内容可读性增强" to "Kilit ekranı okunabilirliğini artır",
        "景深上层壁纸" to "Derinlik efekti üst katmanı", "景深开关" to "Derinlik efekti",
        "音乐封面自定义" to "Müzik kapağını özelleştir", "高级材质" to "Gelişmiş görünüm",
        "自定义专辑壁纸" to "Özel albüm duvar kâğıdı", "自定义日期" to "Özel tarih",
        "自定义桌面壁纸教程" to "Ana ekran duvar kâğıdını özelleştirme rehberi",
        "选定图片应用到锁屏壁纸" to "Seçilen görseli kilit ekranına uygula",
        "重新应用主题锁屏" to "Tema kilit ekranını yeniden uygula",
        "双击打开快捷功能" to "Kısayolları açmak için çift dokunun",
        "雙擊開啟快捷功能" to "Kısayolları açmak için çift dokunun",
        "双击打开锁屏设置" to "Kilit ekranı ayarlarını açmak için çift dokunun",
        // Dynamic MAML lock-screen hints. These must be deterministic: on-device
        // translation models tend to split the arrows and repeat individual words.
        "↓↓下划收起壁纸面板↓↓" to "↓↓ Duvar kâğıdı panelini kapatmak için aşağı kaydırın ↓↓",
        "↑↑上划呼出壁纸面板↑↑" to "↑↑ Duvar kâğıdı panelini açmak için yukarı kaydırın ↑↑",
        "雙擊開啟鎖屏設定" to "Kilit ekranı ayarlarını açmak için çift dokunun",
        "鎖屏設定" to "Kilit Ekranı Ayarları", "時間顏色" to "Saat rengi",
        "時間顏色" to "Saat rengi", "冒号颜色" to "İki nokta rengi", "冒號顏色" to "İki nokta rengi",
        "时钟长度" to "Saat yüksekliği", "時鐘長度" to "Saat yüksekliği", "长度" to "Yükseklik", "長度" to "Yükseklik",
        "通知位置" to "Bildirim konumu", "通知字体颜色" to "Bildirim metni rengi",
        "通知字體顏色" to "Bildirim metni rengi", "通知背景顏色" to "Bildirim arka planı rengi",
        "設定字體顏色" to "Metin rengini ayarla", "我的设备" to "Cihazlarım",
        "智能穿戴" to "Akıllı giyilebilir cihaz", "无线耳机" to "Kablosuz kulaklık",
        "微信" to "WeChat", "支付宝" to "Alipay", "快捷支付" to "Hızlı ödeme",
        "扫一扫" to "Tara", "付款码" to "Ödeme kodu", "确定" to "Onayla", "确认" to "Onayla", "確認" to "Onayla",
        "暂无音乐" to "Müzik çalmıyor", "条新消息" to " yeni mesaj",
        "极速秒充" to "Ultra hızlı şarj", "正在充電 " to "Şarj ediliyor ",
        "红" to "Kırmızı", "绿" to "Yeşil", "蓝" to "Mavi",
        "空/双击进入自定义" to "Boş / özelleştirmek için çift dokunun",
        "感谢您的支持和理解" to "Desteğiniz ve anlayışınız için teşekkürler.",
        "关注公众号获取最新消息和主题进阶教程" to "Yeni duyurular ve gelişmiş tema rehberleri için yapımcıyı takip edin.",
        "打开 主题壁纸➔我的➔混搭➔锁屏" to "Temalar > Profilim > Özelleştir > Kilit ekranı yolunu açın.",
        "即可保留自定义桌面壁纸以及锁屏" to "Özel ana ekran ve kilit ekranı duvar kâğıtlarını korur.",
        "可写日期代码，也可自定义文字，空格将不会显示" to "Tarih biçimi veya özel metin yazabilirsiniz; boşluklar gösterilmez.",
        "如果文字或快捷方式看不清，建议开启" to "Metin veya kısayollar zor okunuyorsa açmanız önerilir.",
        "请手动前往相册设置桌面壁纸后" to "Ana ekran duvar kâğıdını Galeri'den elle ayarladıktan sonra kullanın.",
        "时间设置" to "Saat ayarları", "日期设置" to "Tarih ayarları",
        "小组件设置" to "Widget ayarları", "更多设置" to "Diğer ayarlar",
        "最低" to "En düşük", "最高" to "En yüksek",
        "快速充电" to "Hızlı şarj ediliyor",
        "0~0.3之间，默认0.22" to "0–0,3 arasında; varsayılan: 0,22",
        "不刷新" to "Yenileme kapalı", "个性" to "Kişisel", "个通知" to " bildirim",
        "主要" to "Birincil", "次要" to "İkincil",
        "主题内置18张精美壁纸供您选择" to "Temadaki 18 hazır duvar kâğıdından birini seçebilirsiniz.",
        "五种样式供选择" to "Beş görünüm seçeneği", "四种样式供选择" to "Dört görünüm seçeneği",
        "值越大离底部越远,默认20" to "Değer arttıkça alt kenardan uzaklaşır. Varsayılan: 20.",
        "值越大移动位置越多，不要超过12，默认8,负值为反向" to "Değer arttıkça hareket mesafesi artar. En fazla 12, varsayılan 8. Negatif değer ters yönde hareket ettirir.",
        "值越大越深，默认50，最大255" to "Değer arttıkça koyulaşır. Varsayılan: 50, en fazla: 255.",
        "充滿" to "Tam şarj oldu", "全部隐藏" to "Tümünü gizle",
        "内容" to "İçerik", "内置" to "Hazır", "分鐘" to "dakika", "分钟" to "dakika",
        "分钟相对于小时的透明度，值越大差别越大" to "Dakikanın saate göre saydamlığı. Değer arttıkça aradaki fark artar.",
        "分钟透明度" to "Dakika saydamlığı", "剩余" to "Kalan",
        "卡一" to "SIM 1", "卡二" to "SIM 2", "双击此处" to "Buraya çift dokunun", "可用" to "Kullanılabilir",
        "商务联系" to "İş birliği için iletişim", "嚴重污染" to "Ciddi hava kirliliği",
        "图片尺寸宽400高156,尺寸太大会卡顿" to "Görsel 400 × 156 piksel olmalı. Daha büyük görseller takılmaya neden olabilir.",
        "图片格式为png格式" to "PNG biçiminde bir görsel kullanın.",
        "图片质量最好别超过1m,超过会卡顿" to "Görselin 1 MB'ı aşmaması önerilir; büyük dosyalar takılmaya neden olabilir.",
        "壁纸缩放倍数" to "Duvar kâğıdı ölçeği", "复制到浏览器打开" to "Kopyalayıp tarayıcıda açın",
        "天" to "gün", "天氣" to "Hava durumu", "小时" to "saat", "小時" to "saat",
        "小时刷新+双击刷新" to "Her saat veya çift dokununca yenile",
        "小米主题" to "Xiaomi Temalar", "小组件颜色跟随" to "Widget renk eşleştirmesi",
        "小部件自定义图1" to "Widget özel görseli 1", "小部件自定义图2" to "Widget özel görseli 2",
        "尺寸" to "Boyut", "已关闭" to "Kapalı", "已打开" to "Açık", "平移" to "Kaydırma",
        "开启后主题自带桌面壁纸模糊" to "Temanın ana ekran duvar kâğıdını bulanıklaştırır.",
        "开启后在自定义图颜色将跟随时间颜色,适用于透明图" to "Özel görselin rengi saat rengiyle eşleşir. Saydam görseller için uygundur.",
        "开启后在锁屏界面下滑，主屏元素会跟随下滑" to "Kilit ekranında aşağı kaydırırken ana ekran öğeleri de hareket eder.",
        "开启后在锁屏界面不会自动锁屏，需手动锁屏，仅测试主题使用" to "Otomatik ekran kilidini kapatır; ekranı elle kilitlemeniz gerekir. Yalnızca tema testi içindir.",
        "开启后将自动裁切图片圆角" to "Görselin köşelerini otomatik yuvarlatır.",
        "开启后补全底部缺失的壁纸" to "Duvar kâğıdının altta eksik kalan bölümünü tamamlar.",
        "开启后部分界面显示高光" to "Bazı arayüz öğelerinde parlama efekti gösterir.",
        "开启后锁屏按钮点击震动" to "Kilit ekranı düğmelerine dokunulduğunda titreşim verir.",
        "指纹识别" to "Parmak izi tanıma", "控制中心右上角文字" to "Kontrol merkezi sağ üst metni",
        "文件夹" to "Klasör", "无" to "Yok", "无天气信息" to "Hava durumu bilgisi yok",
        "无套餐" to "Tarife bilgisi yok", "无数据" to "Veri yok", "日一二三四五六" to "Pz Pt Sa Ça Pe Cu Ct",
        "日曆" to "Takvim", "时间整体" to "Saatin tamamı", "時間" to "Saat",
        "景深壁纸上层" to "Derinlik duvar kâğıdı üst katmanı", "景深壁纸网址" to "Derinlik duvar kâğıdı bağlantısı",
        "暂无播放" to "Bir şey çalmıyor", "更多部件" to "Diğer widget'lar", "最多输入4个字" to "En fazla 4 karakter girin.",
        "本月剩余" to "Bu ay kalan", "本月只剩 今天" to "Ayın son günü", "条通知" to " bildirim",
        "桌面壁纸模糊" to "Ana ekran duvar kâğıdını bulanıklaştır",
        "横向" to "Yatay", "横向位置" to "Yatay konum", "歌手" to "Sanatçı",
        "正在使用" to "Kullanımda", "正在使用内置壁纸_" to "Kullanılan hazır duvar kâğıdı: ",
        "正在獲取電量數據" to "Pil bilgisi alınıyor", "正在获取电量数据" to "Pil bilgisi alınıyor",
        "正常显示" to "Normal göster", "正常显示(默认)" to "Normal göster (varsayılan)",
        "每天刷新+双击刷新" to "Her gün veya çift dokununca yenile", "每月刷新+双击刷新" to "Her ay veya çift dokununca yenile",
        "沉浸" to "Tam ekran", "流量卡选择" to "Mobil veri SIM kartı", "测试与反馈" to "Test ve geri bildirim",
        "濕度" to "Nem", "点击获取数据" to "Bilgileri almak için dokunun", "無天氣訊息" to "Hava durumu bilgisi yok",
        "空气良好" to "Hava kalitesi iyi", "空氣優" to "Hava kalitesi çok iyi", "空氣良好" to "Hava kalitesi iyi",
        "站立40次" to "40 kez ayağa kalk", "系统" to "Sistem", "纵向" to "Dikey", "纵向位置" to "Dikey konum",
        "缩放" to "Ölçek", "背景颜色" to "Arka plan rengi",
        "自定义wifi名称" to "Özel Wi-Fi adı", "自定义信号名称" to "Özel operatör adı",
        "自定义图染色" to "Özel görseli renklendir", "自定义底部文字" to "Özel alt metin",
        "自定义文字" to "Özel metin", "自定义日期文字" to "Özel tarih metni", "自定义日期格式" to "Özel tarih biçimi",
        "裁切自定义图" to "Özel görseli kırp", "請稍等" to "Lütfen bekleyin", "请稍等" to "Lütfen bekleyin",
        "计数器刷新" to "Sayaç yenileme", "计数器单位" to "Sayaç birimi", "计数器标题" to "Sayaç başlığı", "计数器目标" to "Sayaç hedefi",
        "设备电量" to "Cihaz pili", "请加群咨询" to "Yardım için gruba katılın", "请勿输入过长" to "Kısa bir metin girin.",
        "跟随底部按钮图标颜色" to "Alt düğme simgesiyle aynı renk", "跟随底部按钮背景颜色" to "Alt düğme arka planıyla aynı renk",
        "跟随时间颜色" to "Saatle aynı renk", "跟随通知文字颜色" to "Bildirim metniyle aynı renk", "跟随通知背景颜色" to "Bildirim arka planıyla aynı renk",
        "輕度污染" to "Hafif hava kirliliği", "酷安搜索" to "Coolapk'te ara", "重力倍数" to "Hareket hassasiyeti",
        "重力壁纸补全" to "Harekete duyarlı duvar kâğıdını tamamla", "锁屏主屏下滑跟随" to "Kilit ekranıyla birlikte aşağı kaydır",
        "锁屏主屏幕" to "Kilit ekranı ana görünümü", "锁屏壁纸下层" to "Kilit ekranı duvar kâğıdı alt katmanı",
        "锁屏小组件" to "Kilit ekranı widget'ları", "锁屏常亮" to "Kilit ekranını açık tut", "锁屏按钮震动" to "Kilit ekranı düğmelerinde titreşim",
        "锁屏控制中心" to "Kilit ekranı kontrol merkezi", "锁屏时间" to "Kilit ekranı saati",
        "锁屏通知距底部" to "Bildirimlerin alt kenara uzaklığı", "锁屏音乐无封面自定义图" to "Kapak yokken gösterilecek özel görsel",
        "锁屏音乐遮罩透明度" to "Müzik kaplaması saydamlığı", "锁屏高光" to "Kilit ekranı parlama efekti",
        "隐藏动画" to "Animasyonu gizle", "隐藏指纹" to "Parmak izini gizle", "隐藏指纹图标" to "Parmak izi simgesini gizle",
        "隐藏指纹图标与识别动画" to "Parmak izi simgesini ve tanıma animasyonunu gizle", "隐藏识别动画" to "Tanıma animasyonunu gizle",
        "電量" to "Pil", "需要" to "Gereken", "預計" to "Tahmini", "预计" to "Tahmini", "高度" to "Yükseklik",
        "點下區域\n切換動畫\n(共8種)" to "Animasyonu değiştirmek için\naşağıya dokunun\n(8 seçenek)", "點擊獲取數據" to "Bilgileri almak için dokunun",
    ) + (1..31).associate { number ->
        val digits = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        val chinese = if (number < 10) digits[number] else (if (number < 20) "" else digits[number / 10]) + "十" + digits[number % 10]
        chinese to number.toString()
    }

    private val TURKISH_DICTIONARY: Map<String, String> = mapOf(
        "正在快充" to "Hızlı şarj ediliyor",
        "正在超级快充" to "Süper hızlı şarj ediliyor",
        "正在极速快充" to "Ultra hızlı şarj ediliyor",
        "农历" to "Çin takvimi",
        "优" to "Çok iyi", "良" to "İyi", "轻度" to "Hafif kirlilik",
        "中度" to "Orta kirlilik", "重度" to "Yüksek kirlilik", "严重" to "Ciddi kirlilik",
        "优秀" to "Çok iyi",
        "壁纸压暗" to "Duvar kâğıdını karart",
        "壁纸模糊" to "Duvar kâğıdını bulanıklaştır",
        "小白条" to "Hareket çubuğu",
        "小白条颜色" to "Hareket çubuğu rengi",
        "右上角小白条" to "Sağ üst hareket çubuğu",
        "右上角控制中心" to "Sağ üst kontrol merkezi",
        "全屏音乐时关闭指纹" to "Müzik tam ekrandayken parmak izini gizle",
        "全屏音乐样式" to "Tam ekran müzik görünümü",
        "双击自定义图" to "Özel görseli çift dokunarak değiştir",
        "内容选择" to "İçerik seçimi",
        "个性壁纸" to "Özel duvar kâğıdı",
        "人脸识别" to "Yüz tanıma",
        "充电动画" to "Şarj animasyonu",
        "充电样式" to "Şarj görünümü",
        "内置壁纸" to "Hazır duvar kâğıtları",
        "小组件" to "Widget'lar",
        "小组件显示位置" to "Widget konumu",
        "底部按钮" to "Alt düğmeler",
        "开启后展开锁屏岛时隐藏指纹功能" to "Kilit ekranı adası açıldığında parmak izi simgesini gizler.",
        "开启后时间与日期同行显示，无法调节字体与颜色" to "Saat ve tarih aynı satırda gösterilir. Yazı tipi ve renk değiştirilemez.",
        "开启后桌面时间与日期同步调节样式" to "Ana ekrandaki saat ve tarih görünümü birlikte değişir.",
        "开启后点击小白条直接打开自定义" to "Hareket çubuğuna dokunulduğunda özelleştirme açılır.",
        "开启后调节时间样式，日期样式同步修改" to "Saat görünümü değiştiğinde tarih görünümü de değişir.",
        "开屏收纳为锁屏岛" to "Ekran açıldığında kilit ekranı adasına küçült",
        "快捷打开自定义" to "Özelleştirmeyi hızlı aç",
        "指纹样式" to "Parmak izi simgesi",
        "按钮背景透明度" to "Düğme arka planı saydamlığı",
        "按钮颜色" to "Düğme rengi",
        "日期样式跟随时间样式" to "Tarih görünümünü saatle eşleştir",
        "日期透明度" to "Tarih saydamlığı",
        "时间与日期同行显示" to "Saat ve tarihi aynı satırda göster",
        "时间联动" to "Saat ve tarihi birlikte değiştir",
        "时间透明度" to "Saat saydamlığı",
        "景深效果" to "Derinlik efekti",
        "查看更多" to "Daha fazla göster",
        "深色遮罩" to "Koyu kaplama",
        "点击白条打开自定义" to "Hareket çubuğuyla özelleştirmeyi aç",
        "空" to "Boş",
        "自动堆叠通知" to "Bildirimleri otomatik grupla",
        "话费" to "Hat bakiyesi",
        "超级时间样式" to "Gelişmiş saat görünümü",
        "跟随音乐收纳为锁屏岛" to "Müzikle birlikte kilit ekranı adasına küçült",
        "通知文字颜色" to "Bildirim metni rengi",
        "通知背景透明度" to "Bildirim arka planı saydamlığı",
        "通知背景颜色" to "Bildirim arka planı rengi",
        "重力壁纸" to "Harekete duyarlı duvar kâğıdı",
        "锁屏AI指纹" to "Kilit ekranı akıllı parmak izi",
        "锁屏人脸识别动画" to "Yüz tanıma animasyonu",
        "锁屏使用可调节高度时间字体样式" to "Kilit ekranı saatinin yüksekliğini ayarla",
        "锁屏底部小白条" to "Kilit ekranı hareket çubuğu",
        "锁屏底部按钮" to "Kilit ekranı alt düğmeleri",
        "锁屏通知" to "Kilit ekranı bildirimleri",
        "锁屏音乐" to "Kilit ekranı müzik çaları",
        "锁屏音乐切歌优化" to "Kilit ekranında parça geçişini iyileştir",
        "日出" to "Gün doğumu", "日落" to "Gün batımı",
        "帧率" to "Kare hızı", "秒" to "saniye",
        "最高:" to "En yüksek: ", "最低:" to "En düşük: ",
        "无闹钟" to "Alarm yok",

        // Weather
        "晴" to "Güneşli",
        "晴天" to "Güneşli",
        "多云" to "Parçalı Bulutlu",
        "少云" to "Az Bulutlu",
        "阴" to "Bulutlu",
        "阴天" to "Kapalı",
        "阵雨" to "Sağanak Yağış",
        "雷阵雨" to "Gök Gürültülü Sağanak",
        "雷雨" to "Gök Gürültülü Yağmur",
        "小雨" to "Hafif Yağmur",
        "中雨" to "Orta Şiddette Yağmur",
        "大雨" to "Kuvvetli Yağmur",
        "暴雨" to "Şiddetli Yağmur",
        "大暴雨" to "Çok Şiddetli Yağmur",
        "特大暴雨" to "Aşırı Şiddetli Yağmur",
        "冻雨" to "Dondurucu Yağmur",
        "雨夹雪" to "Karla Karışık Yağmur",
        "小雪" to "Hafif Kar",
        "中雪" to "Orta Şiddette Kar",
        "大雪" to "Yoğun Kar",
        "暴雪" to "Kar Fırtınası",
        "阵雪" to "Kısa Süreli Kar",
        "雾" to "Sisli",
        "浓雾" to "Yoğun Sis",
        "强浓雾" to "Yoğun Sis",
        "轻雾" to "Hafif Sis",
        "霾" to "Puslu",
        "中度霾" to "Orta Puslu",
        "重度霾" to "Yoğun Puslu",
        "浮尘" to "Tozlu",
        "扬沙" to "Kum Fırtınası",
        "沙尘暴" to "Kum Fırtınası",
        "强沙尘暴" to "Şiddetli Kum Fırtınası",
        "大风" to "Rüzgarlı",
        "微风" to "Hafif Esinti",
        "台风" to "Tayfun",
        "龙卷风" to "Hortum",
        "冰雹" to "Dolu",
        "空气优" to "Hava Kalitesi Mükemmel",
        "空气良" to "Hava Kalitesi İyi",
        "轻度污染" to "Hafif Kirli",
        "中度污染" to "Orta Kirli",
        "重度污染" to "Ağır Kirli",
        "严重污染" to "Aşırı Kirli",
        "湿度" to "Nem",
        "风向" to "Rüzgar Yönü",
        "风力" to "Rüzgar Hızı",
        "紫外线" to "UV İndeksi",
        "气压" to "Basınç",
        "能见度" to "Görüş Mesafesi",
        "体感温度" to "Hissedilen",
        "天气" to "Hava Durumu",

        // Battery & Power
        "充电中" to "Şarj Ediliyor",
        "正在充电" to "Şarj ediliyor",
        "未充电" to "Şarj Olmuyor",
        "未在充电" to "Şarj Olmuyor",
        "已充满" to "Tam şarj oldu",
        "充满" to "Tam Şarj Oldu",
        "电量" to "Pil",
        "剩余电量" to "Kalan Pil",
        "当前电量" to "Mevcut Şarj",
        "电池" to "Pil",
        "电池电量" to "Pil Seviyesi",
        "快充" to "Hızlı Şarj",
        "快速充电" to "Hızlı Şarj",
        "超级快充" to "Süper Hızlı Şarj",
        "超快充" to "Süper Hızlı Şarj",
        "超级闪充" to "Süper Hızlı Şarj",
        "闪充" to "Hızlı Şarj",
        "极速快充" to "Ultra Hızlı Şarj",
        "无线充电" to "Kablosuz Şarj",
        "无线快充" to "Kablosuz Hızlı Şarj",
        "低电量" to "Düşük Pil",
        "电量过低" to "Düşük Pil",
        "请充电" to "Lütfen Şarj Edin",
        "省电模式" to "Güç Tasarrufu",
        "超级省电" to "Süper Güç Tasarrufu",

        // Calendar, Days & Time
        "星期一" to "Pazartesi",
        "星期二" to "Salı",
        "星期三" to "Çarşamba",
        "星期四" to "Perşembe",
        "星期五" to "Cuma",
        "星期六" to "Cumartesi",
        "星期日" to "Pazar",
        "星期天" to "Pazar",
        "周一" to "Pazartesi",
        "周二" to "Salı",
        "周三" to "Çarşamba",
        "周四" to "Perşembe",
        "周五" to "Cuma",
        "周六" to "Cumartesi",
        "周日" to "Pazar",
        "周天" to "Pazar",
        "礼拜一" to "Pazartesi",
        "礼拜二" to "Salı",
        "礼拜三" to "Çarşamba",
        "礼拜四" to "Perşembe",
        "礼拜五" to "Cuma",
        "礼拜六" to "Cumartesi",
        "礼拜日" to "Pazar",
        "礼拜天" to "Pazar",
        "今天" to "Bugün",
        "明天" to "Yarın",
        "后天" to "Ertesi Gün",
        "昨天" to "Dün",
        "前天" to "Önceki Gün",
        "上午" to "ÖÖ",
        "下午" to "ÖS",
        "凌晨" to "Gece",
        "早上" to "Sabah",
        "清晨" to "Sabah",
        "中午" to "Öğle",
        "傍晚" to "Akşamüstü",
        "晚上" to "Akşam",
        "半夜" to "Gece Yarısı",
        "农历" to "Çin takvimi",
        "年" to "Yıl",
        "月" to "Ay",
        "日" to "Gün",
        "时" to "Saat",
        "点" to "Saat",
        "分" to "Dakika",
        "秒" to "Saniye",

        // Health & Fitness
        "步" to "Adım",
        "步数" to "Adım Sayısı",
        "今日步数" to "Bugünkü Adım",
        "目标步数" to "Hedef Adım",
        "步数目标" to "Adım Hedefi",
        "卡路里" to "Kalori",
        "千卡" to "kcal",
        "距离" to "Mesafe",
        "公里" to "km",
        "千米" to "km",
        "米" to "m",
        "心率" to "Nabız",
        "次/分" to "bpm",
        "次/分钟" to "atım/dk",
        "站立" to "Ayakta Kalma",
        "运动" to "Egzersiz",
        "活动" to "Hareket",
        "睡眠" to "Uyku",
        "深睡" to "Derin Uyku",
        "浅睡" to "Hafif Uyku",

        // Lockscreen Gestures & Navigation
        "双击锁屏" to "Ekranı kilitlemek için çift dokunun",
        "双击熄屏" to "Ekranı kapatmak için çift dokunun",
        "双击唤醒" to "Ekranı uyandırmak için çift dokunun",
        "双击切换" to "Değiştirmek için çift dokunun",
        "双击更换" to "Değiştirmek için çift dokunun",
        "双击" to "Çift Dokun",
        "单击" to "Dokun",
        "点击" to "Dokun",
        "长按" to "Basılı Tut",
        "滑动解锁" to "Kilidi açmak için kaydırın",
        "上滑解锁" to "Kilidi açmak için yukarı kaydırın",
        "下滑解锁" to "Kilidi açmak için aşağı kaydırın",
        "右滑解锁" to "Kilidi açmak için sağa kaydırın",
        "左滑解锁" to "Kilidi açmak için sola kaydırın",
        "上滑" to "Yukarı Kaydır",
        "下滑" to "Aşağı Kaydır",
        "左滑" to "Sola Kaydır",
        "右滑" to "Sağa Kaydır",
        "左滑进入" to "Sola kaydırın",
        "右滑进入" to "Sağa kaydırın",
        "左滑负一屏" to "Asistan için sola kaydırın",
        "右滑相机" to "Kamera için sağa kaydırın",
        "上滑相机" to "Kamera için yukarı kaydırın",
        "长按自定义" to "Özelleştirmek için basılı tutun",
        "长按编辑" to "Düzenlemek için basılı tutun",
        "长按进入自定义" to "Özelleştirmek için basılı tutun",
        "长按进入设置" to "Ayarları açmak için basılı tutun",
        "点击进入" to "Açmak için dokunun",
        "点击进入设置" to "Ayarları açmak için dokunun",
        "点击进入自定义" to "Özelleştirmek için dokunun",
        "点击更换" to "Değiştirmek için dokunun",
        "点击切换" to "Geçiş yapmak için dokunun",
        "点击刷新" to "Yenilemek için dokunun",

        // Lockscreen & System Settings
        "锁屏设置" to "Kilit Ekranı Ayarları",
        "锁屏自定义" to "Kilit Ekranı Özelleştirme",
        "锁屏样式" to "Kilit Ekranı Stili",
        "锁屏" to "Kilit Ekranı",
        "桌面设置" to "Ana Ekran Ayarları",
        "桌面" to "Ana Ekran",
        "壁纸设置" to "Duvar Kâğıdı Ayarları",
        "壁纸" to "Duvar Kâğıdı",
        "时钟样式" to "Saat Stili",
        "时钟" to "Saat",
        "时间样式" to "Saat Biçimi",
        "时间" to "Zaman",
        "日期样式" to "Tarih Stili",
        "日期" to "Tarih",
        "字体样式" to "Yazı Tipi Stili",
        "字体" to "Yazı Tipi",
        "颜色设置" to "Renk Ayarları",
        "快捷方式" to "Kısayollar",
        "小部件" to "Araçlar",
        "负一屏" to "Akıllı Asistan",
        "控制中心" to "Kontrol Merkezi",
        "通知中心" to "Bildirim Paneli",
        "通知" to "Bildirim",
        "状态栏" to "Durum Çubuğu",
        "暂无通知" to "Bildirim yok",
        "无通知" to "Bildirim yok",
        "暂无日程" to "Yaklaşan etkinlik yok",
        "无日程" to "Etkinlik yok",
        "日程" to "Etkinlikler",
        "未读消息" to "Okunmamış mesaj",
        "紧急呼叫" to "Acil Arama",
        "无SIM卡" to "SIM kart yok",
        "请插入SIM卡" to "Lütfen SIM kart takın",
        "未连接" to "Bağlı değil",
        "已连接" to "Bağlandı",
        "正在播放" to "Çalıyor",
        "播放" to "Çal",
        "暂停" to "Duraklatıldı",
        "上一首" to "Önceki Parça",
        "下一首" to "Sonraki Parça",
        "音量" to "Ses Düzeyi",
        "亮度" to "Parlaklık",
        "勿扰模式" to "Rahatsız Etmeyin",
        "勿扰" to "Rahatsız Etmeyin",
        "静音模式" to "Sessiz Mod",
        "静音" to "Sessiz",
        "振动模式" to "Titreşim Modu",
        "振动" to "Titreşim",
        "飞行模式" to "Uçak Modu",
        "蓝牙" to "Bluetooth",
        "开启" to "Açık",
        "打开" to "Açık",
        "关闭" to "Kapalı",
        "显示" to "Göster",
        "隐藏" to "Gizle",
        "默认" to "Varsayılan",
        "自定义" to "Özel",
        "样式" to "Stil",
        "颜色" to "Renk",
        "大小" to "Boyut",
        "位置" to "Konum",
        "透明度" to "Opaklık",
        "模糊" to "Bulanıklık",
        "高斯模糊" to "Bulanıklık",
        "居中" to "Ortala",
        "居左" to "Sola Hizala",
        "居右" to "Sağa Hizala",
        "顶部" to "Üst",
        "底部" to "Alt",
        "黑色" to "Siyah",
        "白色" to "Beyaz",
        "红色" to "Kırmızı",
        "蓝色" to "Mavi",
        "绿色" to "Yeşil",
        "黄色" to "Sarı",
        "紫色" to "Mor",
        "橙色" to "Turuncu",
        "浅色" to "Açık",
        "深色" to "Koyu",
        "跟随系统" to "Sistemi Takip Et",
        "重置" to "Sıfırla",
        "保存" to "Kaydet",
        "取消" to "İptal",
        "确认" to "Onayla",
        "完成" to "Bitti",
        "返回" to "Geri",
        "更多" to "Daha Fazla",
        "是" to "Evet",
        "否" to "Hayır",

        // Apps & System
        "相机" to "Kamera",
        "手电筒" to "El Feneri",
        "计算器" to "Hesap Makinesi",
        "相册" to "Galeri",
        "音乐" to "Müzik",
        "视频" to "Video",
        "日历" to "Takvim",
        "闹钟" to "Alarm",
        "设置" to "Ayarlar",
        "浏览器" to "Tarayıcı",
        "电话" to "Telefon",
        "短信" to "Mesajlar",
        "通讯录" to "Kişiler",
        "联系人" to "Kişiler",
        "录音机" to "Ses Kaydedici",
        "便签" to "Notlar",
        "笔记" to "Notlar",
        "文件管理" to "Dosya Yöneticisi",
        "主题壁纸" to "Temalar",
        "应用商店" to "Uygulama Mağazası",
        "主题" to "Tema",
        "作者" to "Yazar",
        "设计" to "Tasarım",
        "设计师" to "Tasarımcı",
        "版本" to "Sürüm",
    )

    private val CHINESE_PIVOT_TERMS = linkedMapOf(
        "息屏空间" to "always-on display area", "萌宠动画" to "animated pet",
        "运营商标" to "carrier logo", "商标符号" to "carrier logo",
        "雨量图" to "precipitation chart", "悬浮球" to "floating button",
        "唱片机" to "turntable player", "报时" to "spoken time",
        "横屏" to "landscape mode", "光栅" to "lenticular", "小米" to "Xiaomi",
        "高斯模糊" to "Gaussian blur", "显示方式" to "display mode",
        "位置调整" to "position adjustment", "大小调整" to "size adjustment",
        "默认开启" to "enabled by default", "默认关闭" to "disabled by default",
        "开关" to "toggle", "指纹" to "fingerprint", "百变刘海" to "customizable notch",
    )

    private val ENGLISH_DICTIONARY: Map<String, String> = mapOf(
        "晴" to "Sunny",
        "晴天" to "Sunny",
        "多云" to "Partly Cloudy",
        "少云" to "Mostly Sunny",
        "阴" to "Overcast",
        "阴天" to "Overcast",
        "阵雨" to "Showers",
        "雷阵雨" to "Thunderstorms",
        "雷雨" to "Thunderstorms",
        "小雨" to "Light Rain",
        "中雨" to "Moderate Rain",
        "大雨" to "Heavy Rain",
        "暴雨" to "Downpour",
        "雨夹雪" to "Sleet",
        "小雪" to "Light Snow",
        "大雪" to "Heavy Snow",
        "雾" to "Foggy",
        "霾" to "Hazy",
        "充电中" to "Charging",
        "正在充电" to "Charging",
        "未充电" to "Not Charging",
        "已充满" to "Fully charged",
        "充满" to "Fully Charged",
        "电量" to "Battery",
        "剩余电量" to "Remaining Battery",
        "快充" to "Fast Charging",
        "超级快充" to "Super Fast Charging",
        "星期一" to "Monday",
        "星期二" to "Tuesday",
        "星期三" to "Wednesday",
        "星期四" to "Thursday",
        "星期五" to "Friday",
        "星期六" to "Saturday",
        "星期日" to "Sunday",
        "周一" to "Monday",
        "周二" to "Tuesday",
        "周三" to "Wednesday",
        "周四" to "Thursday",
        "周五" to "Friday",
        "周六" to "Saturday",
        "周日" to "Sunday",
        "今天" to "Today",
        "明天" to "Tomorrow",
        "昨天" to "Yesterday",
        "上午" to "AM",
        "下午" to "PM",
        "步" to "Steps",
        "步数" to "Step Count",
        "滑动解锁" to "Swipe to unlock",
        "上滑解锁" to "Swipe up to unlock",
        "双击锁屏" to "Double tap to lock",
        "长按自定义" to "Long press to customize",
        "锁屏设置" to "Lock Screen Settings",
        "时钟样式" to "Clock Style",
        "相机" to "Camera",
        "手电筒" to "Flashlight",
        "设置" to "Settings",
        "是" to "Yes",
        "否" to "No",
    )
}
