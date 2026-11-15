package com.videohatak.player.core.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Utility helper to manage Android storage and media permissions across all Android versions
 * from Android 7.0 (API 24) to Android 14+ (API 34+ / 35 / 36).
 */
object PermissionHelper {

    private const val TAG = "VideoHatakPermissions"
    private const val PREFS_NAME = "videohatak_permission_prefs"
    private const val KEY_MEDIA_REQUESTED_ONCE = "media_permission_requested_once"

    fun markMediaPermissionRequested(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MEDIA_REQUESTED_ONCE, true)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to persist permission request flag", e)
        }
    }

    fun wasMediaPermissionRequestedBefore(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MEDIA_REQUESTED_ONCE, false)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Required permissions to browse and play videos without blocking on notifications.
     */
    fun getRequiredPermissions(): Array<String> = getMediaPermissions()

    /**
     * Read-only media permissions strictly scoped by OS version.
     * Notifications and legacy write permissions are intentionally separated.
     */
    fun getMediaPermissions(): Array<String> = getMediaPermissionsForSdk(Build.VERSION.SDK_INT)

    internal fun getMediaPermissionsForSdk(sdkInt: Int): Array<String> {
        return when {
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            }
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            }
            else -> {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    /**
     * Legacy write storage permission ONLY needed on Android 9 and below (API <= 28) when performing
     * file-management actions (rename/move/permanent delete), never on initial library launch.
     */
    fun getLegacyWritePermission(): String? = getLegacyWritePermissionForSdk(Build.VERSION.SDK_INT)

    internal fun getLegacyWritePermissionForSdk(sdkInt: Int): String? {
        return if (sdkInt <= Build.VERSION_CODES.P) {
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        } else {
            null
        }
    }

    fun hasLegacyWritePermission(context: Context): Boolean {
        val perm = getLegacyWritePermission() ?: return true
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns notification permission on Android 13+ (API 33+) or null on older versions.
     */
    fun getNotificationPermission(): String? = getNotificationPermissionForSdk(Build.VERSION.SDK_INT)

    internal fun getNotificationPermissionForSdk(sdkInt: Int): String? {
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        val notifPerm = getNotificationPermission() ?: return true
        return ContextCompat.checkSelfPermission(context, notifPerm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Evaluates the current [VideoPermissionStatus] of the application safely,
     * including rationale & permanent denial detection when [activity] is supplied.
     */
    fun checkPermissionStatus(
        context: Context,
        activity: Activity? = context as? Activity
    ): VideoPermissionStatus {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val hasFull = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED

                val hasPartial = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED

                when {
                    hasFull -> VideoPermissionStatus.FullAccess
                    hasPartial -> VideoPermissionStatus.PartialAccess
                    else -> evaluateDeniedState(context, activity, Manifest.permission.READ_MEDIA_VIDEO)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val hasFull = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED

                if (hasFull) {
                    VideoPermissionStatus.FullAccess
                } else {
                    evaluateDeniedState(context, activity, Manifest.permission.READ_MEDIA_VIDEO)
                }
            }
            else -> {
                val hasStorage = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                if (hasStorage) {
                    VideoPermissionStatus.FullAccess
                } else {
                    evaluateDeniedState(context, activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private fun evaluateDeniedState(
        context: Context,
        activity: Activity?,
        primaryPermission: String
    ): VideoPermissionStatus.Denied {
        val showRationale = try {
            activity != null &&
                !activity.isFinishing &&
                !activity.isDestroyed &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, primaryPermission)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to evaluate rationale on detached activity", e)
            false
        }
        val askedBefore = wasMediaPermissionRequestedBefore(context)
        val permanentlyDenied = askedBefore && !showRationale
        return VideoPermissionStatus.Denied(
            shouldShowRationale = showRationale,
            isPermanentlyDenied = permanentlyDenied
        )
    }

    /**
     * Determines whether the app has at least some video read access (either full or partial).
     */
    fun hasAnyAccess(status: VideoPermissionStatus): Boolean {
        return status is VideoPermissionStatus.FullAccess || status is VideoPermissionStatus.PartialAccess
    }
}
