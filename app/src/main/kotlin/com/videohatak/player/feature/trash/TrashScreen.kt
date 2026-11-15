package com.videohatak.player.feature.trash

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videohatak.player.core.data.repository.MediaExistenceResult
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.trash.model.TrashItem
import com.videohatak.player.feature.settings.PermanentDeleteConfirmDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrashScreen(
    trashItems: List<TrashItem>,
    trashError: String? = null,
    existenceMap: Map<Long, MediaExistenceResult> = emptyMap(),
    missingItemPendingRemoval: TrashItem? = null,
    onRefreshStatus: () -> Unit,
    onDismissError: () -> Unit,
    onRestoreItem: (TrashItem) -> Unit,
    onConfirmRemoveMissingRecord: (TrashItem) -> Unit,
    onDismissMissingRemoval: () -> Unit,
    onPermanentDeleteItem: (TrashItem) -> Unit,
    onEmptyTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current
    var itemPendingPermanentDelete by remember { mutableStateOf<TrashItem?>(null) }
    var confirmEmptyAll by remember { mutableStateOf(false) }

    val pendingDelete = itemPendingPermanentDelete
    if (pendingDelete != null) {
        PermanentDeleteConfirmDialog(
            videoTitle = pendingDelete.title.ifBlank { pendingDelete.displayName },
            isBulk = false,
            onConfirm = {
                itemPendingPermanentDelete = null
                onPermanentDeleteItem(pendingDelete)
            },
            onDismiss = { itemPendingPermanentDelete = null }
        )
    }

    if (confirmEmptyAll) {
        PermanentDeleteConfirmDialog(
            videoTitle = "${trashItems.size} فيديو",
            isBulk = true,
            onConfirm = {
                confirmEmptyAll = false
                onEmptyTrash()
            },
            onDismiss = { confirmEmptyAll = false }
        )
    }

    if (missingItemPendingRemoval != null) {
        val label = missingItemPendingRemoval.title.ifBlank { missingItemPendingRemoval.displayName }
        AlertDialog(
            onDismissRequest = onDismissMissingRemoval,
            containerColor = palette.surface,
            titleContentColor = palette.onSurface,
            textContentColor = palette.onSurface,
            icon = {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text("الملف غير موجود على الجهاز", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "لم يعد ملف الفيديو \"$label\" موجودًا في ذاكرة الجهاز، لذا لا يمكن استعادته. هل تريد إزالة السجل من سلة المحذوفات أم الإبقاء عليه للمراجعة؟",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = { onConfirmRemoveMissingRecord(missingItemPendingRemoval) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("إزالة السجل", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissMissingRemoval) {
                    Text("الإبقاء في السلة", color = palette.accent)
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Section Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = palette.surface,
            border = BorderStroke(0.5.dp, palette.border.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "سلة المحذوفات",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.onSurface
                    )
                    val totalMb = trashItems.sumOf { it.sizeBytes } / (1024.0 * 1024.0)
                    Text(
                        text = String.format(Locale.US, "%d فيديو • %.1f ميجابايت", trashItems.size, totalMb),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.onSurfaceMuted
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onRefreshStatus) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "تحديث وفحص حالة الملفات",
                            tint = palette.accent
                        )
                    }

                    if (trashItems.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { confirmEmptyAll = true },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "إفراغ السلة",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (!trashError.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = trashError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissError) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إغلاق",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        if (trashItems.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = palette.accent.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, palette.border)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "سلة المحذوفات فارغة",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = palette.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "يمكنك نقل أي فيديو إلى سلة المحذوفات من قائمة خيارات الفيديو أو نافذة معلوماته لاستعادته لاحقًا أو حذفه نهائيًا من الجهاز.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceMuted,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        } else {
            val dateFmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(trashItems, key = { it.id }) { item ->
                    val existence = existenceMap[item.id]
                    val sizeMb = item.sizeBytes / (1024.0 * 1024.0)
                    val dateStr = dateFmt.format(Date(item.trashedAtEpochMs))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = palette.surface),
                        border = BorderStroke(0.75.dp, palette.border.copy(alpha = 0.65f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title.ifBlank { item.displayName },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = String.format(Locale.US, "%.1f ميجابايت • %s", sizeMb, dateStr),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.onSurfaceMuted
                                    )
                                }

                                when (existence) {
                                    is MediaExistenceResult.Exists -> {
                                        TrashStatusBadge(
                                            text = "متاح للاستعادة",
                                            color = palette.accent
                                        )
                                    }
                                    is MediaExistenceResult.Missing -> {
                                        TrashStatusBadge(
                                            text = "مفقود على الجهاز",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    is MediaExistenceResult.Unverifiable -> {
                                        TrashStatusBadge(
                                            text = "تعذر التحقق",
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                    null -> Unit
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onRestoreItem(item) },
                                    border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.7f)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RestoreFromTrash,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "استعادة",
                                        color = palette.accent,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                OutlinedButton(
                                    onClick = { itemPendingPermanentDelete = item },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteForever,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "حذف نهائي",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashStatusBadge(
    text: String,
    color: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(0.75.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}
