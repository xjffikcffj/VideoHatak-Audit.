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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.VisualTransformation
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
fun RestoreVaultVideoConfirmationDialog(
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
        title = { Text("إزالة من الخزنة الخاصة", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "سيتم التحقق من وجود الملف الأصلي لـ \"${video.title}\" وسلامة بصمته SHA-256 ثم إعادة إظهاره في مكتبة VideoHatak كما كان دون إنشاء نسخة مكررة، وبعد التأكد فقط تُزال النسخة المشفرة من الخزنة.",
                color = palette.onSurfaceMuted
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("إزالة وإعادة إظهار", color = palette.accent, fontWeight = FontWeight.Bold)
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
        title = { Text("حذف نهائي من الخزنة", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "تحذير: سيؤدي هذا الإجراء إلى حذف النسخة المشفرة من \"${video.title}\" من الخزنة نهائيًا (سيُرفض الحذف إذا كانت هذه النسخة هي النسخة السليمة الوحيدة المتبقية). هل تريد المتابعة؟",
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
                            "• بوابة الوصول المستقلة: الخزنة محمية برمز قفل مستقل مرن يدعم الحروف العربية والإنجليزية والأرقام والرموز (بتجزئة PBKDF2 وتقييد المحاولات الخاطئة) دون الاعتماد على قفل شاشة الهاتف، مع خيار إضافي لاستخدام البصمة.\n" +
                            "• الخصوصية التامة: يفعّل التطبيق FLAG_SECURE لمنع لقطات وتسجيل الشاشة، ويعطّل PiP والإشعارات والتشغيل المصغّر وفي الخلفية، ويُوقف التشغيل فور الخروج أو القفل.\n" +
                            "• الإخفاء والاستعادة العكوسة: عند نقل فيديو إلى الخزنة يُنشأ له نسخ مشفر وتُحفظ بصمته SHA-256 ويُخفى داخل مكتبة VideoHatak دون المساس بالملف الأصلي على الجهاز. وعند اختيار «إزالة من الخزنة» يتحقق التطبيق من وجود الأصل وسلامته ثم يعيد إظهاره في مكانه دون نسخ مكررة، ولا يسمح بحذف النسخة المشفرة نهائيًا إذا كانت هي النسخة السليمة الوحيدة المتبقية.",
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
fun RestoreVideoProgressDialog(
    currentTitle: String,
    completedCount: Int,
    totalCount: Int
) {
    val palette = LocalVideoHatakPalette.current

    AlertDialog(
        onDismissRequest = { /* Modal while verifying & writing restored public media */ },
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
                title = { Text("جارٍ التحقق وإعادة إظهار الفيديو...", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (currentTitle.isNotBlank()) {
                    Text(
                        text = currentTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    text = "يتم التحقق من وجود الملف الأصلي وسلامة بصمته SHA-256 وإعادة إظهاره في المكتبة قبل إزالة النسخة المشفرة.",
                    color = palette.onSurfaceMuted
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (totalCount > 1) {
                    Text(
                        text = "${completedCount + 1} / $totalCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { ((completedCount.toFloat()) / totalCount.coerceAtLeast(1)).coerceIn(0.05f, 1f) },
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
        confirmButton = {}
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
    var isPasscodeVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = { Text("فتح الخزنة الخاصة", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        if (input.length <= VaultPinHasher.MAX_PASSCODE_LENGTH && input.none { it == '\n' || it == '\r' }) {
                            pin = input
                        }
                    },
                    label = { Text("أدخل رمز قفل الخزنة") },
                    visualTransformation = if (isPasscodeVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { isPasscodeVisible = !isPasscodeVisible }) {
                            Icon(
                                imageVector = if (isPasscodeVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isPasscodeVisible) "إخفاء الرمز" else "إظهار الرمز",
                                tint = palette.onSurfaceMuted
                            )
                        }
                    },
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
        cb(true, "تم تغيير رمز قفل الخزنة بنجاح")
    },
    onRemovePin: () -> Unit = {},
    onSelectTimeout: (VaultAutoLockTimeout) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var showCurrent by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var pinFeedback by remember { mutableStateOf<String?>(null) }
    var isPinFeedbackError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.onSurface,
        textContentColor = palette.onSurface,
        title = { Text("تغيير رمز الخزنة والأمان", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (hasPin) {
                        "تغيير رمز قفل الخزنة (يتطلب إدخال الرمز الحالي)"
                    } else {
                        "تعيين رمز قفل مستقل للخزنة (4 أحرف فأكثر)"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.accent,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "يدعم الرمز الحروف العربية والإنجليزية والأرقام والرموز، وتبقى جميع ملفاتك المشفرة داخل الخزنة محفوظة وآمنة عند تغييره.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceMuted
                )
                if (hasPin) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { input ->
                            if (input.length <= VaultPinHasher.MAX_PASSCODE_LENGTH && input.none { it == '\n' || it == '\r' }) {
                                currentPin = input
                            }
                        },
                        label = { Text("رمز قفل الخزنة الحالي") },
                        visualTransformation = if (showCurrent) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showCurrent = !showCurrent }) {
                                Icon(
                                    imageVector = if (showCurrent) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showCurrent) "إخفاء الرمز" else "إظهار الرمز",
                                    tint = palette.onSurfaceMuted
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { input ->
                        if (input.length <= VaultPinHasher.MAX_PASSCODE_LENGTH && input.none { it == '\n' || it == '\r' }) {
                            newPin = input
                        }
                    },
                    label = { Text(if (hasPin) "رمز قفل الخزنة الجديد" else "رمز قفل الخزنة (4 أحرف فأكثر)") },
                    visualTransformation = if (showNew) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showNew = !showNew }) {
                            Icon(
                                imageVector = if (showNew) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showNew) "إخفاء الرمز" else "إظهار الرمز",
                                tint = palette.onSurfaceMuted
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { input ->
                        if (input.length <= VaultPinHasher.MAX_PASSCODE_LENGTH && input.none { it == '\n' || it == '\r' }) {
                            confirmPin = input
                        }
                    },
                    label = { Text("تأكيد رمز قفل الخزنة الجديد") },
                    visualTransformation = if (showConfirm) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showConfirm = !showConfirm }) {
                            Icon(
                                imageVector = if (showConfirm) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showConfirm) "إخفاء الرمز" else "إظهار الرمز",
                                tint = palette.onSurfaceMuted
                            )
                        }
                    },
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
                                hasPin && currentPin.isEmpty() -> {
                                    isPinFeedbackError = true
                                    pinFeedback = "يرجى إدخال رمز قفل الخزنة الحالي للتحقق"
                                }
                                !VaultPinHasher.isValidPinFormat(newPin) -> {
                                    isPinFeedbackError = true
                                    pinFeedback = "يجب أن يتكون الرمز الجديد من 4 أحرف على الأقل (يدعم الحروف العربية والإنجليزية والأرقام والرموز)"
                                }
                                newPin != confirmPin -> {
                                    isPinFeedbackError = true
                                    pinFeedback = "الرمز الجديد وتأكيده غير متطابقين"
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
                        Text(if (hasPin) "تغيير رمز الخزنة" else "حفظ رمز الخزنة", color = palette.accent, fontWeight = FontWeight.Bold)
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
        title = { Text("حذف نهائي من الخزنة", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "تحذير: هل أنت متأكد من الحذف النهائي للنسخة المشفرة لـ \"$videoTitle\"؟ لن يتم تنفيذ الحذف إذا كانت هذه النسخة هي النسخة السليمة الوحيدة المتبقية.",
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
fun BatchRestoreVaultVideosDialog(
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
        title = { Text("إزالة وإعادة إظهار في المكتبة", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "سيتم التحقق من وجود الأصول وسلامتها (SHA-256) وإعادة إظهار $selectedCount من الفيديوهات في المكتبة كما كانت، ثم إزالة نسخها المشفرة من الخزنة.",
                color = palette.onSurfaceMuted
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("إزالة وإعادة إظهار ($selectedCount)", color = palette.accent, fontWeight = FontWeight.Bold)
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
        title = { Text("حذف نهائي من الخزنة", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "تحذير: هل أنت متأكد من الحذف النهائي لـ $selectedCount من النسخ المشفرة في الخزنة؟ سيُحظر حذف أي مقطع إذا كانت النسخة المشفرة هي النسخة السليمة الوحيدة المتبقية.",
                color = palette.onSurfaceMuted
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("حذف نهائي ($selectedCount)", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = palette.onSurfaceMuted)
            }
        }
    )
}
