package com.videohatak.player.core.playback.service

import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import com.videohatak.player.core.model.VideoItem

data class PlaybackNotificationMetadata(
    val title: String,
    val contentText: String,
    val visibility: Int,
    val isVaultPrivate: Boolean
)

object MediaNotificationPrivacy {
    const val VAULT_MASKED_TITLE = "مقطع خاص"
    const val VAULT_MASKED_TEXT = "تشغيل خاص محمي"
    const val DEFAULT_PUBLIC_TITLE = "فيديوهاتك — كما تحب"

    fun isVaultUri(uri: Uri?): Boolean = uri?.scheme.equals("vault", ignoreCase = true)

    fun isVaultVideo(video: VideoItem): Boolean =
        video.bucketId == "secure_vault" || isVaultUri(video.contentUri)

    fun isVaultMediaItem(mediaItem: MediaItem?): Boolean {
        if (mediaItem == null) return false
        if (isVaultUri(mediaItem.localConfiguration?.uri)) return true
        return mediaItem.mediaId.startsWith("vault://", ignoreCase = true)
    }

    fun sanitizeMediaMetadataTitle(isVault: Boolean, originalTitle: String): String {
        return if (isVault) VAULT_MASKED_TITLE else originalTitle
    }

    fun resolveNotificationMetadata(
        mediaItem: MediaItem?,
        isPlaying: Boolean
    ): PlaybackNotificationMetadata {
        if (isVaultMediaItem(mediaItem)) {
            return PlaybackNotificationMetadata(
                title = VAULT_MASKED_TITLE,
                contentText = VAULT_MASKED_TEXT,
                visibility = NotificationCompat.VISIBILITY_PRIVATE,
                isVaultPrivate = true
            )
        }
        val publicTitle = mediaItem?.mediaMetadata?.title?.toString()?.ifBlank { null }
            ?: DEFAULT_PUBLIC_TITLE
        val statusText = if (isPlaying) "قيد التشغيل في الخلفية" else "متوقف مؤقتًا"
        return PlaybackNotificationMetadata(
            title = publicTitle,
            contentText = statusText,
            visibility = NotificationCompat.VISIBILITY_PUBLIC,
            isVaultPrivate = false
        )
    }
}
