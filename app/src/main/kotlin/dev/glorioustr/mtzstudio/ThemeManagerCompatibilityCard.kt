package dev.glorioustr.mtzstudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.glorioustr.mtzstudio.shevery.PreferredPrivilegedCommandRunner
import dev.glorioustr.mtzstudio.tester.InstalledThemeManager
import dev.glorioustr.mtzstudio.tester.ThemeManagerCapabilityProbe
import dev.glorioustr.mtzstudio.tester.ThemeManagerContract
import dev.glorioustr.mtzstudio.tester.ThemeManagerInspector
import dev.glorioustr.mtzstudio.tester.ThemeManagerRuntimeProfile
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeArtifacts
import dev.glorioustr.mtzstudio.tester.ThemeRuntimeTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reports the active route and keeps the real Xiaomi Themes Runtime + MTZ Support root module. */
@Composable
internal fun ThemeManagerCompatibilityCard(
    inspector: ThemeManagerInspector,
    rootAccessAvailable: Boolean = false,
    neonStyle: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember {
        RootThemeImportModuleInstaller(
            context.applicationContext,
            PreferredPrivilegedCommandRunner(context.applicationContext),
        )
    }
    var installed by remember { mutableStateOf<InstalledThemeManager?>(null) }
    var profile by remember { mutableStateOf<ThemeManagerRuntimeProfile?>(null) }
    var module by remember { mutableStateOf<RootThemeImportModuleInstaller.State?>(null) }
    var target by remember { mutableStateOf<ThemeRuntimeTarget?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            val current = withContext(Dispatchers.IO) { inspector.inspect() }
            installed = current
            profile = withContext(Dispatchers.IO) { ThemeManagerCapabilityProbe(context).probe(current) }
            if (rootAccessAvailable) {
                module = runCatching { withContext(Dispatchers.IO) { installer.inspect() } }.getOrNull()
                target = runCatching { withContext(Dispatchers.IO) { installer.detectTarget() } }.getOrNull()
            } else {
                module = null
                target = null
            }
        }
    }

    LaunchedEffect(rootAccessAvailable) { refresh() }

    val accent = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        Color(0xFF006A78)
    } else {
        Color(0xFF00DAF3)
    }
    val runtime = profile
    val legacyReady = runtime?.legacyTesterResolvable == true
    val modernReady = runtime?.modernLocalLibraryResolvable == true
    val selectedTarget = target ?: ThemeRuntimeTarget.GLOBAL
    val bundledCurrent = rootAccessAvailable && installer.isBundledVersion(module, selectedTarget)
    val moduleState = module
    val rootReady = bundledCurrent && moduleState?.runtimeActive == true && moduleState.runtimeVerified &&
        (selectedTarget == ThemeRuntimeTarget.NON_GLOBAL || moduleState.bridgeReady)

    StudioCard(Modifier.fillMaxWidth(), neon = neonStyle) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = accent)
                Text(
                    stringResource(R.string.tm_card_title),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = { showInfo = true }) {
                    Icon(Icons.Filled.Info, contentDescription = null)
                }
            }
            Text(
                if (installed?.installed == true) {
                    "Xiaomi Temalar: ${installed?.versionName ?: stringResource(R.string.tm_version_unknown)}"
                } else {
                    stringResource(R.string.tm_device_not_found)
                },
                fontWeight = FontWeight.Bold,
                color = if (legacyReady || modernReady || rootReady) accent else MaterialTheme.colorScheme.onSurface,
            )
            when {
                legacyReady -> RouteBadge("ApplyThemeForScreenshot etkin", accent)
                modernReady -> RouteBadge("Yerleşik yerel tema kitaplığı etkin", accent)
                rootReady -> RouteBadge("Root MTZ Import runtime hazır", MaterialTheme.colorScheme.tertiary)
                rootAccessAvailable -> {
                    Text("ApplyThemeForScreenshot bulunamadı; mevcut root runtime yolu kullanılabilir.", style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !busy && !rootReady,
                        onClick = {
                            busy = true
                            status = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { installer.installOrUpdate(selectedTarget) }
                                }.onSuccess { result ->
                                    status = "${result.version} root runtime kuruldu."
                                    refresh()
                                }.onFailure { error ->
                                    status = error.message ?: error::class.simpleName
                                }
                                busy = false
                            }
                        },
                    ) { Text(if (busy) "Root runtime kuruluyor…" else "Root MTZ Import runtime kur/güncelle") }
                }
                installed?.installed == true -> Text(
                    "Bu sürüm ApplyThemeForScreenshot etkinliğini ilan etmiyor; root erişimi veya manuel Xiaomi Temalar aktarımı gerekir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Theme Manager runtime") },
            text = { Text("Eski sürüme zorlama ve APK yedekleme akışı kaldırıldı. Mevcut Xiaomi Themes sürümü korunur; root varsa kurulu Xiaomi Themes Runtime + MTZ Support modülü kullanılır.") },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Kapat") } },
        )
    }
}

@Composable
private fun RouteBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), contentColor = color, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.bodySmall)
    }
}

internal fun supportsRootMtzImportModule(versionName: String?): Boolean {
    val canonical = ThemeManagerContract.canonicalVersion(versionName) ?: return false
    return ThemeRuntimeArtifacts.ALL.any { it.versionName.removeSuffix("-global") == canonical }
}
