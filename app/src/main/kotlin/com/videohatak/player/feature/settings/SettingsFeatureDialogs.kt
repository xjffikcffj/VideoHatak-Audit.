package com.videohatak.player.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.trash.model.TrashItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrashBinDialog(
    trashItems: List<TrashItem>,
    onRestoreItem: (TrashItem) -> Unit,
    onPermanentDeleteItem: (TrashItem) -> Unit,
    onEmptyTrash: () -> Unit,
    onDismiss: () -> Unit
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        icon = {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = null,
                tint = palette.accent
            )
        },
        title = {
            Text(
                text = "سلة المحذوفات (${trashItems.size})",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (trashItems.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "سلة المحذوفات فارغة حاليًا",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "يمكنك نقل أي فيديو إلى السلة من نافذة معلومات الفيديو لاستعادته لاحقًا أو حذفه نهائيًا.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceMuted
                    )
                }
            } else {
                val dateFmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(trashItems, key = { it.id }) { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = palette.surfaceElevated,
                            border = BorderStroke(0.75.dp, palette.border.copy(alpha = 0.65f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title.ifBlank { item.displayName },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sizeMb = item.sizeBytes / (1024.0 * 1024.0)
                                    val dateStr = dateFmt.format(Date(item.trashedAtEpochMs))
                                    Text(
                                        text = String.format(Locale.US, "%.1f ميجابايت • %s", sizeMb, dateStr),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.onSurfaceMuted
                                    )
                                }

                                IconButton(onClick = { onRestoreItem(item) }) {
                                    Icon(
                                        imageVector = Icons.Default.RestoreFromTrash,
                                        contentDescription = "استعادة",
                                        tint = palette.accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(onClick = { itemPendingPermanentDelete = item }) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteForever,
                                        contentDescription = "حذف نهائي",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("إغلاق", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (trashItems.isNotEmpty()) {
                TextButton(onClick = { confirmEmptyAll = true }) {
                    Text("إفراغ السلة", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    )
}

@Composable
fun PermanentDeleteConfirmDialog(
    videoTitle: String,
    isBulk: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current

    AlertDialog(
        onDismissRequest = onDismiss,
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
            Text(
                text = if (isBulk) "إفراغ سلة المحذوفات نهائيًا؟" else "حذف الفيديو نهائيًا؟",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = if (isBulk) {
                    "سيتم حذف السجلات المحددة وطلب إزالة ملفات ($videoTitle) من الجهاز بشكل نهائي لا يمكن التراجع عنه."
                } else {
                    "سيتم حذف \"$videoTitle\" من ذاكرة الجهاز نهائيًا ولا يمكن التراجع عن هذا الإجراء."
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceMuted
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("تأكيد الحذف النهائي", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = palette.onSurfaceMuted)
            }
        }
    )
}

@Composable
fun BackupImportConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        icon = {
            Icon(
                imageVector = Icons.Default.Backup,
                contentDescription = null,
                tint = palette.accent
            )
        },
        title = {
            Text("استيراد نسخة احتياطية", fontWeight = FontWeight.Bold)
        },
        text = {
            Text(
                text = "سيتم التحقق من سلامة ملف JSON وبصمة SHA-256 (لاكتشاف أي تلف أو تعديل)، ثم استعادة الثيم وإعدادات التشغيل والاستئناف والترجمة المحفوظة وقوائم التشغيل ومواضع التوقف ومهلة قفل الخزنة. لا تشمل النسخة ملفات الفيديو الخام أو سلة المحذوفات أو النماذج المحلية المنزّلة، ولا تُمسّ رموز PIN أو مفاتيح وملفات الخزنة المشفرة.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceMuted
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("اختيار ملف النسخة", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = palette.onSurfaceMuted)
            }
        }
    )
}
