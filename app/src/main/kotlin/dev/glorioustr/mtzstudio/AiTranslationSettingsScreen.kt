package dev.glorioustr.mtzstudio

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** BYOK configuration ported from BAK Importer. Credentials remain encrypted on-device. */
@Composable
internal fun AiTranslationSettingsScreen(modifier: Modifier = Modifier, neonStyle: Boolean = false) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val store = remember { AiTranslationSettingsStore(context) }
    val initial = remember { store.load() }
    val currentLocale = configuration.locales[0] ?: Locale.getDefault()
    val turkish = currentLocale.language == "tr"
    fun label(tr: String, en: String) = if (turkish) tr else en
    var enabled by remember { mutableStateOf(initial.enabled) }
    var provider by remember { mutableStateOf(initial.provider) }
    var providerMenu by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf(initial.customEndpoint) }
    var model by remember { mutableStateOf(initial.model) }
    var models by remember { mutableStateOf(initial.provider.suggestedModels) }
    var modelMenu by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var storedKey by remember { mutableStateOf(initial.apiKey.isNotBlank()) }
    var useContext by remember { mutableStateOf(initial.useContext) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun settings(forceEnabled: Boolean = enabled) = AiTranslationSettings(
        enabled = forceEnabled,
        provider = provider,
        customEndpoint = endpoint,
        model = model,
        apiKey = apiKey.trim().ifBlank { store.loadApiKey(provider) },
        systemPrompt = "",
        userPrompt = "",
        useContext = useContext,
    )

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StudioCard(Modifier.fillMaxWidth(), neon = neonStyle) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier.size(44.dp).background(Color(0xFF7C4DFF).copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.AutoFixHigh, null, tint = Color(0xFF9C75FF), modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label("Tema Dil Aracı", "Theme Language Tool"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text(label("Bağlamsal yapay zekâ çevirisi", "Context-aware AI translation"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(enabled, { enabled = it })
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
                ) {
                    Text(
                        label("Bağlamsal toplu çeviri, tekrar deneme ve çeviri belleği birlikte çalışır. API kapalıyken cihaz içi çeviri kullanılabilir.", "Contextual batching, retries and translation memory work together. On-device translation remains available when the API is disabled."),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        StudioCard(Modifier.fillMaxWidth(), neon = neonStyle) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                    Text(label("Sağlayıcı ve model", "Provider and model"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                Column {
                    OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(provider.title) }
                    DropdownMenu(
                        expanded = providerMenu,
                        onDismissRequest = { providerMenu = false },
                        modifier = Modifier.heightIn(max = 360.dp),
                        offset = DpOffset(0.dp, 4.dp),
                    ) {
                        AiProvider.entries.forEach { option -> DropdownMenuItem(text = { Text(option.title) }, onClick = {
                            provider = option; model = store.loadModel(option); models = option.suggestedModels; apiKey = ""; storedKey = store.loadApiKey(option).isNotBlank(); providerMenu = false
                        }) }
                    }
                }
                if (provider == AiProvider.CUSTOM) OutlinedTextField(endpoint, { endpoint = it }, label = { Text(label("HTTPS API adresi", "HTTPS API endpoint")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text(label("Model", "Model")) }, supportingText = { Text(provider.suggestedModels.joinToString().ifBlank { label("OpenAI uyumlu model adı", "OpenAI-compatible model name") }) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column {
                    OutlinedButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            runCatching { withContext(Dispatchers.IO) { AiProviderClient.fetchModels(settings(true)) } }
                                .onSuccess { fetched -> models = (provider.suggestedModels + fetched).distinct(); modelMenu = true; status = label("Model listesi güncellendi", "Model list updated") }
                                .onFailure { status = it.message }
                            busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(label("Modelleri çevrimiçi getir", "Fetch models online")) }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        models.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { model = option; modelMenu = false }) }
                    }
                }
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text(label("API anahtarı", "API key")) }, placeholder = { Text(if (storedKey) label("Şifrelenmiş anahtar kayıtlı", "Encrypted key saved") else label("Birden fazla anahtar virgülle ayrılabilir", "Separate multiple keys with commas")) }, leadingIcon = { Icon(Icons.Filled.Lock, null) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(label("Tema bağlamını kullan", "Use theme context"), fontWeight = FontWeight.Medium); Text(label("Aynı gruptaki metinlerde tutarlı ve doğal terimler üretir.", "Produces natural, consistent terms across sibling strings."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                        Switch(useContext, { useContext = it })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch { busy = true; status = runCatching { withContext(Dispatchers.IO) { ProfessionalThemeTranslator(settings(true)).testConnection(currentLocale) } }.fold({ label("Bağlantı başarılı: ", "Connection successful: ") + it }, { it.message ?: label("Bağlantı başarısız", "Connection failed") }); busy = false }
            }, modifier = Modifier.weight(1f)) { Text(if (busy) label("Deneniyor…", "Testing…") else label("Bağlantıyı dene", "Test connection")) }
            Button(onClick = {
                runCatching { require(!enabled || storedKey || apiKey.isNotBlank()) { label("API anahtarı gerekli", "API key is required") }; store.save(settings(), apiKey.takeIf(String::isNotBlank)) }
                    .onSuccess { if (apiKey.isNotBlank()) storedKey = true; apiKey = ""; status = label("Ayarlar kaydedildi", "Settings saved"); Toast.makeText(context, status, Toast.LENGTH_SHORT).show() }
                    .onFailure { status = it.message }
            }, modifier = Modifier.weight(1f)) { Text(label("Kaydet", "Save")) }
        }
        if (storedKey) OutlinedButton(onClick = { store.clearApiKey(provider); storedKey = false; enabled = false; status = label("API anahtarı silindi", "API key removed") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Delete, null); Text(label("Kayıtlı anahtarı sil", "Remove saved key"), Modifier.padding(start = 8.dp)) }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text(label("Gizlilik: Tema metinleri yalnızca çeviri sırasında seçtiğiniz sağlayıcıya gönderilir. API anahtarları Android Keystore ile şifrelenir ve tanı raporlarına yazılmaz.", "Privacy: Theme text is sent only to your chosen provider during translation. API keys are encrypted with Android Keystore and never written to diagnostics."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}
