package dev.glorioustr.mtzstudio.tester

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** A partial external theme-content backup, not a private-app backup or an OS restore guarantee. */
object AggregateThemesBak {
    data class Entry(val path: String, val offset: Long, val size: Long)
    data class Summary(val themeCount: Int, val fileCount: Int, val contentBytes: Long)

    private const val PACKAGE = "com.android.thememanager"
    private const val BLOCK = 512
    // The existing Studio BAK inspector accepts fewer than 20,000 entries, including
    // the manifest. Never publish an archive that its own import flow would reject.
    private const val MAX_FILES = 19_998
    private const val MAX_NODES = 50_000
    private const val MAX_ENTRY = 512L * 1024 * 1024
    private const val MAX_CONTENT = 1024L * 1024 * 1024
    private const val TARGET = "apps/$PACKAGE/ef/MIUI/theme/"
    private val allowed = listOf(".data/meta/", ".data/content/", ".data/preview/")
    private val zeroBlock = ByteArray(BLOCK)

    /** Validates a shell USTAR snapshot without extracting it. Offsets address raw file content. */
    fun inspect(snapshot: File): List<Entry> {
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        var total = 0L
        RandomAccessFile(snapshot, "r").use { input ->
            val length = input.length()
            var terminated = false
            while (input.filePointer < length) {
                val header = ByteArray(BLOCK)
                require(length - input.filePointer >= BLOCK && input.read(header) == BLOCK) { "Truncated tar header" }
                if (header.contentEquals(zeroBlock)) {
                    val second = ByteArray(BLOCK)
                    require(length - input.filePointer >= BLOCK && input.read(second) == BLOCK && second.contentEquals(zeroBlock)) {
                        "Missing tar zero terminators"
                    }
                    // GNU tar can pad its final record with more zero blocks.
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require((0 until n).all { buffer[it] == 0.toByte() }) { "Data after tar terminators" }
                    }
                    terminated = true
                    break
                }
                require(header.copyOfRange(257, 262).contentEquals("ustar".toByteArray(StandardCharsets.US_ASCII)) &&
                    (header[262] == 0.toByte() || header[262] == ' '.code.toByte())) { "Not a USTAR header" }
                val checksum = header.octal(148, 8)
                val actual = header.indices.sumOf { if (it in 148..155) 32L else (header[it].toInt() and 255).toLong() }
                require(checksum == actual) { "Invalid tar checksum" }
                val name = header.text(0, 100)
                val prefix = header.text(345, 155)
                require(name.isNotEmpty()) { "Empty tar entry name" }
                val raw = if (prefix.isEmpty()) name else "$prefix/$name"
                val type = header[156].toInt()
                require(type == 0 || type == '0'.code || type == '5'.code) { "Tar links and special nodes are forbidden" }
                val directory = type == '5'.code
                val path = normalize(raw, directory)
                require(seen.add(path)) { "Duplicate tar path: $path" }
                require(seen.size <= MAX_NODES) { "Too many tar nodes" }
                val size = header.octal(124, 12)
                require(size <= MAX_ENTRY && (!directory || size == 0L)) { "Tar entry too large or invalid directory" }
                val dataOffset = input.filePointer
                val padded = (size + BLOCK - 1) / BLOCK * BLOCK
                require(padded <= length - dataOffset) { "Truncated tar content: $path" }
                if (!directory) {
                    require(allowed.any { path.startsWith(it) && path.length > it.length }) { "Unexpected snapshot path: $path" }
                    require(entries.size < MAX_FILES) { "Too many tar files" }
                    require(size <= MAX_CONTENT - total) { "Snapshot content exceeds limit" }
                    total += size
                    entries += Entry(path, dataOffset, size)
                } else {
                    require(path == "." || path == ".data" || allowed.any {
                        path == it.removeSuffix("/") || path.startsWith(it)
                    }) { "Unexpected snapshot directory: $path" }
                }
                input.seek(dataOffset + padded)
            }
            require(terminated) { "Missing tar zero terminators" }
        }
        return entries
    }

    fun write(snapshot: File, output: File, versionCode: Long, sdk: Int, installer: String, signatures: List<String>): Summary {
        require(snapshot.canonicalFile != output.canonicalFile) { "Input and output must differ" }
        require(versionCode > 0 && sdk >= 0) { "Invalid package version or SDK" }
        require(signatures.isNotEmpty() && signatures.all { it.isNotEmpty() && '\n' !in it && '\r' !in it }) {
            "Theme Manager signatures are required"
        }
        require('\n' !in installer && '\r' !in installer) { "Invalid installer" }
        val files = inspect(snapshot) // No output is opened until the entire input has passed validation.
        val themeCount = files.count { it.path.startsWith(".data/meta/theme/") &&
            it.path.removePrefix(".data/meta/theme/").let { name -> '/' !in name && name.endsWith(".mrm") && name.length > 4 } }
        require(themeCount > 0) { "No root theme metadata in snapshot" }
        val manifest = buildString {
            append("1\n$PACKAGE\n$versionCode\n$sdk\n$installer\n0\n${signatures.size}\n")
            signatures.forEach { append(it).append('\n') }
        }.toByteArray(StandardCharsets.UTF_8)
        require(manifest.size <= 16_384) { "Manifest exceeds Studio importer limit" }
        val manifestPath = "apps/$PACKAGE/_manifest"
        // Ensure every output path fits USTAR before touching the destination.
        ustarPath(manifestPath)
        files.forEach {
            require(".." !in it.path) { "Path is incompatible with Studio BAK import" }
            ustarPath(TARGET + it.path)
        }
        val preamble = "MIUI BACKUP\n2\n$PACKAGE Themes\n-1\n0\nANDROID BACKUP\n5\n0\nnone\n".toByteArray(StandardCharsets.UTF_8)
        fun entryBytes(size: Long) = BLOCK + (size + BLOCK - 1) / BLOCK * BLOCK
        val outputBytes = preamble.size + entryBytes(manifest.size.toLong()) + files.sumOf { entryBytes(it.size) } + BLOCK * 2
        require(outputBytes <= MAX_CONTENT) { "BAK exceeds Studio importer limit" }
        val summary = Summary(themeCount, files.size, files.sumOf { it.size })
        RandomAccessFile(snapshot, "r").use { input ->
            BufferedOutputStream(FileOutputStream(output), 128 * 1024).use { out ->
                out.write(preamble)
                tarHeader(out, manifestPath, manifest.size.toLong())
                out.write(manifest)
                pad(out, manifest.size.toLong())
                val buffer = ByteArray(64 * 1024)
                for (file in files) {
                    tarHeader(out, TARGET + file.path, file.size)
                    input.seek(file.offset)
                    var remaining = file.size
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        require(n > 0) { "Snapshot changed while writing: ${file.path}" }
                        out.write(buffer, 0, n)
                        remaining -= n
                    }
                    pad(out, file.size)
                }
                out.write(ByteArray(BLOCK * 2))
            }
        }
        return summary
    }

    private fun normalize(raw: String, directory: Boolean): String {
        require(!raw.startsWith('/') && '\\' !in raw && raw.none { it.code < 32 || it.code == 127 }) { "Unsafe tar path" }
        val trimmed = if (directory) raw.removeSuffix("/") else raw
        val path = trimmed.removePrefix("./")
        require(path.isNotEmpty() && (directory || !raw.endsWith('/')) &&
            (path == "." && directory || path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })) {
            "Unsafe tar path: $raw"
        }
        return path
    }

    private fun ByteArray.text(start: Int, length: Int): String {
        val end = (start until start + length).firstOrNull { this[it] == 0.toByte() } ?: start + length
        require((end until start + length).all { this[it] == 0.toByte() }) { "Invalid tar text field" }
        return try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(this, start, end - start)).toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Invalid UTF-8 tar path")
        }
    }

    private fun ByteArray.octal(start: Int, length: Int): Long {
        val field = copyOfRange(start, start + length)
        require(field.none { (it.toInt() and 0x80) != 0 }) { "Unsupported binary tar number" }
        val value = String(field, StandardCharsets.US_ASCII).trim(' ', '\u0000')
        require(value.isNotEmpty() && value.all { it in '0'..'7' }) { "Invalid tar octal field" }
        return value.toLongOrNull(8) ?: throw IllegalArgumentException("Tar number overflow")
    }

    private fun ustarPath(path: String): Pair<ByteArray, ByteArray> {
        val bytes = path.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= 100) return bytes to byteArrayOf()
        for (split in path.indices.reversed()) {
            if (path[split] != '/') continue
            val prefix = path.substring(0, split).toByteArray(StandardCharsets.UTF_8)
            val name = path.substring(split + 1).toByteArray(StandardCharsets.UTF_8)
            if (prefix.size <= 155 && name.size in 1..100) return name to prefix
        }
        throw IllegalArgumentException("Tar path cannot fit USTAR: $path")
    }

    private fun tarHeader(out: OutputStream, path: String, size: Long) {
        val h = ByteArray(BLOCK)
        val (name, prefix) = ustarPath(path)
        name.copyInto(h, 0)
        prefix.copyInto(h, 345)
        number(h, 100, 8, 420)
        number(h, 108, 8, 1000)
        number(h, 116, 8, 1000)
        number(h, 124, 12, size)
        number(h, 136, 12, 0)
        h[156] = '0'.code.toByte()
        "ustar\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(h, 257)
        "00".toByteArray(StandardCharsets.US_ASCII).copyInto(h, 263)
        for (i in 148..155) h[i] = 32
        number(h, 148, 7, h.sumOf { (it.toInt() and 255).toLong() })
        h[155] = 32
        out.write(h)
    }

    private fun number(h: ByteArray, at: Int, length: Int, value: Long) {
        val digits = value.toString(8)
        require(digits.length < length) { "Tar number cannot fit" }
        digits.padStart(length - 1, '0').toByteArray(StandardCharsets.US_ASCII).copyInto(h, at)
        h[at + length - 1] = 0
    }

    private fun pad(out: OutputStream, size: Long) {
        val remainder = (size % BLOCK).toInt()
        if (remainder != 0) out.write(ByteArray(BLOCK - remainder))
    }
}
