package dev.glorioustr.mtzstudio.core

import org.w3c.dom.Document
import org.w3c.dom.Element

/** Small MAML layout corrections for labels that expand noticeably in Turkish. */
internal object ThemeLayoutOptimizer {
    fun optimize(document: Document, targetLanguage: String): Int {
        if (!targetLanguage.startsWith("tr")) return 0
        var changes = 0
        val elements = document.getElementsByTagName("*")
        for (index in 0 until elements.length) {
            val element = elements.item(index) as? Element ?: continue
            if (!element.tagName.equals("Text", ignoreCase = true)) continue

            val newSize = when {
                // In landscape mode these labels are rotated into fixed 260 px controls.
                // Long Turkish states such as “Sesli saat: Tam saat” need extra room.
                element.getAttribute("x") == "210" &&
                    element.getAttribute("size") in setOf("30", "40") &&
                    element.hasAttribute("rotation") -> "23"

                // The 光栅 theme's side menus use a fixed 420 px card with 40 px Chinese labels.
                // Turkish state labels need a smaller size to remain inside those cards.
                element.getAttribute("x") == "210" && element.getAttribute("size") == "40" -> "30"

                // Four compact weather cards across a 1080 px canvas.
                element.getAttribute("x").contains("220*(#__i-1.5)") && element.getAttribute("size") == "34" -> "26"

                // Music source label inside the 270 px bottom player card.
                element.getAttribute("w") == "270" && element.getAttribute("size") == "40" -> "30"

                else -> null
            }
            if (newSize != null) {
                element.setAttribute("size", newSize)
                changes++
            }

            // Component editors use fixed-width buttons but Chinese labels are much
            // shorter than their translated counterparts.  Scale a literal label to
            // its own available width so it remains readable instead of overflowing
            // into the neighbouring control.
            val literal = element.getAttribute("text").takeIf { it.isNotBlank() }
                ?: element.textContent.takeIf { it.isNotBlank() && element.childNodes.length == 1 }
            val width = element.getAttribute("w").toFloatOrNull()
            val size = element.getAttribute("size").toFloatOrNull()
            if (literal != null && width != null && size != null && literal.length >= 10) {
                val fitted = (width / (literal.length * 0.56f)).coerceAtLeast(14f)
                if (fitted < size) {
                    element.setAttribute("size", fitted.toInt().toString())
                    changes++
                }
            }
        }
        return changes
    }
}
