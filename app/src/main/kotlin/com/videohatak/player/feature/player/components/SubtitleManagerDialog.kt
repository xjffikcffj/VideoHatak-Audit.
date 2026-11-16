package com.videohatak.player.feature.player.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.subtitle.model.SubtitleStyleConfig
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import java.util.Locale

@Composable
fun SubtitleManagerDialog(
    availableTracks: List<SubtitleTrack>,
    primaryTrack: SubtitleTrack?,
    secondaryTrack: SubtitleTrack?,
    primaryStyle: SubtitleStyleConfig,
    secondaryStyle: SubtitleStyleConfig,
    onSelectPrimaryTrack: (SubtitleTrack?) -> Unit,
    onSelectSecondaryTrack: (SubtitleTrack?) -> Unit,
    onImportSubtitle: () -> Unit,
    onChangePrimaryFontSize: (Float) -> Unit,
    onChangeSecondaryFontSize: (Float) -> Unit,
    onDismiss: () -> Unit,
    areSubtitlesVisible: Boolean = true,
    onToggleSubtitlesVisible: (Boolean) -> Unit = {},
    primaryOffsetMs: Long = 0L,
    onAdjustPrimaryOffsetMs: (Long) -> Unit = {},
    onResetPrimaryOffset: () -> Unit = {},
    secondaryOffsetMs: Long = 0L,
    onAdjustSecondaryOffsetMs: (Long) -> Unit = {},
    onResetSecondaryOffset: () -> Unit = {},
    onEditTrackLocally: (SubtitleTrack) -> Unit = {},
    onRemoveTrack: (SubtitleTrack) -> Unit = {},
    onOpenSmartTranslation: () -> Unit = {}
) {
    val palette = LocalVideoHatakPalette.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = {
            Text(
                text = "إدارة الترجمات المزدوجة",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = palette.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Visibility Toggle
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = palette.surfaceElevated,
                    border = BorderStroke(0.75.dp, palette.border)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "عرض الترجمة على الفيديو",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.onSurface
                            )
                            Text(
                                text = if (areSubtitlesVisible) "الترجمة ظاهرة حاليًا" else "الترجمة مخفية مؤقتًا",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.onSurfaceMuted
                            )
                        }
                        Switch(
                            checked = areSubtitlesVisible,
                            onCheckedChange = onToggleSubtitlesVisible,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = palette.accent,
                                checkedTrackColor = palette.accent.copy(alpha = 0.35f)
                            )
                        )
                    }
                }

                // Import External File (SRT / VTT; basic plain-text fallback for ASS/SSA)
                Button(
                    onClick = onImportSubtitle,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تحميل ملف ترجمة (SRT / VTT)", fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "الصيغ المعتمدة: SRT و WebVTT (تُستخلص النصوص الأساسية فقط من ASS/SSA دون تنسيقات أو مؤثرات).",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.onSurfaceMuted
                )

                // Smart AI Translation Button
                OutlinedButton(
                    onClick = onOpenSmartTranslation,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, palette.accent)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("الترجمة الذكية (محلي دون إنترنت)", color = palette.accent, fontWeight = FontWeight.Bold)
                }

                // Offline Local Editor for Active Primary Track
                if (primaryTrack != null && primaryTrack.cues.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { onEditTrackLocally(primaryTrack) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.75.dp, palette.border)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تعديل جمل وتصدير \"${primaryTrack.name}\"", color = palette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                HorizontalDivider(color = palette.border)

                Text(
                    text = "الترجمة الأساسية (السفلية)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent
                )

                TrackSelectionRow(
                    title = "بدون ترجمة",
                    isSelected = primaryTrack == null,
                    onClick = { onSelectPrimaryTrack(null) },
                    onRemove = null
                )

                availableTracks.forEach { track ->
                    TrackSelectionRow(
                        title = "${track.name} (${track.cues.size})",
                        isSelected = primaryTrack?.id == track.id,
                        onClick = { onSelectPrimaryTrack(track) },
                        onRemove = { onRemoveTrack(track) }
                    )
                }

                Text(
                    text = "حجم خط الأساسية: ${primaryStyle.fontSizeSp.toInt()} sp",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceMuted
                )
                Slider(
                    value = primaryStyle.fontSizeSp,
                    onValueChange = onChangePrimaryFontSize,
                    valueRange = 12f..32f,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent,
                        inactiveTrackColor = palette.border
                    )
                )

                SubtitleSyncRow(
                    label = "مزامنة الأساسية",
                    offsetMs = primaryOffsetMs,
                    onDecrease = { onAdjustPrimaryOffsetMs(-500L) },
                    onReset = onResetPrimaryOffset,
                    onIncrease = { onAdjustPrimaryOffsetMs(500L) }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = palette.border
                )

                Text(
                    text = "الترجمة الثانوية (العلوية)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent
                )

                TrackSelectionRow(
                    title = "بدون ترجمة ثانوية",
                    isSelected = secondaryTrack == null,
                    onClick = { onSelectSecondaryTrack(null) },
                    onRemove = null
                )

                availableTracks.forEach { track ->
                    TrackSelectionRow(
                        title = "${track.name} (${track.cues.size})",
                        isSelected = secondaryTrack?.id == track.id,
                        onClick = { onSelectSecondaryTrack(track) },
                        onRemove = null
                    )
                }

                Text(
                    text = "حجم خط الثانوية: ${secondaryStyle.fontSizeSp.toInt()} sp",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceMuted
                )
                Slider(
                    value = secondaryStyle.fontSizeSp,
                    onValueChange = onChangeSecondaryFontSize,
                    valueRange = 12f..32f,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent,
                        inactiveTrackColor = palette.border
                    )
                )

                SubtitleSyncRow(
                    label = "مزامنة الثانوية",
                    offsetMs = secondaryOffsetMs,
                    onDecrease = { onAdjustSecondaryOffsetMs(-500L) },
                    onReset = onResetSecondaryOffset,
                    onIncrease = { onAdjustSecondaryOffsetMs(500L) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق", color = palette.accent, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun SubtitleSyncRow(
    label: String,
    offsetMs: Long,
    onDecrease: () -> Unit,
    onReset: () -> Unit,
    onIncrease: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    val secText = String.format(Locale.US, "%+.1f ث", offsetMs / 1000f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label: $secText",
            style = MaterialTheme.typography.labelMedium,
            color = palette.onSurface
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onDecrease) {
                Text("-0.5 ث", color = palette.accent, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onReset) {
                Text("0", color = palette.onSurfaceMuted, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onIncrease) {
                Text("+0.5 ث", color = palette.accent, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TrackSelectionRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?
) {
    val palette = LocalVideoHatakPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) palette.accent else palette.onSurface,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(18.dp)
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "حذف المسار",
                    tint = palette.onSurfaceMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
