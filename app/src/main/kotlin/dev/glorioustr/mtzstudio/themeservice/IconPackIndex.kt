package dev.glorioustr.mtzstudio.themeservice

/**
 * Works out which drawable inside an MTZ icons archive belongs to which application package.
 *
 * The archive's internal layout is written by theme designers and consumed by the framework's
 * `miui.content.res.IconCustomizer`, so the Themes APK does not describe it and there is no one
 * authoritative shape. Both layouts seen in the wild are therefore handled, and the result is a
 * plain package → entry-path map. Kept free of Android types so it can be tested directly.
 */
object IconPackIndex {

    enum class Source { NONE, ICON_MAP, PACKAGE_NAMED_DRAWABLES }

    data class Index(val byPackage: Map<String, String>, val source: Source) {
        val isEmpty: Boolean get() = byPackage.isEmpty()
    }

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    private val ICON_MAP_ITEM = Regex(
        "<item[^>]*\\bname\\s*=\\s*\"([^\"]+)\"[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</item>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val DRAWABLE_EXTENSIONS = listOf(".png", ".webp")

    /** The entry inside the icons archive that indexes drawables by component, if it ships one. */
    fun iconMapEntry(entryNames: List<String>): String? = entryNames.firstOrNull { name ->
        when (name.substringAfterLast('/').lowercase()) {
            "icon_map", "icon_map.xml", "iconmap", "iconmap.xml" -> true
            else -> false
        }
    }

    /**
     * @param iconMapXml contents of [iconMapEntry] when present, otherwise null.
     */
    fun build(entryNames: List<String>, iconMapXml: String?): Index {
        if (iconMapXml != null) {
            val mapped = fromIconMap(entryNames, iconMapXml)
            if (mapped.isNotEmpty()) return Index(mapped, Source.ICON_MAP)
        }
        val named = fromPackageNamedDrawables(entryNames)
        return if (named.isEmpty()) Index(emptyMap(), Source.NONE) else Index(named, Source.PACKAGE_NAMED_DRAWABLES)
    }

    /**
     * `<item name="com.pkg/com.pkg.Activity">drawable_name</item>`. Activity-level entries
     * collapse to their package because `saveCustomizedIcon` takes a package, and the first
     * entry for a package wins so a theme's launcher alias cannot overwrite its main icon.
     */
    private fun fromIconMap(entryNames: List<String>, xml: String): Map<String, String> {
        val drawables = drawablesByName(entryNames)
        val result = linkedMapOf<String, String>()
        ICON_MAP_ITEM.findAll(xml).forEach { match ->
            val component = match.groupValues[1].trim()
            val drawable = match.groupValues[2].trim()
            if (drawable.isEmpty()) return@forEach
            val packageName = component.substringBefore('/').trim()
            if (!isPackageLike(packageName)) return@forEach
            val path = drawables[drawable] ?: return@forEach
            result.putIfAbsent(packageName, path)
        }
        return result
    }

    /** Fallback layout: the drawable file itself is named after the package it replaces. */
    private fun fromPackageNamedDrawables(entryNames: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        entryNames.forEach { path ->
            val stem = drawableStem(path) ?: return@forEach
            if (isPackageLike(stem)) result.putIfAbsent(stem, path)
        }
        return result
    }

    private fun drawablesByName(entryNames: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        entryNames.forEach { path ->
            val stem = drawableStem(path) ?: return@forEach
            result.putIfAbsent(stem, path)
        }
        return result
    }

    private fun drawableStem(path: String): String? {
        val leaf = path.substringAfterLast('/')
        val extension = DRAWABLE_EXTENSIONS.firstOrNull { leaf.endsWith(it, ignoreCase = true) } ?: return null
        return leaf.dropLast(extension.length).takeIf { it.isNotBlank() }
    }

    fun isPackageLike(value: String): Boolean = PACKAGE_NAME.matches(value)
}
