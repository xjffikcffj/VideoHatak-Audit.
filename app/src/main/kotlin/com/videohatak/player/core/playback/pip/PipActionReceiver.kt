package com.videohatak.player.core.playback.pip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.videohatak.player.core.playback.VideoPlayerManager

class PipActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PLAY = "com.videohatak.player.PIP_PLAY"
        const val ACTION_PAUSE = "com.videohatak.player.PIP_PAUSE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val manager = VideoPlayerManager.getInstance(context.applicationContext)
        when (action) {
            PipHelper.ACTION_PIP_PLAY_PAUSE -> manager.togglePlayPause()
            ACTION_PLAY -> manager.play()
            ACTION_PAUSE -> manager.pause()
            PipHelper.ACTION_PIP_FORWARD -> manager.seekForward()
            PipHelper.ACTION_PIP_REPLAY -> manager.seekBackward()
        }
    }
}
