package dev.glorioustr.mtzstudio.core

/** Category availability is independent of screenshots; never invent missing components. */
object ThemeVisualPolicy {
    fun isFontOnly(archive: MtzArchive): Boolean =
        archive.components.isNotEmpty() && archive.components.all { it.category == ComponentCategory.FONT }

    fun defaultSourceKey(category: ComponentCategory): String = "mtzstudiodefault_${category.name.lowercase()}"

    fun defaultSourceName(archive: MtzArchive, category: ComponentCategory): String? =
        archive.metadata?.fields?.get(defaultSourceKey(category))?.takeIf { it.isNotBlank() }

    /** Only category-specific files qualify; a launcher image is not a status-bar preview. */
    fun categoryPreviewPaths(entries: List<MtzEntry>, category: ComponentCategory): List<String> {
        val names = when (category) {
            ComponentCategory.ICONS -> listOf("icons", "icon")
            ComponentCategory.LOCKSCREEN -> listOf("lockscreen", "lock_style")
            ComponentCategory.SYSTEM_UI -> listOf("statusbar", "status_bar", "notification", "controlcenter", "control_center", "systemui")
            ComponentCategory.WIDGET -> listOf("clock_2x4", "widget")
            ComponentCategory.CONTACTS -> listOf("contact", "contacts", "dialer", "call", "phone")
            ComponentCategory.MMS -> listOf("mms", "sms", "message", "messages")
            ComponentCategory.LAUNCHER -> listOf("launcher", "home")
            ComponentCategory.AOD -> listOf("aod", "miwallpaper")
            ComponentCategory.FONT -> listOf("fonts", "font")
            ComponentCategory.WALLPAPER -> listOf("wallpaper")
            else -> emptyList()
        }
        val patterns = names.map { Regex("(^|[_.-])${Regex.escape(it)}([_.-]|$)", RegexOption.IGNORE_CASE) }
        return imagePaths(entries).filter {
            (it.startsWith("preview/", true) || it.startsWith("previews/", true)) &&
                patterns.any { pattern -> pattern.containsMatchIn(it.substringAfterLast('/')) }
        }.sortedWith(compareBy<String> { path ->
            patterns.indexOfFirst { it.containsMatchIn(path.substringAfterLast('/')) }
        }.thenBy { it.lowercase() })
    }

    fun categoryWithFallback(entries: List<MtzEntry>, category: ComponentCategory): List<String> =
        (categoryPreviewPaths(entries, category) + defaultPreviewPaths(entries)).distinct()

    fun isPreviewOnly(components: List<ThemeComponent>, entries: List<MtzEntry>, category: ComponentCategory): Boolean =
        components.none { it.category == category } && categoryPreviewPaths(entries, category).isNotEmpty()

    fun previewPaths(entries: List<MtzEntry>, preferredNames: List<String>, keywords: List<String>): List<String> {
        val images = imagePaths(entries)
        val byName = images.associateBy { it.lowercase() }
        val preferred = preferredNames.mapNotNull { byName[it.lowercase()] }
        val matching = images.filter { path ->
            (path.startsWith("preview/", true) || path.startsWith("previews/", true)) &&
                keywords.any { path.contains(it, ignoreCase = true) }
        }
        return (preferred + matching + defaultPreviewPaths(entries)).distinct()
    }

    val personalizationCategories = listOf(
        ComponentCategory.ICONS, ComponentCategory.LOCKSCREEN, ComponentCategory.SYSTEM_UI,
        ComponentCategory.WIDGET, ComponentCategory.CONTACTS, ComponentCategory.MMS, ComponentCategory.LAUNCHER,
        ComponentCategory.AOD, ComponentCategory.FONT,
    )

    fun imagePaths(entries: List<MtzEntry>): List<String> = entries.filter {
        !it.directory && it.expandedBytes in 1..16L * 1024 * 1024 &&
            listOf(".jpg", ".jpeg", ".png", ".webp").any { ext -> it.path.endsWith(ext, true) }
    }.map { it.path }

    fun defaultPreviewPaths(entries: List<MtzEntry>): List<String> {
        val images = imagePaths(entries)
        val byName = images.associateBy { it.lowercase() }
        val preferred = listOf(
            "preview/mtz_studio_generated.jpg", "wallpaper/default_wallpaper.jpg",
            "preview/preview_launcher_0.jpg", "preview/preview_lockscreen_0.jpg",
            "wallpaper/default_lock_wallpaper.jpg",
        ).mapNotNull { byName[it] }
        val covers = images.filter {
            it.startsWith("preview/", true) || it.startsWith("previews/", true) ||
                it.startsWith("wallpaper/", true) || it.substringBeforeLast('.', "").lowercase() in
                setOf("preview", "cover", "thumbnail")
        }.sorted()
        return (preferred + covers).distinct()
    }
}
