package dev.glorioustr.mtzstudio.core

import org.w3c.dom.Document
import org.w3c.dom.Element

/** Localizes display expressions, never predicates, paths or the original control variables. */
internal class MamlTextTranslator(
    private val document: Document,
    private val language: String,
    private val translate: (String) -> String,
    private val shouldTranslate: (String) -> Boolean,
) {
    constructor(document: Document, language: String, translate: (String) -> String) :
        this(document, language, translate, { true })
    private val elements = document.getElementsByTagName("*").let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private val definitions = elements.filter { element ->
        element.tagName == "Var" && (element.getAttribute("type").startsWith("string") ||
            sequenceOf("expression", "values").map(element::getAttribute).any(ThemeGlossary::containsChinese))
    }
        .groupBy { it.getAttribute("name") }
    private val clones = mutableMapOf<String, String>()
    private val visiting = hashSetOf<String>()

    fun expression(input: String, dateTime: String? = null, depth: Int = 0): String {
        if (depth > 24) return input
        val s = input.trim()
        if (s.startsWith("(") && s.endsWith(")") && split(s.substring(1, s.length - 1), ',').size == 1) {
            val inside = s.substring(1, s.length - 1)
            // Only unwrap a balanced outer pair, not '(a)+(b)'.
            if (balanced(inside)) return "(" + expression(inside, dateTime, depth + 1) + ")"
        }
        val parts = split(s, '+')
        if (parts.size > 1) {
            val folded = parts.toMutableList()
            var at = 0
            while (at < folded.lastIndex) {
                val left = literal(folded[at])
                val right = literal(folded[at + 1])
                val merged = when {
                    left != null && right != null -> quote(left + right)
                    left != null && left.isNotEmpty() -> withAffix(folded[at + 1], prefix = left)
                    right != null && right.isNotEmpty() -> withAffix(folded[at], suffix = right)
                    else -> null
                }
                if (merged != null) { folded[at] = merged; folded.removeAt(at + 1); if (at > 0) at-- }
                else at++
            }
            if (folded.size < parts.size) return expression(folded.joinToString("+"), dateTime, depth + 1)
            // Chinese progressive prefix belongs to the whole charging status, not a word on its own.
            val combined = parts.toMutableList()
            for (i in 0 until combined.lastIndex) {
                if (literal(combined[i]) == "正在" && combined[i + 1].contains("充")) {
                    combined[i] = "''"
                    combined[i + 1] = chargingBranches(combined[i + 1])
                }
            }
            return combined.joinToString("+") { expression(it, dateTime, depth + 1) }
        }
        literal(s)?.let { value ->
            return if (dateTime != null) dateLiteral(value, dateTime) else quote(translate(value))
        }
        val call = Regex("([A-Za-z_][A-Za-z_0-9]*)\\((.*)\\)", RegexOption.DOT_MATCHES_ALL).matchEntire(s)
        if (call != null) {
            val args = split(call.groupValues[2], ',')
            return when (call.groupValues[1]) {
                "ifelse" -> if (args.size >= 3 && args.size % 2 == 1) {
                    "ifelse(" + args.mapIndexed { index, arg ->
                        if (index % 2 == 1 || index == args.lastIndex) expression(arg, dateTime, depth + 1) else arg
                    }.joinToString(",") + ")"
                } else s
                "formatDate" -> if (args.size == 2) expression(args[0], args[1], depth + 1) else s
                // Chinese themes often build the weekday from a compact seven-character
                // string. Translating that string as prose would break substr indexes, so
                // preserve the index expression and return localized weekday branches.
                "substr" -> localizedWeekdaySubstring(args) ?: s
                else -> s // Includes comparison literals: translating them would change program behavior.
            }
        }
        // MAML themes use both @name and #name for string values.  Historically we
        // only followed @ references, which left otherwise safe, display-only values
        // untranslated in complex lock-screen packages.
        val ref = Regex("([@#])([A-Za-z_][A-Za-z_0-9.]*)(\\[.*])?").matchEntire(s) ?: return s
        val marker = ref.groupValues[1]
        val name = ref.groupValues[2]
        val key = "$name|$dateTime"
        clones[key]?.let { return "$marker$it${ref.groupValues[3]}" }
        val node = definitions[name]?.singleOrNull()
        val written = elements.any { it.tagName == "VariableCommand" && it.getAttribute("name") == name }
        if (node != null && !written && visiting.add(key)) {
            try {
                val attr = if (node.hasAttribute("values")) "values" else "expression"
                val original = node.getAttribute(attr)
                val output = if (attr == "values") split(original, ',').joinToString(",") { expression(it, dateTime, depth + 1) }
                    else expression(original, dateTime, depth + 1)
                if (output != original) {
                    var cloneName = "__mtz_locale_${clones.size}"
                    while (elements.any { it.getAttribute("name") == cloneName } || cloneName in clones.values) cloneName += "_"
                    val clone = node.cloneNode(true) as Element
                    clone.setAttribute("name", cloneName)
                    clone.setAttribute(attr, output)
                    clone.removeAttribute("persist")
                    node.parentNode.insertBefore(clone, node.nextSibling)
                    clones[key] = cloneName
                    return "$marker$cloneName${ref.groupValues[3]}"
                }
            } finally { visiting.remove(key) }
        }
        // Event-driven and provider values stay intact. Only their displayed value is mapped.
        val candidates = linkedSetOf<String>()
        // Duplicate static variables are common in large MAML files (one per screen/group).
        // They cannot be cloned unambiguously, but their final displayed values can still be
        // mapped without changing any source variable or comparison logic.
        definitions[name].orEmpty().forEach { definition ->
            val attribute = if (definition.hasAttribute("values")) "values" else "expression"
            LITERALS.findAll(definition.getAttribute(attribute)).forEach { literal(it.value)?.let(candidates::add) }
        }
        elements.filter { it.tagName == "VariableCommand" && it.getAttribute("name") == name }.forEach { writer ->
            LITERALS.findAll(writer.getAttribute("expression")).forEach { literal(it.value)?.let(candidates::add) }
        }
        if (elements.any { it.tagName == "Variable" && it.getAttribute("name") == name && it.getAttribute("column") == "description" }) {
            candidates += listOf("晴", "晴天", "多云", "阴", "阴天", "少云", "阵雨", "雷阵雨", "雷雨", "小雨", "中雨", "大雨", "暴雨", "大暴雨", "特大暴雨", "雨夹雪", "小雪", "中雪", "大雪", "暴雪", "雾", "霾", "浮尘", "扬沙", "沙尘暴")
        }
        val mappings = candidates.filter(shouldTranslate).map { it to translate(it) }.filter { it.first != it.second }
        if (mappings.isEmpty()) return s
        return "ifelse(" + mappings.joinToString(",") { (from, to) -> "eqs($s,${quote(from)}),${quote(to)}" } + ",$s)"
    }

    private fun localizedWeekdaySubstring(args: List<String>): String? {
        if (args.size != 3) return null
        val source = literal(args[0]) ?: return null
        if (source !in setOf("日一二三四五六", "天一二三四五六")) return null
        val start = args[1]
        val length = args[2].trim()
        if (length != "1") return null
        val weekdays = if (language.startsWith("tr")) {
            listOf("Paz", "Pzt", "Sal", "Çar", "Per", "Cum", "Cmt")
        } else {
            listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        }
        return "ifelse(" + weekdays.mapIndexed { index, day -> "eq($start,$index),${quote(day)}" }.joinToString(",") + ",${quote(weekdays[0])})"
    }

    private fun chargingBranches(s: String): String = LITERALS.replace(s) { match ->
        when (val value = literal(match.value)) {
            "充电", "快充", "超级快充", "极速快充" -> quote("正在$value")
            else -> match.value
        }
    }

    private fun withAffix(expression: String, prefix: String = "", suffix: String = ""): String? {
        if (!expression.startsWith("ifelse(") || !expression.endsWith(")")) return null
        val args = split(expression.substring(7, expression.length - 1), ',')
        if (args.size < 3 || args.size % 2 != 1) return null
        val output = args.mapIndexed { index, arg ->
            if (index % 2 == 1 || index == args.lastIndex) {
                val value = literal(arg) ?: return null
                quote(prefix + value + suffix)
            } else arg
        }
        return "ifelse(" + output.joinToString(",") + ")"
    }

    private fun dateLiteral(value: String, time: String): String {
        if (value == "今天是今年的第D天" && language == "tr") return "'Yılın '+formatDate('D',$time)+'. günü'"
        // A number of component authors feed custom strftime-like strings into
        // formatDate. They are executable format syntax, not visible prose. Passing
        // those through the language model turns %D / token fragments into words and
        // produces the on-screen "format error" seen in the lock-screen editor.
        if ('%' in value || !DATE_PATTERN_CHARS.matches(value)) return quote(value)
        // Render literal and date fragments separately; translated words must never become date tokens.
        var pattern = ThemeGlossary.convertDatePattern(value, language) ?: value
        val lunar = Regex("(?:Y+年\\s*)?N+月e+")
        val lunarMatch = lunar.find(pattern)
        if (lunarMatch != null && time.trim() == "#time_sys") {
            val before = pattern.substring(0, lunarMatch.range.first)
            val after = pattern.substring(lunarMatch.range.last + 1)
            val label = translate("农历") + " "
            val year = if (lunarMatch.value.startsWith("Y")) "+'/'+#year_lunar" else ""
            return listOf(dateLiteral(before, time), quote(label), "#date_lunar", quote("/"), "(#month_lunar+1)$year", dateLiteral(after, time)).joinToString("+")
        }
        if (pattern.isEmpty()) return "''"
        val output = mutableListOf<String>()
        // Known MAML date tokens only. Chinese prose is translated as a phrase between tokens.
        val token = Regex("[yMdEHhmsSaDZz]+|[YNe]+")
        var at = 0
        token.findAll(pattern).forEach { match ->
            if (match.range.first > at) output += quote(translate(pattern.substring(at, match.range.first)))
            output += when {
                time.trim() == "#time_sys" && match.value.all { it == 'Y' } -> "#year_lunar"
                time.trim() == "#time_sys" && match.value.all { it == 'N' } -> "(#month_lunar+1)"
                time.trim() == "#time_sys" && match.value.all { it == 'e' } -> "#date_lunar"
                else -> "formatDate(${quote(match.value)},$time)"
            }
            at = match.range.last + 1
        }
        if (at < pattern.length) output += quote(translate(pattern.substring(at)))
        return output.joinToString("+").ifEmpty { quote(translate(pattern)) }
    }

    companion object {
        private val DATE_PATTERN_CHARS = Regex("[yMdEHhmsSaDZzYNe年年月日点时分\\s,./:()_\\-·]+")
        private fun balanced(s: String): Boolean {
            var depth = 0
            val stripped = LITERALS.replace(s, "''")
            for (c in stripped) { if (c == '(') depth++; if (c == ')') depth--; if (depth < 0) return false }
            return depth == 0
        }
        private val LITERALS = Regex("""'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"""")
        fun quote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
        fun literal(s: String): String? {
            if (!LITERALS.matches(s.trim())) return null
            val v = s.trim()
            return Regex("\\\\(.)", RegexOption.DOT_MATCHES_ALL).replace(v.substring(1, v.length - 1)) { it.groupValues[1] }
        }
        fun split(s: String, delimiter: Char): List<String> {
            val result = mutableListOf<String>()
            var start = 0
            var depth = 0
            var quote: Char? = null
            var escaped = false
            s.forEachIndexed { i, c ->
                if (quote != null) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
                } else when (c) {
                    '\'', '"' -> quote = c
                    '(', '[' -> depth++
                    ')', ']' -> depth--
                    delimiter -> if (depth == 0) { result += s.substring(start, i).trim(); start = i + 1 }
                }
            }
            result += s.substring(start).trim()
            return result
        }
    }
}
