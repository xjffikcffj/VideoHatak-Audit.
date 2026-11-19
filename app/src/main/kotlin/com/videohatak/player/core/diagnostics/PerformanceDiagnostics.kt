package com.videohatak.player.core.diagnostics

import android.os.Looper
import android.util.Log

/**
 * Lightweight, low-overhead runtime timing helper to surface Main-thread stalls (ANR risks)
 * and unexpectedly slow background operations without altering control flow or swallowing errors.
 */
object PerformanceDiagnostics {
    private const val TAG = "VideoHatakPerf"
    const val DEFAULT_SLOW_MAIN_MS = 60L
    const val DEFAULT_SLOW_BG_MS = 250L

    @Volatile
    internal var onSlowOperationListener: ((SlowOperationRecord) -> Unit)? = null

    data class SlowOperationRecord(
        val operation: String,
        val elapsedMs: Long,
        val onMainThread: Boolean,
        val details: String = ""
    )

    inline fun <T> measure(
        operation: String,
        mainThresholdMs: Long = DEFAULT_SLOW_MAIN_MS,
        bgThresholdMs: Long = DEFAULT_SLOW_BG_MS,
        noinline details: () -> String = { "" },
        block: () -> T
    ): T {
        val startNs = System.nanoTime()
        return try {
            block()
        } finally {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            recordIfSlow(
                operation = operation,
                elapsedMs = elapsedMs,
                mainThresholdMs = mainThresholdMs,
                bgThresholdMs = bgThresholdMs,
                details = details
            )
        }
    }

    fun recordIfSlow(
        operation: String,
        elapsedMs: Long,
        mainThresholdMs: Long = DEFAULT_SLOW_MAIN_MS,
        bgThresholdMs: Long = DEFAULT_SLOW_BG_MS,
        details: () -> String = { "" }
    ) {
        val onMain = isMainThread()
        val threshold = if (onMain) mainThresholdMs else bgThresholdMs
        if (elapsedMs >= threshold) {
            val detailStr = runCatching { details() }.getOrDefault("")
            val record = SlowOperationRecord(
                operation = operation,
                elapsedMs = elapsedMs,
                onMainThread = onMain,
                details = detailStr
            )
            onSlowOperationListener?.invoke(record)
            runCatching {
                val suffix = if (detailStr.isNotBlank()) " ($detailStr)" else ""
                Log.w(TAG, "Slow op [$operation] took ${elapsedMs}ms onMain=$onMain$suffix")
            }
        }
    }

    fun isMainThread(): Boolean = runCatching {
        Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()
    }.getOrDefault(false)
}
