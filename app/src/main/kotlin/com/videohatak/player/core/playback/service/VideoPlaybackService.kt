package com.videohatak.player.core.playback.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.videohatak.player.MainActivity
import com.videohatak.player.core.playback.VideoPlayerManager

@OptIn(UnstableApi::class)
class VideoPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var attachedPlayer: Player? = null
    private var isForegroundStarted = false

    companion object {
        private const val CHANNEL_ID = "videohatak_playback_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.videohatak.player.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.videohatak.player.action.NEXT"
        const val ACTION_PREVIOUS = "com.videohatak.player.action.PREVIOUS"
        const val ACTION_STOP = "com.videohatak.player.action.STOP"

        fun startServiceForPlayback(context: Context) {
            try {
                val intent = Intent(context, VideoPlaybackService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = attachedPlayer ?: return
            if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return
            updateNotification(player, isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = attachedPlayer ?: return
            if (mediaItem != null) {
                updateNotification(player, player.isPlaying)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = attachedPlayer ?: return
            if (playbackState == Player.STATE_IDLE && player.mediaItemCount == 0) {
                stopForegroundServiceCleanly()
            } else if (playbackState == Player.STATE_ENDED) {
                updateNotification(player, false)
            }
        }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playerManager = VideoPlayerManager.getInstance(applicationContext)
        val player = attachedPlayer ?: playerManager.exoPlayer

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

        if (player.mediaItemCount > 0 || player.isPlaying) {
            updateNotification(player, player.isPlaying)
        } else {
            // Satisfy startForeground contract when launched via startForegroundService, then shut down idle service
            updateNotification(player, false)
            stopForegroundServiceCleanly()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = attachedPlayer
        if (player == null || !player.playWhenReady || !player.isPlaying || player.mediaItemCount == 0) {
            stopForegroundServiceCleanly()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
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

    private fun updateNotification(player: Player, isPlaying: Boolean) {
        val session = mediaSession ?: return
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString()?.ifBlank { null }
            ?: "فيديوهاتك — كما تحب"

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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(if (isPlaying) "قيد التشغيل في الخلفية" else "متوقف مؤقتًا")
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
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

        val notification = builder.build()
        if (!isForegroundStarted || isPlaying) {
            startForeground(NOTIFICATION_ID, notification)
            isForegroundStarted = true
        } else {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
                isForegroundStarted = false
            }
        }
    }

    private fun stopForegroundServiceCleanly() {
        isForegroundStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
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
