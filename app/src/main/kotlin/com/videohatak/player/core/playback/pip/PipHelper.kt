package com.videohatak.player.core.playback.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational

class PipHelper(private val context: Context) {

    companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.videohatak.player.PIP_PLAY_PAUSE"
        const val ACTION_PIP_FORWARD = "com.videohatak.player.PIP_FORWARD"
        const val ACTION_PIP_REPLAY = "com.videohatak.player.PIP_REPLAY"

        fun isPipSupported(context: Context): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        }

        fun getValidAspectRatio(width: Int, height: Int): Rational {
            if (width <= 0 || height <= 0) {
                return Rational(16, 9)
            }
            val rawRatio = width.toFloat() / height.toFloat()
            return when {
                rawRatio > 2.39f -> Rational(239, 100)
                rawRatio < (1f / 2.39f) -> Rational(100, 239)
                else -> Rational(width, height)
            }
        }

        /**
         * AUD-08: Check isFinishing / isDestroyed before entering PiP to avoid IllegalStateException
         */
        fun enterPip(
            activity: Activity,
            videoWidth: Int = 1920,
            videoHeight: Int = 1080,
            isPlaying: Boolean = true,
            seekIntervalSeconds: Int = 10
        ): Boolean {
            if (!isPipSupported(activity) || activity.isFinishing ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)
            ) {
                return false
            }

            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val ratio = getValidAspectRatio(videoWidth, videoHeight)
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(ratio)
                        .setActions(buildActions(activity, isPlaying, seekIntervalSeconds))
                        .build()
                    activity.enterPictureInPictureMode(params)
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }

        fun buildActions(
            context: Context,
            isPlaying: Boolean,
            seekIntervalSeconds: Int = 10
        ): List<RemoteAction> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

            val actions = mutableListOf<RemoteAction>()

            // Replay Xs
            val replayIntent = Intent(context, PipActionReceiver::class.java).apply {
                action = ACTION_PIP_REPLAY
            }
            actions.add(
                RemoteAction(
                    Icon.createWithResource(context, android.R.drawable.ic_media_rew),
                    "ترجيع ${seekIntervalSeconds}ث",
                    "ترجيع $seekIntervalSeconds ثوان",
                    PendingIntent.getBroadcast(
                        context,
                        101,
                        replayIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            )

            // Play / Pause
            val playPauseIntent = Intent(context, PipActionReceiver::class.java).apply {
                action = ACTION_PIP_PLAY_PAUSE
            }
            val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            actions.add(
                RemoteAction(
                    Icon.createWithResource(context, playPauseIcon),
                    if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                    if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                    PendingIntent.getBroadcast(
                        context,
                        102,
                        playPauseIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            )

            // Forward Xs
            val forwardIntent = Intent(context, PipActionReceiver::class.java).apply {
                action = ACTION_PIP_FORWARD
            }
            actions.add(
                RemoteAction(
                    Icon.createWithResource(context, android.R.drawable.ic_media_ff),
                    "تقديم ${seekIntervalSeconds}ث",
                    "تقديم $seekIntervalSeconds ثوان",
                    PendingIntent.getBroadcast(
                        context,
                        103,
                        forwardIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            )

            return actions
        }
    }

    fun isPipSupported(): Boolean = isPipSupported(context)

    fun enterPip(
        activity: Activity,
        isPlaying: Boolean,
        videoAspectRatio: Rational? = null,
        seekIntervalSeconds: Int = 10
    ): Boolean {
        val w = videoAspectRatio?.numerator ?: 16
        val h = videoAspectRatio?.denominator ?: 9
        return enterPip(activity, w, h, isPlaying, seekIntervalSeconds)
    }

    fun updatePipActions(
        activity: Activity,
        isPlaying: Boolean,
        seekIntervalSeconds: Int = 10,
        videoWidth: Int = 16,
        videoHeight: Int = 9
    ) {
        if (!isPipSupported() ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.isFinishing ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)
        ) {
            return
        }
        try {
            val params = buildUpdatePipParams(
                activity = activity,
                isPlaying = isPlaying,
                seekIntervalSeconds = seekIntervalSeconds,
                videoWidth = videoWidth,
                videoHeight = videoHeight
            )
            activity.setPictureInPictureParams(params)
        } catch (_: Exception) {}
    }

    internal fun buildUpdatePipParams(
        activity: Activity,
        isPlaying: Boolean,
        seekIntervalSeconds: Int = 10,
        videoWidth: Int = 16,
        videoHeight: Int = 9
    ): PictureInPictureParams {
        val ratio = getValidAspectRatio(videoWidth, videoHeight)
        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(buildActions(activity, isPlaying, seekIntervalSeconds))
            .build()
    }
}
