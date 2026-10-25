package dev.glorioustr.mtzstudio.tester

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemesContentRestoreTest {
    private val id = "11111111-1111-1111-1111-111111111111"
    private val shell = if (System.getProperty("os.name").startsWith("Windows"))
        "C:/Program Files/Git/bin/bash.exe" else "/bin/sh"
    private fun path(file: File): String {
        val path = file.absolutePath.replace('\\', '/')
        return if (path.length > 2 && path[1] == ':') "/${path[0].lowercaseChar()}${path.drop(2)}" else path
    }
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun execute(command: String): Pair<Int, String> {
        val process = ProcessBuilder(shell, "-s").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(command); it.newLine() }
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "Restore test timed out")
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }

    private fun fixture(test: (File, String) -> Unit) {
        val dir = Files.createTempDirectory("restore-test-").toFile()
        try {
            val source = File(dir, "source").apply { mkdirs() }
            File(source, ".data/meta/theme/id.mrm").apply { parentFile.mkdirs(); writeText("{\"localId\":\"id\"}") }
            File(source, ".data/content/android小白条沉浸/id.mrc").apply { parentFile.mkdirs(); writeText("component bytes") }
            File(source, ".data/preview/theme/id/with space.png").apply { parentFile.mkdirs(); writeText("preview") }
            val tar = File(dir, "snapshot.tar")
            val (code, output) = execute("tar --format=ustar -cf '${path(tar)}' -C '${path(source)}' .data")
            assertEquals(0, code, output)
            val bridge = File(dir, "Download/MTZStudioRootless").apply { mkdirs() }
            val bak = File(bridge, "mtzstudio-restore-$id.bak")
            AggregateThemesBak.write(tar, bak, 123, 36, "", listOf("signature"))
            val archive = AggregateThemesBak.inspectBackup(bak)
            val checks = File(bridge, "mtzstudio-restore-$id.txt").apply {
                writeText(ThemesContentRestore.checksums(bak, archive), Charsets.UTF_8)
            }
            val command = ThemesContentRestore.command(id, archive.tarOffset, hash(bak), hash(checks))
                .replace("/sdcard/Android", "${path(dir)}/Android")
                .replace("/sdcard/Download", "${path(dir)}/Download")
            val overrides = "id() { printf '2000\\n'; }; am() { if [ \"\$1\" = get-current-user ]; then printf '0\\n'; fi; };\n"
            test(File(dir, "Android/data/com.android.thememanager/files/MIUI/theme"), overrides + command)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `copies exact unicode files and repeat does not overwrite existing files`() = fixture { root, command ->
        val (code, output) = execute(command)
        assertEquals(0, code, output)
        assertTrue(output.contains("MTZ_RESTORE_FILES_VERIFIED"))
        assertEquals("component bytes", File(root, ".data/content/android小白条沉浸/id.mrc").readText())
        val unrelated = File(root, ".data/content/new-theme.mrc").apply { writeText("keep me") }
        val repeated = execute(command)
        assertEquals(0, repeated.first, repeated.second)
        assertEquals("keep me", unrelated.readText())
    }

    @Test fun `different existing files stop before publishing anything`() = fixture { root, command ->
        val existing = File(root, ".data/content/android小白条沉浸/id.mrc").apply {
            parentFile.mkdirs(); writeText("newer content")
        }
        val (code, output) = execute(command)
        assertEquals(43, code, output)
        assertEquals("newer content", existing.readText())
        assertFalse(File(root, ".data/meta/theme/id.mrm").exists())
        assertFalse(output.contains("MTZ_RESTORE_FILES_VERIFIED"))
    }

    @Test fun `publish failure retains existing data and never reports success`() = fixture { root, command ->
        val existing = File(root, ".data/content/other.mrc").apply { parentFile.mkdirs(); writeText("keep") }
        val (code, output) = execute("mv() { return 1; };\n$command")
        assertTrue(code != 0)
        assertFalse(output.contains("MTZ_RESTORE_FILES_VERIFIED"))
        assertEquals("keep", existing.readText())
    }
}
