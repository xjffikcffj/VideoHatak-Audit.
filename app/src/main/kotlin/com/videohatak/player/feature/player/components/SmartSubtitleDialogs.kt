package com.videohatak.player.feature.player.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.core.subtitle.translation.BackendProxyConfig
import com.videohatak.player.core.subtitle.translation.LanguageModelResourceState
import com.videohatak.player.core.subtitle.translation.OfflineModelDownloadStatus
import com.videohatak.player.core.subtitle.translation.SubtitleSourceMode
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationLanguage
import com.videohatak.player.core.subtitle.translation.TranslationEngineMode
import com.videohatak.player.core.subtitle.translation.TranslationJobStatus
import com.videohatak.player.core.subtitle.translation.VoskModelCatalog
import java.util.Locale

@Composable
fun SubtitleCueEditorDialog(
    track: SubtitleTrack,
    onUpdateCueText: (cueIndex: Int, newText: String) -> Unit,
    onExportTrack: (SubtitleTrack, SubtitleFormat) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    var searchFilter by remember { mutableStateOf("") }
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    var editingDraft by remember { mutableStateOf("") }

    val filteredIndexedCues = remember(track.cues, searchFilter) {
        val q = searchFilter.trim()
        track.cues.mapIndexedNotNull { idx, cue ->
            if (q.isEmpty() || cue.text.contains(q, ignoreCase = true)) {
                idx to cue
            } else {
                null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = {
            Column {
                Text("تعديل نصوص الترجمة محليًا", fontWeight = FontWeight.Bold)
                Text(
                    text = "${track.name} • يتم الحفظ داخل التطبيق دون المساس بالملف الأصلي",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.onSurfaceMuted
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchFilter,
                    onValueChange = { searchFilter = it },
                    label = { Text("ابحث داخل جمل الترجمة...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.border,
                        focusedLabelColor = palette.accent,
                        focusedTextColor = palette.onSurface,
                        unfocusedTextColor = palette.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onExportTrack(track, SubtitleFormat.SRT) },
                        border = BorderStroke(1.dp, palette.accent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("تصدير SRT", color = palette.accent, style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { onExportTrack(track, SubtitleFormat.WEBVTT) },
                        border = BorderStroke(1.dp, palette.accent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("تصدير VTT", color = palette.accent, style = MaterialTheme.typography.labelMedium)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 230.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(filteredIndexedCues, key = { _, item -> item.first }) { _, pair ->
                        val actualIdx = pair.first
                        val cue = pair.second
                        val isEditing = editingIdx == actualIdx

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = palette.surfaceElevated,
                            border = BorderStroke(0.75.dp, if (isEditing) palette.accent else palette.border)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "#${cue.index.takeIf { it > 0 } ?: (actualIdx + 1)} • ${formatCueTimestampShort(cue.startTimeMs)} → ${formatCueTimestampShort(cue.endTimeMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.accent,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (!isEditing) {
                                        TextButton(
                                            onClick = {
                                                editingIdx = actualIdx
                                                editingDraft = cue.text
                                            }
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = null, tint = palette.accent, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("تعديل", color = palette.accent, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                if (isEditing) {
                                    OutlinedTextField(
                                        value = editingDraft,
                                        onValueChange = { editingDraft = it },
                                        maxLines = 4,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = palette.accent,
                                            unfocusedBorderColor = palette.border,
                                            focusedTextColor = palette.onSurface,
                                            unfocusedTextColor = palette.onSurface
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                if (editingDraft.isNotBlank()) {
                                                    onUpdateCueText(actualIdx, editingDraft)
                                                }
                                                editingIdx = null
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = palette.accent,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("حفظ التعديل")
                                        }
                                        TextButton(onClick = { editingIdx = null }) {
                                            Text("إلغاء", color = palette.onSurfaceMuted)
                                        }
                                    }
                                } else {
                                    Text(
                                        text = cue.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق", color = palette.accent, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartSubtitleTranslationDialog(
    availableTracks: List<SubtitleTrack>,
    selectedSourceTrack: SubtitleTrack?,
    proxyUrl: String,
    translationStatus: TranslationJobStatus,
    onProxyUrlChange: (String) -> Unit,
    onStartTranslation: (
        sourceMode: SubtitleSourceMode,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage,
        sourceTrack: SubtitleTrack?,
        glossaryText: String
    ) -> Unit,
    onCancelTranslation: () -> Unit,
    onUpdateTranslatedCue: (positionIndex: Int, newText: String) -> Unit,
    onSaveAndAttach: (trackTitle: String, targetLanguage: SubtitleTranslationLanguage, format: SubtitleFormat, asSecondary: Boolean) -> Unit,
    onExportTranslated: (String, SubtitleFormat) -> Unit,
    onDismiss: () -> Unit,
    engineMode: TranslationEngineMode = TranslationEngineMode.OFFLINE_ON_DEVICE,
    onEngineModeChange: (TranslationEngineMode) -> Unit = {},
    offlineModelStates: Map<SubtitleTranslationLanguage, LanguageModelResourceState> = emptyMap(),
    detectedAudioLanguage: SubtitleTranslationLanguage? = null,
    onDownloadSpeechModel: (SubtitleTranslationLanguage) -> Unit = {},
    onCancelSpeechDownload: (SubtitleTranslationLanguage) -> Unit = {},
    onDeleteSpeechModel: (SubtitleTranslationLanguage) -> Unit = {},
    onDownloadTranslationModel: (SubtitleTranslationLanguage) -> Unit = {},
    onDeleteTranslationModel: (SubtitleTranslationLanguage) -> Unit = {},
    onDownloadRequiredPair: (
        sourceMode: SubtitleSourceMode,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage
    ) -> Unit = { _, _, _ -> }
) {
    val palette = LocalVideoHatakPalette.current

    var sourceMode by remember {
        mutableStateOf(
            if (selectedSourceTrack != null || availableTracks.isNotEmpty()) {
                SubtitleSourceMode.EXISTING_SUBTITLE
            } else {
                SubtitleSourceMode.VIDEO_AUDIO_EXTRACTION
            }
        )
    }
    var activeTrack by remember(selectedSourceTrack, availableTracks) {
        mutableStateOf(selectedSourceTrack ?: availableTracks.firstOrNull())
    }
    var sourceLanguage by remember { mutableStateOf(SubtitleTranslationLanguage.AUTO) }
    var targetLanguage by remember { mutableStateOf(SubtitleTranslationLanguage.ARABIC) }
    var glossaryText by remember { mutableStateOf("") }
    var showModelManagerList by remember { mutableStateOf(false) }
    var showProxyField by remember(proxyUrl) { mutableStateOf(proxyUrl.isBlank()) }
    var draftProxyUrl by remember(proxyUrl) { mutableStateOf(proxyUrl) }
    var assignAsSecondary by remember { mutableStateOf(false) }

    val effectiveSourceForReadiness = if (
        sourceMode == SubtitleSourceMode.VIDEO_AUDIO_EXTRACTION &&
        sourceLanguage == SubtitleTranslationLanguage.AUTO &&
        detectedAudioLanguage != null
    ) {
        detectedAudioLanguage
    } else {
        sourceLanguage
    }

    val selectedSpeechState = if (effectiveSourceForReadiness != SubtitleTranslationLanguage.AUTO) {
        offlineModelStates[effectiveSourceForReadiness]
            ?: LanguageModelResourceState(language = effectiveSourceForReadiness)
    } else {
        null
    }
    val selectedTargetState = offlineModelStates[targetLanguage]
        ?: LanguageModelResourceState(language = targetLanguage)

    val needsSpeechModel = engineMode == TranslationEngineMode.OFFLINE_ON_DEVICE &&
        sourceMode == SubtitleSourceMode.VIDEO_AUDIO_EXTRACTION
    val speechMissing = needsSpeechModel &&
        (selectedSpeechState == null || selectedSpeechState.speechStatus != OfflineModelDownloadStatus.READY)
    val missingMlKitCount = buildList {
        if (effectiveSourceForReadiness != targetLanguage) {
            if (effectiveSourceForReadiness != SubtitleTranslationLanguage.AUTO &&
                effectiveSourceForReadiness != SubtitleTranslationLanguage.ENGLISH
            ) {
                val st = offlineModelStates[effectiveSourceForReadiness]?.translationStatus
                if (st != OfflineModelDownloadStatus.READY) add(effectiveSourceForReadiness)
            }
            if (targetLanguage != SubtitleTranslationLanguage.ENGLISH) {
                val st = selectedTargetState.translationStatus
                if (st != OfflineModelDownloadStatus.READY && targetLanguage !in this) add(targetLanguage)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        icon = {
            Icon(
                imageVector = if (engineMode == TranslationEngineMode.OFFLINE_ON_DEVICE) {
                    Icons.Default.CloudOff
                } else {
                    Icons.Default.AutoAwesome
                },
                contentDescription = null,
                tint = palette.accent
            )
        },
        title = {
            Text(
                text = if (engineMode == TranslationEngineMode.OFFLINE_ON_DEVICE) {
                    "الترجمة الذكية المحلية (دون إنترنت)"
                } else {
                    "الترجمة الذكية عبر الخادم الوسيط"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "توليد وترجمة الحوارات مع حفظ التوقيت الدقيق محليًا دون إرسال الصوت أو النص لأي خادم خارجي، مع تحرير وتطبيق ملفات SRT/VTT.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceMuted
                )

                // Engine Mode Selector (Offline On-Device vs Optional Cloud Proxy)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TranslationEngineMode.entries.forEach { mode ->
                        val selected = engineMode == mode
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) palette.accent.copy(alpha = 0.18f) else palette.surfaceElevated,
                            border = BorderStroke(1.dp, if (selected) palette.accent else palette.border),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onEngineModeChange(mode) }
                        ) {
                            Text(
                                text = if (mode == TranslationEngineMode.OFFLINE_ON_DEVICE) {
                                    "محلي دون إنترنت"
                                } else {
                                    "خادم وسيط (اختياري)"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) palette.accent else palette.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // Source Mode Selection
                SubtitleSourceMode.entries.forEach { mode ->
                    val selected = sourceMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { sourceMode = mode }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { sourceMode = mode },
                            colors = RadioButtonDefaults.colors(selectedColor = palette.accent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.arabicTitle,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (selected) palette.accent else palette.onSurface
                            )
                            Text(
                                text = mode.arabicDescription,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.onSurfaceMuted
                            )
                        }
                    }
                }

                if (sourceMode == SubtitleSourceMode.EXISTING_SUBTITLE && availableTracks.isNotEmpty()) {
                    Text(
                        text = "المسار المصدر:",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableTracks.forEach { track ->
                            val isSelected = activeTrack?.id == track.id
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) palette.accent.copy(alpha = 0.2f) else palette.surfaceElevated,
                                border = BorderStroke(1.dp, if (isSelected) palette.accent else palette.border),
                                modifier = Modifier.clickable { activeTrack = track }
                            ) {
                                Text(
                                    text = track.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) palette.accent else palette.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                // 1) Primary Selector: Original Video / Source Language
                Text(
                    text = "١. لغة الفيديو الأصلية (المصدر):",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.accent,
                    fontWeight = FontWeight.Bold
                )
                if (detectedAudioLanguage != null) {
                    Text(
                        text = "اكتشف المشغّل وسم اللغة في مسار الصوت: ${detectedAudioLanguage.arabicName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (listOf(SubtitleTranslationLanguage.AUTO) + SubtitleTranslationLanguage.SPEECH_RECOGNITION_LANGUAGES)
                        .forEach { lang ->
                            val isSelected = sourceLanguage == lang
                            val st = offlineModelStates[lang]
                            val badge = when {
                                lang == SubtitleTranslationLanguage.AUTO -> null
                                sourceMode == SubtitleSourceMode.VIDEO_AUDIO_EXTRACTION -> {
                                    when (st?.speechStatus) {
                                        OfflineModelDownloadStatus.READY -> "جاهز صوتيًا"
                                        OfflineModelDownloadStatus.DOWNLOADING -> "جارٍ التنزيل"
                                        OfflineModelDownloadStatus.NEEDS_UPDATE -> "يحتاج تحديثًا"
                                        else -> "~${VoskModelCatalog.specFor(lang)?.approximateZipMb ?: 0} MB"
                                    }
                                }
                                else -> null
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) palette.accent.copy(alpha = 0.2f) else palette.surfaceElevated,
                                border = BorderStroke(1.dp, if (isSelected) palette.accent else palette.border),
                                modifier = Modifier.clickable { sourceLanguage = lang }
                            ) {
                                Text(
                                    text = if (badge != null) "${lang.arabicName} ($badge)" else lang.arabicName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) palette.accent else palette.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                }

                // Independent Speech Model Status Card (when Audio Mode + Offline Mode)
                if (needsSpeechModel) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = palette.surfaceElevated,
                        border = BorderStroke(0.75.dp, palette.border)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "نموذج التعرف على الكلام المحلي (Vosk ASR)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.onSurface
                                )
                            }

                            if (effectiveSourceForReadiness == SubtitleTranslationLanguage.AUTO) {
                                Text(
                                    text = "التعرف التلقائي يقرأ وسم اللغة من ملف الفيديو فقط. إذا خلا الملف من الوسم، اختر لغة الفيديو يدويًا أعلاه.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.onSurfaceMuted
                                )
                            } else if (selectedSpeechState != null) {
                                val spec = selectedSpeechState.speechSpec
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${effectiveSourceForReadiness.arabicName}: ${selectedSpeechState.speechStatus.arabicLabel} • ${selectedSpeechState.formattedSpeechSizeLabel}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (selectedSpeechState.speechStatus == OfflineModelDownloadStatus.READY) {
                                                palette.accent
                                            } else {
                                                palette.onSurface
                                            }
                                        )
                                        if (spec != null) {
                                            Text(
                                                text = "${spec.modelId} (${spec.licenseName}) • ${spec.architectureNoteArabic}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.onSurfaceMuted
                                            )
                                        }
                                    }
                                    when (selectedSpeechState.speechStatus) {
                                        OfflineModelDownloadStatus.DOWNLOADING -> {
                                            IconButton(
                                                onClick = { onCancelSpeechDownload(effectiveSourceForReadiness) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "إلغاء التنزيل",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        OfflineModelDownloadStatus.READY -> {
                                            IconButton(
                                                enabled = !selectedSpeechState.isSpeechInUse,
                                                onClick = { onDeleteSpeechModel(effectiveSourceForReadiness) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.DeleteOutline,
                                                    contentDescription = "حذف نموذج الصوت",
                                                    tint = palette.onSurfaceMuted,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        else -> {
                                            TextButton(onClick = { onDownloadSpeechModel(effectiveSourceForReadiness) }) {
                                                Icon(
                                                    Icons.Default.Download,
                                                    contentDescription = null,
                                                    tint = palette.accent,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("تنزيل", color = palette.accent, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }

                                if (selectedSpeechState.speechStatus == OfflineModelDownloadStatus.DOWNLOADING) {
                                    LinearProgressIndicator(
                                        progress = { selectedSpeechState.speechDownloadProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = palette.accent,
                                        trackColor = palette.border
                                    )
                                }
                                selectedSpeechState.speechErrorMessage?.let { err ->
                                    Text(
                                        text = err,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                spec?.accuracyCaveatArabic?.let { caveat ->
                                    Text(
                                        text = "ملاحظة دقة: $caveat",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.onSurfaceMuted
                                    )
                                }
                            }
                        }
                    }
                }

                // 2) Primary Selector: Target Translation Language (1-tap Download inside App Storage)
                Text(
                    text = "٢. لغة الترجمة (الهدف — تحميل محلي):",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.accent,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SubtitleTranslationLanguage.TARGET_LANGUAGES.forEach { lang ->
                        val isSelected = targetLanguage == lang
                        val langState = offlineModelStates[lang] ?: LanguageModelResourceState(language = lang)
                        val statusTag = when {
                            langState.isBuiltInTranslationLanguage -> "مدمجة"
                            langState.translationStatus == OfflineModelDownloadStatus.READY -> "جاهزة"
                            langState.translationStatus == OfflineModelDownloadStatus.DOWNLOADING -> "جارٍ التنزيل"
                            langState.translationStatus == OfflineModelDownloadStatus.NEEDS_UPDATE -> "تحديث"
                            else -> "غير محملة"
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) palette.accent.copy(alpha = 0.2f) else palette.surfaceElevated,
                            border = BorderStroke(1.dp, if (isSelected) palette.accent else palette.border),
                            modifier = Modifier.clickable { targetLanguage = lang }
                        ) {
                            Text(
                                text = "${lang.arabicName} • $statusTag",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) palette.accent else palette.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // Independent Text Translation Model Card (ML Kit)
                if (engineMode == TranslationEngineMode.OFFLINE_ON_DEVICE) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = palette.surfaceElevated,
                        border = BorderStroke(0.75.dp, palette.border)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "نموذج الترجمة النصية (ML Kit): ${targetLanguage.arabicName}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.onSurface
                                    )
                                    Text(
                                        text = "الحالة: ${selectedTargetState.translationStatus.arabicLabel} • ${selectedTargetState.formattedTranslationSizeLabel} داخل مجلد التطبيق",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selectedTargetState.translationStatus == OfflineModelDownloadStatus.READY) {
                                            palette.accent
                                        } else {
                                            palette.onSurfaceMuted
                                        }
                                    )
                                }
                                if (!selectedTargetState.isBuiltInTranslationLanguage) {
                                    when (selectedTargetState.translationStatus) {
                                        OfflineModelDownloadStatus.DOWNLOADING -> {
                                            Text(
                                                text = "جارٍ التنزيل...",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.accent
                                            )
                                        }
                                        OfflineModelDownloadStatus.READY -> {
                                            IconButton(
                                                enabled = !selectedTargetState.isTranslationInUse,
                                                onClick = { onDeleteTranslationModel(targetLanguage) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.DeleteOutline,
                                                    contentDescription = "حذف حزمة الترجمة",
                                                    tint = palette.onSurfaceMuted,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        else -> {
                                            TextButton(onClick = { onDownloadTranslationModel(targetLanguage) }) {
                                                Icon(
                                                    Icons.Default.Download,
                                                    contentDescription = null,
                                                    tint = palette.accent,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("تحميل", color = palette.accent, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }

                            if (selectedTargetState.translationStatus == OfflineModelDownloadStatus.DOWNLOADING) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = palette.accent,
                                    trackColor = palette.border
                                )
                            }
                            selectedTargetState.translationErrorMessage?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Text(
                                text = "ملاحظة: نماذج الترجمة النصية (ML Kit) مستقلة عن نماذج التفريغ الصوتي (Vosk)؛ لتفريغ الصوت تحتاج نموذج الصوت أيضًا.",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.onSurfaceMuted
                            )

                            if ((speechMissing && effectiveSourceForReadiness != SubtitleTranslationLanguage.AUTO) ||
                                missingMlKitCount.isNotEmpty()
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onDownloadRequiredPair(
                                            sourceMode,
                                            effectiveSourceForReadiness,
                                            targetLanguage
                                        )
                                    },
                                    border = BorderStroke(1.dp, palette.accent),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "تنزيل النماذج المطلوبة تلقائيًا في ذاكرة التطبيق",
                                        color = palette.accent,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Expandable All-Languages Model Manager (Vosk + ML Kit separated per language)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = palette.surfaceElevated,
                        border = BorderStroke(0.75.dp, palette.border)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showModelManagerList = !showModelManagerList },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "إدارة نماذج اللغات المحلية (الصوت والنص)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.onSurface
                                )
                                Icon(
                                    imageVector = if (showModelManagerList) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = palette.accent
                                )
                            }

                            if (showModelManagerList) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    color = palette.border
                                )
                                SubtitleTranslationLanguage.TARGET_LANGUAGES.forEach { lang ->
                                    val st = offlineModelStates[lang] ?: LanguageModelResourceState(language = lang)
                                    OfflineLanguageRowItem(
                                        state = st,
                                        onDownloadSpeech = { onDownloadSpeechModel(lang) },
                                        onCancelSpeech = { onCancelSpeechDownload(lang) },
                                        onDeleteSpeech = { onDeleteSpeechModel(lang) },
                                        onDownloadTranslation = { onDownloadTranslationModel(lang) },
                                        onDeleteTranslation = { onDeleteTranslationModel(lang) }
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                }

                // Glossary for Character / Series Terminology
                OutlinedTextField(
                    value = glossaryText,
                    onValueChange = { glossaryText = it },
                    label = { Text("مصطلحات ثابتة اختيارية (مثال: Stark=ستارك)") },
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.border,
                        focusedLabelColor = palette.accent,
                        focusedTextColor = palette.onSurface,
                        unfocusedTextColor = palette.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (engineMode == TranslationEngineMode.OFFLINE_ON_DEVICE) {
                    Text(
                        text = "تنبيه: يترجم المحرك المحلي كل جملة منفردة عبر ML Kit (مع حماية المصطلحات وفواصل المتحدثين) وقد تمر غير الإنجليزية عبر الإنجليزية وسيطًا.",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSurfaceMuted
                    )
                }

                // Optional Cloud Proxy Configuration (kept separate from Offline mode)
                if (engineMode == TranslationEngineMode.CLOUD_PROXY) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = palette.surfaceElevated,
                        border = BorderStroke(0.75.dp, palette.border)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "خادم الوسيط الآمن (HTTPS Backend Proxy)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.onSurface
                                )
                                TextButton(onClick = { showProxyField = !showProxyField }) {
                                    Text(if (showProxyField) "إخفاء" else "تعديل", color = palette.accent)
                                }
                            }
                            if (draftProxyUrl.isBlank()) {
                                Text(
                                    text = "الحالة: غير متصل — يتطلب هذا الوضع الاختياري ربط التطبيق بخادم وسيط خارجي (HTTPS Backend Proxy) لم يُضبط بعد.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (!BackendProxyConfig(draftProxyUrl).isConfiguredAndSecure()) {
                                Text(
                                    text = "تنبيه: يجب استخدام رابط HTTPS آمن للاتصال بخادم الترجمة.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (showProxyField) {
                                OutlinedTextField(
                                    value = draftProxyUrl,
                                    onValueChange = {
                                        draftProxyUrl = it
                                        onProxyUrlChange(it)
                                    },
                                    label = { Text("https://api.yourdomain.com") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = palette.accent,
                                        unfocusedBorderColor = palette.border,
                                        focusedTextColor = palette.onSurface,
                                        unfocusedTextColor = palette.onSurface
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "أمان التطبيق: لا تُحفظ أي مفاتيح API داخل التطبيق أو APK، وتُدار المفاتيح على الخادم الخلفي فقط.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.onSurfaceMuted
                                )
                            }
                        }
                    }
                }

                // Live Background Job Status / Progress / Review
                when (translationStatus) {
                    is TranslationJobStatus.Running -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = palette.accent.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, palette.accent)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = translationStatus.phaseLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.accent
                                )
                                LinearProgressIndicator(
                                    progress = { translationStatus.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = palette.accent,
                                    trackColor = palette.border
                                )
                                OutlinedButton(
                                    onClick = onCancelTranslation,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                                ) {
                                    Text("إلغاء العملية", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    is TranslationJobStatus.Error -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = translationStatus.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Button(
                                    onClick = {
                                        onStartTranslation(
                                            sourceMode,
                                            sourceLanguage,
                                            targetLanguage,
                                            activeTrack,
                                            glossaryText
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.accent,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Text("إعادة المحاولة")
                                }
                            }
                        }
                    }

                    is TranslationJobStatus.Completed -> {
                        HorizontalDivider(color = palette.border)
                        Text(
                            text = translationStatus.summaryMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.accent
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { assignAsSecondary = !assignAsSecondary },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !assignAsSecondary,
                                onClick = { assignAsSecondary = false },
                                colors = RadioButtonDefaults.colors(selectedColor = palette.accent)
                            )
                            Text("ترجمة أساسية", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                            RadioButton(
                                selected = assignAsSecondary,
                                onClick = { assignAsSecondary = true },
                                colors = RadioButtonDefaults.colors(selectedColor = palette.accent)
                            )
                            Text("ترجمة ثانوية (مزدوجة)", style = MaterialTheme.typography.labelMedium)
                        }

                        // Review & Edit Original vs Translated before saving
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 180.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(translationStatus.pairs) { idx, pair ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = palette.surfaceElevated,
                                    border = BorderStroke(0.5.dp, palette.border)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        if (pair.originalText.isNotBlank()) {
                                            Text(
                                                text = pair.originalText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.onSurfaceMuted
                                            )
                                        }
                                        OutlinedTextField(
                                            value = pair.translatedText,
                                            onValueChange = { onUpdateTranslatedCue(idx, it) },
                                            maxLines = 3,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = palette.accent,
                                                unfocusedBorderColor = palette.border,
                                                focusedTextColor = palette.onSurface,
                                                unfocusedTextColor = palette.onSurface
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        val defaultTitle = "ترجمة ذكية (${translationStatus.targetLanguage.arabicName})"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onSaveAndAttach(defaultTitle, translationStatus.targetLanguage, SubtitleFormat.SRT, assignAsSecondary)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.accent,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("حفظ وتطبيق", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = {
                                    val exported = com.videohatak.player.core.subtitle.parser.SubtitleParser.exportSrt(
                                        translationStatus.pairs.map { it.toTranslatedCue() }
                                    )
                                    onExportTranslated(exported, SubtitleFormat.SRT)
                                },
                                border = BorderStroke(1.dp, palette.accent),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("تصدير SRT", color = palette.accent)
                            }
                        }
                    }

                    else -> Unit
                }
            }
        },
        confirmButton = {
            if (translationStatus !is TranslationJobStatus.Running) {
                Button(
                    onClick = {
                        onStartTranslation(
                            sourceMode,
                            sourceLanguage,
                            targetLanguage,
                            activeTrack,
                            glossaryText
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (engineMode == TranslationEngineMode.OFFLINE_ON_DEVICE) {
                            "بدء المعالجة المحلية"
                        } else {
                            "بدء الترجمة السحابية"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق", color = palette.onSurfaceMuted)
            }
        }
    )
}

@Composable
private fun OfflineLanguageRowItem(
    state: LanguageModelResourceState,
    onDownloadSpeech: () -> Unit,
    onCancelSpeech: () -> Unit,
    onDeleteSpeech: () -> Unit,
    onDownloadTranslation: () -> Unit,
    onDeleteTranslation: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = palette.surface,
        border = BorderStroke(0.5.dp, palette.border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${state.language.arabicName} (${state.language.nativeName})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = palette.onSurface
            )

            // Speech Recognition Row (Vosk)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "صوت Vosk: ${state.speechStatus.arabicLabel} (${state.formattedSpeechSizeLabel})",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.speechStatus == OfflineModelDownloadStatus.READY) palette.accent else palette.onSurfaceMuted,
                    modifier = Modifier.weight(1f)
                )
                when (state.speechStatus) {
                    OfflineModelDownloadStatus.DOWNLOADING -> {
                        TextButton(onClick = onCancelSpeech) {
                            Text("إلغاء", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    OfflineModelDownloadStatus.READY -> {
                        TextButton(enabled = !state.isSpeechInUse, onClick = onDeleteSpeech) {
                            Text("حذف", color = palette.onSurfaceMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    else -> {
                        TextButton(onClick = onDownloadSpeech) {
                            Text("تنزيل", color = palette.accent, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (state.speechStatus == OfflineModelDownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { state.speechDownloadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = palette.accent,
                    trackColor = palette.border
                )
            }

            // Text Translation Row (ML Kit)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ترجمة ML Kit: ${state.translationStatus.arabicLabel} (${state.formattedTranslationSizeLabel})",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.translationStatus == OfflineModelDownloadStatus.READY) palette.accent else palette.onSurfaceMuted,
                    modifier = Modifier.weight(1f)
                )
                if (!state.isBuiltInTranslationLanguage) {
                    when (state.translationStatus) {
                        OfflineModelDownloadStatus.DOWNLOADING -> {
                            Text("جارٍ التنزيل...", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                        }
                        OfflineModelDownloadStatus.READY -> {
                            TextButton(enabled = !state.isTranslationInUse, onClick = onDeleteTranslation) {
                                Text("حذف", color = palette.onSurfaceMuted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        else -> {
                            TextButton(onClick = onDownloadTranslation) {
                                Text("تنزيل", color = palette.accent, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatCueTimestampShort(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
