package dev.glorioustr.mtzstudio.core

import java.nio.file.Path

@JvmInline
value class ThemeId(val value: String)

enum class ComponentCategory(val label: String) {
    ICONS("Icons"),
    LOCKSCREEN("Lock screen"),
    WALLPAPER("Wallpaper"),
    SYSTEM_UI("Status bar"),
    WIDGET("Widget"),
    CONTACTS("Dialer & Contacts"),
    MMS("SMS & Messages"),
    FRAMEWORK("Framework resources"),
    SYSTEM_UI_PLUGIN("System UI plugin"),
    LAUNCHER("Launcher"),
    AOD("Always-on display"),
    RINGTONE("Ringtone"),
    FONT("Font"),
    OTHER("Other recognized component"),
}

data class MtzSecurityLimits(
    val maxArchiveBytes: Long = 256L * 1024 * 1024,
    val maxEntries: Int = 10_000,
    // Current HyperOS themes can legitimately contain a single, extensionless icons archive
    // larger than 128 MiB. Keep the archive-wide expansion ceiling as the primary zip-bomb
    // guard, while allowing those real Theme Manager packages to be inspected.
    val maxEntryBytes: Long = 256L * 1024 * 1024,
    val maxExpandedBytes: Long = 512L * 1024 * 1024,
    val maxMetadataBytes: Long = 1024L * 1024,
    val maxCompressionRatio: Double = 250.0,
)

data class MtzMetadata(
    val name: String?,
    val author: String?,
    val designer: String?,
    val version: String?,
    val description: String?,
    val fields: Map<String, String>,
)

data class MtzEntry(
    val path: String,
    val compressedBytes: Long,
    val expandedBytes: Long,
    val crc: Long,
    val directory: Boolean,
    val rightsRelated: Boolean,
)

data class ThemeComponent(
    val category: ComponentCategory,
    val rootPath: String,
    val entryPaths: List<String>,
    val expandedBytes: Long,
)

data class MtzArchive(
    val source: Path,
    val sha256: String,
    val metadata: MtzMetadata?,
    val entries: List<MtzEntry>,
    val components: List<ThemeComponent>,
    val rightsEntries: List<String>,
    val expandedBytes: Long,
)

class UnsafeMtzException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        NOT_A_REGULAR_FILE,
        ARCHIVE_TOO_LARGE,
        INVALID_ZIP,
        UNSUPPORTED_ZIP64,
        TOO_MANY_ENTRIES,
        UNSAFE_PATH,
        DUPLICATE_PATH,
        SYMLINK,
        ENTRY_TOO_LARGE,
        TOTAL_SIZE_EXCEEDED,
        SUSPICIOUS_COMPRESSION_RATIO,
        TRUNCATED_ENTRY,
        METADATA_TOO_LARGE,
        UNSAFE_XML,
    }
}
