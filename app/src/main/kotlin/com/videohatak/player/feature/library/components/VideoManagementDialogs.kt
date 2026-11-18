package com.videohatak.player.feature.library.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RenameVideoDialog(
    video: VideoItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    val initialName = remember(video.id) {
        video.displayName.substringBeforeLast('.').ifBlank { video.safeTitle }
    }
    var newName by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = {
            Text(text = "تغيير اسم الفيديو", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("الاسم الجديد") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.border,
                        focusedLabelColor = palette.accent,
                        unfocusedLabelColor = palette.onSurfaceMuted,
                        focusedTextColor = palette.onSurface,
                        unfocusedTextColor = palette.onSurface,
                        cursorColor = palette.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                val ext = video.displayName.substringAfterLast('.', "")
                if (ext.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "سيتم الحفاظ على الامتداد (.$ext)",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSurfaceMuted
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (newName.isNotBlank()) onConfirm(newName.trim()) },
                enabled = newName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("حفظ", fontWeight = FontWeight.Bold)
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
fun MoveVideoFolderDialog(
    video: VideoItem,
    existingFolders: List<VideoFolder>,
    onDismiss: () -> Unit,
    onMoveToFolder: (relativePath: String, folderName: String) -> Unit,
    selectedCount: Int = 1
) {
    val palette = LocalVideoHatakPalette.current
    var customFolderName by remember { mutableStateOf("") }

    val targetFolders = remember(existingFolders, video.bucketId, selectedCount) {
        if (selectedCount > 1) {
            existingFolders
        } else {
            existingFolders.filter { it.bucketId != video.bucketId }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = {
            Text(
                text = if (selectedCount > 1) "نقل $selectedCount فيديو إلى مجلد" else "نقل الفيديو إلى مجلد",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (selectedCount > 1) "سيتم نقل $selectedCount عنصر محدد إلى المجلد المختار" else video.safeTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (targetFolders.isNotEmpty()) {
                    Text(
                        text = "المجلدات الموجودة:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accent
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(targetFolders, key = { it.bucketId }) { folder ->
                            val relPath = folder.firstVideoItem?.relativePath?.ifBlank { "" }
                                ?: "Movies/${folder.displayName}/"
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = palette.surfaceElevated,
                                border = BorderStroke(0.75.dp, palette.border.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onMoveToFolder(relPath, folder.displayName)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = palette.accent
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = folder.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${folder.videoCount} فيديو",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.onSurfaceMuted
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = "أو نقل إلى مجلد جديد داخل Movies:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = customFolderName,
                    onValueChange = { customFolderName = it },
                    placeholder = { Text("مثال: أفلامي المفضلة") },
                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = palette.accent) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.border,
                        focusedLabelColor = palette.accent,
                        unfocusedLabelColor = palette.onSurfaceMuted,
                        focusedTextColor = palette.onSurface,
                        unfocusedTextColor = palette.onSurface,
                        cursorColor = palette.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleaned = customFolderName.trim()
                    if (cleaned.isNotEmpty()) {
                        onMoveToFolder("Movies/$cleaned/", cleaned)
                    }
                },
                enabled = customFolderName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("نقل إلى مجلد جديد", fontWeight = FontWeight.Bold)
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
fun VideoInfoDialog(
    video: VideoItem,
    onMoveToFolderClick: (() -> Unit)? = null,
    onMoveToTrashClick: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    val formattedDate = remember(video.dateAddedSec) {
        if (video.dateAddedSec <= 0L) {
            "غير متوفر"
        } else {
            val sdf = SimpleDateFormat("yyyy/MM/dd - hh:mm a", Locale("ar"))
            sdf.format(Date(video.dateAddedSec * 1000L))
        }
    }
    val dimensionsText = if (video.width > 0 && video.height > 0) {
        "${video.width} × ${video.height} (${video.resolutionTag})"
    } else {
        video.resolutionTag
    }
    val formatLabel = remember(video.mimeType, video.displayName) {
        val ext = video.displayName.substringAfterLast('.', "").uppercase(Locale.US)
        if (ext.isNotBlank()) "$ext (${video.mimeType})" else video.mimeType
    }
    val storagePath = remember(video.path, video.relativePath, video.bucketDisplayName) {
        video.path.ifBlank {
            if (video.relativePath.isNotBlank()) "${video.relativePath}${video.displayName}" else video.bucketDisplayName
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        icon = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = palette.accent
            )
        },
        title = {
            Text("معلومات الفيديو", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoDetailRow(label = "الاسم", value = video.displayName.ifBlank { video.safeTitle })
                HorizontalDivider(color = palette.border.copy(alpha = 0.35f))
                InfoDetailRow(label = "الحجم", value = video.formattedSize)
                InfoDetailRow(label = "المدة", value = video.formattedDuration)
                InfoDetailRow(label = "الدقة والأبعاد", value = dimensionsText)
                InfoDetailRow(label = "الصيغة", value = formatLabel)
                InfoDetailRow(label = "تاريخ الإضافة", value = formattedDate)
                InfoDetailRow(label = "المجلد", value = video.bucketDisplayName.ifBlank { "غير محدد" })
                HorizontalDivider(color = palette.border.copy(alpha = 0.35f))
                InfoDetailRow(label = "مسار التخزين", value = storagePath)

                if (onMoveToFolderClick != null || onMoveToTrashClick != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onMoveToFolderClick != null) {
                            OutlinedButton(
                                onClick = {
                                    onDismiss()
                                    onMoveToFolderClick()
                                },
                                border = BorderStroke(1.dp, palette.border),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DriveFileMove,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("نقل لمجلد", color = palette.onSurface, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (onMoveToTrashClick != null) {
                            OutlinedButton(
                                onClick = {
                                    onDismiss()
                                    onMoveToTrashClick()
                                },
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("سلة المحذوفات", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
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
                Text("حسناً", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun InfoDetailRow(
    label: String,
    value: String
) {
    val palette = LocalVideoHatakPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.accent,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = palette.onSurface
        )
    }
}
