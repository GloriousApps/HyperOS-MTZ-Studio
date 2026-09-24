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
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Localized replacements for Super Duo's text baked into widget-picker previews. */
internal class ThemeWidgetPreviewLocalizer(private val targetLanguage: String) {
    data class Result(val scannedImages: Int, val changedImages: Int)

    fun rewrite(source: Path, output: Path): Result {
        require(source.toAbsolutePath().normalize() != output.toAbsolutePath().normalize())
        if (!targetLanguage.startsWith("tr")) {
            Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING)
            return Result(0, 0)
        }
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
                                    val matchingTheme = manifest != null && lockscreen.getInputStream(manifest).use {
                                        it.bufferedReader().readText().let { xml ->
                                            "versions_text" in xml && "20260910" in xml && "widget_01_preview.png" in xml
                                        }
                                    }
                                    ZipOutputStream(rewritten.nonClosing()).use { nestedOut ->
                                        lockscreen.entries().asSequence().forEach { component ->
                                            nestedOut.putNextEntry(ZipEntry(component.name).apply { time = component.time })
                                            if (!component.isDirectory) {
                                                val bytes = lockscreen.getInputStream(component).use { it.readBytes() }
                                                val replacement = if (matchingTheme && PREVIEW.matches(component.name)) {
                                                    scanned++
                                                    render(component.name, bytes)
                                                } else null
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
        val gray = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(216, 216, 216) }
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
        val PREVIEW = Regex("advance/menu/widget_(01|2)_preview_(\\d+)\\.png")
        val LOCALIZED_PREVIEWS = setOf("01_0", "01_1", "01_2", "01_6", "01_7", "01_8", "2_0", "2_1", "2_2", "2_3", "2_4", "2_5")
    }
}
