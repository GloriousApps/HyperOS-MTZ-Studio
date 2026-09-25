package dev.glorioustr.mtzstudio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class AiProvider(
    val title: String,
    val endpoint: String,
    val modelsEndpoint: String?,
    val defaultModel: String,
    val suggestedModels: List<String>,
) {
    GOOGLE_AI_STUDIO("Google AI Studio", "https://generativelanguage.googleapis.com/v1beta", "https://generativelanguage.googleapis.com/v1beta/models", "gemini-2.5-flash", listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-2.0-flash")),
    GOOGLE_VERTEX("Google Vertex", "https://aiplatform.googleapis.com/v1", null, "gemini-2.5-flash", listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-2.0-flash")),
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "https://api.openai.com/v1/models", "gpt-4o-mini", listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1")),
    GROQ("Groq", "https://api.groq.com/openai/v1/chat/completions", "https://api.groq.com/openai/v1/models", "openai/gpt-oss-120b", listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b")),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "https://api.deepseek.com/v1/models", "deepseek-chat", listOf("deepseek-chat", "deepseek-reasoner")),
    XAI("xAI", "https://api.x.ai/v1/chat/completions", "https://api.x.ai/v1/models", "grok-4.1-fast", listOf("grok-4.1-fast", "grok-4.1-mini-fast")),
    CEREBRAS("Cerebras", "https://api.cerebras.ai/v1/chat/completions", "https://api.cerebras.ai/v1/models", "gpt-oss-120b", listOf("gpt-oss-120b", "llama-3.3-70b")),
    OLLAMA("Ollama Cloud", "https://ollama.com/v1/chat/completions", "https://ollama.com/v1/models", "gemma3:27b", listOf("gemma3:27b", "llama3.3:70b", "qwen3:32b")),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "https://openrouter.ai/api/v1/models", "openrouter/free", listOf("openrouter/free", "google/gemini-2.5-flash", "deepseek/deepseek-chat", "openai/gpt-4o-mini")),
    VERCEL_AI_GATEWAY("Vercel AI Gateway", "https://ai-gateway.vercel.sh/v1/chat/completions", "https://ai-gateway.vercel.sh/v1/models", "google/gemini-2.5-flash", listOf("google/gemini-2.5-flash", "openai/gpt-4o-mini")),
    CUSTOM("Custom (OpenAI compatible)", "", null, "", emptyList());
}

internal data class AiTranslationSettings(
    val enabled: Boolean = false,
    val provider: AiProvider = AiProvider.GOOGLE_AI_STUDIO,
    val customEndpoint: String = "",
    val model: String = AiProvider.GOOGLE_AI_STUDIO.defaultModel,
    val apiKey: String = "",
    val systemPrompt: String = "",
    val userPrompt: String = "",
    val useContext: Boolean = true,
) {
    val endpoint get() = if (provider == AiProvider.CUSTOM) customEndpoint.trim() else provider.endpoint
    val apiKeys get() = apiKey.split(',').map(String::trim).filter(String::isNotBlank)
    val isReady get() = enabled && apiKeys.isNotEmpty() && endpoint.startsWith("https://") && model.isNotBlank()
}

/** API keys are encrypted with a non-exportable Android Keystore key and excluded from logs. */
internal class AiTranslationSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): AiTranslationSettings {
        val provider = runCatching { AiProvider.valueOf(preferences.getString(KEY_PROVIDER, null).orEmpty()) }
            .getOrDefault(AiProvider.GOOGLE_AI_STUDIO)
        return AiTranslationSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            provider = provider,
            customEndpoint = preferences.getString(KEY_CUSTOM_ENDPOINT, "").orEmpty(),
            model = loadModel(provider),
            apiKey = loadApiKey(provider),
            systemPrompt = "",
            userPrompt = "",
            useContext = preferences.getBoolean(KEY_USE_CONTEXT, true),
        )
    }

    fun save(settings: AiTranslationSettings, newApiKey: String? = null) {
        if (settings.enabled) {
            require(settings.endpoint.startsWith("https://")) { "API endpoint must use HTTPS" }
            require(settings.model.isNotBlank()) { "Model name cannot be empty" }
        }
        val editor = preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_PROVIDER, settings.provider.name)
            .putString(KEY_CUSTOM_ENDPOINT, settings.customEndpoint.trim())
            .putString(modelName(settings.provider), settings.model.trim())
            .remove(KEY_SYSTEM_PROMPT)
            .remove(KEY_USER_PROMPT)
            .putBoolean(KEY_USE_CONTEXT, settings.useContext)
        newApiKey?.trim()?.takeIf(String::isNotBlank)?.let { key ->
            val encrypted = encrypt(key)
            editor.putString(keyDataName(settings.provider), encrypted.first)
            editor.putString(keyIvName(settings.provider), encrypted.second)
        }
        editor.apply()
    }

    fun clearApiKey(provider: AiProvider) {
        preferences.edit().remove(keyDataName(provider)).remove(keyIvName(provider)).putBoolean(KEY_ENABLED, false).apply()
    }

    fun loadModel(provider: AiProvider): String = preferences.getString(modelName(provider), null).orEmpty().ifBlank { provider.defaultModel }

    fun loadApiKey(provider: AiProvider): String {
        val data = preferences.getString(keyDataName(provider), null) ?: return ""
        val iv = preferences.getString(keyIvName(provider), null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            preferences.edit().remove(keyDataName(provider)).remove(keyIvName(provider)).apply()
            ""
        }
    }

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    private fun keyDataName(provider: AiProvider) = "api_key_${provider.name.lowercase()}_encrypted"
    private fun keyIvName(provider: AiProvider) = "api_key_${provider.name.lowercase()}_iv"
    private fun modelName(provider: AiProvider) = "model_${provider.name.lowercase()}"

    companion object {
        private const val PREFERENCES = "ai_translation_settings"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mtz_studio_translation_api_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_CUSTOM_ENDPOINT = "custom_endpoint"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_USER_PROMPT = "user_prompt"
        private const val KEY_USE_CONTEXT = "use_context"
    }
}

