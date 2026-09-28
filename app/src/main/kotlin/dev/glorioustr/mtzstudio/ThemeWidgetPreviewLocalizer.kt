package dev.glorioustr.mtzstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Localizes known text-bearing theme artwork without flattening unrelated images. */
internal class ThemeWidgetPreviewLocalizer(
    private val targetLanguage: String,
    private val translate: (String) -> String = { it },
) {
    data class Result(val scannedImages: Int, val changedImages: Int)

    fun rewrite(source: Path, output: Path): Result {
        require(source.toAbsolutePath().normalize() != output.toAbsolutePath().normalize())
        var scanned = 0
        var changed = 0
        ZipFile(source.toFile()).use { archive ->
            ZipOutputStream(Files.newOutputStream(output)).use { rewritten ->
                archive.entries().asSequence().forEach { entry ->
                    rewritten.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    if (!entry.isDirectory) {
                        if (entry.name == "lockscreen") {
                            val nested = Files.createTempFile(output.parent, ".widget-preview-", ".zip")
                            try {
                                archive.getInputStream(entry).use { input -> Files.newOutputStream(nested).use(input::copyTo) }
                                ZipFile(nested.toFile()).use { lockscreen ->
                                    val manifest = lockscreen.getEntry("advance/manifest.xml")
                                    val manifestText = manifest?.let { lockscreen.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }.orEmpty()
                                    val matchingTheme = targetLanguage.startsWith("tr") && manifestText.let { xml ->
                                        "versions_text" in xml && "20260910" in xml && "widget_01_preview.png" in xml
                                    }
                                    val matchingButtonTheme = manifestText.let { xml ->
                                        "anniu/sz.png" in xml && "anniu/lddk.png" in xml && "anniu/yyfwg.png" in xml
                                    }
                                    // Some MAML themes keep separate normal/pressed artwork. OCR may
                                    // correctly translate the high-contrast `p` asset while the
                                    // customization screen actually displays the dark `n` sibling.
                                    // Recognize this artwork family from its manifest structure and
                                    // localize both states together.
                                    val matchingSuper19Artwork = targetLanguage.startsWith("tr") && manifestText.let { xml ->
                                        "src=\"1p.png\"" in xml &&
                                            "src=\"zj/zj1p.png\"" in xml &&
                                            "@Rec_colord8" in xml
                                    }
                                    ZipOutputStream(rewritten.nonClosing()).use { nestedOut ->
                                        lockscreen.entries().asSequence().forEach { component ->
                                            nestedOut.putNextEntry(ZipEntry(component.name).apply { time = component.time })
                                            if (!component.isDirectory) {
                                                val bytes = lockscreen.getInputStream(component).use { it.readBytes() }
                                                val replacement = when {
                                                    matchingSuper19Artwork && component.name == "advance/manifest.xml" ->
                                                        rewriteSuper19Manifest(bytes)
                                                    matchingTheme && component.name == "advance/manifest.xml" ->
                                                        rewriteSuperDuoManifest(bytes)
                                                    matchingSuper19Artwork && component.name in SUPER19_ARTWORK -> {
                                                        scanned++
                                                        renderSuper19Artwork(component.name, bytes)
                                                    }
                                                    matchingTheme && PREVIEW.matches(component.name) -> {
                                                        scanned++
                                                        render(component.name, bytes)
                                                    }
                                                    matchingButtonTheme && component.name in BUTTON_LABELS -> {
                                                        scanned++
                                                        renderButton(component.name, bytes)
                                                    }
                                                    else -> null
                                                }
                                                if (replacement != null) {
                                                    nestedOut.write(replacement)
                                                    changed++
                                                } else nestedOut.write(bytes)
                                            }
                                            nestedOut.closeEntry()
                                        }
                                    }
                                }
                            } finally {
                                Files.deleteIfExists(nested)
                            }
                        } else archive.getInputStream(entry).use { it.copyTo(rewritten) }
                    }
                    rewritten.closeEntry()
                }
            }
        }
        return Result(scanned, changed)
    }

    private fun rewriteSuper19Manifest(bytes: ByteArray): ByteArray {
        val source = bytes.toString(Charsets.UTF_8)
        val localized = source
            // The generic translator treated this date-format expression as prose.
            .replace(
                "textExp=\"'M ay d gün e Ay takvimi n ay e'\"",
                "textExp=\"formatDate('MMMM d EEEE',#time_sys)\"",
            )
            // The theme's lunar variables are exposed as numeric engine values on
            // some HyperOS builds (for example 18/71). Use stable Gregorian cards.
            .replace(
                "textExp=\"formatDate('d',#time_sys)+' '+formatDate('MMMM',#time_sys)+' '+formatDate('E',#time_sys)+'·'+#year_lunar+'Çin takvimi '+#date_lunar+'/'+(#month_lunar+1)+''\"",
                "textExp=\"formatDate('d MMM E',#time_sys)+' · '+formatDate('yyyy',#time_sys)\"",
            )
            .replace(
                "textExp=\"formatDate('M',#time_sys)+'·'+formatDate('d',#time_sys)+' Çin takvimi '+#date_lunar+'/'+(#month_lunar+1)+''\"",
                "textExp=\"formatDate('M.d E',#time_sys)\"",
            )
            // NNNN is a MAML lunar-month token and produces Han glyphs at runtime.
            .replace("format=\"d E NNNN\"", "format=\"d E MMMM\"")
            .replace("+'Çin takvimi '", "+'Ay takvimi '")
            .replace("+' Çin takvimi '", "+' · Ay takvimi '")
        return localized.toByteArray(Charsets.UTF_8)
    }

    private fun rewriteSuperDuoManifest(bytes: ByteArray): ByteArray {
        var localized = bytes.toString(Charsets.UTF_8)
            // Date templates are MAML format expressions, not prose. The generic
            // translator otherwise turns their tokens into nonsensical sentences.
            .replace("textExp=\"'M ay d gün e t'\"", "textExp=\"formatDate('d MMMM E',#time_sys)\"")
            .replace("textExp=\"'YYA N-ay e'\"", "textExp=\"formatDate('yyyy',#time_sys)\"")
            .replace("textExp=\"'M ay d japon, yyyya, çift'\"", "textExp=\"formatDate('M.d E · yyyy',#time_sys)\"")
            // Widget position feedback shown inside the customization screen.
            .replace(
                "ifelse(#widget_order,'Küçük bileşen altına ayarlandı','Küçük bileşen transfer edildi')",
                "ifelse(#widget_order,'Widget alta taşındı','Widget üste taşındı')",
            )
            // Correct the generic translations used by the live widget layouts.
            .replace("'Güncel'+ifelse", "'Bugün · '+ifelse")
            .replace("'Tüketmek  '+", "'Yakılan  '+")
            .replace("'Tüketmek')", "'Yakılan')")
            .replace("Tempeggle", "Sıcaklık ")
            .replace("+' Çıkış '+", "+' Doğuş '+")
            .replace("+' Düşmek'", "+' Batış'")
            .replace("'Önyükleme '+ifelse", "'Açık kalma: '+ifelse")
            .replace("'Zaten önyükleme'", "'Açık kalma'")
            .replace("'Estimated battery life'", "'Tahmini pil ömrü'")
            .replace("'Bugünün kilidi açıldı'", "'Bugünkü kilit açma'")
            .replace("'Geri kalan'", "'Kalan bakiye'")
            .replace("'Cep telefonu çöpü'", "'Telefon temizliği'")
            .replace("#unlock_times+'Orta'", "#unlock_times+' kez'")
            .replace("'Mm puan ss saniye'", "'mm dk ss sn'")
            .replace("'Mm ne zaman'", "'k sa mm dk'")
            .replace("'Km puanlar'", "'k sa mm dk'")
            .replace("'D gün k'", "'d gün k sa'")
            .replace("'Geçerli güç'", "'Pil '")

        // These helper variables are visible values, but their machine-produced
        // translations retained Chinese minute suffixes. Replace the complete
        // generated variables while leaving the original comparison variables intact.
        val endurance = "ifelse(ge(int(#_enduranceTime/3600000),0),int(#_enduranceTime/3600000)+' sa ','')+int((#_enduranceTime%3600000)/60000)+' dk'"
        val charging = "ifelse(ge(int(#_leftChargeTime/3600000),1),int(#_leftChargeTime/3600000)+' sa ','')+int((#_leftChargeTime%3600000)/60000)+' dk sonra dolu'"
        val uptime = "ifelse(lt(#time,60000),int(#time/1000)+' sn',lt(#time,3600000),int(#time/60000)+' dk',lt(#time,86400000),int(#time/3600000)+' sa '+int((#time%3600000)/60000)+' dk',int(#time/86400000)+' gün '+int((#time%86400000)/3600000)+' sa')"
        fun replaceLocaleVariable(name: String, expression: String) {
            localized = Regex("""<Var name="$name".*?/>""").replace(
                localized,
                "<Var name=\"$name\" expression=\"$expression\" type=\"string\"/>",
            )
        }
        replaceLocaleVariable("__mtz_locale_3", charging)
        replaceLocaleVariable("__mtz_locale_4", endurance)
        replaceLocaleVariable("__mtz_locale_5", uptime)
        replaceLocaleVariable("__mtz_locale_6", endurance)
        replaceLocaleVariable("__mtz_locale_7", uptime)
        replaceLocaleVariable("__mtz_locale_8", charging)
        return localized.toByteArray(Charsets.UTF_8)
    }

    private fun renderSuper19Artwork(path: String, bytes: ByteArray): ByteArray? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        val canvas = Canvas(bitmap)
        val foreground = if (path.endsWith("p.png")) Color.WHITE else Color.rgb(16, 16, 16)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val clearPaint = Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        fun clearAll() = canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), clearPaint)
        fun text(value: String, x: Float, baseline: Float, maxWidth: Float, initialSize: Float, center: Boolean = false) {
            paint.textSize = initialSize
            while (paint.measureText(value) > maxWidth && paint.textSize > 8f) paint.textSize -= 1f
            val left = if (center) x - paint.measureText(value) / 2f else x
            canvas.drawText(value, left, baseline, paint)
        }
        fun line(left: Float, right: Float, y: Float, width: Float = 5f) {
            canvas.drawRoundRect(RectF(left, y, right, y + width), width / 2f, width / 2f, paint)
        }

        when (path.substringAfter("advance/")) {
            "1p.png", "1n.png" -> {
                clearAll()
                text("Pil %40", 8f, 23f, 180f, 18f)
                text("Güzel şeyler yakında", 8f, 48f, 190f, 14f)
                line(8f, 190f, 65f)
                text("Bulutlu · 26°", 215f, 24f, 160f, 18f)
                text("En yüksek 30°", 215f, 46f, 160f, 13f)
                text("En düşük 15°", 215f, 66f, 160f, 13f)
            }
            "2p.png", "2n.png", "2sp.png", "2sn.png" -> {
                // Preserve the four pictograms; only replace the tiny Han step label.
                canvas.drawRect(bitmap.width * .25f, bitmap.height * .57f, bitmap.width * .45f, bitmap.height.toFloat(), clearPaint)
                text("adım", bitmap.width * .35f, bitmap.height - 4f, bitmap.width * .18f, 13f, center = true)
            }
            "dyymrh.webp" -> {
                // This decorative music placeholder contains a small, rotated
                // "暂无封面" label. Cover only that label and retain the artwork.
                val centerX = bitmap.width * .226f
                val centerY = bitmap.height * .50f
                canvas.save()
                canvas.rotate(90f, centerX, centerY)
                val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(190, 24, 70, 69)
                }
                canvas.drawRoundRect(
                    RectF(centerX - 58f, centerY - 14f, centerX + 58f, centerY + 14f),
                    9f,
                    9f,
                    backdrop,
                )
                paint.color = Color.rgb(205, 220, 215)
                text("Kapak yok", centerX, centerY + 6f, 104f, 18f, center = true)
                canvas.restore()
            }
            "4p.png", "4n.png" -> {
                clearAll()
                text("Tutkunu koru", bitmap.width / 2f, 29f, bitmap.width - 12f, 24f, center = true)
                text("Yeni ufuklara ilerle", bitmap.width / 2f, 61f, bitmap.width - 12f, 22f, center = true)
            }
            "5p.png", "5n.png" -> {
                clearAll()
                text("Her gün güzel hisset", bitmap.width / 2f, 35f, bitmap.width - 12f, 25f, center = true)
                text("Güzel bir gün seni bekliyor", bitmap.width / 2f, 64f, bitmap.width - 12f, 17f, center = true)
            }
            "zj/zj1p.png", "zj/zj1n.png" -> {
                clearAll()
                text("Pil %40", 9f, 35f, bitmap.width - 18f, 27f)
                text("Güzel şeyler yakında", 9f, 77f, bitmap.width - 18f, 22f)
                line(9f, bitmap.width - 9f, 105f, 8f)
            }
            "zj/zj2p.png", "zj/zj2n.png" -> {
                clearAll()
                text("Bulutlu · 26°", bitmap.width / 2f, 40f, bitmap.width - 12f, 27f, center = true)
                text("En yüksek 30°", bitmap.width / 2f, 79f, bitmap.width - 12f, 22f, center = true)
                text("En düşük 15°", bitmap.width / 2f, 112f, bitmap.width - 12f, 22f, center = true)
            }
            "zj/zj3p.png", "zj/zj3n.png" -> {
                clearAll()
                text("26°", bitmap.width * .25f, 55f, bitmap.width * .42f, 34f, center = true)
                text("5059", bitmap.width * .75f, 55f, bitmap.width * .42f, 32f, center = true)
                text("adım", bitmap.width * .75f, 91f, bitmap.width * .42f, 20f, center = true)
            }
            "zj/zj7p.png", "zj/zj7n.png" -> {
                clearAll()
                text("Bulutlu", bitmap.width / 2f, 48f, bitmap.width - 12f, 31f, center = true)
                text("Bugün", bitmap.width / 2f, 91f, bitmap.width - 12f, 27f, center = true)
            }
            else -> {
                bitmap.recycle()
                return null
            }
        }
        val stream = ByteArrayOutputStream()
        val saved = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return if (saved) stream.toByteArray() else null
    }

    private fun renderButton(path: String, bytes: ByteArray): ByteArray? {
        val label = BUTTON_LABELS[path] ?: return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (decoded.width !in 290..310 || decoded.height !in 130..145) {
            decoded.recycle()
            return null
        }
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        val startX = if (label.hasIcon) 83f else 45f
        // These bitmaps have a rounded right edge. Keep the text and the
        // repaint inside the flat centre, not merely inside the PNG bounds.
        val endX = 260f
        val centerX = (startX + endX) / 2f
        val background = bitmap.getPixel(271, 42)
        val luminance = Color.red(background) * .299 + Color.green(background) * .587 + Color.blue(background) * .114
        val foreground = if (luminance > 145) Color.rgb(25, 25, 25) else Color.WHITE
        val title = if (targetLanguage.startsWith("tr")) label.turkishTitle else translate(label.chineseTitle)
        val subtitle = if (targetLanguage.startsWith("tr")) label.turkishSubtitle else translate(label.chineseSubtitle)
        if (title.isBlank() || subtitle.isBlank() || CJK.containsMatchIn(title) || CJK.containsMatchIn(subtitle)) {
            bitmap.recycle()
            return null
        }
        // Erase the original Han glyphs all the way to the edge while
        // retaining each pixel's alpha. A flat rectangle otherwise leaves a
        // visible square outside the capsule's rounded silhouette.
        for (y in 35 until 106) for (x in startX.toInt() until 281) {
            val alpha = Color.alpha(bitmap.getPixel(x, y))
            if (alpha != 0) bitmap.setPixel(x, y, Color.argb(alpha,
                Color.red(background), Color.green(background), Color.blue(background)))
        }
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        fun line(text: String, baseline: Float, maxSize: Float, minSize: Float) {
            paint.textSize = maxSize
            while (paint.measureText(text) > endX - startX - 8f && paint.textSize > minSize) paint.textSize -= 1f
            if (paint.measureText(text) <= endX - startX - 8f) {
                canvas.drawText(text, centerX - paint.measureText(text) / 2f, baseline, paint)
            }
        }
        line(title, 77f, 27f, 12f)
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        line(subtitle, 99f, 15f, 9f)
        val stream = ByteArrayOutputStream()
        val saved = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return if (saved) stream.toByteArray() else null
    }

    private fun render(path: String, bytes: ByteArray): ByteArray? {
        val match = PREVIEW.matchEntire(path) ?: return null
        val key = "${match.groupValues[1]}_${match.groupValues[2]}"
        if (key !in LOCALIZED_PREVIEWS) return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val image = decoded.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(image)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        // Preview images are tinted again by the MAML <Image> element. An opaque
        // gray fill therefore becomes a solid theme-color block and hides every
        // label beneath it. Keep surfaces as low-alpha masks like the originals.
        val gray = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 0, 0, 0) }
        fun clear(y: Float = 0f) = canvas.drawRect(0f, y, image.width.toFloat(), image.height.toFloat(),
            Paint().apply { xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        fun label(value: String, x: Float, baseline: Float, maxWidth: Float, size: Float, center: Boolean = false) {
            ink.textSize = size
            while (ink.measureText(value) > maxWidth && ink.textSize > 11f) ink.textSize -= 1f
            canvas.drawText(value, if (center) x - ink.measureText(value) / 2f else x, baseline, ink)
        }
        when (key) {
            "01_0" -> {
                clear(53f)
                label("Çok bulutlu", 2f, 104f, image.width - 4f, 34f)
                label("En yüksek 21° · en düşük 13°", 2f, 153f, image.width - 4f, 22f)
            }
            "01_1" -> {
                canvas.drawRect(0f, 64f, image.width.toFloat(), 125f,
                    Paint().apply { xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
                label("Pil düzeyi", 2f, 108f, image.width - 4f, 34f)
            }
            "01_2" -> {
                clear(75f)
                label("28 Mayıs Cuma", 2f, 113f, image.width - 4f, 28f)
                label("Ay takvimi", 2f, 154f, image.width - 4f, 24f)
            }
            "01_6" -> {
                clear()
                listOf("Mutlu kal", "İleriye bak", "Güzel şeyler bekle").forEachIndexed { index, value ->
                    val y = 43f + index * 57f
                    canvas.drawCircle(18f, y - 8f, 9f, ink)
                    label(value, 42f, y, image.width - 44f, 27f)
                }
            }
            "01_7" -> {
                clear()
                listOf("Adım: 340", "Mesafe: 56 km", "Yakılan: 983 kcal").forEachIndexed { index, value ->
                    label(value, 3f, 42f + index * 56f, image.width - 6f, 28f)
                }
            }
            "01_8" -> {
                clear()
                val days = listOf("Pt", "Sa", "Ça", "Pe", "Cu", "Ct", "Pa")
                val cell = image.width / 7f
                days.forEachIndexed { index, day -> label(day, cell * (index + .5f), 24f, cell - 2f, 19f, center = true) }
                (1..31).forEach { day ->
                    val slot = day + 3 // May 2026 starts on Friday.
                    val column = slot % 7
                    val row = slot / 7
                    label(day.toString(), cell * (column + .5f), 55f + row * 25f, cell - 3f, 19f, center = true)
                }
            }
            "2_0" -> {
                // Keep the other three preview dials intact; only the alarm dial contains baked-in Chinese.
                canvas.drawCircle(image.width - 69f, image.height / 2f, image.height * .49f, gray)
                val center = image.width - 69f
                canvas.drawCircle(center, 44f, 14f, Paint(ink).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                })
                canvas.drawLine(center, 44f, center, 36f, Paint(ink).apply { strokeWidth = 3f })
                canvas.drawLine(center, 44f, center + 7f, 44f, Paint(ink).apply { strokeWidth = 3f })
                label("Kapalı", center, 102f, 110f, 27f, center = true)
            }
            "2_1" -> {
                clear()
                val radius = minOf(image.height * .48f, image.width * .22f)
                listOf(image.width * .25f, image.width * .75f).forEach { x ->
                    canvas.drawCircle(x, image.height / 2f, radius, gray)
                }
                label("Alarm", image.width * .25f, 56f, radius * 1.8f, 28f, center = true)
                label("Kapalı", image.width * .25f, 94f, radius * 1.8f, 25f, center = true)
                label("37°", image.width * .75f, 55f, radius * 1.8f, 33f, center = true)
                label("Bulutlu", image.width * .75f, 94f, radius * 1.8f, 24f, center = true)
            }
            "2_2" -> {
                clear()
                val days = listOf("Bugün", "Yarın", "Pzt", "Sal", "Çar")
                val high = listOf("24°", "22°", "27°", "29°", "22°")
                days.forEachIndexed { index, day ->
                    val width = image.width / 5f
                    val left = index * width + 3f
                    canvas.drawRoundRect(RectF(left, 2f, left + width - 6f, image.height - 2f), 24f, 24f, gray)
                    label(day, left + width / 2f - 3f, 45f, width - 12f, 27f, center = true)
                    label("☀", left + width / 2f - 3f, 112f, width - 12f, 44f, center = true)
                    label(high[index], left + width / 2f - 3f, 172f, width - 12f, 29f, center = true)
                }
            }
            "2_3" -> {
                clear()
                canvas.drawRoundRect(RectF(2f, 2f, image.width - 2f, image.height - 2f), 27f, 27f, gray)
                label("Güneşli · 24°", 19f, 50f, image.width - 38f, 33f)
                label("Hafif rüzgâr", 19f, 105f, image.width - 38f, 31f)
                label("Doğuş 05:23 · Batış 18:25", 19f, 160f, image.width - 38f, 28f)
            }
            "2_4" -> {
                clear()
                canvas.drawRoundRect(RectF(2f, 2f, image.width - 2f, image.height - 2f), 27f, 27f, gray)
                listOf("25 saattir açık", "Bugün 78 MB kullanıldı", "Bugün 256 adım").forEachIndexed { index, value ->
                    label(value, 20f, 55f + index * 62f, image.width - 40f, 35f)
                }
            }
            "2_5" -> {
                clear()
                listOf("Huzur", "Sağlık", "Sevinç", "Neşe").forEachIndexed { index, value ->
                    val center = image.width * (index + .5f) / 4f
                    canvas.drawCircle(center, image.height / 2f, image.height * .47f, gray)
                    label(value, center, image.height / 2f + 10f, image.width / 4f - 12f, 27f, center = true)
                }
            }
        }
        val encoded = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, encoded)
        image.recycle()
        decoded.recycle()
        return encoded.toByteArray()
    }

    private fun ZipOutputStream.nonClosing(): java.io.OutputStream = object : java.io.OutputStream() {
        override fun write(b: Int) = this@nonClosing.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = this@nonClosing.write(b, off, len)
        override fun flush() = this@nonClosing.flush()
        override fun close() = Unit
    }

    private companion object {
        data class ButtonLabel(
            val chineseTitle: String,
            val chineseSubtitle: String,
            val turkishTitle: String,
            val turkishSubtitle: String,
            val hasIcon: Boolean,
        )
        val CJK = Regex("[\\p{IsHan}]")
        val BUTTON_LABELS = mapOf(
            "advance/anniu/sz.png" to ButtonLabel("更多设置", "壁纸/动画/文字/开关", "Diğer ayarlar", "Duvar · animasyon · yazı", true),
            "advance/anniu/zmmhk.png" to ButtonLabel("桌面模糊", "仅主题内置壁纸", "Duvar bulanıklığı", "Tema duvarında", true),
            "advance/anniu/zmmhg.png" to ButtonLabel("桌面模糊", "仅主题内置壁纸", "Duvar bulanıklığı", "Tema duvarında", true),
            "advance/anniu/yyfwg.png" to ButtonLabel("音乐氛围", "顶部模糊音乐封面", "Müzik atmosferi", "Üstte bulanık kapak", true),
            "advance/anniu/yyfwk.png" to ButtonLabel("音乐氛围", "顶部模糊音乐封面", "Müzik atmosferi", "Üstte bulanık kapak", true),
            "advance/anniu/lddk.png" to ButtonLabel("锁屏胶囊", "通知/音乐/手电", "Kilit kapsülü", "Bild. · müzik · fener", true),
            "advance/anniu/lddkg.png" to ButtonLabel("锁屏胶囊", "通知/音乐/手电", "Kilit kapsülü", "Bild. · müzik · fener", true),
            "advance/anniu/lddk2.png" to ButtonLabel("桌面胶囊", "无功能 只适用主题内置壁纸", "Ana ekran kapsülü", "Yalnız tema duvarı", false),
            "advance/anniu/lddkg2.png" to ButtonLabel("桌面胶囊", "无功能 只适用主题内置壁纸", "Ana ekran kapsülü", "Yalnız tema duvarı", false),
        )
        val PREVIEW = Regex("advance/menu/widget_(01|2)_preview_(\\d+)\\.png")
        val LOCALIZED_PREVIEWS = setOf("01_0", "01_1", "01_2", "01_6", "01_7", "01_8", "2_0", "2_1", "2_2", "2_3", "2_4", "2_5")
        val SUPER19_ARTWORK = setOf(
            "advance/1p.png", "advance/1n.png",
            "advance/2p.png", "advance/2n.png", "advance/2sp.png", "advance/2sn.png",
            "advance/4p.png", "advance/4n.png",
            "advance/5p.png", "advance/5n.png",
            "advance/dyymrh.webp",
            "advance/zj/zj1p.png", "advance/zj/zj1n.png",
            "advance/zj/zj2p.png", "advance/zj/zj2n.png",
            "advance/zj/zj3p.png", "advance/zj/zj3n.png",
            "advance/zj/zj7p.png", "advance/zj/zj7n.png",
        )
    }
}
