package dev.glorioustr.mtzstudio.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MtzParserTest {
    @Test
    fun `parses metadata components hashes and reports rights`() {
        val mtz = zip(
            "description.xml" to "<theme><title>Night</title><author>Ada</author><version>1</version></theme>".encodeToByteArray(),
            "icons" to byteArrayOf(1, 2, 3),
            "wallpaper/default.jpg" to byteArrayOf(4, 5),
            "com.android.contacts" to byteArrayOf(6, 7),
            "com.android.mms" to byteArrayOf(8, 9),
            "rights/rights.xml" to "owned-source-only".encodeToByteArray(),
        )

        val parsed = MtzParser().parse(mtz)

        assertEquals("Night", assertNotNull(parsed.metadata).name)
        assertEquals(
            setOf(ComponentCategory.ICONS, ComponentCategory.WALLPAPER, ComponentCategory.CONTACTS, ComponentCategory.MMS),
            parsed.components.map { it.category }.toSet(),
        )
        assertEquals(listOf("rights/rights.xml"), parsed.rightsEntries)
        assertEquals(64, parsed.sha256.length)
    }

    @Test
    fun `rejects traversal paths`() {
        val error = assertFailsWith<UnsafeMtzException> {
            MtzParser().parse(zip("../escape" to byteArrayOf(1)))
        }
        assertEquals(UnsafeMtzException.Reason.UNSAFE_PATH, error.reason)
    }

    @Test
    fun `rejects ambiguous backslash paths`() {
        val error = assertFailsWith<UnsafeMtzException> {
            MtzParser().parse(zip("wallpaper\\..\\escape" to byteArrayOf(1)))
        }
        assertEquals(UnsafeMtzException.Reason.UNSAFE_PATH, error.reason)
    }

    @Test
    fun `rejects case insensitive duplicate paths`() {
        val error = assertFailsWith<UnsafeMtzException> {
            MtzParser().parse(zip("Icons/a" to byteArrayOf(1), "icons/A" to byteArrayOf(2)))
        }
        assertEquals(UnsafeMtzException.Reason.DUPLICATE_PATH, error.reason)
    }

    @Test
    fun `rejects suspicious compression ratios`() {
        val error = assertFailsWith<UnsafeMtzException> {
            MtzParser(MtzSecurityLimits(maxCompressionRatio = 10.0)).parse(
                zip("icons" to ByteArray(100_000)),
            )
        }
        assertEquals(UnsafeMtzException.Reason.SUSPICIOUS_COMPRESSION_RATIO, error.reason)
    }

    @Test
    fun `rejects doctype metadata`() {
        val xml = """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><theme><title>&e;</title></theme>"""
        val error = assertFailsWith<UnsafeMtzException> {
            MtzParser().parse(zip("description.xml" to xml.encodeToByteArray()))
        }
        assertEquals(UnsafeMtzException.Reason.UNSAFE_XML, error.reason)
    }

    @Test
    fun `parses Xiaomi metadata with CDATA and localized fields`() {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <theme>
              <version><![CDATA[OS3 v1.2]]></version>
              <author><![CDATA[Huy KZ, MtzIconPack]]></author>
              <title><![CDATA[iP27 Pro Eng Mod New]]></title>
              <titles><title locale="en_US"><![CDATA[iP27 Pro Eng Mod New]]></title></titles>
            </theme>
        """.trimIndent()

        val parsed = MtzParser().parse(zip("description.xml" to xml.encodeToByteArray(), "icons" to byteArrayOf(1)))

        assertEquals("iP27 Pro Eng Mod New", assertNotNull(parsed.metadata).name)
        assertEquals("Huy KZ, MtzIconPack", parsed.metadata?.author)
        assertEquals("OS3 v1.2", parsed.metadata?.version)
    }

    @Test
    fun `recognizes alternate Theme Manager component names`() {
        val parsed = MtzParser().parse(
            zip(
                "statusbar" to byteArrayOf(1),
                "clock_2x4" to byteArrayOf(10),
                "contact" to byteArrayOf(2),
                "mms" to byteArrayOf(3),
                "lockstyle" to byteArrayOf(4),
                "miwallpaper" to byteArrayOf(5),
                "spaod" to byteArrayOf(6),
                "largeicons" to byteArrayOf(7),
                "fonts_fallback" to byteArrayOf(8),
                "framework-res" to byteArrayOf(9),
            ),
        )

        assertEquals(
            setOf(
                ComponentCategory.SYSTEM_UI,
                ComponentCategory.WIDGET,
                ComponentCategory.CONTACTS,
                ComponentCategory.MMS,
                ComponentCategory.LOCKSCREEN,
                ComponentCategory.WALLPAPER,
                ComponentCategory.AOD,
                ComponentCategory.ICONS,
                ComponentCategory.FONT,
                ComponentCategory.FRAMEWORK,
            ),
            parsed.components.map { it.category }.toSet(),
        )
    }

    @Test
    fun `rejects unix symlink entries`() {
        val mtz = zip("icons" to "target".encodeToByteArray())
        val bytes = Files.readAllBytes(mtz)
        val central = bytes.findSignature(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        assertTrue(central >= 0)
        bytes[central + 5] = 3 // Unix creator system.
        bytes[central + 38] = 0
        bytes[central + 39] = 0
        bytes[central + 40] = 0xff.toByte()
        bytes[central + 41] = 0xa1.toByte() // 0120777 symlink mode.
        mtz.writeBytes(bytes)

        val error = assertFailsWith<UnsafeMtzException> { MtzParser().parse(mtz) }
        assertEquals(UnsafeMtzException.Reason.SYMLINK, error.reason)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): Path {
        val path = Files.createTempFile("mtz-core-test", ".mtz")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return path
    }

    private fun ByteArray.findSignature(signature: ByteArray): Int {
        for (index in 0..size - signature.size) {
            if (signature.indices.all { this[index + it] == signature[it] }) return index
        }
        return -1
    }
}
