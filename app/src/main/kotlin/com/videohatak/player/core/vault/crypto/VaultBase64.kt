package com.videohatak.player.core.vault.crypto

import android.util.Base64 as AndroidBase64
import java.io.ByteArrayOutputStream

/**
 * API-24+ safe Base64 utility (NO_WRAP) that delegates to [android.util.Base64] on Android
 * and falls back cleanly to RFC 4648 pure-Kotlin Base64 under JVM unit tests where
 * `android.util.Base64` returns default null/empty values (`isReturnDefaultValues = true`).
 */
internal object VaultBase64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE_TABLE = IntArray(256) { -1 }.apply {
        for (i in ALPHABET.indices) {
            this[ALPHABET[i].code] = i
        }
    }

    fun encodeNoWrap(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val androidResult = try {
            AndroidBase64.encodeToString(data, AndroidBase64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
        if (!androidResult.isNullOrEmpty()) {
            return androidResult
        }
        return encodePureKotlin(data)
    }

    fun decode(encoded: String): ByteArray {
        if (encoded.isEmpty()) return ByteArray(0)
        val trimmed = encoded.trim()
        // If running on Android, android.util.Base64.decode returns non-null bytes for valid input
        // and throws IllegalArgumentException for malformed Base64.
        // Under JVM unit tests (`isReturnDefaultValues = true`), it returns null without throwing.
        val androidBytes: ByteArray? = try {
            AndroidBase64.decode(trimmed, AndroidBase64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (androidBytes != null) {
            return androidBytes
        }
        return decodePureKotlin(trimmed)
    }

    internal fun encodePureKotlin(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val hasB1 = i + 1 < data.size
            val hasB2 = i + 2 < data.size
            val b1 = if (hasB1) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) data[i + 2].toInt() and 0xFF else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(ALPHABET[(triple ushr 18) and 0x3F])
            out.append(ALPHABET[(triple ushr 12) and 0x3F])
            out.append(if (hasB1) ALPHABET[(triple ushr 6) and 0x3F] else '=')
            out.append(if (hasB2) ALPHABET[triple and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    internal fun decodePureKotlin(input: String): ByteArray {
        val cleaned = input.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return ByteArray(0)
        if (cleaned.length % 4 != 0) {
            throw IllegalArgumentException("Invalid Base64 length")
        }

        val out = ByteArrayOutputStream((cleaned.length / 4) * 3)
        var i = 0
        while (i < cleaned.length) {
            val c0 = cleaned[i]
            val c1 = cleaned[i + 1]
            val c2 = cleaned[i + 2]
            val c3 = cleaned[i + 3]

            val v0 = if (c0.code < 256) DECODE_TABLE[c0.code] else -1
            val v1 = if (c1.code < 256) DECODE_TABLE[c1.code] else -1
            if (v0 < 0 || v1 < 0) {
                throw IllegalArgumentException("Bad Base64 characters")
            }

            val pad2 = c2 == '='
            val pad3 = c3 == '='
            if (pad2 && !pad3) {
                throw IllegalArgumentException("Invalid Base64 padding")
            }
            if ((pad2 || pad3) && i + 4 < cleaned.length) {
                throw IllegalArgumentException("Unexpected Base64 padding before end")
            }

            val v2 = if (pad2) 0 else if (c2.code < 256) DECODE_TABLE[c2.code] else -1
            val v3 = if (pad3) 0 else if (c3.code < 256) DECODE_TABLE[c3.code] else -1
            if (v2 < 0 || v3 < 0) {
                throw IllegalArgumentException("Bad Base64 characters")
            }

            val triple = (v0 shl 18) or (v1 shl 12) or (v2 shl 6) or v3
            out.write((triple ushr 16) and 0xFF)
            if (!pad2) out.write((triple ushr 8) and 0xFF)
            if (!pad3) out.write(triple and 0xFF)
            i += 4
        }
        return out.toByteArray()
    }
}
