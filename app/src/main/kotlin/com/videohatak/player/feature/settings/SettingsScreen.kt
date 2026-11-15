package com.videohatak.player.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videohatak.player.core.designsystem.theme.AppThemeId
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.designsystem.theme.paletteForTheme
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.ResumeMode
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun SettingsScreen(
    selectedTheme: AppThemeId,
    persistedTheme: AppThemeId = selectedTheme,
    isThemePreviewActive: Boolean = false,
    themeMode: ThemeMode,
    playbackMode: PlaybackMode,
    resumeMode: ResumeMode,
    vaultVideoCount: Int,
    trashVideoCount: Int = 0,
    onSelectTheme: (AppThemeId) -> Unit,
    onApplyThemePreview: () -> Unit = {},
    onCancelThemePreview: () -> Unit = {},
    onSelectThemeMode: (ThemeMode) -> Unit,
    onSelectPlaybackMode: (PlaybackMode) -> Unit,
    onSelectResumeMode: (ResumeMode) -> Unit,
    onOpenVault: () -> Unit,
    onOpenTrashBin: () -> Unit = {},
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Section 1: 8 Luxury Themes Live Preview & Apply Selector
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Palette,
                title = "المظاهر الفاخرة (8 ثيمات)",
                subtitle = "اضغط على أي مظهر لمعاينته حيًا ثم اضغط «تطبيق الثيم» لاعتماده"
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (isThemePreviewActive) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.surfaceElevated,
                    border = BorderStroke(1.25.dp, palette.accent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "معاينة حية: ${selectedTheme.displayName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = palette.accent
                            )
                            Text(
                                text = "لم يتم حفظ الثيم بعد — اضغط تطبيق لاعتماده",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.onSurfaceMuted
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = onCancelThemePreview,
                                border = BorderStroke(1.dp, palette.border),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("إلغاء", color = palette.onSurface, style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = onApplyThemePreview,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.accent,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("تطبيق الثيم", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            val themePairs = AppThemeId.entries.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                themePairs.forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { themeId ->
                            ThemePreviewTile(
                                themeId = themeId,
                                isSelected = themeId == selectedTheme,
                                isPreviewing = isThemePreviewActive && themeId == selectedTheme && themeId != persistedTheme,
                                onClick = { onSelectTheme(themeId) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Section 2: Light / Dark Mode Control (moved inside Settings)
        item {
            SettingsSectionHeader(
                icon = Icons.Default.DarkMode,
                title = "سطوع الواجهة",
                subtitle = "التبديل بين الوضع الليلي والنهاري أو التلقائي"
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeModeChip(
                    label = "تلقائي",
                    icon = Icons.Default.BrightnessAuto,
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { onSelectThemeMode(ThemeMode.SYSTEM) },
                    modifier = Modifier.weight(1f)
                )
                ThemeModeChip(
                    label = "داكن",
                    icon = Icons.Default.DarkMode,
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { onSelectThemeMode(ThemeMode.DARK) },
                    modifier = Modifier.weight(1f)
                )
                ThemeModeChip(
                    label = "فاتح",
                    icon = Icons.Default.LightMode,
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { onSelectThemeMode(ThemeMode.LIGHT) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Section 3: 3 Resume Playback Options
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Replay,
                title = "سلوك استئناف التشغيل",
                subtitle = "حدد كيف يبدأ الفيديو عند فتحه مرة أخرى"
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                border = BorderStroke(0.75.dp, palette.border.copy(alpha = 0.7f))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ResumeMode.entries.forEach { mode ->
                        SettingsRadioOptionRow(
                            title = mode.titleArabic,
                            subtitle = mode.descriptionArabic,
                            selected = resumeMode == mode,
                            onClick = { onSelectResumeMode(mode) }
                        )
                    }
                }
            }
        }

        // Section 4: 5 Mutually Exclusive Playback Modes
        item {
            SettingsSectionHeader(
                icon = Icons.Default.PlayCircle,
                title = "أوضاع التشغيل التلقائي",
                subtitle = "وضع واحد نشط في كل مرة — يُطبّق على المشغل وقوائم التشغيل"
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                border = BorderStroke(0.75.dp, palette.border.copy(alpha = 0.7f))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PlaybackMode.entries.forEach { mode ->
                        SettingsRadioOptionRow(
                            title = mode.titleArabic,
                            subtitle = mode.descriptionArabic,
                            selected = playbackMode == mode,
                            onClick = { onSelectPlaybackMode(mode) }
                        )
                    }
                }
            }
        }

        // Section 5: Discreet Secure Vault Entry
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Lock,
                title = "الخصوصية والأمان",
                subtitle = "مساحة معزولة ومشفرة لحفظ مقاطع الفيديو الخاصة"
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenVault),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                border = BorderStroke(1.dp, palette.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = palette.accent
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "الخزنة الخاصة المشفرة",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.onSurface
                        )
                        Text(
                            text = "AES-256-GCM • محمية بالبصمة ($vaultVideoCount فيديو)",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurfaceMuted
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = null,
                        tint = palette.accent
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Trash Bin Entry
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenTrashBin),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                border = BorderStroke(1.dp, palette.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = palette.accent
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "سلة المحذوفات",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.onSurface
                        )
                        Text(
                            text = if (trashVideoCount == 0) "السلة فارغة" else "$trashVideoCount فيديو في انتظار الاستعادة أو الحذف النهائي",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurfaceMuted
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = null,
                        tint = palette.accent
                    )
                }
            }
        }

        // Section 6: Backup & Restore (JSON + SAF)
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Backup,
                title = "النسخ الاحتياطي والاستعادة",
                subtitle = "تصدير واستيراد الإعدادات والترجمة وقوائم التشغيل بصيغة JSON آمنة"
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                border = BorderStroke(0.75.dp, palette.border.copy(alpha = 0.7f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "تشمل النسخة الاحتياطية: المظهر، أوضاع التشغيل والاستئناف، إعدادات الترجمة، مواضع التوقف المحفوظة، قوائم التشغيل ومهلة قفل الخزنة (مع بصمة SHA-256 للتحقق من سلامة الملف من التلف). لا تُصدّر الفيديوهات نفسها ولا سجل سلة المحذوفات ولا رموز PIN أو مفاتيح وملفات الخزنة المشفرة.",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSurfaceMuted
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onExportBackup,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.accent,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تصدير JSON", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onImportBackup,
                            border = BorderStroke(1.dp, palette.accent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("استيراد JSON", color = palette.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    val palette = LocalVideoHatakPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = palette.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = palette.onSurfaceMuted
            )
        }
    }
}

@Composable
private fun ThemePreviewTile(
    themeId: AppThemeId,
    isSelected: Boolean,
    isPreviewing: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val previewPalette = paletteForTheme(themeId, forceLight = themeId.isLightTheme)

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = previewPalette.background),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) previewPalette.accent else previewPalette.border.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(previewPalette.topBarGradientStart, previewPalette.surface)
                    )
                )
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(previewPalette.accent)
                    )
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(previewPalette.secondaryAccent)
                    )
                }

                if (isPreviewing) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = previewPalette.accent.copy(alpha = 0.22f)
                    ) {
                        Text(
                            text = "معاينة",
                            style = MaterialTheme.typography.labelSmall,
                            color = previewPalette.accent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                } else if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "نشط",
                        tint = previewPalette.accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Mini live Wavy SeekBar preview in the theme's signature glow color
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
            ) {
                val midY = size.height / 2f
                val progressX = size.width * 0.65f
                val playedPath = Path()
                val unplayedPath = Path()
                val step = 2f
                var x = 0f
                while (x <= size.width) {
                    val y = midY + sin((x / size.width) * 10f * PI).toFloat() * 3.2f
                    if (x <= progressX) {
                        if (x == 0f) playedPath.moveTo(x, y) else playedPath.lineTo(x, y)
                    } else {
                        if (unplayedPath.isEmpty) unplayedPath.moveTo(x, y) else unplayedPath.lineTo(x, y)
                    }
                    x += step
                }
                drawPath(
                    path = unplayedPath,
                    color = previewPalette.seekBarUnplayed,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = playedPath,
                    color = previewPalette.seekBarGlow,
                    style = Stroke(width = 6f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = playedPath,
                    brush = Brush.horizontalGradient(
                        listOf(previewPalette.seekBarPlayedStart, previewPalette.seekBarPlayedEnd)
                    ),
                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                )
                drawCircle(
                    color = previewPalette.seekBarThumb,
                    radius = 5f,
                    center = Offset(progressX, midY)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = themeId.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = previewPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = themeId.arabicTagline,
                style = MaterialTheme.typography.labelSmall,
                color = previewPalette.accent,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ThemeModeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) palette.accent.copy(alpha = 0.16f) else palette.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 0.75.dp,
            color = if (selected) palette.accent else palette.border.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) palette.accent else palette.onSurfaceMuted,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) palette.accent else palette.onSurface
            )
        }
    }
}

@Composable
private fun SettingsRadioOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = palette.accent,
                unselectedColor = palette.onSurfaceMuted
            )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) palette.accent else palette.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = palette.onSurfaceMuted
            )
        }
    }
}
