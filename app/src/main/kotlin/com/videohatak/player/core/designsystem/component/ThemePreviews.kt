package com.videohatak.player.core.designsystem.component

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.designsystem.theme.AppThemeId
import com.videohatak.player.core.designsystem.theme.VideoHatakTheme

@Composable
fun DesignSystemShowcaseContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "فيديوهاتك — كما تحب",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "تصميم سينمائي فاخر لتشغيل الفيديو دون إنترنت مع دعم 8 مظاهر فخمة.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "بطاقة العرض السينمائي (4K UHD)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "/storage/emulated/0/Movies/Sample.mkv",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = "بحث في المكتبة...",
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = MaterialTheme.shapes.small
            ) {
                Text(text = "تشغيل الآن", style = MaterialTheme.typography.labelLarge)
            }

            OutlinedButton(
                onClick = {},
                shape = MaterialTheme.shapes.small
            ) {
                Text(text = "الإعدادات", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Preview(name = "Obsidian Elite", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PreviewDarkTheme() {
    VideoHatakTheme(appTheme = AppThemeId.OBSIDIAN_ELITE, darkTheme = true) {
        VideoHatakScreenSurface {
            DesignSystemShowcaseContent()
        }
    }
}

@Preview(name = "Royal Ivory", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun PreviewLightTheme() {
    VideoHatakTheme(appTheme = AppThemeId.ROYAL_IVORY, darkTheme = false) {
        VideoHatakScreenSurface {
            DesignSystemShowcaseContent()
        }
    }
}

@Preview(name = "Arabic RTL", locale = "ar")
@Composable
fun PreviewArabicRTL() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        VideoHatakTheme(appTheme = AppThemeId.SAPPHIRE_PREMIUM, darkTheme = true) {
            VideoHatakScreenSurface {
                DesignSystemShowcaseContent()
            }
        }
    }
}
