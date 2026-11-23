package dev.glorioustr.mtzstudio.themeservice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.glorioustr.mtzstudio.LiveDiagnosticsRecorder
import dev.glorioustr.mtzstudio.library.LibraryTheme
import java.io.File
import java.util.zip.ZipFile

/**
 * Pulls per-application icons out of an MTZ so they can be handed to Themes through
 * `saveCustomizedIcon(String, Bitmap)`.
 *
 * The MTZ `icons` component is a top-level, extensionless **nested ZIP** — the same shape
 * `MtzToBakConverter` already detects by ZIP magic. [IconPackIndex] works out which drawable
 * belongs to which package; this class does the IO and the decoding, and drops everything that
 * is not an installed application so a theme's few hundred unused drawables never turn into a
 * few hundred binder calls.
 */
class MtzIconExtractor(private val context: Context) {

    private val diagnostics get() = LiveDiagnosticsRecorder.get(context.applicationContext)

    data class Extraction(
        val icons: Map<String, Bitmap>,
        /** Packages found in the archive, including those not installed on this device. */
        val discoveredPackages: Set<String>,
        val source: IconPackIndex.Source,
    ) {
        companion object {
            val NONE = Extraction(emptyMap(), emptySet(), IconPackIndex.Source.NONE)
        }
    }

    /** True when the MTZ carries an icons component at all. */
    fun hasIconComponent(theme: LibraryTheme): Boolean = iconEntryName(theme) != null

    /**
     * @param maxIcons hard ceiling on binder calls; archive order decides which survive.
     */
    fun extract(theme: LibraryTheme, maxIcons: Int = MAX_ICONS): Extraction {
        val entryName = iconEntryName(theme) ?: return Extraction.NONE
        val staged = stageIconArchive(theme, entryName) ?: return Extraction.NONE
        return try {
            ZipFile(staged).use { archive ->
                val names = archive.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .toList()
                val iconMapXml = IconPackIndex.iconMapEntry(names)?.let { indexName ->
                    val entry = archive.getEntry(indexName)
                    if (entry == null || entry.size > MAX_INDEX_BYTES) {
                        null
                    } else {
                        runCatching {
                            archive.getInputStream(entry).use { it.readBytes().decodeToString() }
                        }.getOrNull()
                    }
                }
                val index = IconPackIndex.build(names, iconMapXml)
                val installed = index.byPackage.filterKeys(::isInstalled)
                val icons = linkedMapOf<String, Bitmap>()
                for ((packageName, path) in installed) {
                    if (icons.size >= maxIcons) break
                    val entry = archive.getEntry(path) ?: continue
                    if (entry.size > MAX_ICON_BYTES) continue
                    val bitmap = runCatching {
                        archive.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                    }.getOrNull() ?: continue
                    if (bitmap.width !in 1..MAX_ICON_DIMENSION || bitmap.height !in 1..MAX_ICON_DIMENSION) {
                        bitmap.recycle()
                        continue
                    }
                    icons[packageName] = bitmap
                }
                diagnostics.record(
                    "theme_service_icons_extracted",
                    "MTZ ikon bileşeninden uygulanabilir ikonlar çıkarıldı",
                    mapOf(
                        "theme" to theme.displayName,
                        "source" to index.source.name,
                        "archiveEntries" to names.size,
                        "discoveredPackages" to index.byPackage.size,
                        "installedPackages" to installed.size,
                        "icons" to icons.size,
                        "cappedAt" to maxIcons.takeIf { installed.size > it },
                    ),
                )
                Extraction(
                    icons = icons,
                    discoveredPackages = index.byPackage.keys,
                    source = if (icons.isEmpty()) IconPackIndex.Source.NONE else index.source,
                )
            }
        } catch (error: Throwable) {
            diagnostics.record(
                "theme_service_icons_failed",
                "MTZ ikon bileşeni okunamadı",
                mapOf("entry" to entryName),
                error,
            )
            Extraction.NONE
        } finally {
            staged.delete()
        }
    }

    /**
     * The icons component: a top-level entry with no directory and no extension, as produced by
     * Xiaomi's packer. The recognised names match `ComponentRecognizer`'s ICONS category.
     */
    private fun iconEntryName(theme: LibraryTheme): String? = theme.archive.entries
        .asSequence()
        .filterNot { it.directory }
        .map { it.path }
        .firstOrNull { path -> '/' !in path && path.lowercase() in ICON_COMPONENT_NAMES }

    /** ZipFile needs a seekable file, so the nested archive is copied into private cache. */
    private fun stageIconArchive(theme: LibraryTheme, entryName: String): File? = runCatching {
        val target = File.createTempFile("mtz-icons-", ".zip", context.cacheDir)
        val copied = ZipFile(theme.archive.source.toFile()).use { zip ->
            val entry = zip.getEntry(entryName) ?: return@use false
            if (entry.size > MAX_ARCHIVE_BYTES) {
                diagnostics.record(
                    "theme_service_icons_too_large",
                    "İkon bileşeni boyut sınırını aşıyor",
                    mapOf("entry" to entryName, "bytes" to entry.size),
                )
                return@use false
            }
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }
        if (!copied || target.length() == 0L) {
            target.delete()
            null
        } else {
            target
        }
    }.getOrNull()

    private fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private companion object {
        val ICON_COMPONENT_NAMES = setOf("icons", "largeicons")
        const val MAX_ICONS = 120
        const val MAX_ICON_BYTES = 2L * 1024 * 1024
        const val MAX_ICON_DIMENSION = 1024
        const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
        const val MAX_INDEX_BYTES = 4L * 1024 * 1024
    }
}
