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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "• التشفير: يتم تشفير مقاطع الفيديو والبيانات الوصفية محليًا بتشفير AES-256-GCM.\n" +
                            "• المفاتيح: يُحفظ مفتاح التشفير الرئيسي داخل Android Keystore على الجهاز لحماية مفاتيح الملفات.\n" +
                            "• بوابة الوصول المستقلة: الخزنة محمية برمز PIN مستقل (بتجزئة PBKDF2-HMAC-SHA256 وتقييد المحاولات الخاطئة) دون الاعتماد على قفل شاشة الهاتف، مع خيار إضافي لاستخدام البصمة.\n" +
                            "• الخصوصية التامة: يفعّل التطبيق FLAG_SECURE لمنع لقطات وتسجيل الشاشة، ويعطّل PiP والإشعارات والتشغيل المصغّر وفي الخلفية، ويُوقف التشغيل فور الخروج أو القفل.\n" +
                            "• النقل إلى الخزنة: عند نقل فيديو إلى الخزنة يتم تشفيره والتحقق من سلامة النسخة المشفرة أولًا، ثم حذف النسخة الأصلية من الجهاز بأمان.",
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
    onSetPin: (String) -> Unit = {},
    onChangePin: (currentPin: String, newPin: String, onResult: (Boolean, String) -> Unit) -> Unit = { _, newPin, cb ->
        onSetPin(newPin)
        cb(true, "تم حفظ رمز PIN بنجاح")
    },
    onRemovePin: () -> Unit = {},
    onSelectTimeout: (VaultAutoLockTimeout) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var pinFeedback by remember { mutableStateOf<String?>(null) }
    var isPinFeedbackError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = { Text("إعدادات أمان الخزنة", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (hasPin) {
                        "تغيير رمز PIN المستقل للخزنة (يتطلب الرمز الحالي)"
                    } else {
                        "تعيين رمز PIN مستقل للخزنة (4 إلى 12 رقمًا)"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.accent,
                    fontWeight = FontWeight.Bold
                )
                if (hasPin) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) currentPin = it },
                        label = { Text("رمز PIN الحالي") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) newPin = it },
                    label = { Text(if (hasPin) "رمز PIN الجديد" else "رمز PIN (4 إلى 12 رقمًا)") },
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
                        color = if (isPinFeedbackError) MaterialTheme.colorScheme.error else palette.accent
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            when {
                                hasPin && currentPin.isBlank() -> {
                                    isPinFeedbackError = true
                                    pinFeedback = "يرجى إدخال رمز PIN الحالي للتحقق"
                                }
                                !VaultPinHasher.isValidPinFormat(newPin) -> {
                                    isPinFeedbackError = true
                                    pinFeedback = "الرمز الجديد يجب أن يكون من 4 إلى 12 رقمًا"
                                }
                                newPin != confirmPin -> {
                                    isPinFeedbackError = true
                                    pinFeedback = "الرمزان غير متطابقين"
                                }
                                else -> {
                                    onChangePin(currentPin, newPin) { ok, msg ->
                                        isPinFeedbackError = !ok
                                        pinFeedback = msg
                                        if (ok) {
                                            currentPin = ""
                                            newPin = ""
                                            confirmPin = ""
                                        }
                                    }
                                }
                            }
                        }
                    ) {
                        Text(if (hasPin) "تحديث PIN" else "حفظ PIN", color = palette.accent, fontWeight = FontWeight.Bold)
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

@Composable
fun BatchDeleteVaultVideosDialog(
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
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
                "هل أنت متأكد من حذف $selectedCount من الفيديوهات المحددة نهائيًا من الخزنة المشفرة؟",
                color = palette.onSurfaceMuted
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("حذف المحدد", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = palette.onSurfaceMuted)
            }
        }
    )
}
