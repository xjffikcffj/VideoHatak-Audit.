package com.videohatak.player.core.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "videohatak_playback")

interface PlaybackPreferencesRepository {
    val playbackSpeed: Flow<Float>
    val resizeMode: Flow<Int>
    val repeatMode: Flow<String>

    val playbackMode: Flow<PlaybackMode>
        get() = flowOf(PlaybackMode.PLAY_ALL_IN_ORDER)

    val resumeMode: Flow<ResumeMode>
        get() = flowOf(ResumeMode.RESUME)

    val seekIntervalSeconds: Flow<Int>
        get() = flowOf(DEFAULT_SEEK_INTERVAL_SECONDS)

    suspend fun setPlaybackSpeed(speed: Float)
    suspend fun setResizeMode(mode: Int)
    suspend fun setRepeatMode(mode: String)
    suspend fun setPlaybackMode(mode: PlaybackMode) {}
    suspend fun setResumeMode(mode: ResumeMode) {}
    suspend fun setSeekIntervalSeconds(seconds: Int) {}
    suspend fun saveVideoPosition(videoUri: String, positionMs: Long, durationMs: Long)
    suspend fun getSavedVideoPosition(videoUri: String, durationMs: Long = 0L, legacyVideoId: Long? = null): Long
    suspend fun exportSavedPositions(): Map<String, Long> = emptyMap()
    suspend fun restoreSavedPositions(positions: Map<String, Long>) {}
    suspend fun replaceSavedPositions(positions: Map<String, Long>) {
        restoreSavedPositions(positions)
    }

    companion object {
        const val DEFAULT_SEEK_INTERVAL_SECONDS = 10
        val SUPPORTED_SEEK_INTERVALS_SECONDS = listOf(5, 10, 15)

        fun sanitizeSeekIntervalSeconds(seconds: Int): Int {
            return if (seconds in SUPPORTED_SEEK_INTERVALS_SECONDS) {
                seconds
            } else {
                SUPPORTED_SEEK_INTERVALS_SECONDS.minByOrNull { kotlin.math.abs(it - seconds) }
                    ?: DEFAULT_SEEK_INTERVAL_SECONDS
            }
        }
    }
}

