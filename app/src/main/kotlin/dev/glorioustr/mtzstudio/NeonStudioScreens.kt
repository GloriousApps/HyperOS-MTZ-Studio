package dev.glorioustr.mtzstudio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val NeonBackground = Color(0xFF080A1C)
private val NeonBackgroundMid = Color(0xFF101132)
private val NeonSurface = Color(0xCC17183C)
private val NeonSurfaceHigh = Color(0xE6222550)
private val NeonText = Color(0xFFF4F1FF)
private val NeonMutedText = Color(0xFFA9ACD0)
private val NeonPurple = Color(0xFF9B6DFF)
private val NeonViolet = Color(0xFFB54DFF)
private val NeonCyan = Color(0xFF55E4FF)
private val NeonBlue = Color(0xFF5D8DFF)
private val NeonGreen = Color(0xFF54E6B2)
private val NeonOrange = Color(0xFFFFA45B)
private val NeonPink = Color(0xFFFF6FBB)

/** A small, integration-friendly model for a dashboard action. */
data class NeonQuickAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: Color = NeonPurple,
    val onClick: () -> Unit = {},
    val enabled: Boolean = true,
)

/** A row model shared by the tools and library shells. */
data class NeonToolItem(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val accent: Color = NeonCyan,
    val badge: String? = null,
    val onClick: () -> Unit = {},
    val enabled: Boolean = true,
)

data class NeonLibraryItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector = Icons.Filled.Palette,
    val accent: Color = NeonPurple,
    val badge: String? = null,
    val onClick: () -> Unit = {},
)

data class NeonBuilderSection(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val accent: Color = NeonPurple,
    val selectedValue: String? = null,
    val onClick: () -> Unit = {},
)

data class NeonMetric(
    val value: String,
    val label: String,
    val accent: Color = NeonPurple,
)

/**
 * The glass surface used by the redesigned screens.  Keeping the content as a ColumnScope
 * makes this useful for both a full card and small cards embedded in an existing screen.
 */
@Composable
fun NeonGlassCard(
    modifier: Modifier = Modifier,
    accent: Color = NeonPurple,
    shape: Shape = RoundedCornerShape(24.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(enabled = true, onClick = onClick)
    }
    val surfaceBrush = Brush.linearGradient(
        colors = listOf(
            NeonSurfaceHigh,
            NeonSurface,
            Color(0xCC111331),
        ),
    )
    Box(
        modifier = modifier
            .then(interactionModifier)
            .clip(shape)
            .background(surfaceBrush)
            .border(
                BorderStroke(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.72f),
                            Color(0xFF5A5A96).copy(alpha = 0.48f),
                            NeonCyan.copy(alpha = 0.38f),
                        ),
                    ),
                ),
                shape,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
fun NeonSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                color = NeonText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = NeonMutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(actionLabel, color = NeonCyan, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun NeonQuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color = NeonPurple,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    NeonGlassCard(
        modifier = modifier,
        accent = accent,
        onClick = if (enabled) onClick else null,
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NeonIconTile(icon = icon, accent = accent)
            Text(
                text = title,
                color = if (enabled) NeonText else NeonMutedText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = NeonMutedText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun NeonToolRow(
    title: String,
    description: String,
    icon: ImageVector,
    accent: Color = NeonCyan,
    badge: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NeonIconTile(icon = icon, accent = accent, size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    color = if (enabled) NeonText else NeonMutedText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    description,
                    color = NeonMutedText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!badge.isNullOrBlank()) {
                NeonBadge(badge, accent)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = accent)
        }
    }
}

@Composable
fun NeonPanelScreen(
    modifier: Modifier = Modifier,
    title: String = "Panel",
    subtitle: String = "HyperOS tema çalışma alanın",
    statusLabel: String = "HAZIR",
    statusColor: Color = NeonGreen,
    metrics: List<NeonMetric> = defaultNeonMetrics(),
    quickActions: List<NeonQuickAction> = defaultNeonPanelActions(),
    recentItems: List<NeonLibraryItem> = emptyList(),
    recentTitle: String = "Son çalışmalar",
    onSeeAllRecent: (() -> Unit)? = null,
    footerContent: @Composable () -> Unit = {},
) {
    NeonScreenBackdrop(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                NeonHeroHeader(
                    title = title,
                    subtitle = subtitle,
                    statusLabel = statusLabel,
                    statusColor = statusColor,
                    icon = Icons.Filled.Dashboard,
                )
            }
            if (metrics.isNotEmpty()) {
                item { NeonMetricsRow(metrics) }
            }
            item {
                NeonSectionHeader(
                    title = "Hızlı erişim",
                    subtitle = "Stüdyo araçlarına tek dokunuşla ulaş",
                )
            }
            item {
                NeonActionGrid(quickActions)
            }
            if (recentItems.isNotEmpty()) {
                item {
                    NeonSectionHeader(
                        title = recentTitle,
                        subtitle = "En son düzenlediğin temalar",
                        actionLabel = if (onSeeAllRecent != null) "Tümünü gör" else null,
                        onAction = onSeeAllRecent,
                    )
                }
                item {
                    NeonGlassCard(accent = NeonBlue) {
                        Column(Modifier.padding(vertical = 5.dp)) {
                            recentItems.forEachIndexed { index, item ->
                                NeonLibraryRow(item)
                                if (index < recentItems.lastIndex) NeonDivider()
                            }
                        }
                    }
                }
            }
            item { footerContent() }
        }
    }
}

