package dev.glorioustr.mtzstudio.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Text-only rewrite. Nested components are identified by ZIP magic, not file extensions. */
class ThemeTextLocalizer(
    private val maxExpandedBytes: Long = 512L * 1024 * 1024,
    private val maxEntryBytes: Long = 256L * 1024 * 1024,
    private val maxPreservedEntryBytes: Long = 512L * 1024 * 1024,
    // Modern lock-screen themes commonly contain the MTZ > lockscreen > clock >
    // left/right component chain. Four layers was enough for older packages but
    // leaves the component labels untouched in newer, modular themes.
    private val maxDepth: Int = 8,
    private val targetLanguage: String = "tr",
    private val translateAllDisplayText: Boolean = false,
    private val shouldTranslate: ((String) -> Boolean)? = null,
) {
    data class Result(val changedFiles: List<String>, val translatedNodes: Int, val skippedFiles: List<String>, val unresolvedTexts: List<String> = emptyList())

    fun rewrite(source: Path, output: Path, translate: (String) -> String): Result {
        require(source.toAbsolutePath().normalize() != output.toAbsolutePath().normalize())
        val state = State(translate)
        try {
            rewriteZip(source, output, "", 0, state)
            return Result(state.changed.toList(), state.nodes, state.skipped.toList(), state.unresolved.toList())
        } catch (error: Throwable) {
            Files.deleteIfExists(output)
            throw error
        }
    }

    /** Scans only nested packages and small textual resources; large images are never copied. */
    fun collectCandidates(source: Path): Set<String> {
        require(Files.exists(source) && Files.isRegularFile(source)) { "Theme archive not found" }
        val candidates = linkedSetOf<String>()
        val state = State({ value -> value.trim().takeIf(String::isNotBlank)?.let(candidates::add); value }, collectOnly = true)
        scanZip(source, "", 0, state)
        return candidates
    }

    private inner class State(val translate: (String) -> String, val collectOnly: Boolean = false) {
        var expanded = 0L
        var entries = 0
        var nodes = 0
        val changed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val cache = mutableMapOf<String, String>()
        val unresolved = linkedSetOf<String>()
        fun text(value: String): String {
            if (!isTranslationCandidate(value)) return value
            check(!Thread.currentThread().isInterrupted) { "Translation interrupted" }
            return cache.getOrPut(value) {
                translate(value).also {
                    check(it.isNotBlank()) { "Empty translation result" }
                    if (CHINESE.containsMatchIn(it)) unresolved += value
                }
            }
        }

        fun isTranslationCandidate(value: String): Boolean {
            shouldTranslate?.let { return it(value) }
            if (!translateAllDisplayText) return CHINESE.containsMatchIn(value)
            val trimmed = value.trim()
            if (trimmed.isEmpty() || !LETTER.containsMatchIn(trimmed)) return false
            if (NON_DISPLAY_VALUE.matches(trimmed)) return false
            return true
        }
    }

    private fun scanZip(source: Path, prefix: String, depth: Int, state: State) {
        check(depth <= maxDepth) { "Theme archive nesting limit exceeded" }
        ZipFile(source.toFile()).use { zip ->
            val names = hashSetOf<String>()
            zip.entries().asSequence().forEach { entry ->
                check(++state.entries <= 20_000) { "Theme archive entry limit exceeded" }
                val name = SafeArchivePath.normalize(entry.name, entry.isDirectory)
                check(names.add(name)) { "Duplicate archive entry: $name" }
                if (entry.isDirectory) return@forEach
                val path = "$prefix$name"
                if (isOpaqueComponent(name, depth) || entry.size > maxEntryBytes) { state.skipped += path; return@forEach }
                zip.getInputStream(entry).buffered().use { input ->
                    input.mark(4)
                    val magic = ByteArray(4)
                    val count = input.read(magic)
                    input.reset()
                    when {
                        count == 4 && magic.contentEquals(byteArrayOf(80, 75, 3, 4)) -> {
                            val temp = Files.createTempFile(source.toAbsolutePath().parent, ".translation-scan-", ".zip")
                            try {
                                Files.newOutputStream(temp).use { copyBounded(input, it, state) }
                                scanZip(temp, "$path!/", depth + 1, state)
                            } finally { Files.deleteIfExists(temp) }
                        }
                        entry.size in 0..MAX_RESOURCE_BYTES && mayContainTextResource(name, input) -> {
                            val bytes = ByteArrayOutputStream(); copyBounded(input, bytes, state, MAX_RESOURCE_BYTES)
                            when (resourceFormat(name, bytes.toByteArray())) {
                                ResourceFormat.XML -> localizeXml(bytes.toByteArray(), path, state)
                                ResourceFormat.JSON -> localizeJson(bytes.toByteArray(), path, state)
                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    private fun rewriteZip(source: Path, output: Path, prefix: String, depth: Int, state: State) {
        check(depth <= maxDepth) { "Theme archive nesting limit exceeded" }
        ZipFile(source.toFile()).use { zip ->
            ZipOutputStream(Files.newOutputStream(output)).use { out ->
                val names = hashSetOf<String>()
                zip.entries().asSequence().forEach { entry ->
                    check(++state.entries <= 20_000) { "Theme archive entry limit exceeded" }
                    val name = SafeArchivePath.normalize(entry.name, entry.isDirectory)
                    check(names.add(name)) { "Duplicate archive entry: $name" }
                    val path = "$prefix$name"
                    out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    if (!entry.isDirectory) zip.getInputStream(entry).buffered().use { input ->
                        if (isOpaqueComponent(name, depth) || entry.size > maxEntryBytes) {
                            check(entry.size <= maxPreservedEntryBytes) { "Theme entry too large: $name" }
                            state.skipped += path
                            copyBounded(input, out, state, maxPreservedEntryBytes)
                            out.closeEntry()
                            return@forEach
                        }
                        input.mark(4)
                        val magic = ByteArray(4)
                        val count = input.read(magic)
                        input.reset()
                        when {
                            count == 4 && magic.contentEquals(byteArrayOf(80, 75, 3, 4)) -> {
                                val nested = Files.createTempFile(output.parent, ".translate-input-", ".zip")
                                val rewritten = Files.createTempFile(output.parent, ".translate-output-", ".zip")
                                try {
                                    Files.newOutputStream(nested).use { copyBounded(input, it, state) }
                                    val before = state.nodes
                                    rewriteZip(nested, rewritten, "$path!/", depth + 1, state)
                                    // Preserve the exact original component bytes if no text changed.
                                    Files.newInputStream(if (before == state.nodes) nested else rewritten).use { it.copyTo(out) }
                                } finally {
                                    Files.deleteIfExists(nested)
                                    Files.deleteIfExists(rewritten)
                                }
                            }
                            entry.size in 0..MAX_RESOURCE_BYTES && mayContainTextResource(name, input) -> {
                                val bytes = ByteArrayOutputStream()
                                copyBounded(input, bytes, state, MAX_RESOURCE_BYTES)
                                val original = bytes.toByteArray()
                                val replacement = when (resourceFormat(name, original)) {
                                    ResourceFormat.XML -> localizeXml(original, path, state)
                                    ResourceFormat.JSON -> localizeJson(original, path, state)
                                    null -> original
                                }
                                out.write(replacement)
                            }
                            else -> {
                                if ((name.endsWith(".xml", true) || name.endsWith(".json", true)) && entry.size > MAX_RESOURCE_BYTES) {
                                    state.skipped += path
                                }
                                copyBounded(input, out, state)
                            }
                        }
                    }
                    out.closeEntry()
                }
            }
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, state: State, limit: Long = maxEntryBytes) {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "Translation interrupted" }
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            state.expanded += count
            check(total <= limit && state.expanded <= maxExpandedBytes) { "Theme expansion limit exceeded" }
            output.write(buffer, 0, count)
        }
    }

    private fun localizeXml(bytes: ByteArray, path: String, state: State): ByteArray {
        // Reject declarations even on Android parsers that do not implement every hardening flag.
        val declarationProbe = bytes.toString(Charsets.ISO_8859_1).replace("\u0000", "")
        check(!Regex("<!\\s*(DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE).containsMatchIn(declarationProbe)) {
            "External XML declarations are forbidden: $path"
        }
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                runCatching { isXIncludeAware = false }
                runCatching { isExpandEntityReferences = false }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw SAXException("External XML entities are disabled") }
            }.parse(bytes.inputStream())
        } catch (error: Exception) {
            // Non-XML resources with an .xml suffix must remain untouched.
            state.skipped += path
            return bytes
        }
        val before = state.nodes
        val expressions = MamlTextTranslator(document, targetLanguage, state::text, state::isTranslationCandidate)
        // Snapshot: localized helper variables inserted during rewriting must not be processed twice.
        val nodes = document.getElementsByTagName("*").let { all ->
            (0 until all.length).map { all.item(it) as Element }
        }
        for (originalNode in nodes) {
            var node = originalNode
            if (node.tagName.equals("DateTime", true)) {
                val pattern = if (node.hasAttribute("formatExp")) node.getAttribute("formatExp") else MamlTextTranslator.quote(node.getAttribute("format"))
                if (node.hasAttribute("format") || node.hasAttribute("formatExp")) {
                    val display = expressions.expression(pattern, node.getAttribute("value").ifBlank { "#time_sys" })
                    if (CHINESE.containsMatchIn(pattern) ||
                        (translateAllDisplayText && containsDateProse(pattern)) || pattern.contains('@')
                    ) {
                        val replacement = document.createElement("Text")
                        for (i in 0 until node.attributes.length) {
                            val attribute = node.attributes.item(i)
                            if (attribute.nodeName !in setOf("format", "formatExp", "value")) replacement.setAttribute(attribute.nodeName, attribute.nodeValue)
                        }
                        replacement.setAttribute("textExp", display)
                        node.parentNode.replaceChild(replacement, node)
                        node = replacement
                        state.nodes++
                    }
                }
            }
            val tag = node.tagName.lowercase(Locale.ROOT)
            val attrs = node.attributes
            for (i in 0 until attrs.length) {
                val attr = attrs.item(i)
                val name = attr.nodeName.lowercase(Locale.ROOT)
                val original = attr.nodeValue
                val replacement = when {
                    name in setOf("textexp", "formatexp") && tag == "text" -> expressions.expression(original)
                    name == "text" && tag == "text" -> {
                        if (state.isTranslationCandidate(original)) state.text(original)
                        else expressions.expression(original).takeIf { it != original } ?: state.text(original)
                    }
                    name == "format" && tag == "text" -> state.text(original)
                    name == "format" && tag == "datetime" -> ThemeGlossary.convertDatePattern(original, targetLanguage) ?: original
                    name in DISPLAY_ATTRIBUTES && tag !in CODE_TAGS -> state.text(original)
                    name == "default" && tag in setOf("stringinput", "string") -> ThemeGlossary.convertDatePattern(original, targetLanguage) ?: state.text(original)
                    else -> original
                }
                if (replacement != original) {
                    attr.nodeValue = replacement
                    state.nodes++
                }
            }
            val leaf = (0 until node.childNodes.length).none { node.childNodes.item(it) is Element }
            if (leaf && tag in setOf("string", "title", "description", "summary", "label", "p", "span")) {
                val original = node.textContent
                // MAML string resource files also store date-format patterns. Sending those
                // through a prose model can turn tokens into visible words and break the clock.
                val replacement = ThemeGlossary.convertDatePattern(original, targetLanguage) ?: state.text(original)
                if (replacement != original) { node.textContent = replacement; state.nodes++ }
            }
        }
        if (state.collectOnly) return bytes
        if (state.nodes > before) state.nodes += ThemeLayoutOptimizer.optimize(document, targetLanguage)
        if (state.nodes == before) return bytes
        val result = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(result))
        // Parse the serialized XML again before it may replace a library item.
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(result.toString("UTF-8"))))
        state.changed += path
        return result.toByteArray()
    }

    private fun localizeJson(bytes: ByteArray, path: String, state: State): ByteArray {
        val text = bytes.toString(Charsets.UTF_8)
        var changes = 0
        // Validate before invoking the translator, so malformed JSON cannot partially consume work.
        try { JsonDisplayLocalizer(text) { it }.rewrite() } catch (_: IllegalArgumentException) {
            state.skipped += path
            return bytes
        } catch (_: IllegalStateException) {
            state.skipped += path
            return bytes
        }
        val rewritten = JsonDisplayLocalizer(text) { original ->
            state.text(original).also { if (it != original) changes++ }
        }.rewrite()
        if (state.collectOnly) return bytes
        if (changes == 0) return bytes
        state.nodes += changes
        state.changed += path
        return rewritten.toByteArray(Charsets.UTF_8)
    }

    /**
     * Some MAML packages deliberately omit a file extension (for example
     * `left_component` and `right_component`).  Their payload is still XML, so
     * recognize safe textual content instead of relying only on its filename.
     */
    private fun mayContainTextResource(name: String, input: InputStream): Boolean {
        if (name.contains("rights", true)) return false
        val leaf = name.substringAfterLast('/')
        if (leaf.endsWith(".xml", true) || leaf.endsWith(".json", true) || leaf.endsWith(".maml", true)) return true
        input.mark(TEXT_PROBE_BYTES)
        val probe = ByteArray(TEXT_PROBE_BYTES)
        val count = input.read(probe)
        input.reset()
        return resourceFormat(leaf, probe.copyOf(count.coerceAtLeast(0))) != null
    }

    private fun resourceFormat(name: String, bytes: ByteArray): ResourceFormat? {
        if (name.contains("rights", true)) return null
        val text = decodeTextProbe(bytes)?.trimStart('\uFEFF', ' ', '\t', '\r', '\n') ?: return null
        return when {
            name.endsWith(".xml", true) || name.endsWith(".maml", true) || text.startsWith('<') -> ResourceFormat.XML
            name.endsWith(".json", true) || text.startsWith('{') || text.startsWith('[') -> ResourceFormat.JSON
            else -> null
        }
    }

    private fun decodeTextProbe(bytes: ByteArray): String? = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> bytes.toString(Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> bytes.toString(Charsets.UTF_16BE)
        else -> bytes.toString(Charsets.UTF_8)
    }.takeIf { text -> text.none { it == '\u0000' } }

    private enum class ResourceFormat { XML, JSON }

    companion object {
        private const val MAX_RESOURCE_BYTES = 2L * 1024 * 1024
        private const val TEXT_PROBE_BYTES = 512
        private val CHINESE = Regex("[\\p{IsHan}]+")
        private val LETTER = Regex("\\p{L}")
        private val NON_DISPLAY_VALUE = Regex(
            """(?ix)(?:[a-z][a-z0-9+.-]*://\S+)|(?:[/\\][^\s]+)+|(?:\x23[0-9a-f]{3,8})|(?:-?\d+(?:\.\d+)?(?:dp|sp|px|%)?)""",
        )
        private val DISPLAY_ATTRIBUTES = setOf(
            "text", "summary", "title", "description", "hint", "label",
            "contentdescription", "content_description", "placeholder", "preview_text", "subtitle", "message",
        )
        private val CODE_TAGS = setOf(
            "script", "source", "command", "var", "variable", "variablecommand",
            "action", "intent", "method", "function",
        )

        private fun isOpaqueComponent(name: String, depth: Int): Boolean =
            depth == 0 && name.substringAfterLast('/').equals("icons", ignoreCase = true)

        private fun containsDateProse(pattern: String): Boolean {
            val withoutTokens = pattern
                .replace(Regex("[yMdEHhmsSaDZzYNe]+"), "")
                .replace(Regex("['\\s,./:()_-]+"), "")
            return LETTER.containsMatchIn(withoutTokens)
        }
    }
}
