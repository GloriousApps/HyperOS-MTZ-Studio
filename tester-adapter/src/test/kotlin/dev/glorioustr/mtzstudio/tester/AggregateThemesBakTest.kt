package dev.glorioustr.mtzstudio.tester

import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregateThemesBakTest {
    private val pkg = "com.android.thememanager"
    private val preamble = "MIUI BACKUP\n2\n$pkg Themes\n-1\n0\nANDROID BACKUP\n5\n0\nnone\n"

    @Test fun multipleThemesBecomeOneBakWithManifestFirstAndExactContents() = withTemp { dir ->
        val snapshot = File(dir, "snapshot.tar")
        val content = ByteArray(1025) { (it % 251).toByte() }
        snapshot.outputStream().use { out ->
            entry(out, "./", directory = true)
            entry(out, "./.data/meta/theme/first.mrm", "{\"localId\":\"first\"}".toByteArray())
            entry(out, "./.data/meta/theme/second.mrm", "{}".toByteArray())
            entry(out, "./.data/content/theme/first.mrc", content)
            entry(out, "./.data/preview/theme/first/a.png", byteArrayOf(1, 2, 3))
            finish(out)
        }
        val files = AggregateThemesBak.inspect(snapshot)
        assertEquals(4, files.size)
        assertEquals("{\"localId\":\"first\"}", snapshot.readBytes().copyOfRange(files[0].offset.toInt(), (files[0].offset + files[0].size).toInt()).toString(StandardCharsets.UTF_8))
        val output = File(dir, "themes.bak")
        val result = AggregateThemesBak.write(snapshot, output, 123L, 35, "installer", listOf("sig-one", "sig-two"))
        val restored = AggregateThemesBak.inspectBackup(output)
        assertEquals(files.map { it.path }, restored.files.map { it.path })
        assertEquals(preamble.length.toLong(), restored.tarOffset)
        assertEquals(4, ThemesContentRestore.checksums(output, restored).lines().count { it.isNotEmpty() })
        assertEquals(AggregateThemesBak.Summary(2, 4, 19L + 2 + content.size + 3), result)
        val bytes = output.readBytes()
        assertTrue(bytes.copyOfRange(0, preamble.length).toString(StandardCharsets.UTF_8) == preamble)
        val tar = bytes.copyOfRange(preamble.length, bytes.size)
        val resultTar = File(dir, "result.tar").apply { writeBytes(tar) }
        val manifest = "1\n$pkg\n123\n35\ninstaller\n0\n2\nsig-one\nsig-two\n"
        val written = readTar(resultTar)
        assertEquals("apps/$pkg/_manifest", written.first().first)
        assertEquals(manifest, written.first().second.toString(StandardCharsets.UTF_8))
        assertEquals(5, written.size)
        assertEquals(files.map { "apps/$pkg/ef/MIUI/theme/${it.path}" }, written.drop(1).map { it.first })
        assertEquals(2, written.drop(1).count { it.first.endsWith(".mrm") })
        val source = snapshot.readBytes()
        files.zip(written.drop(1)).forEach { (entry, archived) ->
            assertArrayEquals(entry.path, source.copyOfRange(entry.offset.toInt(), (entry.offset + entry.size).toInt()), archived.second)
        }
        assertArrayEquals(content, written.single { it.first.endsWith("/content/theme/first.mrc") }.second)
        val expectedTarSize = (listOf(manifest.length.toLong()) + files.map { it.size })
            .sumOf { 512L + ((it + 511) / 512) * 512 } + 1024
        assertEquals(preamble.toByteArray(StandardCharsets.UTF_8).size + expectedTarSize, bytes.size.toLong())
        assertArrayEquals(ByteArray(1024), tar.copyOfRange(tar.size - 1024, tar.size))
    }

    @Test fun longNamesUseUstarPrefixWithoutTruncation() = withTemp { dir ->
        val path = ".data/preview/theme/id/${"directory".repeat(8)}/${"name".repeat(19)}.png"
        val snapshot = File(dir, "long.tar")
        snapshot.outputStream().use { out ->
            entry(out, "./.data/meta/theme/id.mrm", byteArrayOf(3))
            entry(out, "./$path", byteArrayOf(7))
            finish(out)
        }
        val output = File(dir, "long.bak")
        assertEquals(2, AggregateThemesBak.write(snapshot, output, 1, 34, "", listOf("sig")).fileCount)
        val tar = File(dir, "output.tar").apply { writeBytes(output.readBytes().copyOfRange(preamble.length, output.length().toInt())) }
        assertTrue(readTar(tar).any { it.first == "apps/$pkg/ef/MIUI/theme/$path" && it.second.contentEquals(byteArrayOf(7)) })
    }

    @Test fun missingMetadataLeavesDestinationUntouched() = withTemp { dir ->
        val snapshot = File(dir, "empty.tar")
        snapshot.outputStream().use { entry(it, "./.data/content/theme/id.mrc", byteArrayOf(1)); finish(it) }
        val output = File(dir, "old.bak").apply { writeText("untouched") }
        fails { AggregateThemesBak.write(snapshot, output, 1, 34, "", listOf("sig")) }
        assertEquals("untouched", output.readText())
        fails { AggregateThemesBak.write(snapshot, File(dir, "none.bak"), 1, 34, "", emptyList()) }
        assertFalse(File(dir, "none.bak").exists())
    }

    @Test fun truncatedTarAndAbsentOrCorruptTerminatorsAreRejected() = withTemp { dir ->
        val file = File(dir, "truncated.tar")
        file.outputStream().use { entry(it, "./.data/meta/theme/id.mrm", byteArrayOf(1, 2, 3)); finish(it) }
        file.writeBytes(file.readBytes().copyOfRange(0, 514))
        fails { AggregateThemesBak.inspect(file) }
        file.outputStream().use { entry(it, "./.data/meta/theme/id.mrm", byteArrayOf(1)); it.write(ByteArray(512)) }
        fails { AggregateThemesBak.inspect(file) }
        file.outputStream().use { entry(it, "./.data/meta/theme/id.mrm", byteArrayOf(1)); finish(it); it.write(1) }
        fails { AggregateThemesBak.inspect(file) }
    }

    @Test fun extraEntryAfterTerminatorCannotProduceAggregateBackup() = withTemp { dir ->
        val file = File(dir, "after-end.tar")
        file.outputStream().use { out ->
            entry(out, "./.data/meta/theme/id.mrm", byteArrayOf(1))
            finish(out)
            entry(out, "./.data/meta/theme/hidden.mrm", byteArrayOf(2))
            finish(out)
        }
        val output = File(dir, "themes.bak").apply { writeText("existing backup") }
        fails { AggregateThemesBak.inspect(file) }
        fails { AggregateThemesBak.write(file, output, 1, 34, "", listOf("sig")) }
        assertEquals("existing backup", output.readText())
    }

    @Test fun checksumSymlinkPaxTraversalAbsoluteAndDuplicatesAreRejected() = withTemp { dir ->
        val file = File(dir, "bad.tar")
        val meta = "./.data/meta/theme/id.mrm"
        file.outputStream().use { entry(it, meta, byteArrayOf(1)); finish(it) }
        file.writeBytes(file.readBytes().also { it[0] = 'Z'.code.toByte() })
        fails { AggregateThemesBak.inspect(file) }
        for (type in listOf('2', 'x', 'g', '1', '3')) {
            file.outputStream().use { entry(it, meta, type = type); finish(it) }
            fails { AggregateThemesBak.inspect(file) }
        }
        for (name in listOf("./.data/meta/../content/id.mrc", "/.data/meta/theme/id.mrm", "./.data/meta/./theme/id.mrm", "./.data/meta/theme\\id.mrm")) {
            file.outputStream().use { entry(it, name); finish(it) }
            fails { AggregateThemesBak.inspect(file) }
        }
        file.outputStream().use { entry(it, meta); entry(it, meta.removePrefix("./")); finish(it) }
        fails { AggregateThemesBak.inspect(file) }
    }

    @Test fun fileLimitIsEnforced() = withTemp { dir ->
        val file = File(dir, "many.tar")
        file.outputStream().buffered().use { out ->
            repeat(19_999) { entry(out, "./.data/meta/theme/id$it.mrm") }
            finish(out)
        }
        fails { AggregateThemesBak.inspect(file) }
    }

    @Test fun importerIncompatiblePathsAndManifestLeaveExistingBackupUntouched() = withTemp { dir ->
        val snapshot = File(dir, "snapshot.tar")
        val output = File(dir, "existing.bak").apply { writeText("keep") }
        snapshot.outputStream().use { out ->
            entry(out, "./.data/meta/theme/id.mrm", byteArrayOf(1))
            entry(out, "./.data/preview/theme/id/a..png", byteArrayOf(2))
            finish(out)
        }
        fails { AggregateThemesBak.write(snapshot, output, 1, 34, "", listOf("sig")) }
        assertEquals("keep", output.readText())
        snapshot.outputStream().use { out ->
            entry(out, "./.data/meta/theme/id.mrm", byteArrayOf(1))
            finish(out)
        }
        fails { AggregateThemesBak.write(snapshot, output, 1, 34, "", listOf("s".repeat(16_384))) }
        assertEquals("keep", output.readText())
    }

    @Test fun oversizedEntryIsRejectedBeforeOutputCreation() = withTemp { dir ->
        val file = File(dir, "huge.tar")
        file.outputStream().use { entry(it, "./.data/meta/theme/id.mrm", declaredSize = 512L * 1024 * 1024 + 1); finish(it) }
        val output = File(dir, "huge.bak")
        fails { AggregateThemesBak.write(file, output, 1, 34, "", listOf("sig")) }
        assertFalse(output.exists())
    }

    @Test fun restoreRejectsPrivateDomainsLinksAndTruncatedPayloads() = withTemp { dir ->
        val file = File(dir, "bad.bak")
        for ((path, type) in listOf(
            "apps/$pkg/f/private" to '0',
            "apps/$pkg/ef/MIUI/theme/.data/content/icons/link" to '2',
            "apps/$pkg/ef/MIUI/theme/.data/../outside" to '0',
        )) {
            file.outputStream().use { out ->
                out.write(preamble.toByteArray())
                entry(out, "apps/$pkg/_manifest", "1\n$pkg\n123\n".toByteArray())
                entry(out, path, type = type)
                finish(out)
            }
            fails { AggregateThemesBak.inspectBackup(file) }
        }
        file.outputStream().use { out ->
            out.write(preamble.toByteArray())
            entry(out, "apps/$pkg/_manifest", "1\n$pkg\n123\n".toByteArray())
            entry(out, "apps/$pkg/ef/MIUI/theme/.data/meta/theme/id.mrm", declaredSize = 5000)
        }
        fails { AggregateThemesBak.inspectBackup(file) }
    }

    @Test fun restoreRejectsFileDirectoryCollisions() = withTemp { dir ->
        val file = File(dir, "collision.tar")
        file.outputStream().use { out ->
            entry(out, ".data/meta/theme", byteArrayOf(1))
            entry(out, ".data/meta/theme/id.mrm", byteArrayOf(2))
            finish(out)
        }
        fails { AggregateThemesBak.inspect(file) }
    }

    private fun readTar(file: File): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        file.inputStream().buffered().use { input ->
            while (true) {
                val h = input.readNBytes(512)
                if (h.all { it == 0.toByte() }) break
                fun text(from: Int, len: Int) = h.copyOfRange(from, from + len).takeWhile { it != 0.toByte() }.toByteArray().toString(StandardCharsets.UTF_8)
                val expectedChecksum = text(148, 8).trim().toLong(8)
                val actualChecksum = h.indices.sumOf { if (it in 148..155) 32L else (h[it].toInt() and 255).toLong() }
                assertEquals(expectedChecksum, actualChecksum)
                val name = text(0, 100)
                val prefix = text(345, 155)
                val size = text(124, 12).trim().toLong(8).toInt()
                result += (if (prefix.isEmpty()) name else "$prefix/$name") to input.readNBytes(size)
                input.skipNBytes(((size + 511) / 512 * 512 - size).toLong())
            }
        }
        return result
    }

    private fun entry(out: OutputStream, path: String, data: ByteArray = byteArrayOf(), type: Char = '0', directory: Boolean = false, declaredSize: Long = data.size.toLong()) {
        val h = ByteArray(512)
        val parts = path.indices.reversed().firstOrNull { path[it] == '/' && path.substring(0, it).toByteArray().size <= 155 && path.substring(it + 1).toByteArray().size in 1..100 }
        val name = if (path.toByteArray().size > 100 && parts != null) path.substring(parts + 1) else path
        name.toByteArray().copyInto(h, 0)
        if (path.toByteArray().size > 100 && parts != null) path.substring(0, parts).toByteArray().copyInto(h, 345)
        fun octal(at: Int, len: Int, value: Long) { value.toString(8).padStart(len - 1, '0').toByteArray().copyInto(h, at); h[at + len - 1] = 0 }
        octal(100, 8, 420)
        octal(108, 8, 1000)
        octal(116, 8, 1000)
        octal(124, 12, declaredSize)
        octal(136, 12, 0)
        h[156] = (if (directory) '5' else type).code.toByte()
        "ustar\u0000".toByteArray().copyInto(h, 257)
        "00".toByteArray().copyInto(h, 263)
        for (i in 148..155) h[i] = 32
        octal(148, 7, h.sumOf { (it.toInt() and 255).toLong() })
        h[155] = 32
        out.write(h)
        out.write(data)
        if (data.size % 512 != 0) out.write(ByteArray(512 - data.size % 512))
    }

    private fun finish(out: OutputStream) { out.write(ByteArray(1024)) }
    private fun fails(block: () -> Unit) { try { block(); throw AssertionError("Expected invalid archive to fail") } catch (_: IllegalArgumentException) { } }
    private fun withTemp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("aggregate-bak-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