@Composable
fun NeonLibraryScreen(
    items: List<NeonLibraryItem>,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    title: String = "Kitaplık",
    subtitle: String = "Temalarını keşfet, düzenle ve yönet",
    onRefresh: (() -> Unit)? = null,
    emptyTitle: String = "Kitaplık boş",
    emptyDescription: String = "İlk temanı içe aktararak çalışma alanını oluştur.",
) {
    NeonScreenBackdrop(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                NeonHeroHeader(
                    title = title,
                    subtitle = subtitle,
                    icon = Icons.Filled.ColorLens,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = NeonCyan) },
                        placeholder = { Text("Tema ara", color = NeonMutedText) },
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = Color(0xFF55588D),
                            focusedTextColor = NeonText,
                            unfocusedTextColor = NeonText,
                            cursorColor = NeonCyan,
                        ),
                    )
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Yenile", tint = NeonCyan)
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                item {
                    NeonGlassCard(accent = NeonPurple) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            NeonIconTile(Icons.Filled.FolderOpen, NeonPurple, size = 52.dp)
                            Text(emptyTitle, color = NeonText, fontWeight = FontWeight.Bold)
                            Text(emptyDescription, color = NeonMutedText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                items(items, key = { it.title }) { item ->
                    NeonGlassCard(accent = item.accent, onClick = item.onClick) {
                        NeonLibraryRow(item, padded = true)
                    }
                }
            }
        }
    }
}

@Composable
fun NeonThemeBuilderScreen(
    sections: List<NeonBuilderSection>,
    modifier: Modifier = Modifier,
    title: String = "Temanı Oluştur",
    subtitle: String = "Tema parçalarını seç, önizle ve dışa aktar",
    primaryActionLabel: String = "Temayı oluştur",
    onPrimaryAction: () -> Unit = {},
    secondaryActionLabel: String? = "Önizle",
    onSecondaryAction: (() -> Unit)? = null,
) {
    NeonScreenBackdrop(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            item {
                NeonHeroHeader(
                    title = title,
                    subtitle = subtitle,
                    icon = Icons.Filled.AutoAwesome,
                )
            }
            item {
                NeonGlassCard(accent = NeonViolet) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        NeonSectionHeader(
                            title = "Tema bileşenleri",
                            subtitle = "İstediğin kaynakları çalışma alanına ekle",
                        )
                        sections.forEachIndexed { index, section ->
                            NeonBuilderRow(section)
                            if (index < sections.lastIndex) NeonDivider()
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        Button(
                            onClick = onSecondaryAction,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonSurfaceHigh,
                                contentColor = NeonText,
                            ),
                        ) {
                            Icon(Icons.Filled.GridView, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(secondaryActionLabel)
                        }
                    }
                    Button(
                        onClick = onPrimaryAction,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonPurple,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(primaryActionLabel)
                    }
                }
            }
        }
    }
}

@Composable
fun NeonToolsScreen(
    tools: List<NeonToolItem> = defaultNeonTools(),
    modifier: Modifier = Modifier,
    title: String = "Araçlar",
    subtitle: String = "Tema çalışma alanını güçlendiren yardımcılar",
    sectionTitle: String = "Tüm araçlar",
    sectionSubtitle: String = "Bir araca dokunarak işlemi başlat",
    redesignedUi: Boolean = true,
    onRedesignedUiChange: (Boolean) -> Unit = {},
) {
    NeonScreenBackdrop(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            item {
                NeonHeroHeader(title = title, subtitle = subtitle, icon = Icons.Filled.Build)
            }
            item {
                NeonGlassCard(accent = NeonPurple) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NeonIconTile(Icons.Filled.GridView, NeonPurple, size = 42.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Yeni tasarımı kullan", color = NeonText, fontWeight = FontWeight.Bold)
                            Text(
                                if (redesignedUi) "Panel, Kitaplık ve Temanı Oluştur için neon glass arayüzü aktif"
                                else "Klasik ekran düzeni aktif",
                                color = NeonMutedText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = redesignedUi, onCheckedChange = onRedesignedUiChange)
                    }
                }
            }
            item {
                NeonGlassCard(accent = NeonCyan) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        NeonSectionHeader(
                            title = sectionTitle,
                            subtitle = sectionSubtitle,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                        tools.forEachIndexed { index, tool ->
                            NeonToolRow(
                                title = tool.title,
                                description = tool.description,
                                icon = tool.icon,
                                accent = tool.accent,
                                badge = tool.badge,
                                onClick = tool.onClick,
                                enabled = tool.enabled,
                            )
                            if (index < tools.lastIndex) NeonDivider(Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NeonScreenBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(NeonBackground, NeonBackgroundMid, Color(0xFF17113A)),
                ),
            )
            .drawBehind {
                // Low-opacity color pools create the neon ambience without an image dependency.
                drawCircle(NeonPurple.copy(alpha = 0.11f), radius = size.minDimension * 0.58f, center = androidx.compose.ui.geometry.Offset(size.width * 0.94f, 0f))
                drawCircle(NeonCyan.copy(alpha = 0.07f), radius = size.minDimension * 0.44f, center = androidx.compose.ui.geometry.Offset(0f, size.height * 0.82f))
            },
    ) {
        content()
    }
}

@Composable
private fun NeonHeroHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    statusLabel: String? = null,
    statusColor: Color = NeonGreen,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        NeonIconTile(icon = icon, accent = NeonPurple, size = 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = NeonText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, color = NeonMutedText, style = MaterialTheme.typography.bodySmall)
        }
        if (!statusLabel.isNullOrBlank()) NeonBadge(statusLabel, statusColor)
    }
}

