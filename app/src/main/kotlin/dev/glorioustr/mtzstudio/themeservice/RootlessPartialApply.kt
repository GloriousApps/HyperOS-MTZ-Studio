package dev.glorioustr.mtzstudio.themeservice

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import dev.glorioustr.mtzstudio.LiveDiagnosticsRecorder
import dev.glorioustr.mtzstudio.core.Hashing
import dev.glorioustr.mtzstudio.library.LibraryTheme
import java.util.zip.ZipFile

/**
 * Applies the parts of an MTZ that an unprivileged app can apply on current HyperOS builds.
 *
 * Two different channels, chosen from what device testing showed actually works on
 * HyperOS OS3.0.308.0.WPJMIXM with Xiaomi Themes 3.4.8.36-global:
 *
 *  * **Wallpapers → [WallpaperManager], not Themes.** Themes holds no storage read permission on
 *    a stock device (`READ_MEDIA_IMAGES` denied, `READ_EXTERNAL_STORAGE` appop `ignore`,
 *    `MANAGE_EXTERNAL_STORAGE` rejected), so there is no file path an unprivileged app could hand
 *    to `IThemeService.saveWallpaper(String)` that Themes would be able to open. WallpaperManager
 *    takes a stream, needs only the normal `SET_WALLPAPER` permission, and sets home and lock
 *    directly.
 *  * **Icons → `IThemeService.saveCustomizedIcon(String, Bitmap)`.** This one passes the bitmap
 *    across the binder, so it needs no file access at all — which is exactly why it survives
 *    where the wallpaper call cannot.
 *
 * This is explicitly a partial apply. Fonts, framework/SystemUI overlays, the lockscreen style
 * and the launcher layout are not touched, and the result must never be presented to the user as
 * an applied theme.
 */
