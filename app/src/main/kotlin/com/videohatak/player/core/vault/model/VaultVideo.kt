package com.videohatak.player.core.vault.model

import android.net.Uri
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.model.PlaylistItem
import java.io.File

enum class VaultEntryState(val storageValue: String) {
    ENCRYPTING("ENCRYPTING"),
    ENCRYPTED_VERIFIED("ENCRYPTED_VERIFIED"),
    ORIGINAL_HIDDEN("ORIGINAL_HIDDEN"),
    ORIGIN_DELETE_PENDING("ORIGIN_DELETE_PENDING"),
    AWAITING_ORIGINAL_DELETE_CONSENT("AWAITING_ORIGINAL_DELETE_CONSENT"),
    ORIGINAL_DELETE_FAILED("ORIGINAL_DELETE_FAILED"),
    ORIGINAL_DELETED_VERIFIED("ORIGINAL_DELETED_VERIFIED"),
    HIDE_FAILED("HIDE_FAILED"),
    RESTORING("RESTORING"),
    ORIGINAL_RESTORED_VERIFIED("ORIGINAL_RESTORED_VERIFIED"),
    RESTORE_FAILED("RESTORE_FAILED"),
    ORIGINAL_MISSING("ORIGINAL_MISSING"),
    ORIGINAL_CHANGED("ORIGINAL_CHANGED"),
    REQUIRES_PERMISSION("REQUIRES_PERMISSION");

    val shouldHideOriginalInLibrary: Boolean
        get() = when (this) {
            ORIGINAL_HIDDEN,
            ORIGIN_DELETE_PENDING,
            AWAITING_ORIGINAL_DELETE_CONSENT,
            ORIGINAL_DELETE_FAILED,
            ORIGINAL_DELETED_VERIFIED,
            RESTORING,
            RESTORE_FAILED,
            ORIGINAL_MISSING,
            ORIGINAL_CHANGED,
            REQUIRES_PERMISSION -> true
            ENCRYPTING,
            ENCRYPTED_VERIFIED,
            HIDE_FAILED,
            ORIGINAL_RESTORED_VERIFIED -> false
        }

    companion object {
        fun fromStorageValue(
            raw: String?,
            fallback: VaultEntryState = ENCRYPTED_VERIFIED
        ): VaultEntryState {
            val clean = raw?.trim().orEmpty()
            return entries.firstOrNull { it.storageValue.equals(clean, ignoreCase = true) } ?: fallback
        }
    }
}

