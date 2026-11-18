package com.videohatak.player.core.playback.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.videohatak.player.MainActivity
import com.videohatak.player.core.playback.VideoPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class VideoPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var attachedPlayer: Player? = null
    private var isForegroundStarted = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkLoadJob: Job? = null
    private var currentArtworkUriKey: String? = null
    private var currentArtworkBitmap: Bitmap? = null

    companion object {
        private const val CHANNEL_ID = "videohatak_playback_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.videohatak.player.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.videohatak.player.action.NEXT"
        const val ACTION_PREVIOUS = "com.videohatak.player.action.PREVIOUS"
        const val ACTION_STOP = "com.videohatak.player.action.STOP"

        fun startServiceForPlayback(context: Context) {
            val player = runCatching { VideoPlayerManager.getInstance(context.applicationContext).exoPlayer }.getOrNull()
            if (isVaultPlaybackActive(player)) {
                stopAndCancelNotification(context)
                return
            }
            try {
                val intent = Intent(context, VideoPlaybackService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
            }
        }

        fun stopAndCancelNotification(context: Context) {
            try {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager?.cancel(NOTIFICATION_ID)
            } catch (_: Exception) {
            }
            try {
                context.stopService(Intent(context, VideoPlaybackService::class.java))
            } catch (_: Exception) {
            }
        }

        internal fun isVaultPlaybackActive(player: Player?): Boolean {
            if (player == null) return false
            if (MediaNotificationPrivacy.isVaultMediaItem(player.currentMediaItem)) return true
            for (i in 0 until player.mediaItemCount) {
                if (MediaNotificationPrivacy.isVaultMediaItem(player.getMediaItemAt(i))) return true
            }
            return false
        }

        internal fun shouldKeepForegroundNotification(
            mediaItemCount: Int,
            playbackState: Int,
            isVaultPrivate: Boolean
        ): Boolean {
            if (isVaultPrivate) return false
            if (mediaItemCount <= 0) return false
            return playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = attachedPlayer ?: return
            if (isVaultPlaybackActive(player) ||
                (player.mediaItemCount == 0 && player.playbackState == Player.STATE_IDLE)
            ) {
                stopForegroundServiceCleanly()
                return
            }
            updateNotification(player, shouldStayForeground(player))
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = attachedPlayer ?: return
            if (isVaultPlaybackActive(player) || MediaNotificationPrivacy.isVaultMediaItem(mediaItem)) {
                stopForegroundServiceCleanly()
                stopSelf()
                return
            }
            if (mediaItem != null) {
                currentArtworkUriKey = null
                currentArtworkBitmap = null
                updateNotification(player, shouldStayForeground(player))
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = attachedPlayer ?: return
            if (isVaultPlaybackActive(player)) {
                stopForegroundServiceCleanly()
                stopSelf()
                return
            }
            if (playbackState == Player.STATE_IDLE && player.mediaItemCount == 0) {
                stopForegroundServiceCleanly()
            } else if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) {
                updateNotification(player, shouldStayForeground(player))
            } else if (playbackState == Player.STATE_ENDED) {
                stopForegroundServiceCleanly()
            }
        }
    }

    private fun shouldStayForeground(player: Player): Boolean {
        return shouldKeepForegroundNotification(
            mediaItemCount = player.mediaItemCount,
            playbackState = player.playbackState,
            isVaultPrivate = isVaultPlaybackActive(player)
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val playerManager = VideoPlayerManager.getInstance(applicationContext)
        val exoPlayer = playerManager.exoPlayer
        attachedPlayer = exoPlayer

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        exoPlayer.removeListener(playerListener)
        exoPlayer.addListener(playerListener)
    }

    /**
     * Prevent Media3's internal notification manager from exposing any Vault item
     * or replacing NOTIFICATION_ID = 1001.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val player = attachedPlayer ?: session.player
        if (isVaultPlaybackActive(player)) {
            if (startInForegroundRequired && !isForegroundStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                postTransientSilentCleanupForeground()
            }
            stopForegroundServiceCleanly()
            stopSelf()
            return
        }
        if (player.mediaItemCount > 0 || startInForegroundRequired) {
            updateNotification(player, keepForeground = startInForegroundRequired || shouldStayForeground(player))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val playerManager = VideoPlayerManager.getInstance(applicationContext)
        val player = attachedPlayer ?: playerManager.exoPlayer

        if (isVaultPlaybackActive(player)) {
            if (!isForegroundStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                postTransientSilentCleanupForeground()
            }
            stopForegroundServiceCleanly()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                if (player.mediaItemCount > 0) {
                    playerManager.togglePlayPause()
                }
            }
            ACTION_NEXT -> {
                if (player.mediaItemCount > 0) {
                    playerManager.nextTrack()
                }
            }
            ACTION_PREVIOUS -> {
                if (player.mediaItemCount > 0) {
                    playerManager.previousTrack()
                }
            }
            ACTION_STOP -> {
                playerManager.stopPlayback()
                stopForegroundServiceCleanly()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val hasActiveMedia = player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        if (hasActiveMedia || player.isPlaying || player.playWhenReady) {
            updateNotification(player, keepForeground = shouldStayForeground(player) || !isForegroundStarted)
            return START_STICKY
        } else {
            if (!isForegroundStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                postTransientSilentCleanupForeground()
            }
            stopForegroundServiceCleanly()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = attachedPlayer
        val keepAlive = player != null &&
            !isVaultPlaybackActive(player) &&
            player.mediaItemCount > 0 &&
            player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        if (!keepAlive) {
            stopForegroundServiceCleanly()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (isVaultPlaybackActive(attachedPlayer)) {
            return null
        }
        return mediaSession
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, VideoPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun updateNotification(player: Player, keepForeground: Boolean) {
        if (isVaultPlaybackActive(player)) {
            stopForegroundServiceCleanly()
            return
        }
        val session = mediaSession ?: return
        val currentMediaItem = player.currentMediaItem
        val privacyMeta = MediaNotificationPrivacy.resolveNotificationMetadata(
            mediaItem = currentMediaItem,
            isPlaying = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        )
        if (privacyMeta.isVaultPrivate) {
            stopForegroundServiceCleanly()
            return
        }

        val mediaUri = currentMediaItem?.localConfiguration?.uri
        val uriKey = mediaUri?.toString()
        val artworkBitmap = resolveOrScheduleArtwork(mediaUri, uriKey, isVaultPrivate = false)

        val notification = buildPlaybackNotification(
            session = session,
            privacyMeta = privacyMeta,
            isPlaying = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
            artwork = artworkBitmap
        )

        postNotification(notification, keepForeground)
    }

    private fun resolveOrScheduleArtwork(
        mediaUri: android.net.Uri?,
        uriKey: String?,
        isVaultPrivate: Boolean
    ): Bitmap {
        if (isVaultPrivate || mediaUri == null || uriKey.isNullOrBlank()) {
            artworkLoadJob?.cancel()
            currentArtworkUriKey = uriKey
            val fallback = MediaNotificationArtworkLoader.getFallbackArtwork(this)
            currentArtworkBitmap = fallback
            return fallback
        }

        if (currentArtworkUriKey == uriKey && currentArtworkBitmap != null && !currentArtworkBitmap!!.isRecycled) {
            return currentArtworkBitmap!!
        }

        val immediate = MediaNotificationArtworkLoader.resolveImmediateArtwork(
            context = this,
            uriString = uriKey,
            isVaultPrivate = false
        )
        currentArtworkUriKey = uriKey
        currentArtworkBitmap = immediate

        if (MediaNotificationArtworkLoader.getCachedArtwork(uriKey) == null) {
            artworkLoadJob?.cancel()
            artworkLoadJob = serviceScope.launch {
                val loaded = MediaNotificationArtworkLoader.loadOrFallbackArtwork(
                    context = applicationContext,
                    uri = mediaUri,
                    isVaultPrivate = false
                )
                val player = attachedPlayer ?: return@launch
                if (isVaultPlaybackActive(player)) {
                    stopForegroundServiceCleanly()
                    return@launch
                }
                val activeUriKey = player.currentMediaItem?.localConfiguration?.uri?.toString()
                if (activeUriKey == uriKey && !loaded.isRecycled) {
                    currentArtworkBitmap = loaded
                    val session = mediaSession ?: return@launch
                    val privacyMeta = MediaNotificationPrivacy.resolveNotificationMetadata(
                        mediaItem = player.currentMediaItem,
                        isPlaying = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
                    )
                    if (privacyMeta.isVaultPrivate) {
                        stopForegroundServiceCleanly()
                        return@launch
                    }
                    val refreshed = buildPlaybackNotification(
                        session = session,
                        privacyMeta = privacyMeta,
                        isPlaying = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
                        artwork = loaded
                    )
                    postNotification(refreshed, keepForeground = shouldStayForeground(player))
                }
            }
        }
        return immediate
    }

    private fun buildPlaybackNotification(
        session: MediaSession,
        privacyMeta: PlaybackNotificationMetadata,
        isPlaying: Boolean,
        artwork: Bitmap
    ): Notification {
        val prevAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_previous,
            "السابق",
            serviceActionPendingIntent(ACTION_PREVIOUS, 101)
        ).build()

        val playPauseAction = NotificationCompat.Action.Builder(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "إيقاف مؤقت" else "تشغيل",
            serviceActionPendingIntent(ACTION_PLAY_PAUSE, 102)
        ).build()

        val nextAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_next,
            "التالي",
            serviceActionPendingIntent(ACTION_NEXT, 103)
        ).build()

        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "إغلاق",
            serviceActionPendingIntent(ACTION_STOP, 104)
        ).build()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(artwork)
            .setContentTitle(privacyMeta.title)
            .setContentText(privacyMeta.contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(stopAction)
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun postTransientSilentCleanupForeground() {
        val transientNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("")
            .setContentText("")
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, transientNotification, fgType)
            isForegroundStarted = true
        }
    }

    private fun postNotification(notification: Notification, keepForeground: Boolean) {
        if (keepForeground) {
            val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
            isForegroundStarted = true
        } else {
            stopForegroundServiceCleanly()
        }
    }

    private fun stopForegroundServiceCleanly() {
        isForegroundStarted = false
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.cancel(NOTIFICATION_ID)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تشغيل الوسائط",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعارات التحكم بالتشغيل في الخلفية"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        artworkLoadJob?.cancel()
        serviceScope.cancel()
        attachedPlayer?.removeListener(playerListener)
        attachedPlayer = null
        mediaSession?.run {
            release()
            mediaSession = null
        }
        stopForegroundServiceCleanly()
        super.onDestroy()
    }
}
