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
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
    // OCR selection is deliberately based on pixels, never on a global image count.
    private val openCvReady by lazy { context?.let { OpenCVUtils.init(it) } == true }

    fun rewrite(source: Path, output: Path): Result {
        require(source.toAbsolutePath().normalize() != output.toAbsolutePath().normalize())
        val selectedImages = selectImages(source)
        val totalImages = selectedImages.values.sumOf(Set<String>::size).coerceAtLeast(1)
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
                            if (isComponentEntry(entry)) {
                                val nestedPath = Files.createTempFile(output.parent, ".mtz-ocr-", ".zip")
                                try {
                                    outer.getInputStream(entry).use { input ->
                                        Files.newOutputStream(nestedPath).use { input.copyTo(it) }
                                    }
                                    ZipFile(nestedPath.toFile()).use { nested ->
                                        val selected = selectedImages[entry.name].orEmpty()
                                        ZipOutputStream(out.nonClosing()).use { nestedOut ->
                                            nested.entries().asSequence().forEach { asset ->
                                                nestedOut.putNextEntry(ZipEntry(asset.name).apply { time = asset.time })
                                                if (!asset.isDirectory) {
                                                    val replacement = if (asset.name in selected) {
                                                        val bytes = nested.getInputStream(asset).use { it.readBytes() }
                                                        processedImages++
                                                        val value = try { scanImage(bytes, asset.name, recognizer, paddle, writeChanges = true) } catch (_: Exception) { null } ?: bytes
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
        val selectedImages = selectImages(source)
        val totalImages = selectedImages.values.sumOf(Set<String>::size).coerceAtLeast(1)
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
                    .filter { isComponentEntry(it) }
                    .forEach { component ->
                        val nestedPath = Files.createTempFile(source.parent, ".mtz-ocr-scan-", ".zip")
                        try {
                            outer.getInputStream(component).use { input ->
                                Files.newOutputStream(nestedPath).use { input.copyTo(it) }
                            }
                            ZipFile(nestedPath.toFile()).use { nested ->
                                val selected = selectedImages[component.name].orEmpty()
                                nested.entries().asSequence().forEach { asset ->
                                    if (!asset.isDirectory && asset.name in selected) {
                                        val bytes = nested.getInputStream(asset).use { it.readBytes() }
                                        processedImages++
                                        runCatching { scanImage(bytes, asset.name, recognizer, paddle, writeChanges = false) }
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

    /**
     * A nested zip entry is a component when it is a file whose final path segment
     * has no extension and is not one of the known non-component entries.
     */
    private fun isComponentEntry(entry: ZipEntry): Boolean {
        if (entry.isDirectory) return false
        val segment = entry.name.substringAfterLast('/')
        return '.' !in segment && segment !in NON_COMPONENT_ENTRIES
    }

    private fun selectImages(source: Path): Map<String, Set<String>> {
        val result = linkedMapOf<String, Set<String>>()
        ZipFile(source.toFile()).use { outer ->
            outer.entries().asSequence()
                .filter { isComponentEntry(it) }
                .forEach { component ->
                    val nestedPath = Files.createTempFile(source.parent, ".mtz-ocr-select-", ".zip")
                    try {
                        outer.getInputStream(component).use { input ->
                            Files.newOutputStream(nestedPath).use { input.copyTo(it) }
                        }
                        ZipFile(nestedPath.toFile()).use { nested ->
                            // Do not trust an MTZ manifest or a filename to decide whether an
                            // image contains text.  Widgets and clock faces commonly store their
                            // labels under opaque names or omit their manifest references.
                            val decodable = linkedSetOf<String>()
                            val textLike = linkedSetOf<String>()
                            nested.entries().asSequence()
                                .filter { !it.isDirectory && mayContainRaster(it.name) }
                                .forEach { entry ->
                                    val bytes = nested.getInputStream(entry).use { it.readBytes() }
                                    if (isEligibleImage(bytes)) {
                                        decodable += entry.name
                                        if (isLikelyTextAsset(bytes)) textLike += entry.name
                                    }
                                }
                            // A content gate may be imperfect on a highly stylised theme.  Never
                            // let it suppress an entire component (especially lockscreen or
                            // clock widgets): OCR is the safe, local fallback in that case.
                            result[component.name] = if (textLike.isNotEmpty()) textLike else decodable
                        }
                    } finally {
                        Files.deleteIfExists(nestedPath)
                    }
                }
        }
        return result
    }

    private fun scanImage(
        bytes: ByteArray,
        name: String,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        paddle: PaddleOCR?,
        writeChanges: Boolean,
    ): ByteArray? {
        if (!isEligibleImage(bytes)) return null
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        scannedImages++
        try {
            val scale = if (source.width < 700 && source.height < 700) 2 else 1
            val observed = if (scale == 2) Bitmap.createScaledBitmap(source, source.width * 2, source.height * 2, true) else source
            val lines = try {
                fun recognizeWithMlKit(): List<DetectedLine> =
                    Tasks.await(recognizer.process(InputImage.fromBitmap(observed, 0)), 30, TimeUnit.SECONDS)
                        .textBlocks.flatMap { it.lines }
                        .mapNotNull { line -> line.boundingBox?.let { DetectedLine(line.text, Rect(it), line.confidence ?: .65f) } }

                if (paddle != null) {
                    runCatching {
                        runBlocking {
                            paddle.recognize(observed).results
                                .filter { it.confidence >= 0.45f }
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
                    if (line.confidence >= HIGH_CONFIDENCE) highConfidenceLabels++
                    else mediumConfidenceLabels++
                }
                return null
            }
            val plans = mutableListOf<Pair<String, Plan>>()
            lines.forEach { line ->
                if (!CJK.containsMatchIn(line.text)) return@forEach
                if (line.confidence >= HIGH_CONFIDENCE) highConfidenceLabels++ else mediumConfidenceLabels++
                val originalBox = line.box
                val box = Rect(originalBox.left / scale, originalBox.top / scale,
                    (originalBox.right + scale - 1) / scale, (originalBox.bottom + scale - 1) / scale)
                if (box.width() < 8 || box.height() < 8) { skippedLabels++; return@forEach }
                val translated = runCatching { translate(line.text.trim()) }.getOrNull()?.trim().orEmpty()
                if (translated.isBlank() || CJK.containsMatchIn(translated) || translated == line.text.trim()) {
                    skippedLabels++
                    return@forEach
                }
                val plan = planText(source, box, translated, sourceBoxes, scale)
                if (plan == null) { skippedLabels++; return@forEach }
                plans += translated to plan
            }
            // Commit every label that could be planned. A partial replacement leaves some
            // Chinese untranslated, but that is strictly better than discarding the whole
            // image's translations, so one unplannable label no longer discards the rest.
            if (plans.isEmpty()) return null
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
                canvas.save()
                canvas.clipRect(plan.region)
                canvas.drawText(translated, plan.region.centerX() - foreground.measureText(translated) / 2f, baseline, foreground)
                canvas.restore()
            }
            val stream = ByteArrayOutputStream()
            // JPEG must remain JPEG: some MTZ renderers select a decoder from the filename,
            // rather than sniffing the image header.  PNG/WebP retain their existing format.
            val format = when {
                name.endsWith(".png", true) -> Bitmap.CompressFormat.PNG
                name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> Bitmap.CompressFormat.JPEG
                else -> Bitmap.CompressFormat.WEBP
            }
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
        if (height < 12f || box.left < 4 || box.right > bitmap.width - 4) return null
        val tight = RectF(box.left - 3f, max(1f, box.top - height * .15f),
            box.right + 3f, min(bitmap.height - 1f, box.bottom + height * .15f))
        val bg = sampleBackground(bitmap, tight, box) ?: return null
        // A transparent button asset is composited over an unknown wallpaper at runtime.
        // Choosing white or black from the asset alone can yield invisible text, so leave it.
        if (Color.alpha(bg) < 245) return null
        val surface = solidSurfaceSpan(bitmap, box, bg)
        val width = (surface.second - surface.first - 4f).coerceAtMost(box.width() * 2.5f)
        if (width < box.width() || width > bitmap.width - 4f) return null
        val left = (box.centerX() - width / 2f).coerceIn(surface.first + 2f, surface.second - width - 2f)
        val region = RectF(left, tight.top, left + width, tight.bottom)
        if (allBoxes.any { other ->
                val projected = Rect(other.left / scale, other.top / scale, other.right / scale, other.bottom / scale)
                (abs(projected.centerX() - box.centerX()) > 1 || abs(projected.centerY() - box.centerY()) > 1) &&
                    RectF.intersects(region, RectF(projected))
            }) return null
        val foreground = if ((Color.red(bg) * .299 + Color.green(bg) * .587 + Color.blue(bg) * .114) > 145) Color.BLACK else Color.WHITE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var font = min(48f, height * .91f)
        while (font >= max(8f, height * .35f)) {
            paint.textSize = font
            if (paint.measureText(text) <= region.width() - 5f && font <= region.height() * .9f)
                return Plan(region, bg, foreground, font)
            font -= 1f
        }
        return null
    }

    /** Finds the actual solid-color button surface on a row outside the detected glyphs. */
    private fun solidSurfaceSpan(bitmap: Bitmap, box: Rect, background: Int): Pair<Int, Int> {
        val rows = listOf(box.top - 3, box.bottom + 2).filter { it in 0 until bitmap.height }
        for (row in rows) {
            if (!sameSurface(bitmap.getPixel(box.centerX(), row), background)) continue
            var left = box.centerX()
            var right = box.centerX()
            while (left > 1 && sameSurface(bitmap.getPixel(left - 1, row), background)) left--
            while (right < bitmap.width - 2 && sameSurface(bitmap.getPixel(right + 1, row), background)) right++
            if (left <= box.left - 2 && right >= box.right + 2) return left to (right + 1)
        }
        // The edge was not measurable: only the original label area is safe to repaint.
        return (box.left - 3) to (box.right + 3)
    }

    private fun sameSurface(pixel: Int, background: Int): Boolean =
        Color.alpha(pixel) >= 245 &&
            abs(Color.red(pixel) - Color.red(background)) <= 15 &&
            abs(Color.green(pixel) - Color.green(background)) <= 15 &&
            abs(Color.blue(pixel) - Color.blue(background)) <= 15

    private fun sampleBackground(bitmap: Bitmap, region: RectF, box: Rect): Int? {
        val samples = ArrayList<Int>()
        for (y in region.top.toInt() until region.bottom.toInt()) {
            for (x in region.left.toInt() until region.right.toInt()) {
                if (x in (box.left - 2)..(box.right + 2) && y in (box.top - 2)..(box.bottom + 2)) continue
                samples += bitmap.getPixel(x, y)
            }
        }
        if (samples.size < 12) return null
        if (samples.count { Color.alpha(it) < 16 } >= samples.size * .9) return Color.TRANSPARENT
        val opaque = samples.filter { Color.alpha(it) >= 245 }
        if (opaque.size < samples.size * .9) return null
        // Antialiased glyph edges leak a few tinted pixels beyond OCR's box.
        // A median surface estimate tolerates those pixels while still
        // rejecting gradients, photos and multi-colour illustrations.
        val red = opaque.map(Color::red).sorted()[opaque.size / 2]
        val green = opaque.map(Color::green).sorted()[opaque.size / 2]
        val blue = opaque.map(Color::blue).sorted()[opaque.size / 2]
        if (opaque.count {
                abs(Color.red(it) - red) <= 18 && abs(Color.green(it) - green) <= 18 && abs(Color.blue(it) - blue) <= 18
            } < opaque.size * .85
        ) return null
        return Color.rgb(red, green, blue)
    }

    /**
     * Retains every asset that BitmapFactory may plausibly decode.  File extensions are only
     * used to avoid known non-raster payloads; extension-less bitmaps are intentionally kept.
     */
    private fun mayContainRaster(name: String): Boolean {
        val lower = name.lowercase()
        return NON_RASTER_SUFFIXES.none(lower::endsWith)
    }

    private fun isEligibleImage(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    /**
     * Cheap, high-recall local gate before either OCR engine receives an image.  It analyses a
     * sampled bitmap, so a 4K clock face costs roughly the same as a small icon.  The gate is
     * deliberately permissive: a false positive costs one local OCR pass, while a false
     * negative would permanently hide a translated label from the user.
     */
    private fun isLikelyTextAsset(bytes: ByteArray): Boolean {
        if (!openCvReady) return true
        val preview = decodeForPreflight(bytes) ?: return false
        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val darkText = Mat()
        val lightText = Mat()
        val mask = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            Utils.bitmapToMat(preview, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
            Imgproc.Canny(blurred, edges, 55.0, 145.0)
            val edgeDensity = Core.countNonZero(edges).toFloat() / (edges.rows() * edges.cols())

            Imgproc.adaptiveThreshold(
                blurred, darkText, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 7.0,
            )
            Imgproc.adaptiveThreshold(
                blurred, lightText, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 7.0,
            )
            Core.bitwise_or(darkText, lightText, mask)

            val componentCount = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
            val minimumHeight = max(4, preview.height / 260)
            val maximumHeight = max(minimumHeight + 1, preview.height / 2)
            val minimumArea = max(6, preview.width * preview.height / 180_000)
            var glyphs = 0
            val rows = hashMapOf<Int, Int>()

            for (label in 1 until componentCount) {
                val x = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
                val width = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val height = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
                val aspect = width.toFloat() / height.coerceAtLeast(1)
                if (x >= 0 && y >= 0 && area >= minimumArea && width >= 2 &&
                    height in minimumHeight..maximumHeight && aspect in 0.07f..14f
                ) {
                    glyphs++
                    rows[y / max(minimumHeight, 8)] = (rows[y / max(minimumHeight, 8)] ?: 0) + 1
                }
            }

            val lineLikeGroups = rows.values.count { it >= 2 }
            // Keep ambiguous assets.  Text may be one or two large CJK glyphs in a widget.
            return glyphs >= 3 || lineLikeGroups > 0 || (glyphs >= 1 && edgeDensity in 0.004f..0.30f)
        } catch (_: Exception) {
            // A native OpenCV failure must never turn into a missed translation candidate.
            return true
        } finally {
            preview.recycle()
            rgba.release(); gray.release(); blurred.release(); edges.release()
            darkText.release(); lightText.release(); mask.release()
            labels.release(); stats.release(); centroids.release()
        }
    }

    private fun decodeForPreflight(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > PREFLIGHT_MAX_SIDE || bounds.outHeight / sample > PREFLIGHT_MAX_SIDE) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun ZipOutputStream.nonClosing(): OutputStream = object : OutputStream() {
        override fun write(b: Int) = this@nonClosing.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = this@nonClosing.write(b, off, len)
        override fun flush() = this@nonClosing.flush()
        override fun close() = Unit
    }

    internal companion object {
        val CJK = Regex("[\\p{IsHan}]")
        const val HIGH_CONFIDENCE = .85f
        const val PREFLIGHT_MAX_SIDE = 1024
        // Entries that look like components but are not theme components.
        val NON_COMPONENT_ENTRIES = setOf("preview", "icons", "description.xml", "theme_values.xml", "wallpaper", "res", "raw", "fonts", "audio", "boots")
        val NON_RASTER_SUFFIXES = setOf(
            ".xml", ".json", ".maml", ".txt", ".js", ".css", ".ttf", ".otf", ".mp3", ".wav", ".ogg", ".zip",
        )

        /** Counts the same image-content candidates that the local OCR stage will receive. */
        fun countEligibleImages(source: Path, context: Context): Int =
            ExperimentalThemeOcrLocalizer({ it }, context = context)
                .selectImages(source)
                .values
                .sumOf(Set<String>::size)
    }
}
