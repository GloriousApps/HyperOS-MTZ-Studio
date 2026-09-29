package dev.glorioustr.mtzstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.paddle.ocr.util.OpenCVUtils
import dev.glorioustr.mtzstudio.core.OcrDetectionFusion
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
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
    private var paddleFallbackAttempts = 0
    // OCR selection is deliberately based on pixels, never on a global image count.
    private val openCvReady by lazy { context?.let { OpenCVUtils.init(it) } == true }

    fun rewrite(source: Path, output: Path): Result {
        require(source.toAbsolutePath().normalize() != output.toAbsolutePath().normalize())
        val selectedImages = selectImages(source)
        val totalImages = selectedImages.values.sumOf(Set<String>::size).coerceAtLeast(1)
        var processedImages = 0
        onProgress(0, totalImages)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
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
                                                        val value = try { scanImage(bytes, "${entry.name}!/${asset.name}", recognizer, writeChanges = true) } catch (_: Exception) { null } ?: bytes
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
                                        runCatching { scanImage(bytes, "${component.name}!/${asset.name}", recognizer, writeChanges = false) }
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
        writeChanges: Boolean,
    ): ByteArray? {
        if (!isEligibleImage(bytes)) return null
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        scannedImages++
        try {
            val scale = if (source.width < 700 && source.height < 700) 2 else 1
            val observed = if (scale == 2) Bitmap.createScaledBitmap(source, source.width * 2, source.height * 2, true) else source
            val lines = try {
                fun recognizeBitmap(bitmap: Bitmap): List<DetectedLine> =
                    Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
                        .textBlocks.flatMap { it.lines }
                        .mapNotNull { line ->
                            // Element boxes exclude adjacent pictograms that ML Kit may
                            // include in the wider line rectangle (for example a plus icon
                            // beside a Chinese "add widget" label). Keep the elements joined
                            // so the translation engine still receives the complete phrase.
                            val textElements = line.elements.filter { CJK.containsMatchIn(it.text) }
                            val elementBoxes = textElements.mapNotNull { it.boundingBox }
                            if (elementBoxes.isNotEmpty()) {
                                DetectedLine(
                                    textElements.joinToString("") { it.text },
                                    Rect(
                                        elementBoxes.minOf { it.left },
                                        elementBoxes.minOf { it.top },
                                        elementBoxes.maxOf { it.right },
                                        elementBoxes.maxOf { it.bottom },
                                    ),
                                    textElements.mapNotNull { it.confidence }.average().takeIf { !it.isNaN() }?.toFloat()
                                        ?: line.confidence ?: .65f,
                                )
                            } else {
                                line.boundingBox?.let { DetectedLine(line.text, Rect(it), line.confidence ?: .65f) }
                            }
                        }

                fun recognizeWithMlKit(): List<DetectedLine> {
                    val variants = listOf(
                        observed,
                        makeOcrVariant(observed, contrast = 1.8f, brightness = 0f, invert = false, background = Color.WHITE),
                        makeOcrVariant(observed, contrast = 1.8f, brightness = 0f, invert = false, background = Color.BLACK),
                        makeOcrVariant(observed, contrast = 2.2f, brightness = 18f, invert = true, background = Color.WHITE),
                    )
                    return try {
                        fuseDetections(variants.map(::recognizeBitmap))
                    } finally {
                        variants.drop(1).forEach(Bitmap::recycle)
                    }
                }

                val mlKitLines = recognizeWithMlKit()
                val paddleLines = if (
                    context != null &&
                    paddleFallbackAttempts < MAX_PADDLE_FALLBACK_ATTEMPTS &&
                    mlKitLines.none { CJK.containsMatchIn(it.text) && it.confidence >= MIN_CONFIDENCE }
                ) {
                    paddleFallbackAttempts++
                    PaddleOcrFallback.recognize(context, observed).mapNotNull { line ->
                        if (line.right > line.left && line.bottom > line.top) {
                            DetectedLine(line.text, Rect(line.left, line.top, line.right, line.bottom), line.confidence)
                        } else null
                    }
                } else emptyList()
                fuseDetections(listOf(mlKitLines, paddleLines))
            } finally {
                if (observed !== source) observed.recycle()
            }
            val sourceBoxes = lines.map { it.box }
            if (!writeChanges) {
                lines.forEach { line ->
                    if (!CJK.containsMatchIn(line.text)) return@forEach
                    if (line.confidence < MIN_CONFIDENCE) {
                        // Low-confidence Han-shaped fragments are overwhelmingly clock
                        // strokes and pictograms, not protected translation candidates.
                        // Keep them out of the user-facing "skipped text" count.
                        return@forEach
                    }
                    if (line.confidence >= HIGH_CONFIDENCE) highConfidenceLabels++
                    else mediumConfidenceLabels++
                }
                return null
            }
            val plans = mutableListOf<Pair<String, Plan>>()
            lines.forEach { line ->
                if (!CJK.containsMatchIn(line.text)) return@forEach
                if (line.confidence < MIN_CONFIDENCE) {
                    recordOcrDecision(name, line, "atlandi", "ocr guveni dusuk")
                    return@forEach
                }
                if (line.confidence >= HIGH_CONFIDENCE) highConfidenceLabels++ else mediumConfidenceLabels++
                val originalBox = line.box
                val box = Rect(originalBox.left / scale, originalBox.top / scale,
                    (originalBox.right + scale - 1) / scale, (originalBox.bottom + scale - 1) / scale)
                if (box.width() < 5 || box.height() < 5) {
                    skippedLabels++
                    recordOcrDecision(name, line, "atlandi", "algilanan alan cok kucuk")
                    return@forEach
                }
                val translated = runCatching { translate(line.text.trim()) }.getOrNull()?.trim().orEmpty()
                if (translated.isBlank() || CJK.containsMatchIn(translated) || translated == line.text.trim()) {
                    skippedLabels++
                    recordOcrDecision(name, line, "atlandi", "yerel ceviri sonucu uygun degil", translated)
                    return@forEach
                }
                val plan = planText(source, box, translated, sourceBoxes, scale)
                if (plan == null) {
                    skippedLabels++
                    recordOcrDecision(name, line, "atlandi", "guvenli temizleme veya metin yerlesimi bulunamadi", translated)
                    return@forEach
                }
                val cleanup = when {
                    plan.background == null -> "opencv inpaint"
                    Color.alpha(plan.background) == 0 -> "seffaf metin katmani"
                    else -> "duz yuzey"
                }
                recordOcrDecision(name, line, "cevrildi", cleanup, translated)
                plans += translated to plan
            }
            // Commit every label that could be planned. A partial replacement leaves some
            // Chinese untranslated, but that is strictly better than discarding the whole
            // image's translations, so one unplannable label no longer discards the rest.
            if (plans.isEmpty()) return null
            val result = source.copy(Bitmap.Config.ARGB_8888, true)
            val inpaintRegions = plans.mapNotNull { (_, plan) -> if (plan.background == null) plan.sourceRegion else null }
            if (inpaintRegions.isNotEmpty() && !inpaintRegions(result, inpaintRegions)) return null
            val canvas = Canvas(result)
            plans.forEach { (translated, plan) ->
                plan.background?.let { backgroundColor ->
                    val isTransparent = Color.alpha(backgroundColor) == 0
                    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = backgroundColor
                        if (isTransparent) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    }
                    // Transparent PNG/WebP assets are overlay layers. Clear only the source
                    // glyphs; clearing the wider translated-text region could erase an icon.
                    canvas.drawRect(
                        if (isTransparent) RectF(plan.sourceRegion) else plan.region,
                        background,
                    )
                }
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

    private data class Plan(
        val region: RectF,
        val sourceRegion: Rect,
        val background: Int?,
        val foreground: Int,
        val fontSize: Float,
    )
    private data class DetectedLine(val text: String, val box: Rect, val confidence: Float)

    private fun fuseDetections(detectorResults: List<List<DetectedLine>>): List<DetectedLine> =
        OcrDetectionFusion.fuse(
            detectorResults.map { detections ->
                detections.map { detection ->
                    OcrDetectionFusion.Detection(
                        text = detection.text,
                        bounds = OcrDetectionFusion.Bounds(
                            detection.box.left,
                            detection.box.top,
                            detection.box.right,
                            detection.box.bottom,
                        ),
                        confidence = detection.confidence,
                    )
                }
            },
        ).map { detection ->
            DetectedLine(
                text = detection.text,
                box = Rect(
                    detection.bounds.left,
                    detection.bounds.top,
                    detection.bounds.right,
                    detection.bounds.bottom,
                ),
                confidence = detection.confidence,
            )
        }

    private fun planText(bitmap: Bitmap, box: Rect, text: String, allBoxes: List<Rect>, scale: Int): Plan? {
        val height = box.height().toFloat()
        if (height < 5f || box.left < 1 || box.right > bitmap.width - 1) return null
        val tight = RectF(box.left - 3f, max(1f, box.top - height * .15f),
            box.right + 3f, min(bitmap.height - 1f, box.bottom + height * .15f))
        val bg = sampleBackground(bitmap, tight, box)
        val transparentLayer = bg != null && Color.alpha(bg) < 16
        val estimatedBackground = if (transparentLayer) null else bg ?: estimateLocalBackground(bitmap, tight, box) ?: return null
        if (!transparentLayer && Color.alpha(estimatedBackground!!) < 245) return null
        val projectedBoxes = allBoxes.map { other ->
            Rect(other.left / scale, other.top / scale, other.right / scale, other.bottom / scale)
        }
        val solidBackground = bg?.takeIf { Color.alpha(it) >= 245 }
        val surface = when {
            solidBackground != null -> solidSurfaceSpan(bitmap, box, solidBackground)
            transparentLayer -> safeTransparentSpan(bitmap, box, projectedBoxes)
            else -> safeHorizontalSpan(bitmap, box, projectedBoxes)
        }
        val widthLimit = if (solidBackground != null) box.width() * 2.5f else box.width() * 4f
        val width = (surface.second - surface.first - 4f).coerceAtMost(widthLimit)
        if (width < box.width() || width > bitmap.width - 2f) return null
        val left = (box.centerX() - width / 2f).coerceIn(surface.first + 2f, surface.second - width - 2f)
        val region = RectF(left, tight.top, left + width, tight.bottom)
        if (projectedBoxes.any { projected ->
                (abs(projected.centerX() - box.centerX()) > 1 || abs(projected.centerY() - box.centerY()) > 1) &&
                    RectF.intersects(region, RectF(projected))
            }) return null
        // Only the glyph paint changes. In particular, never draw a backdrop behind a
        // transparent theme layer: its alpha mask can be tinted by MAML at runtime and a
        // painted rectangle would recolour the entire widget rather than its label.
        val foreground = if (transparentLayer) {
            visibleGlyphColor(bitmap, box, sampleGlyphColor(bitmap, box))
        } else {
            highestContrastTextColor(estimatedBackground!!)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var font = min(48f, height * .91f)
        while (font >= max(5f, height * .25f)) {
            paint.textSize = font
            if (paint.measureText(text) <= region.width() - 5f && font <= region.height() * .9f) {
                val cleanupPad = max(2, (height * .08f).toInt())
                val cleanup = Rect(
                    (box.left - cleanupPad).coerceAtLeast(surface.first),
                    (box.top - cleanupPad).coerceAtLeast(0),
                    (box.right + cleanupPad).coerceAtMost(surface.second),
                    (box.bottom + cleanupPad).coerceAtMost(bitmap.height),
                )
                return Plan(region, cleanup, if (transparentLayer) Color.TRANSPARENT else solidBackground, foreground, font)
            }
            font -= 1f
        }
        return null
    }

    /**
     * Finds non-text opaque artwork beside a label on a transparent layer and keeps it
     * outside the translated text region. This protects arbitrary icons without relying
     * on a filename, manifest entry, theme name or language-specific coordinates.
     */
    private fun safeTransparentSpan(bitmap: Bitmap, box: Rect, boxes: List<Rect>): Pair<Int, Int> {
        var (left, right) = safeHorizontalSpan(bitmap, box, boxes)
        val top = (box.top - box.height() / 3).coerceAtLeast(0)
        val bottom = (box.bottom + box.height() / 3).coerceAtMost(bitmap.height)
        val occupied = BooleanArray(bitmap.width)
        for (x in 0 until bitmap.width) {
            if (x in box.left until box.right) continue
            var pixels = 0
            for (y in top until bottom) if (Color.alpha(bitmap.getPixel(x, y)) >= 64) pixels++
            occupied[x] = pixels >= max(2, (bottom - top) / 12)
        }
        var x = 0
        while (x < bitmap.width) {
            if (!occupied[x]) { x++; continue }
            val start = x
            while (x + 1 < bitmap.width && occupied[x + 1]) x++
            val end = x
            if (end - start >= 2) {
                if (end < box.left) left = max(left, end + 4)
                if (start > box.right) right = min(right, start - 3)
            }
            x++
        }
        return left to right
    }

    /** Limits a translated label to free horizontal space without trusting filenames/layout XML. */
    private fun safeHorizontalSpan(bitmap: Bitmap, box: Rect, boxes: List<Rect>): Pair<Int, Int> {
        var left = 1
        var right = bitmap.width - 1
        boxes.forEach { other ->
            if (other == box || other.bottom < box.top || other.top > box.bottom) return@forEach
            if (other.right <= box.left) left = max(left, other.right + 2)
            if (other.left >= box.right) right = min(right, other.left - 2)
        }
        return left to right
    }

    /**
     * Returns a median colour even for gradients/textures, while rejecting transparent assets.
     * It is used for contrast selection; OpenCV reconstructs the actual varying background.
     */
    private fun estimateLocalBackground(bitmap: Bitmap, region: RectF, box: Rect): Int? {
        val samples = ArrayList<Int>()
        for (y in region.top.toInt().coerceAtLeast(0) until region.bottom.toInt().coerceAtMost(bitmap.height)) {
            for (x in region.left.toInt().coerceAtLeast(0) until region.right.toInt().coerceAtMost(bitmap.width)) {
                if (x in box.left until box.right && y in box.top until box.bottom) continue
                samples += bitmap.getPixel(x, y)
            }
        }
        if (samples.size < 6 || samples.count { Color.alpha(it) >= 245 } < samples.size * .9) return null
        val opaque = samples.filter { Color.alpha(it) >= 245 }
        return Color.rgb(
            opaque.map(Color::red).sorted()[opaque.size / 2],
            opaque.map(Color::green).sorted()[opaque.size / 2],
            opaque.map(Color::blue).sorted()[opaque.size / 2],
        )
    }

    /** Uses the actual opaque glyph pixels when an asset is a transparent overlay layer. */
    private fun sampleGlyphColor(bitmap: Bitmap, box: Rect): Int {
        val pixels = ArrayList<Int>()
        for (y in box.top.coerceAtLeast(0) until box.bottom.coerceAtMost(bitmap.height)) {
            for (x in box.left.coerceAtLeast(0) until box.right.coerceAtMost(bitmap.width)) {
                bitmap.getPixel(x, y).takeIf { Color.alpha(it) >= 96 }?.let(pixels::add)
            }
        }
        if (pixels.isEmpty()) return Color.WHITE
        return Color.argb(
            pixels.map(Color::alpha).sorted()[pixels.size / 2],
            pixels.map(Color::red).sorted()[pixels.size / 2],
            pixels.map(Color::green).sorted()[pixels.size / 2],
            pixels.map(Color::blue).sorted()[pixels.size / 2],
        )
    }

    /** Chooses the text pixel with the greatest WCAG contrast against an opaque surface. */
    private fun highestContrastTextColor(background: Int): Int =
        if (contrastRatio(Color.BLACK, background) >= contrastRatio(Color.WHITE, background)) {
            Color.BLACK
        } else {
            Color.WHITE
        }

    private fun contrastRatio(first: Int, second: Int): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) / (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(Color.red(color)) +
            0.7152 * linear(Color.green(color)) +
            0.0722 * linear(Color.blue(color))
    }

    /**
     * A transparent overlay keeps its own glyph colour, but that colour was chosen for the
     * surface the theme author had in mind. When the host draws the layer over a surface of
     * similar luminance the translated label disappears, so fall back to a contrasting
     * colour. The decision uses only the layer's own pixels, so it stays theme-agnostic.
     */
    private fun visibleGlyphColor(bitmap: Bitmap, box: Rect, glyph: Int): Int {
        val backdrop = overlayBackdropLuminance(bitmap, box) ?: return glyph
        val glyphLuminance = luminance(glyph)
        if (abs(glyphLuminance - backdrop) >= MIN_GLYPH_CONTRAST) return glyph
        return if (backdrop > 145) Color.BLACK else Color.WHITE
    }

    /**
     * Estimates the luminance the overlay will sit on. A transparent layer carries no
     * backdrop of its own, so the surrounding opaque artwork inside the same asset is the
     * closest available proxy; when the asset is fully transparent there is nothing to
     * measure and the caller keeps the sampled colour.
     */
    private fun overlayBackdropLuminance(bitmap: Bitmap, box: Rect): Int? {
        val samples = ArrayList<Int>()
        val pad = max(4, box.height() / 2)
        val left = (box.left - pad).coerceAtLeast(0)
        val right = (box.right + pad).coerceAtMost(bitmap.width)
        val top = (box.top - pad).coerceAtLeast(0)
        val bottom = (box.bottom + pad).coerceAtMost(bitmap.height)
        for (y in top until bottom) {
            for (x in left until right) {
                if (x in box.left until box.right && y in box.top until box.bottom) continue
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= 245) samples += pixel
            }
        }
        if (samples.size < 8) return null
        return samples.map(::luminance).sorted()[samples.size / 2]
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * .299 + Color.green(color) * .587 + Color.blue(color) * .114).toInt()

    /** Removes detected source glyphs while preserving gradients, photos and textured cards. */
    private fun inpaintRegions(bitmap: Bitmap, regions: List<Rect>): Boolean {
        if (!openCvReady) return false
        val rgba = Mat()
        val rgb = Mat()
        val alpha = Mat()
        val mask = Mat.zeros(bitmap.height, bitmap.width, CvType.CV_8UC1)
        val repaired = Mat()
        val repairedRgba = Mat()
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Core.extractChannel(rgba, alpha, 3)
            regions.forEach { region ->
                val pad = max(1, min(region.width(), region.height()) / 8)
                val left = (region.left - pad).coerceAtLeast(0)
                val top = (region.top - pad).coerceAtLeast(0)
                val right = (region.right + pad).coerceAtMost(bitmap.width - 1)
                val bottom = (region.bottom + pad).coerceAtMost(bitmap.height - 1)
                Imgproc.rectangle(mask, Point(left.toDouble(), top.toDouble()), Point(right.toDouble(), bottom.toDouble()), Scalar(255.0), -1)
            }
            Photo.inpaint(rgb, mask, repaired, 3.0, Photo.INPAINT_TELEA)
            Imgproc.cvtColor(repaired, repairedRgba, Imgproc.COLOR_RGB2RGBA)
            Core.insertChannel(alpha, repairedRgba, 3)
            Utils.matToBitmap(repairedRgba, bitmap)
            true
        } catch (_: Exception) {
            false
        } finally {
            rgba.release(); rgb.release(); alpha.release(); mask.release(); repaired.release(); repairedRgba.release()
        }
    }

    private fun recordOcrDecision(path: String, line: DetectedLine, status: String, reason: String, translated: String = "") {
        context?.let { appContext ->
            LiveDiagnosticsRecorder.get(appContext).record(
                "theme_ocr_label_$status",
                "OCR etiketi $status",
                mapOf(
                    "path" to path,
                    "source" to line.text,
                    "translated" to translated,
                    "confidence" to line.confidence,
                    "box" to "${line.box.left},${line.box.top},${line.box.right},${line.box.bottom}",
                    "reason" to reason,
                ),
            )
        }
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

    private fun makeOcrVariant(
        source: Bitmap,
        contrast: Float,
        brightness: Float,
        invert: Boolean,
        background: Int,
    ): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(background)
        val matrix = ColorMatrix().apply {
            set(floatArrayOf(
                contrast * if (invert) -1f else 1f, 0f, 0f, 0f,
                    brightness + if (invert) 255f else 0f,
                0f, contrast * if (invert) -1f else 1f, 0f, 0f,
                    brightness + if (invert) 255f else 0f,
                0f, 0f, contrast * if (invert) -1f else 1f, 0f,
                    brightness + if (invert) 255f else 0f,
                0f, 0f, 0f, 1f, 0f,
            ))
        }
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
            alpha = 255
            canvas.drawBitmap(source, 0f, 0f, this)
        }
        return output
    }

    /**
     * Cheap, high-recall local gate before either OCR engine receives an image. It deliberately
     * avoids native OpenCV calls: malformed and unusual MTZ bitmaps must not be able to crash the
     * app during a scan of thousands of files. A sampled bitmap keeps the cost bounded, and a
     * false positive only costs one local OCR pass.
     */
    private fun isLikelyTextAsset(bytes: ByteArray): Boolean {
        val preview = decodeForPreflight(bytes) ?: return false
        try {
            val width = preview.width
            val height = preview.height
            if (width < 3 || height < 3) return false
            val pixels = IntArray(width * height)
            preview.getPixels(pixels, 0, width, 0, 0, width, height)
            val step = if (width * height > 160_000) 2 else 1
            var tested = 0
            var strongEdges = 0
            var textLikeRows = 0
            var globalMin = 255
            var globalMax = 0
            for (y in step until height step step) {
                var rowEdges = 0
                for (x in step until width step step) {
                    val current = compositedLuminancePair(pixels[y * width + x])
                    val left = compositedLuminancePair(pixels[y * width + x - step])
                    val above = compositedLuminancePair(pixels[(y - step) * width + x])
                    val currentBlack = current ushr 8
                    val currentWhite = current and 0xff
                    val localEdge = max(
                        max(abs(currentBlack - (left ushr 8)), abs(currentWhite - (left and 0xff))),
                        max(abs(currentBlack - (above ushr 8)), abs(currentWhite - (above and 0xff))),
                    )
                    globalMin = min(globalMin, min(currentBlack, currentWhite))
                    globalMax = max(globalMax, max(currentBlack, currentWhite))
                    tested++
                    if (localEdge >= 34) {
                        strongEdges++
                        rowEdges++
                    }
                }
                if (rowEdges >= max(3, width / (42 * step))) textLikeRows++
            }
            val edgeDensity = strongEdges.toFloat() / tested.coerceAtLeast(1)
            val contrast = globalMax - globalMin
            // This is deliberately high-recall. Photos may proceed to OCR, while a flat colour
            // can be discarded without invoking native image code thousands of times.
            return contrast >= 28 && (edgeDensity in 0.0025f..0.58f || textLikeRows >= 2)
        } catch (_: Exception) {
            return true
        } finally {
            preview.recycle()
        }
    }

    /** Luminance over black and white composites; detects glyphs in transparent overlays too. */
    private fun compositedLuminancePair(pixel: Int): Int {
        val alpha = Color.alpha(pixel)
        val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
        val onBlack = luminance * alpha / 255
        val onWhite = 255 - (255 - luminance) * alpha / 255
        return (onBlack shl 8) or onWhite
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
        const val MIN_CONFIDENCE = .50f
        // Minimum luminance gap between a translated label and the surface it sits on.
        // Below this the label is unreadable, so a contrasting colour is substituted.
        const val MIN_GLYPH_CONTRAST = 60
        const val PREFLIGHT_MAX_SIDE = 512
        const val MAX_PADDLE_FALLBACK_ATTEMPTS = 3
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