class PlaybackPreferencesRepositoryImpl(
    private val dataStore: DataStore<Preferences>
) : PlaybackPreferencesRepository {

    constructor(context: Context) : this(context.playbackDataStore)

    private val speedKey = floatPreferencesKey("playback_speed")
    private val resizeModeKey = intPreferencesKey("resize_mode")
    private val repeatModeKey = stringPreferencesKey("repeat_mode")
    private val playbackModeKey = stringPreferencesKey("playback_mode_v2")
    private val resumeModeKey = stringPreferencesKey("resume_mode_v1")
    private val seekIntervalKey = intPreferencesKey("seek_interval_seconds")

    private fun positionKey(uri: String): Preferences.Key<Long> {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        val hex = digest.take(12).joinToString("") { "%02x".format(it) }
        return longPreferencesKey("vid_pos_$hex")
    }

    override val playbackSpeed: Flow<Float> = dataStore.data.map { prefs ->
        prefs[speedKey] ?: 1.0f
    }

    override val resizeMode: Flow<Int> = dataStore.data.map { prefs ->
        prefs[resizeModeKey] ?: 0
    }

    override val repeatMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[repeatModeKey] ?: "OFF"
    }

    override val playbackMode: Flow<PlaybackMode> = dataStore.data.map { prefs ->
        val raw = prefs[playbackModeKey]
        PlaybackMode.entries.firstOrNull { it.name == raw } ?: PlaybackMode.PLAY_ALL_IN_ORDER
    }

    override val resumeMode: Flow<ResumeMode> = dataStore.data.map { prefs ->
        val raw = prefs[resumeModeKey]
        ResumeMode.entries.firstOrNull { it.name == raw } ?: ResumeMode.RESUME
    }

    override val seekIntervalSeconds: Flow<Int> = dataStore.data.map { prefs ->
        PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(
            prefs[seekIntervalKey] ?: PlaybackPreferencesRepository.DEFAULT_SEEK_INTERVAL_SECONDS
        )
    }

    override suspend fun setPlaybackSpeed(speed: Float) {
        dataStore.edit { prefs ->
            prefs[speedKey] = speed
        }
    }

    override suspend fun setResizeMode(mode: Int) {
        dataStore.edit { prefs ->
            prefs[resizeModeKey] = mode
        }
    }

    override suspend fun setRepeatMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[repeatModeKey] = mode
        }
    }

    override suspend fun setPlaybackMode(mode: PlaybackMode) {
        dataStore.edit { prefs ->
            prefs[playbackModeKey] = mode.name
            prefs[repeatModeKey] = mode.repeatModeEquivalent.name
        }
    }

    override suspend fun setResumeMode(mode: ResumeMode) {
        dataStore.edit { prefs ->
            prefs[resumeModeKey] = mode.name
        }
    }

    override suspend fun setSeekIntervalSeconds(seconds: Int) {
        val valid = PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(seconds)
        dataStore.edit { prefs ->
            prefs[seekIntervalKey] = valid
        }
    }

    private fun safeExtractMediaStoreId(uri: String): Long? {
        if (!uri.startsWith("content://media/") && !uri.startsWith("content://com.android.providers.media")) {
            return null
        }
        val lastSegment = uri.substringAfterLast('/')
        return lastSegment.toLongOrNull()
    }

    private fun legacyKeyFromUri(uri: String, explicitLegacyId: Long? = null): Preferences.Key<Long>? {
        val id = explicitLegacyId ?: safeExtractMediaStoreId(uri) ?: return null
        return longPreferencesKey("playback_pos_$id")
    }

    override suspend fun saveVideoPosition(videoUri: String, positionMs: Long, durationMs: Long) {
        if (videoUri.isBlank()) return
        val effectivePos = PlaybackPositionThresholds.calculateEffectivePosition(positionMs, durationMs)
        dataStore.edit { prefs ->
            prefs[positionKey(videoUri)] = effectivePos
        }
    }

    override suspend fun getSavedVideoPosition(videoUri: String, durationMs: Long, legacyVideoId: Long?): Long {
        if (videoUri.isBlank()) return 0L
        val prefs = dataStore.data.first()
        var pos = prefs[positionKey(videoUri)]

        if (pos == null) {
            val legacyKey = legacyKeyFromUri(videoUri, legacyVideoId)
            if (legacyKey != null) {
                val legacyVal = prefs[legacyKey]
                if (legacyVal != null && legacyVal > 0L) {
                    pos = legacyVal
                    try {
                        dataStore.edit { editPrefs ->
                            editPrefs[positionKey(videoUri)] = legacyVal
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val raw = pos ?: 0L
        if (durationMs > 0L) {
            return PlaybackPositionThresholds.calculateEffectivePosition(raw, durationMs)
        }
        return raw
    }

    override suspend fun exportSavedPositions(): Map<String, Long> {
        val prefs = dataStore.data.first()
        val result = sortedMapOf<String, Long>()
        for ((key, value) in prefs.asMap()) {
            if (key.name.startsWith("vid_pos_") && value is Long && value > 0L) {
                result[key.name] = value
            }
        }
        return result
    }

    override suspend fun restoreSavedPositions(positions: Map<String, Long>) {
        if (positions.isEmpty()) return
        dataStore.edit { prefs ->
            for ((rawKey, posMs) in positions) {
                if (posMs <= 0L || rawKey.isBlank()) continue
                val prefKey = if (rawKey.startsWith("vid_pos_") && rawKey.length in 10..64) {
                    longPreferencesKey(rawKey)
                } else {
                    positionKey(rawKey)
                }
                prefs[prefKey] = posMs
            }
        }
    }

    override suspend fun replaceSavedPositions(positions: Map<String, Long>) {
        dataStore.edit { prefs ->
            val existingPosKeys = prefs.asMap().keys.filter { it.name.startsWith("vid_pos_") }
            for (oldKey in existingPosKeys) {
                prefs.remove(oldKey)
            }
            for ((rawKey, posMs) in positions) {
                if (posMs <= 0L || rawKey.isBlank()) continue
                val prefKey = if (rawKey.startsWith("vid_pos_") && rawKey.length in 10..64) {
                    longPreferencesKey(rawKey)
                } else {
                    positionKey(rawKey)
                }
                prefs[prefKey] = posMs
            }
        }
    }
}

object PlaybackPositionThresholds {
    fun calculateEffectivePosition(positionMs: Long, durationMs: Long): Long {
        if (positionMs <= 0L) return 0L
        if (durationMs <= 0L) {
            return if (positionMs < 500L) 0L else positionMs
        }

        val startThresholdMs = when {
            durationMs <= 2000L -> 100L
            durationMs <= 5000L -> 300L
            durationMs <= 10000L -> 1000L
            else -> 2000L
        }

        val endWindowMs = when {
            durationMs <= 2000L -> 150L
            durationMs <= 5000L -> 400L
            durationMs <= 15000L -> 1000L
            durationMs <= 60000L -> 2000L
            else -> 3000L
        }

        return when {
            positionMs >= (durationMs - endWindowMs) -> 0L
            positionMs < startThresholdMs -> 0L
            else -> positionMs
        }
    }
}
