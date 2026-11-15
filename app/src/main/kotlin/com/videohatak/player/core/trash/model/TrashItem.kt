package com.videohatak.player.core.trash.model

import android.net.Uri
import com.videohatak.player.core.model.VideoItem

data class TrashItem(
    val id: Long = 0L,
    val videoId: Long,
    val title: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val bucketId: String,
    val bucketDisplayName: String,
    val width: Int,
    val height: Int,
    val dateAddedSec: Long,
    val contentUri: String,
    val filePath: String,
    val trashedAtEpochMs: Long
) {
    fun toVideoItem(): VideoItem {
        return VideoItem(
            id = videoId,
            contentUri = Uri.parse(contentUri),
            title = title,
            displayName = displayName,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            dateAddedSec = dateAddedSec,
            dateModifiedSec = trashedAtEpochMs / 1000L,
            width = width,
            height = height,
            mimeType = mimeType,
            path = filePath,
            relativePath = "",
            bucketId = bucketId,
            bucketDisplayName = bucketDisplayName
        )
    }
}
