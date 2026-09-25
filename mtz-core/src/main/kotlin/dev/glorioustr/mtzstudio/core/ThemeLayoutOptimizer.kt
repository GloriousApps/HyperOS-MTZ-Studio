package dev.glorioustr.mtzstudio.core

import org.w3c.dom.Document
import org.w3c.dom.Element

/** Keeps translated MAML labels inside the fixed controls supplied by each theme. */
internal object ThemeLayoutOptimizer {
    fun optimize(document: Document, targetLanguage: String): Int {
        var changes = 0
        val elements = document.getElementsByTagName("*")
        for (index in 0 until elements.length) {
            val element = elements.item(index) as? Element ?: continue
            if (!element.tagName.equals("Text", ignoreCase = true)) continue

            val newSize = if (targetLanguage.startsWith("tr")) when {
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

                // Super Duo's three editor switches are only 220 px wide.  The
                // original 38 px Chinese labels fit; Turkish labels overlap.
                element.getAttribute("x") == "#function1_x+110+(#function_width+#function_gap)*#__i" &&
                    element.getAttribute("size") == "38" -> "24"

                else -> null
            } else null
            if (newSize != null) {
                element.setAttribute("size", newSize)
                changes++
            }

            // Component editors use fixed-width buttons but Chinese labels are much
            // shorter than their translated counterparts.  Scale a literal label to
            // its own available width so it remains readable instead of overflowing
            // into the neighbouring control.
            val literal = element.getAttribute("text").takeIf {
                it.isNotBlank() && !it.contains('#') && !it.contains('@')
            }
                ?: element.textContent.takeIf { it.isNotBlank() && element.childNodes.length == 1 }
            val ownWidth = element.getAttribute("w").toFloatOrNull()
            // MAML buttons normally nest their labels in Normal/Pressed. The
            // immediate parent has no width; the Button two levels up does.
            var ancestor = element.parentNode as? Element
            var parentWidth: Float? = null
            repeat(3) {
                if (parentWidth == null) {
                    parentWidth = ancestor?.getAttribute("w")?.toFloatOrNull()
                        ?.takeIf { it in 24f..500f }
                    ancestor = ancestor?.parentNode as? Element
                }
            }
            val width = ownWidth ?: parentWidth
            val size = element.getAttribute("size").toFloatOrNull()
            if (literal != null && width != null && width >= 24f && size != null && size > 9f) {
                // Leave a margin for rounded corners and use a conservative glyph estimate.
                // Long words are reduced only when they exceed this particular control.
                val units = literal.sumOf { char ->
                    when {
                        char.isWhitespace() -> 0.34
                        char in "ıilIj.,:;!|'" -> 0.36
                        char in "MWĞŞÖÜ@#" -> 0.9
                        else -> 0.62
                    }
                }.toFloat()
                val fitted = ((width * .86f) / units).coerceAtLeast(9f)
                if (fitted < size) {
                    element.setAttribute("size", fitted.toInt().toString())
                    changes++
                }
            }
        }
        return changes
    }
}