internal object AiProviderClient {
    fun fetchModels(settings: AiTranslationSettings): List<String> {
        val endpoint = settings.provider.modelsEndpoint ?: settings.endpoint
            .replace(Regex("/(chat/completions|responses)/?$", RegexOption.IGNORE_CASE), "").trimEnd('/') + "/models"
        val key = settings.apiKeys.firstOrNull() ?: error("Enter the provider API key first")
        return http(endpoint, "GET", null, settings.provider == AiProvider.GOOGLE_AI_STUDIO, key).let { root ->
            val array = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
            buildList {
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index)
                    item?.optString("id").orEmpty().ifBlank { item?.optString("name").orEmpty() }
                        .removePrefix("models/").takeIf(String::isNotBlank)?.let(::add)
                }
            }.distinct().sorted()
        }
    }

    internal fun http(endpoint: String, method: String, body: JSONObject?, gemini: Boolean, key: String): JSONObject {
        require(endpoint.startsWith("https://")) { "API endpoint must use HTTPS" }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            if (body != null) connection.doOutput = true
            connection.setRequestProperty(if (gemini) "x-goog-api-key" else "Authorization", if (gemini) key else "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                    .orEmpty().ifBlank { "HTTP $status" }
                error(message)
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}

/** Context-aware, batched BYOK translation with placeholder integrity checks and key rotation. */
internal class ProfessionalThemeTranslator(
    private val settings: AiTranslationSettings,
    private val memory: TranslationMemory? = null,
) {
    data class Result(val translations: Map<String, String>, val warnings: List<String>)
    private data class BatchResult(val translations: Map<String, String>, val warnings: List<String>)

    fun translate(candidates: Collection<String>, locale: Locale): Result {
        if (!settings.isReady) return Result(emptyMap(), emptyList())
        val unique = candidates.map(String::trim).filter(String::isNotBlank).distinct()
        val engine = "${settings.provider}|${settings.endpoint}|${settings.model}|${settings.systemPrompt.hashCode()}|${settings.userPrompt.hashCode()}|${settings.useContext}"
        val language = locale.toLanguageTag()
        val accepted = linkedMapOf<String, String>()
        accepted.putAll(memory?.get(unique, language, engine).orEmpty())
        val batches = TranslationBatchPlanner.chunk(unique.filterNot(accepted::containsKey))
        val warnings = mutableListOf<String>()
        val pool = Executors.newFixedThreadPool(minOf(3, batches.size.coerceAtLeast(1)))
        try {
            val futures = batches.mapIndexed { index, batch -> pool.submit(Callable { requestValidatedBatch(batch, locale, index + 1) }) }
            futures.forEach { future ->
                val result = runCatching { future.get() }.getOrElse {
                    warnings += "API batch failed: ${it.cause?.message ?: it.message}"; return@forEach
                }
                accepted.putAll(result.translations)
                warnings += result.warnings
            }
        } finally { pool.shutdownNow() }
        memory?.put(accepted, language, engine)
        return Result(accepted, warnings.distinct())
    }

    private fun requestValidatedBatch(texts: List<String>, locale: Locale, group: Int, depth: Int = 0): BatchResult {
        val response = runCatching { request(texts, locale) }
        if (response.isFailure) {
            if (texts.size > 8 && depth < 3) {
                val middle = texts.size / 2
                return merge(
                    requestValidatedBatch(texts.subList(0, middle), locale, group, depth + 1),
                    requestValidatedBatch(texts.subList(middle, texts.size), locale, group, depth + 1),
                )
            }
            return BatchResult(emptyMap(), listOf("API group $group failed: ${response.exceptionOrNull()?.message.orEmpty()}"))
        }
        val valid = response.getOrThrow().filter { (source, translated) -> TranslationIntegrityValidator.isValid(source, translated.trim()) }
        val missing = texts.filterNot(valid::containsKey)
        if (missing.isEmpty()) return BatchResult(valid, emptyList())
        if (missing.size == 1) {
            if (texts.size > 1 && depth < 4) return merge(BatchResult(valid, emptyList()), requestValidatedBatch(missing, locale, group, depth + 1))
            return BatchResult(valid, listOf("One result in API group $group was rejected by integrity validation"))
        }
        val middle = missing.size / 2
        return merge(BatchResult(valid, emptyList()), requestValidatedBatch(missing.subList(0, middle), locale, group, depth + 1), requestValidatedBatch(missing.subList(middle, missing.size), locale, group, depth + 1))
    }

    private fun merge(vararg results: BatchResult) = BatchResult(
        buildMap { results.forEach { putAll(it.translations) } }, results.flatMap { it.warnings },
    )

    fun testConnection(locale: Locale = Locale("tr")): String = request(listOf("Settings"), locale)["Settings"]
        ?.takeIf(String::isNotBlank) ?: error("The model returned an empty response")

    private fun request(texts: List<String>, locale: Locale): Map<String, String> {
        val indexed = texts.mapIndexed { index, value -> index.toString() to value }
        val input = JSONArray().apply { indexed.forEach { (id, text) -> put(JSONObject().put("id", id).put("text", text)) } }
        val language = locale.getDisplayName(Locale.ENGLISH).ifBlank { locale.language }
        val system = buildString {
            append("You are a senior Xiaomi/HyperOS theme localization editor. Translate naturally into $language. ")
            if (settings.useContext) append("Infer meaning from sibling strings and keep terminology consistent. ")
            append("Use concise phone UI wording. Preserve variables, format specifiers, resource references, MAML expressions, numbers and punctuation structure. Return every id exactly once as JSON; no commentary.")
            settings.systemPrompt.takeIf(String::isNotBlank)?.let { append(" Additional instructions: $it") }
        }
        val user = buildString {
            settings.userPrompt.takeIf(String::isNotBlank)?.let { append(it).append("\n\n") }
            append(JSONObject().put("target_language", language).put("strings", input))
        }
        val gemini = settings.provider in setOf(AiProvider.GOOGLE_AI_STUDIO, AiProvider.GOOGLE_VERTEX)
        val endpoint = if (gemini) {
            val model = settings.model.removePrefix("models/").removePrefix("google/")
            val path = if (settings.provider == AiProvider.GOOGLE_VERTEX) "publishers/google/models/$model" else "models/$model"
            "${settings.endpoint.trimEnd('/')}/$path:generateContent"
        } else settings.endpoint.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
        val body = if (gemini) JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        else JSONObject().put("model", settings.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                val response = AiProviderClient.http(endpoint, "POST", body, gemini, nextKey())
                return parse(output(response), indexed)
            } catch (error: Throwable) {
                last = error
                if (attempt == 0) Thread.sleep(1_000)
            }
        }
        throw IllegalStateException(last?.message ?: "API connection failed", last)
    }

    private fun output(response: JSONObject): String {
        response.optJSONArray("output")?.let { output ->
            repeat(output.length()) { i -> output.optJSONObject(i)?.optJSONArray("content")?.let { content ->
                repeat(content.length()) { j -> content.optJSONObject(j)?.takeIf { it.optString("type") == "output_text" }?.optString("text")?.takeIf(String::isNotBlank)?.let { return it } }
            } }
        }
        response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?.takeIf(String::isNotBlank)?.let { return it }
        response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?.optJSONObject(0)?.optString("text")?.takeIf(String::isNotBlank)?.let { return it }
        response.optString("output_text").takeIf(String::isNotBlank)?.let { return it }
        error("Translation text was not found in the API response")
    }

    private fun parse(raw: String, indexed: List<Pair<String, String>>): Map<String, String> {
        val clean = raw.trim().let { if (it.startsWith("```")) it.substringAfter('\n').substringBeforeLast("```").trim() else it }
        val array = if (clean.startsWith("[")) JSONArray(clean) else JSONObject(clean).optJSONArray("translations")
            ?: error("API did not return a translations array")
        val byId = linkedMapOf<String, String>()
        repeat(array.length()) { index ->
            when (val item = array.opt(index)) {
                is JSONObject -> item.optString("text").ifBlank { item.optString("translation") }.takeIf(String::isNotBlank)
                    ?.let { byId[item.optString("id", index.toString())] = it }
                is String -> byId[index.toString()] = item
            }
        }
        return indexed.mapNotNull { (id, original) -> byId[id]?.let { original to it } }.toMap()
    }

    private fun nextKey(): String {
        val keys = settings.apiKeys
        require(keys.isNotEmpty()) { "API key is missing" }
        return keys[Math.floorMod(keyIndex.getAndIncrement(), keys.size)]
    }

    companion object {
        private val keyIndex = AtomicInteger(0)
        fun fromSettings(context: Context, settings: AiTranslationSettings): ProfessionalThemeTranslator? =
            settings.takeIf(AiTranslationSettings::isReady)?.let { ProfessionalThemeTranslator(it, TranslationMemory(context)) }
    }
}

private object TranslationBatchPlanner {
    fun chunk(texts: List<String>): List<List<String>> {
        val result = mutableListOf<MutableList<String>>(); var current = mutableListOf<String>(); var chars = 0
        texts.forEach { text ->
            if (current.isNotEmpty() && (current.size >= 80 || chars + text.length > 20_000)) { result += current; current = mutableListOf(); chars = 0 }
            current += text; chars += text.length
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}

private object TranslationIntegrityValidator {
    private val token = Regex("%(?:\\d+\\$)?[a-zA-Z]|#[A-Za-z0-9_]+|@[A-Za-z0-9_./:-]+|\\$\\{[^}]+}|\\{[A-Za-z0-9_]+}|\\\\[ntr]")
    fun isValid(original: String, translated: String): Boolean = translated.isNotBlank() &&
        translated.length <= maxOf(240, original.length * 5) &&
        token.findAll(original).map { it.value }.sorted().toList() == token.findAll(translated).map { it.value }.sorted().toList()
}
