package dev.glorioustr.mtzstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Process
import dev.glorioustr.mtzstudio.composer.CompositionMetadata
import dev.glorioustr.mtzstudio.composer.FontExportRequest
import dev.glorioustr.mtzstudio.composer.MtzComposer
import dev.glorioustr.mtzstudio.core.MtzParser
import dev.glorioustr.mtzstudio.core.ThemeVisualPolicy
import dev.glorioustr.mtzstudio.library.LibraryTheme
import dev.glorioustr.mtzstudio.library.ThemeLibrary
import dev.glorioustr.mtzstudio.tester.PrivilegedCommandRunner
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class DeviceThemeSummary(
    val localId: String,
    val title: String,
    val author: String?,
    val version: String?,
    val previewPath: Path?,
    val isAlreadyImported: Boolean,
)

internal data class DeviceThemeImportResult(
    val theme: LibraryTheme,
    val addedToLibrary: Boolean,
)

internal data class DeviceThemeBulkImportResult(
    val found: Int,
    val added: Int,
    val duplicates: Int,
    val failed: Int,
    val errors: List<String>,
    val completed: Boolean = true,
)

internal data class DeviceCatalogProgress(
    val processed: Int = 0,
    val total: Int = 0,
    val themes: List<LibraryTheme> = emptyList(),
)