enum class VaultOriginalStorageType(val storageValue: String) {
    MEDIA_STORE("MEDIA_STORE"),
    EXTERNAL_SD_OR_REMOVABLE("EXTERNAL_SD_OR_REMOVABLE"),
    SAF_DOCUMENT("SAF_DOCUMENT"),
    FILE_PATH("FILE_PATH"),
    CONTENT_URI("CONTENT_URI"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromStorageValue(raw: String?): VaultOriginalStorageType {
            val clean = raw?.trim().orEmpty()
            return entries.firstOrNull { it.storageValue.equals(clean, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

data class VaultOriginalReference(
    val originalUri: String = "",
    val originalMediaStoreId: Long? = null,
    val originalFileName: String = "",
    val originalRelativePath: String = "",
    val originalFilePath: String = "",
    val originalMimeType: String = "video/mp4",
    val originalSizeBytes: Long = 0L,
    val originalDateModifiedSec: Long = 0L,
    val originalSha256: String = "",
    val storageType: VaultOriginalStorageType = VaultOriginalStorageType.UNKNOWN,
    val uriPermissionFlags: Int = 0
)

data class VaultHiddenOriginal(
    val vaultId: Long,
    val originalUri: String = "",
    val originalMediaStoreId: Long? = null,
    val originalFilePath: String = "",
    val originalFileName: String = "",
    val originalRelativePath: String = "",
    val storageType: VaultOriginalStorageType = VaultOriginalStorageType.UNKNOWN,
    val vaultState: VaultEntryState = VaultEntryState.ORIGINAL_HIDDEN
)

object VaultHiddenMatcher {
    private fun normalizeUri(raw: String?): String = raw?.trim().orEmpty()

    private fun extractFilePath(rawUri: String, explicitPath: String?): String {
        val cleanExplicit = explicitPath?.trim().orEmpty()
        if (cleanExplicit.isNotEmpty()) return normalizePath(cleanExplicit)
        if (rawUri.startsWith("file://", ignoreCase = true)) {
            return runCatching { Uri.parse(rawUri).path?.let(::normalizePath).orEmpty() }.getOrDefault("")
        }
        return ""
    }

    private fun normalizePath(path: String): String {
        return runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
    }

    private fun isMediaStoreUri(rawUri: String): Boolean {
        if (rawUri.isBlank()) return false
        return rawUri.startsWith("content://media/", ignoreCase = true)
    }

    fun isTargetHidden(
        videoId: Long,
        contentUri: String?,
        filePath: String? = null,
        hiddenEntries: List<VaultHiddenOriginal>
    ): Boolean {
        if (hiddenEntries.isEmpty()) return false
        val normUri = normalizeUri(contentUri)
        if (normUri.startsWith("vault://", ignoreCase = true)) return false
        val normPath = extractFilePath(normUri, filePath)

        for (entry in hiddenEntries) {
            if (!entry.vaultState.shouldHideOriginalInLibrary) continue
            val entryUri = normalizeUri(entry.originalUri)
            if (normUri.isNotEmpty() && entryUri.isNotEmpty() && normUri == entryUri) {
                return true
            }
            val entryPath = extractFilePath(entryUri, entry.originalFilePath)
            if (normPath.isNotEmpty() && entryPath.isNotEmpty() && normPath == entryPath) {
                return true
            }
            val entryMediaId = entry.originalMediaStoreId
            if (entryMediaId != null && entryMediaId > 0L && videoId > 0L && videoId == entryMediaId) {
                val compatibleScheme = isMediaStoreUri(normUri) ||
                    (entry.storageType == VaultOriginalStorageType.MEDIA_STORE && (normUri.isEmpty() || !normUri.startsWith("content://", ignoreCase = true) || isMediaStoreUri(normUri)))
                val compatiblePath = entryPath.isEmpty() || normPath.isEmpty() || normPath == entryPath
                if (compatibleScheme && compatiblePath) {
                    return true
                }
            }
        }
        return false
    }

    fun isVideoHidden(video: VideoItem, hiddenEntries: List<VaultHiddenOriginal>): Boolean {
        if (hiddenEntries.isEmpty()) return false
        return isTargetHidden(
            videoId = video.id,
            contentUri = video.contentUri.toString(),
            filePath = video.path,
            hiddenEntries = hiddenEntries
        )
    }

    fun isPlaylistItemHidden(item: PlaylistItem, hiddenEntries: List<VaultHiddenOriginal>): Boolean {
        if (hiddenEntries.isEmpty()) return false
        return isTargetHidden(
            videoId = item.videoId,
            contentUri = item.contentUri,
            filePath = null,
            hiddenEntries = hiddenEntries
        )
    }

    fun filterVisibleVideos(
        videos: List<VideoItem>,
        hiddenEntries: List<VaultHiddenOriginal>
    ): List<VideoItem> {
        if (hiddenEntries.isEmpty() || videos.isEmpty()) return videos
        return videos.filterNot { isVideoHidden(it, hiddenEntries) }
    }
}

data class VaultVideo(
    val id: Long,
    val displayName: String,
    val originalSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val durationMs: Long,
    val mimeType: String = "video/mp4",
    val width: Int = 0,
    val height: Int = 0,
    val relativeEncryptedPath: String,
    val createdAtEpochMs: Long,
    val ivBase64: String = "",
    val encryptedMetaBase64: String? = null,
    val originalUri: String = "",
    val originalMediaStoreId: Long? = null,
    val originalFilePath: String = "",
    val originalDateModifiedSec: Long = 0L,
    val originalSha256: String = "",
    val storageType: VaultOriginalStorageType = VaultOriginalStorageType.UNKNOWN,
    val uriPermissionFlags: Int = 0,
    val vaultState: VaultEntryState = VaultEntryState.ENCRYPTED_VERIFIED,
    val isHiddenInLibrary: Boolean = false,
    val stateUpdatedAtMs: Long = createdAtEpochMs,
    val lastError: String? = null,
    val originalDeleteVerified: Boolean = false,
    val publicRestoredUri: String = ""
) {
    val title: String get() = displayName
    val fileSizeBytes: Long get() = encryptedSizeBytes
    val isOriginalDeletedProtected: Boolean
        get() = originalDeleteVerified || vaultState == VaultEntryState.ORIGINAL_DELETED_VERIFIED
    val needsOriginalDeletionAttention: Boolean
        get() = vaultState == VaultEntryState.AWAITING_ORIGINAL_DELETE_CONSENT ||
            vaultState == VaultEntryState.ORIGINAL_DELETE_FAILED ||
            vaultState == VaultEntryState.ORIGIN_DELETE_PENDING

    fun toHiddenOriginalOrNull(): VaultHiddenOriginal? {
        if (!isHiddenInLibrary && !vaultState.shouldHideOriginalInLibrary) return null
        if (originalUri.isBlank() && originalFilePath.isBlank() && originalMediaStoreId == null) return null
        return VaultHiddenOriginal(
            vaultId = id,
            originalUri = originalUri,
            originalMediaStoreId = originalMediaStoreId,
            originalFilePath = originalFilePath,
            originalFileName = displayName,
            originalRelativePath = "",
            storageType = storageType,
            vaultState = vaultState
        )
    }

    val resolutionTag: String
        get() {
            val minDim = minOf(width, height)
            val maxDim = maxOf(width, height)
            return when {
                maxDim >= 3840 || minDim >= 2160 -> "4K"
                maxDim >= 1920 || minDim >= 1080 -> "1080p"
                maxDim >= 1280 || minDim >= 720 -> "720p"
                else -> "480p"
            }
        }

    val formattedDuration: String
        get() {
            if (durationMs <= 0L) return "00:00"
            val totalSeconds = durationMs / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

    val formattedSize: String
        get() {
            if (encryptedSizeBytes <= 0L) return "0 بايت"
            val gb = encryptedSizeBytes / (1024.0 * 1024.0 * 1024.0)
            val mb = encryptedSizeBytes / (1024.0 * 1024.0)
            return when {
                gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f جيجابايت مشفرة", gb)
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f ميجابايت مشفرة", mb)
                else -> String.format(java.util.Locale.US, "%d كيلوبايت مشفرة", encryptedSizeBytes / 1024L)
            }
        }
}

fun VaultVideo.toVideoItem(): VideoItem {
    val vaultUri = Uri.parse("vault://$id")

    return VideoItem(
        id = id,
        contentUri = vaultUri,
        title = displayName,
        displayName = displayName,
        durationMs = durationMs,
        sizeBytes = encryptedSizeBytes,
        dateAddedSec = createdAtEpochMs / 1000L,
        dateModifiedSec = createdAtEpochMs / 1000L,
        width = width,
        height = height,
        mimeType = mimeType,
        path = relativeEncryptedPath,
        relativePath = "vault",
        bucketId = "secure_vault",
        bucketDisplayName = "الخزنة الخاصة"
    )
}