@Composable
private fun NeonIconTile(icon: ImageVector, accent: Color, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(15.dp))
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.09f)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.48f))
    }
}

@Composable
private fun NeonBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.40f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun NeonMetricsRow(metrics: List<NeonMetric>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        metrics.take(3).forEach { metric ->
            NeonGlassCard(modifier = Modifier.weight(1f), accent = metric.accent) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(metric.value, color = metric.accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(metric.label, color = NeonMutedText, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun NeonActionGrid(actions: List<NeonQuickAction>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(2).forEach { rowActions ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowActions.forEach { action ->
                    NeonQuickActionCard(
                        title = action.title,
                        subtitle = action.subtitle,
                        icon = action.icon,
                        accent = action.accent,
                        onClick = action.onClick,
                        enabled = action.enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowActions.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NeonLibraryRow(item: NeonLibraryItem, padded: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (padded) Modifier.padding(13.dp) else Modifier.padding(horizontal = 13.dp, vertical = 10.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NeonIconTile(item.icon, item.accent, size = 42.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, color = NeonText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, color = NeonMutedText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!item.badge.isNullOrBlank()) NeonBadge(item.badge, item.accent)
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = NeonMutedText)
    }
}

@Composable
private fun NeonBuilderRow(section: NeonBuilderSection) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = section.onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NeonIconTile(section.icon, section.accent, size = 42.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(section.title, color = NeonText, fontWeight = FontWeight.SemiBold)
            Text(section.description, color = NeonMutedText, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(section.selectedValue ?: "Seç", color = section.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = NeonMutedText, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun NeonDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFF4D4E82).copy(alpha = 0.42f)),
    )
}

private fun defaultNeonMetrics() = listOf(
    NeonMetric("12", "Tema", NeonPurple),
    NeonMetric("03", "Taslak", NeonCyan),
    NeonMetric("100%", "Uyum", NeonGreen),
)

private fun defaultNeonPanelActions() = listOf(
    NeonQuickAction("Kitaplığı aç", "Temalarını görüntüle", Icons.Filled.FolderOpen, NeonCyan),
    NeonQuickAction("Tema oluştur", "Yeni bir çalışma başlat", Icons.Filled.AutoAwesome, NeonPurple),
    NeonQuickAction("İçe aktar", "MTZ veya yedek ekle", Icons.Filled.CloudUpload, NeonBlue),
    NeonQuickAction("Araçlar", "Gelişmiş yardımcılar", Icons.Filled.Build, NeonOrange),
)

private fun defaultNeonTools() = listOf(
    NeonToolItem("Tema çevirisi", "Metinleri hızlıca yerelleştir", Icons.Filled.Translate, NeonCyan),
    NeonToolItem("Duvar kâğıdı", "Ana ekran ve kilit ekranını düzenle", Icons.Filled.Image, NeonPurple),
    NeonToolItem("Yazı tipleri", "Tema yazı tiplerini yönet", Icons.Filled.FontDownload, NeonPink),
    NeonToolItem("Zil sesleri", "Ses bileşenlerini içe aktar", Icons.Filled.MusicNote, NeonOrange),
    NeonToolItem("Widget galerisi", "Widget kaynaklarını incele", Icons.Filled.Widgets, NeonGreen),
    NeonToolItem("Tema koruması", "Tema kayıtlarını güvenceye al", Icons.Filled.Security, NeonBlue),
    NeonToolItem("Görünüm ayarları", "Stüdyo deneyimini özelleştir", Icons.Filled.Settings, NeonViolet),
)
