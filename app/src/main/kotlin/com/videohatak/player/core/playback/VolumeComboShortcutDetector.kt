package com.videohatak.player.core.playback

import android.view.KeyEvent

/**
 * Detects simultaneous press of Volume Up + Volume Down hardware buttons to skip to the next video.
 *
 * Guarantees:
 * - Single volume button presses pass through untouched for normal system volume control.
 * - Key-repeat events while holding buttons down never re-trigger the shortcut multiple times.
 * - State resets cleanly when buttons are released or focus is lost.
 */
class VolumeComboShortcutDetector(
    private val onShortcutTriggered: () -> Unit
) {
    private var isVolumeUpDown = false
    private var isVolumeDownDown = false
    private var comboTriggeredForCurrentHold = false
    private var swallowNextVolumeUpRelease = false
    private var swallowNextVolumeDownRelease = false

    fun onKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        isShortcutActive: Boolean
    ): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        if (!isShortcutActive) {
            reset()
            return false
        }

        return when (action) {
            KeyEvent.ACTION_DOWN -> handleKeyDown(keyCode, repeatCount)
            KeyEvent.ACTION_UP -> handleKeyUp(keyCode)
            else -> false
        }
    }

    private fun handleKeyDown(keyCode: Int, repeatCount: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            isVolumeUpDown = true
        } else {
            isVolumeDownDown = true
        }

        if (comboTriggeredForCurrentHold) {
            // Consume key repeats while the combo hold is active so it does not fire again
            // or cause repeated stray volume jumps before release.
            return repeatCount > 0 || (isVolumeUpDown && isVolumeDownDown)
        }

        if (isVolumeUpDown && isVolumeDownDown && repeatCount == 0) {
            comboTriggeredForCurrentHold = true
            swallowNextVolumeUpRelease = true
            swallowNextVolumeDownRelease = true
            onShortcutTriggered()
            return true
        }

        // Single volume key press: allow normal system volume behavior.
        return false
    }

    private fun handleKeyUp(keyCode: Int): Boolean {
        val shouldConsume = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            isVolumeUpDown = false
            val consume = swallowNextVolumeUpRelease
            swallowNextVolumeUpRelease = false
            consume
        } else {
            isVolumeDownDown = false
            val consume = swallowNextVolumeDownRelease
            swallowNextVolumeDownRelease = false
            consume
        }

        if (!isVolumeUpDown && !isVolumeDownDown) {
            comboTriggeredForCurrentHold = false
        }

        return shouldConsume
    }

    fun reset() {
        isVolumeUpDown = false
        isVolumeDownDown = false
        comboTriggeredForCurrentHold = false
        swallowNextVolumeUpRelease = false
        swallowNextVolumeDownRelease = false
    }
}
