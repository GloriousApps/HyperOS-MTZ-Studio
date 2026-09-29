package dev.glorioustr.mtzstudio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class PaddleOcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
)

/**
 * Runs native OCR outside the app process. A faulty ONNX build can terminate this process, but
 * the caller simply times out and preserves the ML Kit result instead of losing the whole app.
 */
internal object PaddleOcrFallback {
    private const val MAX_SIDE = 720
    private const val MAX_REQUEST_BYTES = 450 * 1024
    private const val TIMEOUT_MS = 45_000L

    fun recognize(context: Context, source: Bitmap): List<PaddleOcrLine> {
        val requestId = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "paddle-ocr").apply { mkdirs() }
        val input = File(root, "$requestId.jpg")
        val output = File(root, "$requestId.json")
        val scaled = scaleForTransport(source)
        try {
            val encoded = input.outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)
            }
            if (!encoded) return emptyList()
            context.startService(
                Intent(context, PaddleOcrFallbackService::class.java)
                    .putExtra(PaddleOcrFallbackService.EXTRA_INPUT, input.absolutePath)
                    .putExtra(PaddleOcrFallbackService.EXTRA_OUTPUT, output.absolutePath),
            )
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (output.isFile) return parseResult(output, source.width, source.height, scaled.width, scaled.height)
                Thread.sleep(100)
            }
            return emptyList()
        } catch (_: Exception) {
            return emptyList()
        } finally {
            if (scaled !== source) scaled.recycle()
            input.delete()
            output.delete()
        }
    }

    private fun scaleForTransport(source: Bitmap): Bitmap {
        var bitmap = source
        var quality = 88
        while (true) {
            val probe = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, probe)
            if (probe.size() <= MAX_REQUEST_BYTES || bitmap.width <= 160 || bitmap.height <= 160) return bitmap
            val scale = minOf(MAX_SIDE.toFloat() / bitmap.width, MAX_SIDE.toFloat() / bitmap.height, .8f)
            val resized = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (bitmap !== source) bitmap.recycle()
            bitmap = resized
            quality = (quality - 8).coerceAtLeast(56)
        }
    }

    private fun parseResult(
        file: File,
        originalWidth: Int,
        originalHeight: Int,
        processedWidth: Int,
        processedHeight: Int,
    ): List<PaddleOcrLine> = runCatching {
        val root = JSONObject(file.readText())
        if (!root.optBoolean("ok")) return emptyList()
        val xScale = originalWidth.toFloat() / processedWidth
        val yScale = originalHeight.toFloat() / processedHeight
        val lines = root.getJSONArray("lines")
        buildList {
            for (index in 0 until lines.length()) {
                val line = lines.getJSONObject(index)
                add(
                    PaddleOcrLine(
                        text = line.getString("text"),
                        left = (line.getInt("left") * xScale).toInt(),
                        top = (line.getInt("top") * yScale).toInt(),
                        right = (line.getInt("right") * xScale).toInt(),
                        bottom = (line.getInt("bottom") * yScale).toInt(),
                        confidence = line.getDouble("confidence").toFloat(),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

internal class PaddleOcrFallbackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val input = intent?.getStringExtra(EXTRA_INPUT)?.let(::File)
        val output = intent?.getStringExtra(EXTRA_OUTPUT)?.let(::File)
        if (input == null || output == null) return START_NOT_STICKY
        Thread {
            val payload = runCatching {
                val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: error("Unable to decode fallback image")
                try {
                    val engine = runBlocking {
                        PaddleOCR.create(
                            applicationContext,
                            PaddleOCRConfig(detLimitSideLen = 720, detMaxSideLimit = 720),
                            EngineConfig(numThreads = 1, enableXnnpack = false),
                        )
                    }
                    try {
                        val lines = runBlocking { engine.recognize(bitmap) }.results
                        JSONObject().put("ok", true).put("lines", JSONArray().apply {
                            lines.filter { it.confidence >= .45f }.forEach { item ->
                                val points = item.box.points
                                put(JSONObject().apply {
                                    put("text", item.text)
                                    put("left", points.minOf { it.x }.toInt())
                                    put("top", points.minOf { it.y }.toInt())
                                    put("right", points.maxOf { it.x }.toInt())
                                    put("bottom", points.maxOf { it.y }.toInt())
                                    put("confidence", item.confidence)
                                })
                            }
                        })
                    } finally {
                        runCatching { runBlocking { engine.release() } }
                    }
                } finally {
                    bitmap.recycle()
                }
            }.getOrElse { error ->
                JSONObject().put("ok", false).put("error", error.javaClass.simpleName)
            }
            output.parentFile?.mkdirs()
            output.writeText(payload.toString())
            stopSelf(startId)
        }.start()
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_INPUT = "input"
        const val EXTRA_OUTPUT = "output"
    }
}
