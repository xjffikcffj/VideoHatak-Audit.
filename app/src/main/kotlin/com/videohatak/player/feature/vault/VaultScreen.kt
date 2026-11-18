package com.videohatak.player.feature.vault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.designsystem.theme.LocalWindowSizeClass
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.core.vault.security.VaultPinHasher
import com.videohatak.player.feature.vault.components.BatchDeleteVaultVideosDialog
import com.videohatak.player.feature.vault.components.BatchRestoreVaultVideosDialog
import com.videohatak.player.feature.vault.components.DeleteVaultVideoConfirmationDialog
import com.videohatak.player.feature.vault.components.ImportVideoProgressDialog
import com.videohatak.player.feature.vault.components.RenameVaultVideoDialog
import com.videohatak.player.feature.vault.components.RestoreVaultVideoConfirmationDialog
import com.videohatak.player.feature.vault.components.RestoreVideoProgressDialog
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
    onSetupInitialPin: (pin: String, onResult: (Boolean, String) -> Unit) -> Unit = { _, cb ->
        cb(false, "تعذر إعداد رمز الخزنة")
    },
    onSetPin: (String) -> Unit = {},
    onChangePin: (currentPin: String, newPin: String, onResult: (Boolean, String) -> Unit) -> Unit = { _, newPin, cb ->
        onSetPin(newPin)
        cb(true, "تم حفظ رمز PIN بنجاح")
    },
    onRemovePin: () -> Unit = {},
    onSelectAutoLockTimeout: (VaultAutoLockTimeout) -> Unit = {},
    onLockClick: () -> Unit,
    onImportClick: () -> Unit,
    onCancelImport: () -> Unit,
    onDismissImportState: () -> Unit,
    onDismissError: () -> Unit = {},
    onVideoClick: (VaultVideo) -> Unit,
    onRenameVideo: (Long, String) -> Unit,
    restoreState: VaultRestoreState = VaultRestoreState.Idle,
    onRestoreVideo: (Long) -> Unit = {},
    onRestoreVideos: (Set<Long>) -> Unit = { ids -> ids.forEach(onRestoreVideo) },
    onDeleteVideo: (Long) -> Unit,
    onDeleteVideos: (Set<Long>) -> Unit = { ids -> ids.forEach(onDeleteVideo) },
    onUserInteraction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current
    var videoToRename by remember { mutableStateOf<VaultVideo?>(null) }
    var videoToRestore by remember { mutableStateOf<VaultVideo?>(null) }
    var videoToDelete by remember { mutableStateOf<VaultVideo?>(null) }
    var showSecurityInfo by remember { mutableStateOf(false) }
    var showPinUnlockDialog by remember { mutableStateOf(false) }
    var showSecuritySettingsDialog by remember { mutableStateOf(false) }
    var selectedVideoIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchRestoreConfirm by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    val isSelectionMode = selectedVideoIds.isNotEmpty()

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            showPinUnlockDialog = false
        } else {
            selectedVideoIds = emptySet()
            videoToRestore = null
            videoToDelete = null
            showBatchRestoreConfirm = false
            showBatchDeleteConfirm = false
        }
    }

    LaunchedEffect(videos) {
        val liveIds = videos.map { it.id }.toSet()
        if (selectedVideoIds.any { it !in liveIds }) {
            selectedVideoIds = selectedVideoIds.intersect(liveIds)
        }
    }

    BackHandler(enabled = isUnlocked && isSelectionMode) {
        selectedVideoIds = emptySet()
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

    val toRestore = videoToRestore
    if (toRestore != null) {
        RestoreVaultVideoConfirmationDialog(
            video = toRestore,
            onDismiss = { videoToRestore = null },
            onConfirm = {
                onRestoreVideo(toRestore.id)
                videoToRestore = null
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

    if (showBatchRestoreConfirm && isSelectionMode) {
        BatchRestoreVaultVideosDialog(
            selectedCount = selectedVideoIds.size,
            onDismiss = { showBatchRestoreConfirm = false },
            onConfirm = {
                val toExport = selectedVideoIds
                showBatchRestoreConfirm = false
                selectedVideoIds = emptySet()
                onRestoreVideos(toExport)
            }
        )
    }

    if (showBatchDeleteConfirm && isSelectionMode) {
        BatchDeleteVaultVideosDialog(
            selectedCount = selectedVideoIds.size,
            onDismiss = { showBatchDeleteConfirm = false },
            onConfirm = {
                val toRemove = selectedVideoIds
                showBatchDeleteConfirm = false
                selectedVideoIds = emptySet()
                onDeleteVideos(toRemove)
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
            onChangePin = onChangePin,
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

    if (restoreState is VaultRestoreState.Restoring) {
        RestoreVideoProgressDialog(
            currentTitle = restoreState.currentTitle,
            completedCount = restoreState.completedCount,
            totalCount = restoreState.totalCount
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
            var initialPin by remember { mutableStateOf("") }
            var confirmInitialPin by remember { mutableStateOf("") }
            var showInitialPin by remember { mutableStateOf(false) }
            var showConfirmInitialPin by remember { mutableStateOf(false) }
            var setupPinError by remember { mutableStateOf<String?>(null) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = palette.accent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, palette.border)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "الخزنة الخاصة المشفرة",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (!hasPinConfigured) {
                        "أنشئ رمز قفل مستقلًا لحماية الخزنة الخاصة. يدعم الحروف العربية والإنجليزية والأرقام والرموز، ولا يعتمد على قفل شاشة الهاتف."
                    } else {
                        "مساحة آمنة ومعزولة لحفظ فيديوهاتك الحساسة بتشفير AES-256-GCM وحماية مفاتيح Android Keystore."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onSurfaceMuted,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )

                val displayError = setupPinError ?: biometricError
                if (displayError != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = displayError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                if (!hasPinConfigured) {
                    OutlinedTextField(
                        value = initialPin,
                        onValueChange = { input ->
                            if (input.length <= VaultPinHasher.MAX_PASSCODE_LENGTH && input.none { it == '\n' || it == '\r' }) {
                                initialPin = input
                                setupPinError = null
                            }
                        },
                        label = { Text("إنشاء رمز قفل الخزنة (4 أحرف فأكثر)") },
                        supportingText = { Text("يدعم الحروف العربية والإنجليزية والأرقام والرموز") },
                        visualTransformation = if (showInitialPin) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showInitialPin = !showInitialPin }) {
                                Icon(
                                    imageVector = if (showInitialPin) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showInitialPin) "إخفاء الرمز" else "إظهار الرمز",
                                    tint = palette.onSurfaceMuted
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmInitialPin,
                        onValueChange = { input ->
                            if (input.length <= VaultPinHasher.MAX_PASSCODE_LENGTH && input.none { it == '\n' || it == '\r' }) {
                                confirmInitialPin = input
                                setupPinError = null
                            }
                        },
                        label = { Text("تأكيد رمز قفل الخزنة") },
                        visualTransformation = if (showConfirmInitialPin) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showConfirmInitialPin = !showConfirmInitialPin }) {
                                Icon(
                                    imageVector = if (showConfirmInitialPin) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showConfirmInitialPin) "إخفاء الرمز" else "إظهار الرمز",
                                    tint = palette.onSurfaceMuted
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            when {
                                !VaultPinHasher.isValidPinFormat(initialPin) ->
                                    setupPinError = "يجب أن يتكون رمز قفل الخزنة من 4 أحرف على الأقل (يدعم الحروف والأرقام والرموز)"
                                initialPin != confirmInitialPin ->
                                    setupPinError = "الرمزان غير متطابقين"
                                else -> {
                                    onSetupInitialPin(initialPin) { ok, msg ->
                                        if (!ok) setupPinError = msg
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("إنشاء الرمز وفتح الخزنة", fontWeight = FontWeight.Bold)
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
                        Text("فتح الخزنة بالرمز", fontWeight = FontWeight.Bold)
                    }

                    if (canUseBiometrics) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onAuthenticateClick,
                            border = BorderStroke(1.dp, palette.accent),
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("فتح بالبصمة (اختياري)", color = palette.accent, fontWeight = FontWeight.Bold)
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
                    if (isSelectionMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { selectedVideoIds = emptySet() }) {
                                    Icon(Icons.Default.Close, contentDescription = "إلغاء التحديد", tint = palette.onSurface)
                                }
                                Text(
                                    text = "${selectedVideoIds.size} محدد",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.accent
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        selectedVideoIds = if (selectedVideoIds.size == videos.size) {
                                            emptySet()
                                        } else {
                                            videos.map { it.id }.toSet()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.SelectAll, contentDescription = "تحديد الكل", tint = palette.accent)
                                }
                                OutlinedButton(
                                    onClick = { showBatchRestoreConfirm = true },
                                    border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.75f))
                                ) {
                                    Icon(
                                        Icons.Default.DriveFileMove,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("إزالة من الخزنة", color = palette.accent, fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = { showBatchDeleteConfirm = true },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f))
                                ) {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("حذف نهائي", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
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

                                OutlinedButton(
                                    onClick = onLockClick,
                                    border = BorderStroke(1.dp, palette.border),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = palette.onSurface, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("قفل", color = palette.onSurface, fontSize = 12.sp)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { showSecuritySettingsDialog = true },
                                    border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.7f)),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تغيير رمز الخزنة", color = palette.accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                Button(
                                    onClick = onImportClick,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.accent,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("نقل إلى الخزنة", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
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
                            text = "اضغط على «نقل إلى الخزنة» لتشفير نسخة من الفيديو داخل الخزنة وإخفائه داخل VideoHatak فقط مع إبقاء الملف الأصلي سليمًا في موقعه.",
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
                            Text("نقل فيديو إلى الخزنة الآن", fontWeight = FontWeight.Bold)
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
                            val isSelected = video.id in selectedVideoIds
                            VaultVideoCard(
                                video = video,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedVideoIds = if (isSelected) {
                                            selectedVideoIds - video.id
                                        } else {
                                            selectedVideoIds + video.id
                                        }
                                    } else {
                                        onVideoClick(video)
                                    }
                                },
                                onLongClick = {
                                    selectedVideoIds = if (isSelected) {
                                        selectedVideoIds - video.id
                                    } else {
                                        selectedVideoIds + video.id
                                    }
                                },
                                onRename = { videoToRename = video },
                                onRestore = { videoToRestore = video },
                                onDelete = { videoToDelete = video }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultVideoCard(
    video: VaultVideo,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onRename: () -> Unit,
    onRestore: () -> Unit = {},
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) palette.accent.copy(alpha = 0.14f) else palette.surface
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 0.75.dp,
            color = if (isSelected) palette.accent else palette.border.copy(alpha = 0.7f)
        ),
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

                if (isSelectionMode) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp),
                        shape = CircleShape,
                        color = if (isSelected) palette.accent else palette.surface.copy(alpha = 0.85f),
                        border = BorderStroke(1.5.dp, palette.accent)
                    ) {
                        if (isSelected) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "محدد",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
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
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = palette.accent
                                )
                            },
                            onClick = {
                                showMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "إزالة من الخزنة",
                                    color = palette.accent,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DriveFileMove,
                                    contentDescription = null,
                                    tint = palette.accent
                                )
                            },
                            onClick = {
                                showMenu = false
                                onRestore()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "حذف نهائي",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DeleteForever,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
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
