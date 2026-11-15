package com.videohatak.player.feature.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.designsystem.theme.LocalWindowSizeClass
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.feature.vault.components.DeleteVaultVideoConfirmationDialog
import com.videohatak.player.feature.vault.components.ImportVideoProgressDialog
import com.videohatak.player.feature.vault.components.RenameVaultVideoDialog
import com.videohatak.player.feature.vault.components.VaultPinUnlockDialog
import com.videohatak.player.feature.vault.components.VaultSecurityInfoDialog
import com.videohatak.player.feature.vault.components.VaultSecuritySettingsDialog
import java.util.Locale

@Composable
fun VaultScreen(
    isUnlocked: Boolean,
    videos: List<VaultVideo>,
    importState: VaultImportState,
    biometricError: String?,
    vaultError: String? = null,
    hasPinConfigured: Boolean = false,
    canUseBiometrics: Boolean = true,
    autoLockTimeout: VaultAutoLockTimeout = VaultAutoLockTimeout.IMMEDIATE,
    onAuthenticateClick: () -> Unit,
    onUnlockWithPin: (String) -> Unit = {},
    onSetPin: (String) -> Unit = {},
    onRemovePin: () -> Unit = {},
    onSelectAutoLockTimeout: (VaultAutoLockTimeout) -> Unit = {},
    onLockClick: () -> Unit,
    onImportClick: () -> Unit,
    onCancelImport: () -> Unit,
    onDismissImportState: () -> Unit,
    onDismissError: () -> Unit = {},
    onVideoClick: (VaultVideo) -> Unit,
    onRenameVideo: (Long, String) -> Unit,
    onDeleteVideo: (Long) -> Unit,
    onUserInteraction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current
    var videoToRename by remember { mutableStateOf<VaultVideo?>(null) }
    var videoToDelete by remember { mutableStateOf<VaultVideo?>(null) }
    var showSecurityInfo by remember { mutableStateOf(false) }
    var showPinUnlockDialog by remember { mutableStateOf(false) }
    var showSecuritySettingsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            showPinUnlockDialog = false
        }
    }

    val windowSizeClass = LocalWindowSizeClass.current
    val gridColumnCount = when (windowSizeClass?.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 3
        WindowWidthSizeClass.Medium -> 2
        else -> 1
    }

    val toRename = videoToRename
    if (toRename != null) {
        RenameVaultVideoDialog(
            currentTitle = toRename.displayName,
            onDismiss = { videoToRename = null },
            onConfirm = { newTitle ->
                onRenameVideo(toRename.id, newTitle)
                videoToRename = null
            }
        )
    }

    val toDelete = videoToDelete
    if (toDelete != null) {
        DeleteVaultVideoConfirmationDialog(
            video = toDelete,
            onDismiss = { videoToDelete = null },
            onConfirm = {
                onDeleteVideo(toDelete.id)
                videoToDelete = null
            }
        )
    }

    if (showSecurityInfo) {
        VaultSecurityInfoDialog(onDismiss = { showSecurityInfo = false })
    }

    if (showPinUnlockDialog) {
        VaultPinUnlockDialog(
            errorText = biometricError,
            onDismiss = { showPinUnlockDialog = false },
            onSubmitPin = { enteredPin ->
                onUnlockWithPin(enteredPin)
            }
        )
    }

    if (showSecuritySettingsDialog) {
        VaultSecuritySettingsDialog(
            hasPin = hasPinConfigured,
            currentTimeout = autoLockTimeout,
            onSetPin = onSetPin,
            onRemovePin = onRemovePin,
            onSelectTimeout = onSelectAutoLockTimeout,
            onDismiss = { showSecuritySettingsDialog = false }
        )
    }

    if (importState is VaultImportState.Encrypting) {
        ImportVideoProgressDialog(
            progress = importState.progress,
            onCancel = onCancelImport
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isUnlocked) {
                if (!isUnlocked) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            onUserInteraction()
                        }
                    }
                }
            }
    ) {
        if (!isUnlocked) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(84.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = palette.accent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, palette.border)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "الخزنة الخاصة المشفرة",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "مساحة آمنة ومعزولة لحفظ فيديوهاتك الحساسة بتشفير AES-256-GCM وحماية مفاتيح Android Keystore.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onSurfaceMuted,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )

                if (biometricError != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = biometricError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                if (!canUseBiometrics) {
                    Text(
                        text = if (hasPinConfigured) {
                            "التحقق الحيوي غير متاح على هذا الجهاز حاليًا — يمكنك الفتح برمز PIN."
                        } else {
                            "يرجى تفعيل البصمة أو قفل الشاشة في إعدادات الجهاز أو فتح الخزنة وتعيين رمز PIN."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceMuted,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (canUseBiometrics || !hasPinConfigured) {
                    Button(
                        onClick = onAuthenticateClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("فتح الخزنة بالبصمة أو القفل", fontWeight = FontWeight.Bold)
                    }
                }

                if (hasPinConfigured) {
                    if (canUseBiometrics) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { showPinUnlockDialog = true },
                            border = BorderStroke(1.dp, palette.accent),
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.Pin, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("فتح برمز PIN", color = palette.accent, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { showPinUnlockDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.accent,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("فتح الخزنة برمز PIN", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { showSecurityInfo = true },
                    border = BorderStroke(1.dp, palette.border),
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(44.dp)
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = palette.onSurface, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("كيفية عمل نظام التشفير", color = palette.onSurface)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "الخزنة الخاصة",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.onSurface
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = palette.accent.copy(alpha = 0.18f)
                                ) {
                                    Text(
                                        text = "AES-256",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.accent,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            val totalBytes = videos.sumOf { it.encryptedSizeBytes }
                            val totalMb = totalBytes / (1024.0 * 1024.0)
                            Text(
                                text = String.format(Locale.US, "%d فيديو • %.1f ميجابايت مشفرة", videos.size, totalMb),
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.onSurfaceMuted
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showSecuritySettingsDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "إعدادات أمان الخزنة و PIN",
                                    tint = palette.accent
                                )
                            }

                            OutlinedButton(
                                onClick = onLockClick,
                                border = BorderStroke(1.dp, palette.border),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = palette.onSurface, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("قفل", color = palette.onSurface, fontSize = 12.sp)
                            }

                            Button(
                                onClick = onImportClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.accent,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("استيراد", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (!vaultError.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = vaultError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onDismissError) {
                                Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (importState is VaultImportState.Error) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = importState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onDismissImportState) {
                                Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (videos.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier.size(64.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = palette.accent.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, palette.border)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = palette.accent, modifier = Modifier.size(30.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "الخزنة فارغة حاليًا",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "اضغط على «استيراد» لتشفير فيديو وحفظه بأمان مع الإبقاء على النسخة الأصلية دون حذفها.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurfaceMuted,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onImportClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.accent,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("استيراد فيديو الآن", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumnCount),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(videos, key = { it.id }) { video ->
                            VaultVideoCard(
                                video = video,
                                onClick = { onVideoClick(video) },
                                onRename = { videoToRename = video },
                                onDelete = { videoToDelete = video }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VaultVideoCard(
    video: VaultVideo,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        border = BorderStroke(0.75.dp, palette.border.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(
                        Brush.linearGradient(
                            listOf(palette.surface, palette.surfaceElevated)
                        )
                    )
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "مشفر AES-256",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (video.width > 0 || video.height > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = palette.background.copy(alpha = 0.85f)
                    ) {
                        Text(
                            text = video.resolutionTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = palette.background.copy(alpha = 0.85f)
                ) {
                    Text(
                        text = video.formattedDuration,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSurface,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = palette.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = video.formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSurfaceMuted
                    )
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "خيارات",
                            tint = palette.onSurfaceMuted
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("تغيير الاسم") },
                            onClick = {
                                showMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "حذف من الخزنة",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}
