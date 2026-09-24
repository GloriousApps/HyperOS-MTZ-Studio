package dev.glorioustr.mtzstudio

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.Translation
import dev.glorioustr.mtzstudio.core.ThemeGlossary
import dev.glorioustr.mtzstudio.core.ThemeTextLocalizer
import dev.glorioustr.mtzstudio.core.ChineseTranslationSegmenter
import dev.glorioustr.mtzstudio.core.ConversationalThemeGlossary
import dev.glorioustr.mtzstudio.core.TranslationTextFilter
import dev.glorioustr.mtzstudio.library.LibraryTheme
import dev.glorioustr.mtzstudio.library.ThemeLibrary
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Device-local multilingual translation; the original library source is retained before replacement. */
internal class ThemeLanguageTool(context: Context, private val library: ThemeLibrary) {
    private val appContext = context.applicationContext
    private val diagnostics = LiveDiagnosticsRecorder.get(appContext)

    private data class TranslatorSession(val translator: Translator, var modelReady: Boolean = false)

    fun translateTextToSystemLanguage(
        theme: LibraryTheme,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): LibraryTheme = translateTextToSystemLanguage(theme, false, true, onProgress)

    fun translateTextToSystemLanguage(
        theme: LibraryTheme,
        experimentalOcr: Boolean,
        allowApiTranslation: Boolean,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): LibraryTheme {
        val locale = appContext.resources.configuration.locales[0] ?: Locale.getDefault()
        val target = translateLanguage(locale.toLanguageTag())
            ?: translateLanguage(locale.language)
            ?: TranslateLanguage.ENGLISH
        val output = library.newExportPath("${theme.displayName}-translated")
        val bitmapOutput = library.newExportPath("${theme.displayName}-widget-preview")
        val ocrOutput = library.newExportPath("${theme.displayName}-experimental-ocr")
        val identifier = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.45f).build(),
        )
        val translators = linkedMapOf<String, TranslatorSession>()
        val detectedLanguageCounts = linkedMapOf<String, Int>()
        val undetermined = linkedSetOf<String>()
        var uniqueTexts = 0
        val original = library.translationSource(theme)
        val allCandidates = ThemeTextLocalizer(
            targetLanguage = target,
            translateAllDisplayText = true,
            shouldTranslate = TranslationTextFilter::isCandidate,
        ).collectCandidates(original).map(String::trim).filter(String::isNotBlank).distinct()
        val totalCandidates = allCandidates.size.coerceAtLeast(1)
        val reportedCandidates = linkedSetOf<String>()
        var ocrStage = false
        fun reportTextProgress(processed: Int) {
            if (!ocrStage) {
                if (experimentalOcr) onProgress(processed * 800 / totalCandidates, 1000)
                else onProgress(processed, totalCandidates)
            }
        }
        reportTextProgress(0)
        val apiSettings = AiTranslationSettingsStore(appContext).load()
        val apiCandidates = linkedSetOf<String>()
        val apiResult = if (allowApiTranslation && apiSettings.isReady) {
            diagnostics.record(
                "theme_language_api_started",
                "Bağlamsal API çevirisi hazırlanıyor",
                mapOf("provider" to apiSettings.provider.title, "model" to apiSettings.model),
            )
            runCatching {
                allCandidates.forEach { candidate ->
                    val text = candidate.trim()
                    if (ThemeGlossary.resolve(text, target) == null &&
                        ConversationalThemeGlossary.resolve(text, target) == null
                    ) apiCandidates += text
                }
                ProfessionalThemeTranslator.fromSettings(appContext, apiSettings)!!.translate(apiCandidates, locale)
            }.getOrElse { error ->
                diagnostics.record(
                    "theme_language_api_failed",
                    "API çevirisi kullanılamadı; cihaz içi motora devam ediliyor",
                    error = error,
                )
                ProfessionalThemeTranslator.Result(emptyMap(), listOf(error.message ?: "API translation failed"))
            }
        } else ProfessionalThemeTranslator.Result(emptyMap(), emptyList())
        val apiTranslations = apiResult.translations
        var apiTranslatedTexts = 0

        fun identifySource(text: String): String? {
            val scriptHint = when {
                JAPANESE_SCRIPT.containsMatchIn(text) -> TranslateLanguage.JAPANESE
                KOREAN_SCRIPT.containsMatchIn(text) -> TranslateLanguage.KOREAN
                ThemeGlossary.containsChinese(text) -> TranslateLanguage.CHINESE
                else -> null
            }
            val identified = scriptHint ?: runCatching {
                Tasks.await(identifier.identifyLanguage(text.take(200)), 30, TimeUnit.SECONDS)
            }.getOrNull()
            val source = identified?.takeUnless { it == UNDETERMINED }?.let(::translateLanguage)
            if (source == null) undetermined += text
            return source
        }

        fun translate(text: String): String {
            val candidate = text.trim()
            try {
                if (!TranslationTextFilter.isCandidate(text)) return text
                val conversational = ConversationalThemeGlossary.resolve(text, target)
                val source = conversational?.sourceLanguage ?: identifySource(text) ?: return text
                detectedLanguageCounts[source] = (detectedLanguageCounts[source] ?: 0) + 1
                if (source == target) return text

            // Preserve the carefully curated Chinese theme vocabulary before neural translation.
                if (source == TranslateLanguage.CHINESE) {
                    ThemeGlossary.resolve(text, target)?.let { return it }
                }

                conversational?.translation?.let { return text.takeWhile(Char::isWhitespace) + it + text.takeLastWhile(Char::isWhitespace) }

                apiTranslations[text.trim()]?.let { apiTranslation ->
                    apiTranslatedTexts++
                    val polished = ThemeGlossary.postProcessTranslation(apiTranslation, target)
                    return text.takeWhile(Char::isWhitespace) + polished + text.takeLastWhile(Char::isWhitespace)
                }

                val session = translators.getOrPut(source) {
                TranslatorSession(
                    Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(source)
                            .setTargetLanguage(target)
                            .build(),
                    ),
                )
            }
                if (!session.modelReady) {
                diagnostics.record(
                    "theme_language_model_loading",
                    "Yerel çeviri dil modeli hazırlanıyor",
                    mapOf("sourceLanguage" to source, "targetLanguage" to target),
                )
                Tasks.await(
                    session.translator.downloadModelIfNeeded(DownloadConditions.Builder().build()),
                    5,
                    TimeUnit.MINUTES,
                )
                session.modelReady = true
                diagnostics.record(
                    "theme_language_model_ready",
                    "Dil modeli hazır",
                    mapOf("sourceLanguage" to source, "targetLanguage" to target),
                )
            }
                fun translateWithModel(input: String, from: String, to: String): String {
                val route = "$from>$to"
                val routeSession = translators.getOrPut(route) {
                    TranslatorSession(Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()))
                }
                if (!routeSession.modelReady) {
                    Tasks.await(routeSession.translator.downloadModelIfNeeded(DownloadConditions.Builder().build()), 5, TimeUnit.MINUTES)
                    routeSession.modelReady = true
                }
                return Tasks.await(routeSession.translator.translate(input), 30, TimeUnit.SECONDS).trim()
            }

                val translated = if (source == TranslateLanguage.CHINESE) {
                ChineseTranslationSegmenter.translate(text.trim(), target) { clause ->
                    if (target != TranslateLanguage.TURKISH) return@translate translateWithModel(clause, source, target)
                    val englishSource = ThemeGlossary.prepareChineseForEnglishPivot(clause)
                    val english = translateWithModel(englishSource, source, TranslateLanguage.ENGLISH)
                    val pivot = if (english.isBlank() || ThemeGlossary.containsChinese(english)) ""
                    else translateWithModel(english, TranslateLanguage.ENGLISH, target)
                    if (pivot.isNotBlank() && !ThemeGlossary.containsChinese(pivot)) pivot
                    else translateWithModel(clause, source, target)
                }
            } else {
                Tasks.await(session.translator.translate(text), 30, TimeUnit.SECONDS).trim()
            }
                uniqueTexts++
                if (uniqueTexts % 25 == 0) {
                diagnostics.record(
                    "theme_language_progress",
                    "Tema metinleri çevriliyor",
                    mapOf("uniqueTexts" to uniqueTexts, "detectedLanguages" to detectedLanguageCounts.keys.joinToString()),
                )
            }
                val polished = ThemeGlossary.postProcessTranslation(translated, target)
                return text.takeWhile(Char::isWhitespace) + polished + text.takeLastWhile(Char::isWhitespace)
            } finally {
                if (candidate.isNotBlank() && reportedCandidates.add(candidate)) {
                    reportTextProgress(reportedCandidates.size.coerceAtMost(totalCandidates))
                }
            }
        }

        try {
            diagnostics.record(
                "theme_language_tool_started",
                "İç bileşenler dahil çok dilli tema metinleri taranıyor",
                mapOf("sourceTheme" to theme.id.value, "targetLanguage" to target),
            )
            val result = ThemeTextLocalizer(
                targetLanguage = target,
                translateAllDisplayText = true,
                shouldTranslate = TranslationTextFilter::isCandidate,
            ).rewrite(original, output, ::translate)
            val bitmapResult = runCatching {
                ThemeWidgetPreviewLocalizer(target).rewrite(output, bitmapOutput)
            }.getOrElse { error ->
                diagnostics.record("theme_widget_preview_failed", "Bileşen önizlemesi düzenlenemedi", error = error)
                null
            }
            val previewOutput = if ((bitmapResult?.changedImages ?: 0) > 0) bitmapOutput else output
            val ocrResult = if (experimentalOcr) runCatching {
                reportTextProgress(totalCandidates)
                ocrStage = true
                ExperimentalThemeOcrLocalizer(::translate) { processed, total ->
                    onProgress(800 + processed * 200 / total.coerceAtLeast(1), 1000)
                }.rewrite(previewOutput, ocrOutput)
            }.getOrElse { error ->
                diagnostics.record("theme_experimental_ocr_failed", "Deneysel OCR atlandı; metin çevirisi korundu", error = error)
                null
            } else null
            require(result.translatedNodes > 0 || (bitmapResult?.changedImages ?: 0) > 0 || (ocrResult?.changedImages ?: 0) > 0) {
                "Çevrilebilen tema metni veya güvenle düzenlenebilen görsel bulunamadı; tema değiştirilmedi (${result.skippedFiles.size} bölüm atlandı)."
            }
            val finalOutput = if ((ocrResult?.changedImages ?: 0) > 0) ocrOutput else previewOutput
            val localized = Files.newInputStream(finalOutput).use { library.replaceTheme(theme, it) }
            library.recordTranslation(localized)
            diagnostics.record(
                "theme_language_tool_completed",
                "Mevcut MTZ çok dilli içerikleriyle birlikte çevrildi",
                mapOf(
                    "sourceTheme" to theme.id.value,
                    "targetLanguage" to target,
                    "detectedLanguages" to detectedLanguageCounts.entries.joinToString { "${it.key}:${it.value}" },
                    "undeterminedTextCount" to undetermined.size,
                    "changedFiles" to result.changedFiles.joinToString(),
                    "translatedNodes" to result.translatedNodes,
                    "previewScannedImages" to bitmapResult?.scannedImages,
                    "previewChangedImages" to bitmapResult?.changedImages,
                    "experimentalOcr" to experimentalOcr,
                    "ocrScannedImages" to ocrResult?.scannedImages,
                    "ocrChangedImages" to ocrResult?.changedImages,
                    "ocrTranslatedLabels" to ocrResult?.translatedLabels,
                    "ocrSkippedLabels" to ocrResult?.skippedLabels,
                    "apiEnabled" to apiSettings.enabled,
                    "apiAllowedForThisRun" to allowApiTranslation,
                    "apiProvider" to apiSettings.provider.title,
                    "apiModel" to apiSettings.model,
                    "apiTranslatedTexts" to apiTranslatedTexts,
                    "apiWarnings" to apiResult.warnings.joinToString(" | "),
                    "unresolvedTextCount" to result.unresolvedTexts.size,
                    "unresolvedTexts" to result.unresolvedTexts.take(40).joinToString(" | "),
                    "skippedFiles" to result.skippedFiles.distinct().joinToString(),
                ),
            )
            return localized
        } catch (error: Throwable) {
            diagnostics.record("theme_language_tool_failed", "Çeviri tamamlanamadı; mevcut tema değiştirilmedi", error = error)
            throw error
        } finally {
            identifier.close()
            translators.values.forEach { it.translator.close() }
            Files.deleteIfExists(output)
            Files.deleteIfExists(bitmapOutput)
            Files.deleteIfExists(ocrOutput)
        }
    }

    private fun translateLanguage(tag: String): String? {
        val normalized = when (tag.substringBefore('-').lowercase(Locale.ROOT)) {
            "in" -> "id"
            "iw" -> "he"
            else -> tag
        }
        return TranslateLanguage.fromLanguageTag(normalized)
    }

    companion object {
        private const val UNDETERMINED = "und"
        private val JAPANESE_SCRIPT = Regex("[\\p{IsHiragana}\\p{IsKatakana}]")
        private val KOREAN_SCRIPT = Regex("[\\p{IsHangul}]")
    }
}