internal class DeviceThemeImporter(
    context: Context,
    private val library: ThemeLibrary,
    private val composer: MtzComposer,
    private val commandRunner: PrivilegedCommandRunner,
    private val parser: MtzParser = MtzParser(),
) {
    private val appContext = context.applicationContext
    private val stagingRoot = appContext.filesDir.toPath().resolve("device-import")
    private val importOrigins = appContext.getSharedPreferences(ORIGIN_PREFERENCES, Context.MODE_PRIVATE)
    private val diagnostics = LiveDiagnosticsRecorder.get(appContext)
    private val mutableCatalogProgress = kotlinx.coroutines.flow.MutableStateFlow(DeviceCatalogProgress())
    val catalogProgress: kotlinx.coroutines.flow.StateFlow<DeviceCatalogProgress> = mutableCatalogProgress

    /** Scans Theme Manager and returns list of available themes with metadata. */
    @Synchronized
    fun listAvailableDeviceThemes(): List<DeviceThemeSummary> {
        val records = readRecords()
        val previewDirectory = stagingRoot.resolve("scan-previews")
        Files.createDirectories(previewDirectory)
        copyThemePreviewFiles(records, previewDirectory)
        val knownThemes = library.load().themes
        return records.map { record ->
            val isAlreadyImported = findExisting(record, knownThemes) != null
            DeviceThemeSummary(
                localId = record.localId,
                title = record.title,
                author = record.author,
                version = record.version,
                previewPath = previewDirectory.resolve("${record.localId}.preview")
                    .takeIf { Files.isRegularFile(it) && Files.size(it) > 0L },
                isAlreadyImported = isAlreadyImported,
            )
        }
    }

    /** Reads metadata only; no MTZ reconstruction or Theme Manager component copy is performed. */
    @Synchronized
    fun availableThemeCount(): Int = readRecords().size

    /** Captures Xiaomi Themes' current local IDs before its native importer is opened. */
    @Synchronized
    fun localThemeIds(): Set<String> = readRecords().mapTo(linkedSetOf(), ThemeManagerRecord::localId)

    /** Resolves the record created by Xiaomi's own importer without touching its private database. */
    @Synchronized
    fun resolveImportedLocalId(theme: LibraryTheme, previousIds: Set<String>): String? {
        val records = readRecords()
        val newlyCreated = records.filter { it.localId !in previousIds }
        val metadata = theme.archive.metadata
        fun List<ThemeManagerRecord>.matchingRecord(): ThemeManagerRecord? {
            val title = metadata?.name?.normalized().orEmpty()
            val version = metadata?.version?.normalized().orEmpty()
            val author = metadata?.author?.normalized().orEmpty()
            return firstOrNull { record ->
                (title.isBlank() || record.title.normalized() == title) &&
                    (version.isBlank() || record.version.normalized() == version) &&
                    (author.isBlank() || record.author.normalized() == author)
            }
        }
        return newlyCreated.matchingRecord()?.localId
            ?: newlyCreated.singleOrNull()?.localId
    }

    /** Finds an already imported Xiaomi theme when an older Studio build has no saved mapping. */
    @Synchronized
    fun resolveExistingLocalId(theme: LibraryTheme): String? {
        val metadata = theme.archive.metadata ?: return null
        val title = metadata.name.normalized()
        if (title.isBlank()) return null
        val version = metadata.version.normalized()
        val author = metadata.author.normalized()
        return readRecords().filter { record ->
            record.title.normalized() == title &&
                (version.isBlank() || record.version.normalized() == version) &&
                (author.isBlank() || record.author.normalized() == author)
        }.singleOrNull()?.localId
    }

    /** Reconstructs selected Theme Manager items by localId. */
    @Synchronized
    fun importSelectedThemes(selectedLocalIds: Set<String>): DeviceThemeBulkImportResult {
        val records = readRecords().filter { selectedLocalIds.isEmpty() || it.localId in selectedLocalIds }
        if (records.isEmpty()) return DeviceThemeBulkImportResult(0, 0, 0, 0, emptyList())
        records.forEach { importOrigins.edit().remove(hiddenKey(it.localId)).apply() }
        return importRecords(records)
    }

    private fun readRecords(): List<ThemeManagerRecord> {
        resetDirectory(stagingRoot)
        val metadataDirectory = stagingRoot.resolve("metadata")
        Files.createDirectories(metadataDirectory)
        copyThemeMetadata(metadataDirectory)

        val records = mutableListOf<ThemeManagerRecord>()
        val skipped = mutableListOf<String>()
        Files.newDirectoryStream(metadataDirectory, "*.mrm").use { paths ->
            paths.forEach { path ->
                if (!Files.isRegularFile(path)) return@forEach
                runCatching { parseThemeRecord(path) }
                    .onSuccess(records::add)
                    .onFailure { error ->
                        skipped += "${path.fileName}: ${error.message ?: error::class.simpleName}"
                    }
            }
        }
        if (skipped.isNotEmpty()) {
            diagnostics.record(
                event = "catalog_records_skipped",
                message = "Geçersiz veya boş Tema Yöneticisi kayıtları atlandı",
                details = mapOf("count" to skipped.size, "records" to skipped.joinToString(" | ").take(5_000)),
            )
        }
        return records.sortedWith(compareBy(ThemeManagerRecord::title, ThemeManagerRecord::localId))
    }

    private fun importRecords(
        records: List<ThemeManagerRecord>,
        shouldPause: () -> Boolean = { false },
        incremental: Boolean = false,
        maxNewImports: Int = Int.MAX_VALUE,
    ): DeviceThemeBulkImportResult {
        val knownThemes = library.load().themes.toMutableList()
        val startedAt = System.nanoTime()
        var processed = 0
        var attemptedNewImports = 0
        fun publishProgress(forceDiagnostic: Boolean = false) {
            if (!incremental) return
            mutableCatalogProgress.value = DeviceCatalogProgress(processed, records.size, knownThemes.toList())
            if (forceDiagnostic || processed == 0 || processed == records.size || processed % CATALOG_PROGRESS_LOG_INTERVAL == 0) {
                diagnostics.record("catalog_item_progress", "Tema kitaplığı aşamalı güncelleniyor",
                    mapOf("processed" to processed, "total" to records.size))
            }
        }
        publishProgress()
        var added = 0
        var duplicates = 0
        val errors = mutableListOf<String>()
        for ((index, record) in records.withIndex()) {
            if (shouldPause() || (incremental && System.nanoTime() - startedAt >= AUTO_CATALOG_TIME_BUDGET_NANOS) ||
                (incremental && attemptedNewImports >= maxNewImports)) {
                diagnostics.record("catalog_scan_paused", "Kitaplık taraması sınırlandırıldı; mevcut kaynaklar korundu",
                    mapOf("processed" to processed, "total" to records.size, "added" to added, "attempted" to attemptedNewImports,
                        "maxNewImports" to maxNewImports))
                return DeviceThemeBulkImportResult(records.size, added, duplicates, errors.size, errors, completed = false)
            }
            val existing = findExisting(record, knownThemes)
            if (existing != null) {
                // A Theme Manager backup may contain a lockstyle-only resource. It still belongs
                // in the Themes gallery after a BAK restore. Font-only resources are the exception:
                // they belong exclusively in Fonts and must not appear as complete themes.
                val visibleExisting = library.setThemeGalleryVisibility(
                    existing,
                    !ThemeVisualPolicy.isFontOnly(existing.archive),
                )
                val existingIndex = knownThemes.indexOfFirst { it.id == existing.id }
                if (existingIndex >= 0) knownThemes[existingIndex] = visibleExisting
                rememberOrigin(record, visibleExisting)
                duplicates++
                processed++
                publishProgress()
                continue
            }

            attemptedNewImports++
            val itemDirectory = stagingRoot.resolve("theme-$index")
            val output = library.newExportPath(record.title)
            try {
                Files.createDirectories(itemDirectory)
                copyThemeFiles(record, itemDirectory, if (incremental) 30 else 300)
                buildMtz(record, itemDirectory, output)
                val verified = parser.parse(output)
                if (verified.components.isEmpty()) error("No recognizable MTZ component was produced")
                val imported = Files.newInputStream(output).use {
                    library.importTheme(
                        it,
                        record.title,
                        includeInThemeGallery = !ThemeVisualPolicy.isFontOnly(verified),
                    )
                }
                knownThemes += imported
                rememberOrigin(record, imported)
                added++
            } catch (error: Exception) {
                if (error is InterruptedException || error is java.util.concurrent.CancellationException) throw error
                errors += "${record.title}: ${error.message ?: error::class.simpleName}"
            } finally {
                Files.deleteIfExists(output)
                itemDirectory.toFile().deleteRecursively()
            }
            processed++
            publishProgress()
        }
        stagingRoot.toFile().deleteRecursively()
        return DeviceThemeBulkImportResult(records.size, added, duplicates, errors.size, errors)
    }

    /** Reconstructs every local Theme Manager item from its .mrm metadata and .mrc components. */
    fun importAllThemes(): DeviceThemeBulkImportResult {
        return importSelectedThemes(emptySet())
    }

    /** Keeps the private editor cache aligned with Theme Manager, which remains the source of truth. */
    @Synchronized
    fun synchronizeModernLibrary(shouldPause: () -> Boolean = { false }): DeviceThemeBulkImportResult {
        // One metadata snapshot, not two scans that may disagree during a native operation.
        val records = readRecords().filterNot { importOrigins.getBoolean(hiddenKey(it.localId), false) }
        val availableIds = records.mapTo(mutableSetOf(), ThemeManagerRecord::localId)
        val result = importRecords(
            records,
            shouldPause,
            incremental = true,
            maxNewImports = AUTO_CATALOG_NEW_IMPORT_LIMIT,
        )
        if (result.failed > 0 || !result.completed) return result
        val staleOrigins = importOrigins.all.keys.filter { key ->
            key.startsWith(ORIGIN_PREFIX) && key.removePrefix(ORIGIN_PREFIX) !in availableIds
        }
        staleOrigins.forEach { key ->
            // A catalog refresh must never delete the user's private MTZ source.
            // Detach stale entries; explicit native deletion handles its own mirror cleanup.
            importOrigins.edit().remove(key).apply()
        }
        return result
    }

    fun localIdFor(theme: LibraryTheme): String? {
        return localIdsFor(theme).firstOrNull()
    }

    fun localIdsFor(theme: LibraryTheme): Set<String> {
        if (themeManagerOriginNeedsRefresh(theme)) return emptySet()
        return linkedLocalIdsFor(theme)
    }

    /**
     * Returns saved Theme Manager identities even while an edited archive needs a one-time
     * refresh.  The refresh path needs these old identities as a snapshot; hiding them made
     * every subsequent Root apply look like a brand-new import.
     */
    fun linkedLocalIdsFor(theme: LibraryTheme): Set<String> {
        return importOrigins.all.entries.mapNotNullTo(linkedSetOf()) { (key, value) ->
            if (!key.startsWith(ORIGIN_PREFIX)) return@mapNotNullTo null
            val mappedThemeId = value?.toString()?.substringAfter('|', "").orEmpty()
            key.removePrefix(ORIGIN_PREFIX).takeIf { mappedThemeId == theme.id.value }
        }
    }

    fun themeManagerOriginNeedsRefresh(theme: LibraryTheme): Boolean {
        if (importOrigins.getBoolean(refreshKey(theme.id.value), false)) return true
        // Migrate themes translated by an older Studio build: their translation receipt points
        // at the current private archive, while the saved Xiaomi link still contains the hash
        // from before translation. Non-translated device imports keep their legacy mapping.
        val translationReceipt = appContext.filesDir.toPath()
            .resolve("mtz-library")
            .resolve(theme.id.value)
            .resolve("translation")
            .resolve("last-output.sha256")
        if (!Files.isRegularFile(translationReceipt)) return false
        val translatedHash = runCatching {
            String(Files.readAllBytes(translationReceipt), Charsets.UTF_8).trim()
        }.getOrDefault("")
        if (translatedHash != theme.archive.sha256) return false
        val links = importOrigins.all.filter { (key, value) ->
            key.startsWith(ORIGIN_PREFIX) && value?.toString()?.substringAfter('|', "") == theme.id.value
        }.values.map { it.toString().substringBefore('|') }
        return links.isNotEmpty() && theme.archive.sha256 !in links
    }

    fun rememberThemeManagerOrigin(localId: String, theme: LibraryTheme) {
        val editor = importOrigins.edit()
        importOrigins.all.forEach { (key, value) ->
            if (key.startsWith(ORIGIN_PREFIX) &&
                key.removePrefix(ORIGIN_PREFIX) != localId &&
                value?.toString()?.substringAfter('|', "") == theme.id.value
            ) {
                editor.remove(key)
            }
        }
        editor.apply()
        linkThemeManagerOrigin(appContext, localId, theme.id.value, theme.archive.sha256)
    }

    /**
     * Removes only the exact, previously linked Xiaomi Themes records after a Studio archive was
     * replaced.  The native importer has no in-place archive update API: leaving the old record
     * visible makes every text/OCR pass look like another theme.  We therefore remove its metadata,
     * preview and component files only after the new record has been observed successfully.
     */
    @Synchronized
    fun removeReplacedThemeManagerRecords(localIds: Set<String>): Int {
        val safeIds = localIds.filterTo(linkedSetOf()) { it.matches(SAFE_IDENTIFIER) }
        if (safeIds.isEmpty()) return 0
        val records = readRecords().filter { it.localId in safeIds }
        if (records.isEmpty()) return 0
        val command = buildString {
            records.forEach { record ->
                append("rm -f ").append(shellQuote("$THEME_DATA_ROOT/meta/theme/${record.localId}.mrm")).append('\n')
                append("rm -rf ").append(shellQuote("$THEME_DATA_ROOT/preview/theme/${record.localId}")).append('\n')
                record.resources.forEach { resource ->
                    append("rm -f ").append(
                        shellQuote("$THEME_DATA_ROOT/content/${resource.resourceCode}/${resource.localId}.mrc"),
                    ).append('\n')
                }
            }
            append("sync")
        }
        val result = commandRunner.run(command, 120)
        check(result.exitCode == 0) { "Eski Xiaomi Temalar kaydı kaldırılamadı: ${result.output.takeLast(500)}" }
        val editor = importOrigins.edit()
        records.forEach { editor.remove(originKey(it.localId)).remove(hiddenKey(it.localId)) }
        editor.apply()
        diagnostics.record(
            "theme_manager_replaced_records_removed",
            "Çeviri öncesi Xiaomi Temalar kayıtları kaldırıldı",
            mapOf("localIds" to records.joinToString { it.localId }),
        )
        return records.size
    }

    /** Keeps a deliberately removed Studio mirror from being recreated by automatic catalog sync. */
    fun hideThemeManagerOriginFor(theme: LibraryTheme) {
        val localId = localIdFor(theme) ?: return
        importOrigins.edit()
            .remove(originKey(localId))
            .putBoolean(hiddenKey(localId), true)
            .apply()
    }

    fun forgetThemeManagerOrigin(localId: String) {
        if (localId.matches(SAFE_IDENTIFIER)) importOrigins.edit().remove(originKey(localId)).apply()
    }

    fun importActiveFont(): DeviceThemeImportResult {
        Files.createDirectories(stagingRoot)
        val font = stagingRoot.resolve("active-font.ttf")
        val preview = stagingRoot.resolve("active-font-preview.png")
        Files.deleteIfExists(font)
        Files.deleteIfExists(preview)
        try {
            copyFirstAvailable(
                candidates = listOf(
                    "/data/system/theme/fonts/Roboto-Regular.ttf",
                    "/data/system/theme/fonts/Miui-Regular.ttf",
                    "/data/system/theme/fonts/MiuiEx-Regular.ttf",
                ),
                target = font,
                missingMessage = "No active Theme Manager font file was found",
            )
            val displayName = appContext.getString(R.string.device_active_font_name)
            createFontPreview(font, preview, displayName)
            val output = library.newExportPath(displayName)
            val composition = composer.composeFont(
                FontExportRequest(
                    metadata = CompositionMetadata(
                        name = displayName,
                        description = "Exported locally from the active HyperOS Theme Manager font",
                    ),
                    fontFile = font,
                    previewFile = preview,
                ),
                output,
            )
            val existing = library.load().themes.firstOrNull {
                it.archive.sha256 == composition.outputSha256
            }
            if (existing != null) {
                Files.deleteIfExists(output)
                return DeviceThemeImportResult(existing, false)
            }
            val imported = Files.newInputStream(output).use { library.importTheme(it, null) }
            Files.deleteIfExists(output)
            return DeviceThemeImportResult(imported, true)
        } finally {
            Files.deleteIfExists(font)
            Files.deleteIfExists(preview)
        }
    }

    private fun createFontPreview(font: Path, target: Path, displayName: String) {
        val typeface = Typeface.createFromFile(font.toFile())
        val bitmap = Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(24, 22, 30))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textAlign = Paint.Align.CENTER
            }
            paint.color = Color.rgb(205, 178, 255)
            paint.textSize = 280f
            canvas.drawText("Aa", 360f, 390f, paint)
            paint.color = Color.WHITE
            paint.textSize = 62f
            canvas.drawText(displayName, 360f, 555f, paint)
            paint.color = Color.rgb(205, 198, 214)
            paint.textSize = 42f
            canvas.drawText("HyperOS MTZ Studio", 360f, 660f, paint)
            canvas.drawText("ABCÇĞİÖŞÜ 1234567890", 360f, 755f, paint)
            Files.newOutputStream(target).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    error("Font preview could not be generated")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun copyThemeMetadata(target: Path) {
        val command = dev.glorioustr.mtzstudio.tester.ThemeCatalogCommands.copyMetadata(
            "$THEME_DATA_ROOT/meta/theme", target.toAbsolutePath().toString(), Process.myUid(),
        )
        val result = commandRunner.run(command, 120)
        LiveDiagnosticsRecorder.get(appContext).record(
            "catalog_metadata_snapshot", "Tema kayıtları okundu",
            mapOf("exitCode" to result.exitCode, "output" to result.output),
        )
        check(result.exitCode == 0) {
            appContext.getString(if (result.exitCode == 4) R.string.catalog_location_unavailable else R.string.catalog_read_failed)
        }
        check(Regex("MTZ_METADATA_COUNT=[0-9]+").containsMatchIn(result.output)) {
            appContext.getString(R.string.catalog_read_failed)
        }
    }

    private fun copyThemeFiles(record: ThemeManagerRecord, target: Path, timeoutSeconds: Long = 300) {
        val parts = target.resolve("parts")
        val previews = target.resolve("preview")
        Files.createDirectories(parts)
        Files.createDirectories(previews)
        val command = buildString {
            record.resources.forEachIndexed { index, resource ->
                val source = "$THEME_DATA_ROOT/content/${resource.resourceCode}/${resource.localId}.mrc"
                val destination = parts.resolve("$index.mrc").toAbsolutePath().toString()
                append("cp ").append(shellQuote(source)).append(' ').append(shellQuote(destination))
                    .append(" || exit ").append(20 + index).append('\n')
            }
            append("preview_count=0\n")
            record.previewNames.forEach { name ->
                val source = "$THEME_DATA_ROOT/preview/theme/${record.localId}/$name"
                val destination = previews.resolve(name).toAbsolutePath().toString()
                append("if [ \"${'$'}preview_count\" -lt $MAX_PREVIEWS_PER_THEME ] && [ -f ")
                    .append(shellQuote(source)).append(" ]; then cp ")
                    .append(shellQuote(source)).append(' ').append(shellQuote(destination))
                    .append(" && preview_count=${'$'}((preview_count + 1)); fi\n")
            }
            append("chown -R ").append(Process.myUid()).append(':').append(Process.myUid()).append(' ')
                .append(shellQuote(target.toAbsolutePath().toString())).append(" || exit 90\n")
            append("chmod -R u+rwX,go-rwx ").append(shellQuote(target.toAbsolutePath().toString()))
                .append(" || exit 91")
        }
        runPrivileged(command, timeoutSeconds, "Theme components could not be copied")
        record.resources.indices.forEach { index ->
            val file = parts.resolve("$index.mrc")
            if (!Files.isRegularFile(file) || Files.size(file) <= 0L) {
                error("Theme component ${record.resources[index].resourceCode} is missing")
            }
        }
    }

    private fun copyThemePreviewFiles(records: List<ThemeManagerRecord>, target: Path) {
        if (records.isEmpty()) return
        val command = buildString {
            records.forEach { record ->
                val destination = target.resolve("${record.localId}.preview").toAbsolutePath().toString()
                append("for candidate in ")
                record.previewNames.forEach { name ->
                    append(shellQuote("$THEME_DATA_ROOT/preview/theme/${record.localId}/$name")).append(' ')
                }
                append("; do if [ -f \"${'$'}candidate\" ]; then cp \"${'$'}candidate\" ")
                    .append(shellQuote(destination)).append("; break; fi; done\n")
            }
            append("chown -R ").append(Process.myUid()).append(':').append(Process.myUid()).append(' ')
                .append(shellQuote(target.toAbsolutePath().toString())).append(" || exit 90\n")
            append("chmod -R u+rwX,go-rwx ").append(shellQuote(target.toAbsolutePath().toString()))
                .append(" || exit 91")
        }
        runPrivileged(command, 120, "Theme previews could not be copied")
    }

    private fun buildMtz(record: ThemeManagerRecord, sourceDirectory: Path, output: Path) {
        val temporary = output.resolveSibling(".${output.fileName}.tmp")
        Files.deleteIfExists(temporary)
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                // MRC payloads are already compressed packages; level 0 keeps bulk import responsive.
                zip.setLevel(0)
                zip.putNextEntry(deterministicEntry("description.xml"))
                zip.write(descriptionXml(record).encodeToByteArray())
                zip.closeEntry()

                val written = hashSetOf("description.xml")
                record.resources.forEachIndexed { index, resource ->
                    val archivePath = archivePath(resource)
                    if (!written.add(archivePath.lowercase(Locale.ROOT))) {
                        error("Duplicate reconstructed MTZ path: $archivePath")
                    }
                    zip.putNextEntry(deterministicEntry(archivePath))
                    Files.newInputStream(sourceDirectory.resolve("parts/$index.mrc")).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                Files.list(sourceDirectory.resolve("preview")).use { previews ->
                    previews.filter(Files::isRegularFile).sorted().forEach { preview ->
                        val archivePath = "preview/${preview.fileName}"
                        if (written.add(archivePath.lowercase(Locale.ROOT))) {
                            zip.putNextEntry(deterministicEntry(archivePath))
                            Files.newInputStream(preview).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            parser.parse(temporary)
            Files.move(temporary, output)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun parseThemeRecord(path: Path): ThemeManagerRecord {
        val jsonText = Files.newBufferedReader(path).use { it.readText() }
        val json = JSONObject(jsonText)
        val localId = json.getString("localId").requireSafeIdentifier("theme local ID")
        val resourcesJson = json.optJSONArray("subResources") ?: JSONArray()
        val resources = buildList {
            for (index in 0 until resourcesJson.length()) {
                val item = resourcesJson.getJSONObject(index)
                add(
                    ThemeManagerResource(
                        localId = item.getString("localId").requireSafeIdentifier("component local ID"),
                        resourceCode = item.getString("resourceCode").requireSafeResourceCode(),
                    ),
                )
            }
        }
        if (resources.isEmpty()) error("Theme record $localId has no components")
        val previewNames = buildList {
            add("preview_lockscreen_0.jpg")
            add("preview_launcher_0.jpg")
            add("preview_icons_0.jpg")
            add("preview_miwallpaper_0.jpg")
            add("preview_statusbar_0.jpg")
            localizedArray(json.optJSONObject("builtInPreviews")).forEach { name ->
                if (name.matches(SAFE_PREVIEW_NAME)) add(name)
            }
        }.distinct().take(MAX_PREVIEW_CANDIDATES)
        return ThemeManagerRecord(
            localId = localId,
            hash = json.optString("hash").take(128),
            platform = json.optInt("platform", 17).coerceIn(1, 99),
            version = json.optString("version", "1.0").take(80),
            title = localizedString(json.optJSONObject("titles"))?.take(180) ?: "Theme $localId",
            author = localizedString(json.optJSONObject("authors"))?.take(180),
            designer = localizedString(json.optJSONObject("designers"))?.take(180),
            description = localizedString(json.optJSONObject("descriptions"))?.take(4_096),
            resources = resources,
            previewNames = previewNames,
        )
    }

    private fun findExisting(record: ThemeManagerRecord, themes: List<LibraryTheme>): LibraryTheme? {
        val stored = importOrigins.getString(originKey(record.localId), null)
        if (stored != null) {
            val pieces = stored.split('|', limit = 2)
            val storedHash = pieces.firstOrNull().orEmpty()
            val themeId = pieces.getOrNull(1).orEmpty()
            themes.firstOrNull { it.id.value == themeId && storedHash == record.hash }?.let { return it }
        }
        return themes.firstOrNull { theme ->
            val metadata = theme.archive.metadata
            metadata?.name.normalized() == record.title.normalized() &&
                metadata?.version.normalized() == record.version.normalized() &&
                metadata?.author.normalized() == record.author.normalized()
        }
    }

    private fun rememberOrigin(record: ThemeManagerRecord, theme: LibraryTheme) {
        importOrigins.edit().putString(originKey(record.localId), "${record.hash}|${theme.id.value}").apply()
    }

    private fun archivePath(resource: ThemeManagerResource): String = when (resource.resourceCode) {
        "contact" -> "com.android.contacts"
        "mms" -> "com.android.mms"
        "launcher" -> "com.miui.home"
        "statusbar" -> "com.android.systemui"
        "lockstyle" -> "lockscreen"
        "wallpaper" -> "wallpaper/default_wallpaper.jpg"
        "lockscreen" -> "wallpaper/default_lock_wallpaper.jpg"
        "fonts" -> "fonts/Roboto-Regular.ttf"
        "framework" -> "framework-res"
        "bootanimation" -> "boots/bootanimation.zip"
        else -> resource.resourceCode
    }

    private fun descriptionXml(record: ThemeManagerRecord): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<theme>\n")
        append("  <version>").append(xml(record.version)).append("</version>\n")
        append("  <uiVersion>").append(record.platform).append("</uiVersion>\n")
        record.author?.let { append("  <author>").append(xml(it)).append("</author>\n") }
        record.designer?.let { append("  <designer>").append(xml(it)).append("</designer>\n") }
        append("  <title>").append(xml(record.title)).append("</title>\n")
        record.description?.let { append("  <description>").append(xml(it)).append("</description>\n") }
        append("</theme>\n")
    }

    private fun localizedString(values: JSONObject?): String? {
        if (values == null) return null
        val locale = Locale.getDefault()
        val candidates = listOf(
            "${locale.language}_${locale.country}",
            locale.toLanguageTag().replace('-', '_'),
            locale.language,
            "en_US",
            "fallback",
            "zh_CN",
        )
        candidates.forEach { key -> values.optString(key).takeIf(String::isNotBlank)?.let { return it } }
        val keys = values.keys()
        while (keys.hasNext()) values.optString(keys.next()).takeIf(String::isNotBlank)?.let { return it }
        return null
    }

    private fun localizedArray(values: JSONObject?): List<String> {
        if (values == null) return emptyList()
        val array = listOf("fallback", "en_US", "zh_CN").firstNotNullOfOrNull(values::optJSONArray)
            ?: values.keys().asSequence().mapNotNull(values::optJSONArray).firstOrNull()
            ?: return emptyList()
        return buildList { for (index in 0 until array.length()) add(array.optString(index)) }
    }

    private fun copyFirstAvailable(candidates: List<String>, target: Path, missingMessage: String) {
        val sourceTests = candidates.joinToString(" ") { shellQuote(it) }
        val targetPath = shellQuote(target.toAbsolutePath().toString())
        val uid = Process.myUid()
        val command = """
            source_path=''
            for candidate in $sourceTests; do
              if [ -f "${'$'}candidate" ]; then source_path="${'$'}candidate"; break; fi
            done
            if [ -z "${'$'}source_path" ]; then echo ${shellQuote(missingMessage)}; exit 4; fi
            cp "${'$'}source_path" $targetPath || exit 5
            chown $uid:$uid $targetPath || exit 6
            chmod 600 $targetPath || exit 7
        """.trimIndent()
        runPrivileged(command, 120, missingMessage)
        if (!Files.isRegularFile(target) || Files.size(target) <= 0L) {
            error("The privileged copy completed without a readable file")
        }
    }

    private fun runPrivileged(command: String, timeoutSeconds: Long, fallbackMessage: String) {
        val result = commandRunner.run(command, timeoutSeconds)
        if (result.exitCode != 0) error(result.output.ifBlank { fallbackMessage })
    }

    private fun resetDirectory(path: Path) {
        path.toFile().deleteRecursively()
        Files.createDirectories(path)
    }

    private fun String.requireSafeIdentifier(label: String): String =
        takeIf { it.matches(SAFE_IDENTIFIER) } ?: error("Invalid $label")

    private fun String.requireSafeResourceCode(): String =
        takeIf { it.matches(SAFE_RESOURCE_CODE) } ?: error("Invalid Theme Manager resource code")

    private fun String?.normalized(): String = orEmpty().trim().lowercase(Locale.ROOT)

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun deterministicEntry(path: String) = ZipEntry(path).apply { time = 0L }
    private fun originKey(localId: String) = "$ORIGIN_PREFIX$localId"
    private fun hiddenKey(localId: String) = "$HIDDEN_PREFIX$localId"
    private fun refreshKey(themeId: String) = "$REFRESH_PREFIX$themeId"
    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private data class ThemeManagerRecord(
        val localId: String,
        val hash: String,
        val platform: Int,
        val version: String,
        val title: String,
        val author: String?,
        val designer: String?,
        val description: String?,
        val resources: List<ThemeManagerResource>,
        val previewNames: List<String>,
    )

    private data class ThemeManagerResource(val localId: String, val resourceCode: String)

    companion object {
        internal fun invalidateThemeManagerOriginAfterMutation(context: Context, themeId: String) {
            val preferences = context.applicationContext.getSharedPreferences(ORIGIN_PREFERENCES, Context.MODE_PRIVATE)
            val editor = preferences.edit()
            preferences.all.forEach { (key, value) ->
                if (key.startsWith(ORIGIN_PREFIX) && value?.toString()?.substringAfter('|', "") == themeId) {
                    editor.remove(key)
                }
            }
            editor.putBoolean("$REFRESH_PREFIX$themeId", true).apply()
        }

        internal fun linkThemeManagerOrigin(
            context: Context,
            localId: String,
            themeId: String,
            archiveSha256: String,
        ) {
            require(localId.matches(SAFE_IDENTIFIER)) { "Invalid theme local ID" }
            val preferences = context.applicationContext.getSharedPreferences(ORIGIN_PREFERENCES, Context.MODE_PRIVATE)
            val editor = preferences.edit()
            preferences.all.forEach { (key, value) ->
                if (key.startsWith(ORIGIN_PREFIX) && value?.toString()?.substringAfter('|', "") == themeId) {
                    editor.remove(key)
                }
            }
            editor
                .remove("$REFRESH_PREFIX$themeId")
                .putString("$ORIGIN_PREFIX$localId", "$archiveSha256|$themeId")
                .apply()
        }

        private const val ORIGIN_PREFERENCES = "theme-manager-imports"
        const val THEME_DATA_ROOT =
            "/data/media/0/Android/data/com.android.thememanager/files/MIUI/theme/.data"
        const val MAX_PREVIEWS_PER_THEME = 16
        const val MAX_PREVIEW_CANDIDATES = 16
        const val AUTO_CATALOG_NEW_IMPORT_LIMIT = 8
        const val AUTO_CATALOG_TIME_BUDGET_NANOS = 35_000_000_000L
        const val CATALOG_PROGRESS_LOG_INTERVAL = 5
        const val ORIGIN_PREFIX = "theme:"
        const val HIDDEN_PREFIX = "hidden:"
        const val REFRESH_PREFIX = "refresh:"
        val SAFE_IDENTIFIER = Regex("[A-Za-z0-9._-]{1,128}")
        val SAFE_RESOURCE_CODE = Regex("[A-Za-z0-9._-]{1,160}")
        val SAFE_PREVIEW_NAME = Regex("[A-Za-z0-9._-]{1,180}")
    }
}
