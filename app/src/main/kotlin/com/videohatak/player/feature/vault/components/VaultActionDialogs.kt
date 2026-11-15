package com.videohatak.player.feature.vault.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.core.vault.security.VaultPinHasher

@Composable
fun RenameVaultVideoDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    var title by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = { Text("إعادة تسمية الفيديو المشفر", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("الاسم الجديد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(title.trim())
                    }
                }
            ) {
                Text("حفظ", color = palette.accent, fontWeight = FontWeight.Bold)
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
fun DeleteVaultVideoConfirmationDialog(
    video: VaultVideo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = { Text("حذف من الخزنة المشفرة", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "هل أنت متأكد من حذف \"${video.title}\" من الخزنة الخاصة؟ سيتم حذف النسخة المشفرة نهائيًا.",
                color = palette.onSurfaceMuted
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("حذف نهائي", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
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
fun VaultSecurityInfoDialog(
    onDismiss: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = { Text("معلومات الأمان والتشفير", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "• التشفير: يتم تشفير مقاطع الفيديو والبيانات الوصفية محليًا بتشفير AES-256-GCM.\n" +
                            "• المفاتيح: يُحفظ مفتاح التشفير الرئيسي داخل Android Keystore على الجهاز لحماية مفاتيح الملفات.\n" +
                            "• بوابة الوصول: البصمة أو رمز PIN الاحتياطي (بتجزئة PBKDF2-HMAC-SHA256 وتقييد المحاولات الخاطئة) يتحكمان في فتح قفل الخزنة وإعادة القفل التلقائي.\n" +
                            "• الخصوصية: التطبيق يفعّل FLAG_SECURE لمنع لقطات وتسجيل الشاشة ويعطل PiP داخل الخزنة.\n" +
                            "• سلامة ملفاتك: استيراد أي فيديو إلى الخزنة لا يحذف ملف الفيديو الأصلي تلقائيًا من ذاكرة الهاتف.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onSurfaceMuted
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("حسناً", color = palette.accent, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun ImportVideoProgressDialog(
    progress: Float,
    onCancel: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current

    AlertDialog(
        onDismissRequest = { /* Modal during crypto progress */ },
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = { Text("جارٍ تشفير وحفظ الفيديو...", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("يتم الآن تطبيق تشفير AES-256-GCM على ملفك.", color = palette.onSurfaceMuted)
                Spacer(modifier = Modifier.height(16.dp))
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.accent,
                        trackColor = palette.border
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.accent,
                        trackColor = palette.border
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("إلغاء العملية", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
fun VaultPinUnlockDialog(
    errorText: String?,
    onDismiss: () -> Unit,
    onSubmitPin: (String) -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = { Text("فتح الخزنة برمز PIN", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        if (input.length <= 12 && input.all { it.isDigit() }) {
                            pin = input
                        }
                    },
                    label = { Text("أدخل رمز PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmitPin(pin) },
                enabled = VaultPinHasher.isValidPinFormat(pin)
            ) {
                Text("فتح", color = palette.accent, fontWeight = FontWeight.Bold)
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
fun VaultSecuritySettingsDialog(
    hasPin: Boolean,
    currentTimeout: VaultAutoLockTimeout,
    onSetPin: (String) -> Unit,
    onRemovePin: () -> Unit,
    onSelectTimeout: (VaultAutoLockTimeout) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var pinFeedback by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = { Text("إعدادات أمان الخزنة", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (hasPin) "تغيير أو إزالة رمز PIN الاحتياطي" else "تعيين رمز PIN احتياطي (4 إلى 12 رقمًا)",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.accent,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) newPin = it },
                    label = { Text("رمز PIN الجديد") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) confirmPin = it },
                    label = { Text("تأكيد رمز PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (pinFeedback != null) {
                    Text(
                        text = pinFeedback ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.accent
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            when {
                                !VaultPinHasher.isValidPinFormat(newPin) ->
                                    pinFeedback = "الرمز يجب أن يكون من 4 إلى 12 رقمًا"
                                newPin != confirmPin ->
                                    pinFeedback = "الرمزان غير متطابقين"
                                else -> {
                                    onSetPin(newPin)
                                    newPin = ""
                                    confirmPin = ""
                                    pinFeedback = "تم حفظ رمز PIN بنجاح"
                                }
                            }
                        }
                    ) {
                        Text(if (hasPin) "تحديث PIN" else "حفظ PIN", color = palette.accent, fontWeight = FontWeight.Bold)
                    }
                    if (hasPin) {
                        TextButton(
                            onClick = {
                                onRemovePin()
                                pinFeedback = "تمت إزالة رمز PIN"
                            }
                        ) {
                            Text("إزالة PIN", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Text(
                    text = "القفل التلقائي عند الخمول أو مغادرة الخزنة",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.accent,
                    fontWeight = FontWeight.Bold
                )
                VaultAutoLockTimeout.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectTimeout(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentTimeout == option,
                            onClick = { onSelectTimeout(option) },
                            colors = RadioButtonDefaults.colors(selectedColor = palette.accent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = option.titleArabic,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق", color = palette.accent)
            }
        }
    )
}

@Composable
fun ConfirmDeleteVaultDialog(
    videoTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = { Text("حذف الفيديو المشفر", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "هل أنت متأكد من حذف \"$videoTitle\" نهائيًا من الخزنة المشفرة؟",
                color = palette.onSurfaceMuted
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("حذف", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = palette.onSurfaceMuted)
            }
        }
    )
}
