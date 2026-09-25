package dev.glorioustr.mtzstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Opt-in OCR for text baked into simple lock-screen and widget images. Never rewrites uncertain artwork. */
internal class ExperimentalThemeOcrLocalizer(
    private val translate: (String) -> String,
    private val onProgress: (Int, Int) -> Unit = { _, _ -> },
    private val context: Context? = null,
    private val preferPaddle: Boolean = false,
) {
    data class Result(
        val scannedImages: Int,
        val changedImages: Int,
        val translatedLabels: Int,
        val highConfidenceLabels: Int,
        val mediumConfidenceLabels: Int,
        val skippedLabels: Int,
    )

    private var scannedImages = 0
    private var changedImages = 0
    private var translatedLabels = 0
    private var highConfidenceLabels = 0
    private var mediumConfidenceLabels = 0
    private var skippedLabels = 0

    fun rewrite(source: Path, output: Path): Result {
        require(source.toAbsolutePath().normalize() != output.toAbsolutePath().normalize())
        val totalImages = countImages(source).coerceAtLeast(1)
        var processedImages = 0
        onProgress(0, totalImages)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val paddleContext = context
        val paddle = if (preferPaddle && paddleContext != null) runCatching {
            if (OpenCVUtils.init(paddleContext)) runBlocking { PaddleOCR.create(paddleContext) } else null
        }.getOrNull() else null
        try {
            ZipFile(source.toFile()).use { outer ->
                ZipOutputStream(Files.newOutputStream(output)).use { out ->
                    outer.entries().asSequence().forEach { entry ->
                        out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        if (!entry.isDirectory) {
                            if (entry.name == "lockscreen" || entry.name == "clock_2x4") {
                                val nestedPath = Files.createTempFile(output.parent, ".mtz-ocr-", ".zip")
                                try {
                                    outer.getInputStream(entry).use { input ->
                                        Files.newOutputStream(nestedPath).use { input.copyTo(it) }
                                    }
                                    ZipFile(nestedPath.toFile()).use { nested ->
                                        ZipOutputStream(out.nonClosing()).use { nestedOut ->
                                            nested.entries().asSequence().forEach { asset ->
                                                nestedOut.putNextEntry(ZipEntry(asset.name).apply { time = asset.time })
                                                if (!asset.isDirectory) {
                                                    val replacement = if (processedImages < totalImages && isImage(asset.name)) {
                                                        processedImages++
                                                        val value = if (asset.size in 1..MAX_IMAGE_BYTES) {
                                                            val bytes = nested.getInputStream(asset).use { it.readBytes() }
                                                            try { scanImage(bytes, asset.name, recognizer, paddle, writeChanges = true) } catch (_: Exception) { null } ?: bytes
                                                        } else null
                                                        onProgress(processedImages, totalImages)
                                                        value
                                                    } else null
                                                    if (replacement != null) nestedOut.write(replacement)
                                                    else nested.getInputStream(asset).use { it.copyTo(nestedOut) }
                                                }
                                                nestedOut.closeEntry()
                                            }
                                        }
                                    }
                                } finally {
                                    Files.deleteIfExists(nestedPath)
                                }
                            } else outer.getInputStream(entry).use { it.copyTo(out) }
                        }
                        out.closeEntry()
                    }
                }
            }
        } finally {
            recognizer.close()
            if (paddle != null) runCatching { runBlocking { paddle.release() } }
        }
        return Result(
            scannedImages,
            changedImages,
            translatedLabels,
            highConfidenceLabels,
            mediumConfidenceLabels,
            skippedLabels,
        )
    }

    /**
     * Detects likely visual translation candidates without touching the MTZ. This lets the
     * ordinary text translation finish first, then gives the user one informed OCR choice.
     */
    fun scanOnly(source: Path): Result {
        val totalImages = countImages(source).coerceAtLeast(1)
        var processedImages = 0
        onProgress(0, totalImages)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val paddleContext = context
        val paddle = if (preferPaddle && paddleContext != null) runCatching {
            if (OpenCVUtils.init(paddleContext)) runBlocking { PaddleOCR.create(paddleContext) } else null
        }.getOrNull() else null
        try {
            ZipFile(source.toFile()).use { outer ->
                outer.entries().asSequence()
                    .filter { it.name == "lockscreen" || it.name == "clock_2x4" }
                    .forEach { component ->
                        val nestedPath = Files.createTempFile(source.parent, ".mtz-ocr-scan-", ".zip")
                        try {
                            outer.getInputStream(component).use { input ->
                                Files.newOutputStream(nestedPath).use { input.copyTo(it) }
                            }
                            ZipFile(nestedPath.toFile()).use { nested ->
                                nested.entries().asSequence().forEach { asset ->
                                    if (!asset.isDirectory && processedImages < totalImages && isImage(asset.name)) {
                                        processedImages++
                                        if (asset.size in 1..MAX_IMAGE_BYTES) {
                                            val bytes = nested.getInputStream(asset).use { it.readBytes() }
                                            runCatching { scanImage(bytes, asset.name, recognizer, paddle, writeChanges = false) }
                                        }
                                        onProgress(processedImages, totalImages)
                                    }
                                }
                            }
                        } finally {
                            Files.deleteIfExists(nestedPath)
                        }
                    }
            }
        } finally {
            recognizer.close()
            if (paddle != null) runCatching { runBlocking { paddle.release() } }
        }
        return Result(
            scannedImages,
            changedImages,
            translatedLabels,
            highConfidenceLabels,
            mediumConfidenceLabels,
            skippedLabels,
        )
    }

    private fun countImages(source: Path): Int {
        var count = 0
        ZipFile(source.toFile()).use { outer ->
            outer.entries().asSequence().filter { it.name == "lockscreen" || it.name == "clock_2x4" }.forEach { component ->
                outer.getInputStream(component).use { stream ->
                    ZipInputStream(stream).use { nested ->
                        while (count < 250) {
                            val entry = nested.nextEntry ?: break
                            if (!entry.isDirectory && isImage(entry.name)) count++
                        }
                    }
                }
            }
        }
        return count
    }

    private fun scanImage(
        bytes: ByteArray,
        name: String,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        paddle: PaddleOCR?,
        writeChanges: Boolean,
    ): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 48..MAX_OCR_WIDTH || bounds.outHeight !in 24..MAX_OCR_HEIGHT ||
            bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS
        ) return null
        if (scannedImages >= 250) return null
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        scannedImages++
        try {
            val scale = if (source.width < 700 && source.height < 700) 2 else 1
            val observed = if (scale == 2) Bitmap.createScaledBitmap(source, source.width * 2, source.height * 2, true) else source
            val lines = try {
                fun recognizeWithMlKit(): List<DetectedLine> =
                    Tasks.await(recognizer.process(InputImage.fromBitmap(observed, 0)), 30, TimeUnit.SECONDS)
                        .textBlocks.flatMap { it.lines }
                        .mapNotNull { line -> line.boundingBox?.let { DetectedLine(line.text, Rect(it), .65f) } }

                if (paddle != null) {
                    runCatching {
                        runBlocking {
                            paddle.recognize(observed).results
                                .filter { it.confidence >= 0.55f }
                                .mapNotNull { item ->
                                    val points = item.box.points
                                    val left = points.minOf { it.x }.toInt()
                                    val top = points.minOf { it.y }.toInt()
                                    val right = points.maxOf { it.x }.toInt()
                                    val bottom = points.maxOf { it.y }.toInt()
                                    if (right > left && bottom > top) {
                                        DetectedLine(item.text, Rect(left, top, right, bottom), item.confidence)
                                    } else null
                                }
                        }
                    }.getOrElse { recognizeWithMlKit() }
                } else recognizeWithMlKit()
            } finally {
                if (observed !== source) observed.recycle()
            }
            val sourceBoxes = lines.map { it.box }
            if (!writeChanges) {
                lines.forEach { line ->
                    if (!CJK.containsMatchIn(line.text)) return@forEach
                    if (CJK.findAll(line.text).count() < 2) skippedLabels++
                    else if (line.confidence >= HIGH_CONFIDENCE) highConfidenceLabels++
                    else mediumConfidenceLabels++
                }
                return null
            }
            val plans = mutableListOf<Pair<String, Plan>>()
            var unsafe = false
            lines.forEach { line ->
                if (!CJK.containsMatchIn(line.text)) return@forEach
                if (CJK.findAll(line.text).count() < 2) { skippedLabels++; unsafe = true; return@forEach }
                if (line.confidence >= HIGH_CONFIDENCE) highConfidenceLabels++ else mediumConfidenceLabels++
                val originalBox = line.box
                val box = Rect(originalBox.left / scale, originalBox.top / scale,
                    (originalBox.right + scale - 1) / scale, (originalBox.bottom + scale - 1) / scale)
                if (box.width() < 8 || box.height() < 8) { skippedLabels++; unsafe = true; return@forEach }
                val translated = runCatching { translate(line.text.trim()) }.getOrNull()?.trim().orEmpty()
                if (translated.isBlank() || CJK.containsMatchIn(translated) || translated == line.text.trim()) {
                    skippedLabels++
                    unsafe = true
                    return@forEach
                }
                val plan = planText(source, box, translated, sourceBoxes, scale)
                if (plan == null) { skippedLabels++; unsafe = true; return@forEach }
                plans += translated to plan
            }
            // Partial replacement leaves mixed-language or damaged artwork. Commit only a complete image.
            if (unsafe || plans.isEmpty()) return null
            val result = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)
            plans.forEach { (translated, plan) ->
                val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = plan.background
                    if (Color.alpha(plan.background) == 0) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                canvas.drawRect(plan.region, background)
                val foreground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = plan.foreground
                    textSize = plan.fontSize
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                }
                val metrics = foreground.fontMetrics
                val baseline = plan.region.centerY() - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(translated, plan.region.centerX() - foreground.measureText(translated) / 2f, baseline, foreground)
            }
            val stream = ByteArrayOutputStream()
            val format = if (name.endsWith(".png", true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.WEBP
            val encoded = result.compress(format, 100, stream)
            result.recycle()
            if (!encoded) return null
            translatedLabels += plans.size
            changedImages++
            return stream.toByteArray()
        } finally {
            source.recycle()
        }
    }

    private data class Plan(val region: RectF, val background: Int, val foreground: Int, val fontSize: Float)
    private data class DetectedLine(val text: String, val box: Rect, val confidence: Float)

    private fun planText(bitmap: Bitmap, box: Rect, text: String, allBoxes: List<Rect>, scale: Int): Plan? {
        val height = box.height().toFloat()
        val desiredWidth = max(box.width().toFloat() + 8f, text.length * height * .55f + 12f)
        val width = min(desiredWidth, box.width() * 2.5f)
        if (width > bitmap.width - 4 || height < 12f) return null
        val candidateLefts = listOf(box.left.toFloat(), box.centerX() - width / 2f, box.right - width)
            .map { it.coerceIn(2f, bitmap.width - width - 2f) }.distinct()
        val chosen = candidateLefts.firstNotNullOfOrNull { left ->
            val candidate = RectF(left, max(1f, box.top - height * .15f), left + width,
                min(bitmap.height - 1f, box.bottom + height * .15f))
            val collides = allBoxes.any { other ->
                val projected = Rect(other.left / scale, other.top / scale, other.right / scale, other.bottom / scale)
                (abs(projected.centerX() - box.centerX()) > 1 || abs(projected.centerY() - box.centerY()) > 1) &&
                    RectF.intersects(candidate, RectF(projected))
            }
            if (collides) null else sampleBackground(bitmap, candidate, box)?.let { candidate to it }
        } ?: return null
        val (region, bg) = chosen
        val transparent = Color.alpha(bg) == 0
        val foreground = if (transparent) {
            val glyphs = ArrayList<Int>()
            for (y in box.top until box.bottom step max(1, box.height() / 8))
                for (x in box.left until box.right step max(1, box.width() / 8)) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) > 100) glyphs += pixel
                }
            if (glyphs.isNotEmpty() && glyphs.map { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3 }.average() > 150) Color.WHITE
            else Color.BLACK
        } else if ((Color.red(bg) * .299 + Color.green(bg) * .587 + Color.blue(bg) * .114) > 145) Color.BLACK else Color.WHITE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var font = min(48f, height * .91f)
        while (font >= max(12f, height * .48f)) {
            paint.textSize = font
            if (paint.measureText(text) <= region.width() - 5f && font <= region.height() * .9f)
                return Plan(region, bg, foreground, font)
            font -= 1f
        }
        return null
    }

    private fun sampleBackground(bitmap: Bitmap, region: RectF, box: Rect): Int? {
        val samples = ArrayList<Int>()
        for (y in region.top.toInt() until region.bottom.toInt()) {
            for (x in region.left.toInt() until region.right.toInt()) {
                if (x in (box.left - 2)..(box.right + 2) && y in (box.top - 2)..(box.bottom + 2)) continue
                samples += bitmap.getPixel(x, y)
            }
        }
        if (samples.size < 12) return null
        if (samples.all { Color.alpha(it) < 16 }) return Color.TRANSPARENT
        if (samples.any { Color.alpha(it) < 245 }) return null
        val red = samples.map(Color::red).average()
        val green = samples.map(Color::green).average()
        val blue = samples.map(Color::blue).average()
        if (samples.any { abs(Color.red(it) - red) > 15 || abs(Color.green(it) - green) > 15 || abs(Color.blue(it) - blue) > 15 }) return null
        return Color.rgb(red.toInt(), green.toInt(), blue.toInt())
    }

    private fun isImage(name: String): Boolean = !name.endsWith(".9.png", true) &&
        (name.endsWith(".png", true) || name.endsWith(".webp", true))

    private fun ZipOutputStream.nonClosing(): OutputStream = object : OutputStream() {
        override fun write(b: Int) = this@nonClosing.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = this@nonClosing.write(b, off, len)
        override fun flush() = this@nonClosing.flush()
        override fun close() = Unit
    }

    internal companion object {
        val CJK = Regex("[\\p{IsHan}]")
        const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
        const val HIGH_CONFIDENCE = .85f
        const val MAX_PIXELS = 5_000_000L
        // Larger assets tend to be composites or screenshots; do not paint partial text onto them.
        const val MAX_OCR_WIDTH = 800
        const val MAX_OCR_HEIGHT = 300

        /** Counts exactly the assets the OCR stage may inspect, for truthful progress. */
        fun countEligibleImages(source: Path): Int {
            var count = 0
            ZipFile(source.toFile()).use { outer ->
                outer.entries().asSequence()
                    .filter { it.name == "lockscreen" || it.name == "clock_2x4" }
                    .forEach { component ->
                        outer.getInputStream(component).use { stream ->
                            ZipInputStream(stream).use { nested ->
                                while (count < 250) {
                                    val entry = nested.nextEntry ?: break
                                    if (!entry.isDirectory && isEligibleImageName(entry.name)) count++
                                }
                            }
                        }
                    }
            }
            return count
        }

        private fun isEligibleImageName(name: String): Boolean = !name.endsWith(".9.png", true) &&
            (name.endsWith(".png", true) || name.endsWith(".webp", true))
    }
}