class RootlessPartialApply(
    private val context: Context,
    private val client: ThemeServiceClient = ThemeServiceClient(context),
    private val iconExtractor: MtzIconExtractor = MtzIconExtractor(context),
) {
    private val diagnostics get() = LiveDiagnosticsRecorder.get(context.applicationContext)

    enum class WallpaperResult { NOT_REQUESTED, APPLIED, UNAVAILABLE, FAILED }

    data class Result(
        val homeWallpaper: WallpaperResult = WallpaperResult.NOT_REQUESTED,
        val lockWallpaper: WallpaperResult = WallpaperResult.NOT_REQUESTED,
        val iconOutcome: ThemeServiceOutcome = ThemeServiceOutcome(),
        val iconsSent: Int = 0,
        val iconSource: IconPackIndex.Source = IconPackIndex.Source.NONE,
        val failureMessage: String? = null,
    ) {
        val wallpapersApplied: Int
            get() = listOf(homeWallpaper, lockWallpaper).count { it == WallpaperResult.APPLIED }
        val iconsAccepted: Int
            get() = iconOutcome.customizedIcons.values.count { it == ThemeServiceCall.ACCEPTED }
        val anyApplied: Boolean get() = wallpapersApplied > 0 || iconsAccepted > 0
    }

    /** Icons need Themes; wallpapers do not, so the two are reported separately. */
    fun iconChannelAvailable(): Boolean = client.declaration().usable

    fun hasSendableWallpaper(theme: LibraryTheme): Boolean =
        wallpaperEntry(theme, lockScreen = false) != null || wallpaperEntry(theme, lockScreen = true) != null

    /**
     * True when this MTZ carries an icons component. Whether any icon maps to an installed
     * application is only known after extraction.
     */
    fun hasIconComponent(theme: LibraryTheme): Boolean = iconExtractor.hasIconComponent(theme)

    suspend fun applyWallpapers(theme: LibraryTheme): Result =
        sendComponents(theme, includeWallpapers = true, includeIcons = false)

    suspend fun applyIcons(theme: LibraryTheme): Result =
        sendComponents(theme, includeWallpapers = false, includeIcons = true)

    suspend fun applyWallpapersAndIcons(theme: LibraryTheme): Result =
        sendComponents(theme, includeWallpapers = true, includeIcons = true)

    private suspend fun sendComponents(
        theme: LibraryTheme,
        includeWallpapers: Boolean,
        includeIcons: Boolean,
    ): Result {
        check(Hashing.sha256(theme.archive.source) == theme.archive.sha256) {
            "Tema kaynağı doğrulama sonrası değişmiş"
        }
        val home = if (includeWallpapers) setWallpaper(theme, lockScreen = false) else WallpaperResult.NOT_REQUESTED
        val lock = if (includeWallpapers) setWallpaper(theme, lockScreen = true) else WallpaperResult.NOT_REQUESTED
        val extraction = if (includeIcons) iconExtractor.extract(theme) else MtzIconExtractor.Extraction.NONE
        val iconOutcome = if (extraction.icons.isNotEmpty()) {
            client.send(ThemeServiceRequest(customizedIcons = extraction.icons))
        } else {
            ThemeServiceOutcome()
        }
        recycle(extraction.icons)
        val result = Result(
            homeWallpaper = home,
            lockWallpaper = lock,
            iconOutcome = iconOutcome,
            iconsSent = extraction.icons.size,
            iconSource = extraction.source,
            failureMessage = failureFor(theme, includeWallpapers, includeIcons, home, lock, extraction, iconOutcome),
        )
        diagnostics.record(
            if (result.anyApplied) "rootless_partial_apply_done" else "rootless_partial_apply_empty",
            if (result.anyApplied) {
                "MTZ bileşenleri rootsuz uygulandı"
            } else {
                "Rootsuz uygulanabilecek bir bileşen uygulanamadı"
            },
            mapOf(
                "theme" to theme.displayName,
                "home" to home.name,
                "lock" to lock.name,
                "iconsSent" to result.iconsSent,
                "iconsAccepted" to result.iconsAccepted,
                "iconSource" to extraction.source.name,
                "failure" to result.failureMessage,
            ),
        )
        return result
    }

    /**
     * Streams the MTZ's wallpaper entry straight into [WallpaperManager]. Nothing is written to
     * shared storage: the bytes never leave the process except through the wallpaper API.
     */
    private fun setWallpaper(theme: LibraryTheme, lockScreen: Boolean): WallpaperResult {
        val entry = wallpaperEntry(theme, lockScreen) ?: return WallpaperResult.NOT_REQUESTED
        val manager = runCatching { WallpaperManager.getInstance(context) }.getOrNull()
            ?: return WallpaperResult.UNAVAILABLE
        if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) {
            diagnostics.record(
                "rootless_wallpaper_not_allowed",
                "Cihaz duvar kağıdı değiştirmeye izin vermiyor",
                mapOf("lockScreen" to lockScreen),
            )
            return WallpaperResult.UNAVAILABLE
        }
        val which = if (lockScreen) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
        return runCatching {
            ZipFile(theme.archive.source.toFile()).use { zip ->
                val zipEntry = zip.getEntry(entry) ?: return@use WallpaperResult.NOT_REQUESTED
                if (zipEntry.size > MAX_WALLPAPER_BYTES) {
                    diagnostics.record(
                        "rootless_wallpaper_too_large",
                        "Duvar kağıdı bileşeni boyut sınırını aşıyor",
                        mapOf("entry" to entry, "bytes" to zipEntry.size),
                    )
                    return@use WallpaperResult.FAILED
                }
                zip.getInputStream(zipEntry).use { input ->
                    manager.setStream(input, null, true, which)
                }
                WallpaperResult.APPLIED
            }
        }.onFailure { error ->
            diagnostics.record(
                "rootless_wallpaper_failed",
                "Duvar kağıdı uygulanamadı",
                mapOf("entry" to entry, "lockScreen" to lockScreen),
                error,
            )
        }.getOrDefault(WallpaperResult.FAILED)
    }

    /**
     * Says which of the several distinct "nothing happened" cases this was: no such component in
     * the MTZ, icons for applications that are not installed, Themes declining, or an archive
     * whose icon layout could not be read.
     */
    private fun failureFor(
        theme: LibraryTheme,
        includeWallpapers: Boolean,
        includeIcons: Boolean,
        home: WallpaperResult,
        lock: WallpaperResult,
        extraction: MtzIconExtractor.Extraction,
        iconOutcome: ThemeServiceOutcome,
    ): String? {
        val wallpaperApplied = home == WallpaperResult.APPLIED || lock == WallpaperResult.APPLIED
        if (wallpaperApplied || iconOutcome.anyAccepted) return null
        val string = { id: Int -> context.getString(id) }
        return when {
            includeIcons && !includeWallpapers -> when {
                !hasIconComponent(theme) -> string(dev.glorioustr.mtzstudio.R.string.theme_service_no_icons)
                !iconChannelAvailable() -> string(dev.glorioustr.mtzstudio.R.string.theme_service_unavailable)
                extraction.icons.isNotEmpty() ->
                    iconOutcome.failureMessage ?: string(dev.glorioustr.mtzstudio.R.string.theme_service_rejected)
                extraction.discoveredPackages.isNotEmpty() ->
                    string(dev.glorioustr.mtzstudio.R.string.theme_service_icons_not_installed)
                else -> string(dev.glorioustr.mtzstudio.R.string.theme_service_icons_unreadable)
            }
            includeWallpapers && !includeIcons -> when {
                !hasSendableWallpaper(theme) -> string(dev.glorioustr.mtzstudio.R.string.theme_service_no_wallpaper)
                else -> string(dev.glorioustr.mtzstudio.R.string.rootless_wallpaper_failed)
            }
            else -> string(dev.glorioustr.mtzstudio.R.string.theme_service_nothing_sendable)
        }
    }

    private fun recycle(icons: Map<String, Bitmap>) {
        icons.values.forEach { bitmap -> runCatching { if (!bitmap.isRecycled) bitmap.recycle() } }
    }

    /** Tolerant of the extension: current MTZ archives ship .jpg, but .png and .webp exist. */
    private fun wallpaperEntry(theme: LibraryTheme, lockScreen: Boolean): String? {
        val prefix = if (lockScreen) LOCK_WALLPAPER_PREFIX else HOME_WALLPAPER_PREFIX
        return theme.archive.entries
            .asSequence()
            .filterNot { it.directory }
            .map { it.path }
            .firstOrNull { path ->
                path.substringBeforeLast('.', path).equals(prefix, ignoreCase = true)
            }
    }

    private companion object {
        const val HOME_WALLPAPER_PREFIX = "wallpaper/default_wallpaper"
        const val LOCK_WALLPAPER_PREFIX = "wallpaper/default_lock_wallpaper"
        const val MAX_WALLPAPER_BYTES = 64L * 1024 * 1024
    }
}
