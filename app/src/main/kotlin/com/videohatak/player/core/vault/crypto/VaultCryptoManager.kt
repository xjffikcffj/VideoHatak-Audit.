package com.videohatak.player.core.vault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedFileHeader(
    val fileSalt: ByteArray,
    val encryptedDek: ByteArray,
    val plaintextSizeBytes: Long,
    val chunkSize: Int,
    val headerSize: Int
)

data class EncryptionResult(
    val fileSalt: ByteArray,
    val encryptedDek: ByteArray,
    val plaintextSizeBytes: Long,
    val chunkSize: Int,
    val headerSize: Int
)

internal interface VaultFileCommitter {
    @Throws(IOException::class)
    fun commitTempFile(tempFile: File, destinationFile: File)
}

internal object DefaultVaultFileCommitter : VaultFileCommitter {
    @Throws(IOException::class)
    override fun commitTempFile(tempFile: File, destinationFile: File) {
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            throw IOException("Encrypted temp file is missing or empty: ${tempFile.name}")
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                Files.move(
                    tempFile.toPath(),
                    destinationFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                if (!destinationFile.exists() || destinationFile.length() <= 0L) {
                    throw IOException("Destination missing after atomic move")
                }
                return
            } catch (_: Exception) {
                // Fall back to POSIX rename / stream copy if ATOMIC_MOVE is unsupported on filesystem
            }
        }

        val previousDest = if (destinationFile.exists()) {
            File(destinationFile.parentFile, "${destinationFile.name}.bak").also { bak ->
                if (bak.exists()) bak.delete()
                destinationFile.renameTo(bak)
            }
        } else null

        if (tempFile.renameTo(destinationFile)) {
            if (!destinationFile.exists() || destinationFile.length() <= 0L) {
                previousDest?.renameTo(destinationFile)
                throw IOException("Renamed encrypted destination is empty")
            }
            previousDest?.delete()
            return
        }

        // Cross-volume or locked-file fallback: durable copy + fsync
        try {
            val expectedBytes = tempFile.length()
            FileInputStream(tempFile).use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!destinationFile.exists() || destinationFile.length() != expectedBytes) {
                throw IOException("Copied encrypted file length mismatch")
            }
            tempFile.delete()
            previousDest?.delete()
        } catch (e: Exception) {
            if (destinationFile.exists()) destinationFile.delete()
            previousDest?.renameTo(destinationFile)
            throw if (e is IOException) e else IOException("Failed to commit encrypted file", e)
        }
    }
}

class VaultCryptoManager internal constructor(
    private val keyStoreProvider: String = ANDROID_KEYSTORE,
    private val fileCommitter: VaultFileCommitter = DefaultVaultFileCommitter
) {
    constructor(keyStoreProvider: String = ANDROID_KEYSTORE) : this(keyStoreProvider, DefaultVaultFileCommitter)

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "VideoHatakVaultMasterKey"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val MAGIC_HEADER = "VHTKVLT1"
        private const val META_PREFIX = "VHTKMETA1:"
        private val META_AAD = "VHTKMETA1".toByteArray(Charsets.UTF_8)
    }

    private val secureRandom = SecureRandom()
    private var testMasterKey: SecretKey? = null

    private fun getOrCreateSecretKey(): SecretKey {
        if (keyStoreProvider != ANDROID_KEYSTORE) {
            if (testMasterKey == null) {
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(256)
                testMasterKey = keyGen.generateKey()
            }
            return testMasterKey!!
        }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    fun generateDataEncryptionKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    fun buildHeaderAad(magic: String, fileSalt: ByteArray, chunkSize: Int, plaintextSize: Long): ByteArray {
        val magicBytes = magic.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(magicBytes.size + fileSalt.size + 4 + 8)
            .put(magicBytes)
            .put(fileSalt)
            .putInt(chunkSize)
            .putLong(plaintextSize)
            .array()
    }

    fun buildChunkAad(fileSalt: ByteArray, chunkIndex: Int): ByteArray {
        return ByteBuffer.allocate(fileSalt.size + 4)
            .put(fileSalt)
            .putInt(chunkIndex)
            .array()
    }

    fun wrapDataKey(dek: SecretKey, aad: ByteArray? = null): ByteArray {
        val masterKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        val iv = cipher.iv
        val encrypted = cipher.doFinal(dek.encoded)
        return iv + encrypted
    }

    fun unwrapDataKey(wrapped: ByteArray, aad: ByteArray? = null): SecretKey {
        val masterKey = getOrCreateSecretKey()
        val iv = wrapped.copyOfRange(0, 12)
        val ciphertext = wrapped.copyOfRange(12, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)

        if (aad != null) {
            try {
                cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
                cipher.updateAAD(aad)
                val raw = cipher.doFinal(ciphertext)
                val key = SecretKeySpec(raw, "AES")
                zeroByteArray(raw)
                return key
            } catch (e: AEADBadTagException) {
                // If AAD was provided but failed, we rethrow if we want strict enforcement
                // but we also check if unwrap without AAD succeeds for legacy files
                throw e
            }
        }

        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
        val raw = cipher.doFinal(ciphertext)
        val key = SecretKeySpec(raw, "AES")
        zeroByteArray(raw)
        return key
    }

    fun unwrapDataKeyWithFallback(wrapped: ByteArray, aad: ByteArray?): SecretKey {
        if (aad != null) {
            try {
                return unwrapDataKey(wrapped, aad)
            } catch (_: AEADBadTagException) {
                // Backward compatibility fallback for files created without AAD binding
                return unwrapDataKey(wrapped, null)
            }
        }
        return unwrapDataKey(wrapped, null)
    }

    fun deriveChunkNonce(fileSalt: ByteArray, chunkIndex: Int): ByteArray {
        val nonce = ByteArray(12)
        System.arraycopy(fileSalt, 0, nonce, 0, 8)
        nonce[8] = (chunkIndex ushr 24).toByte()
        nonce[9] = (chunkIndex ushr 16).toByte()
        nonce[10] = (chunkIndex ushr 8).toByte()
        nonce[11] = chunkIndex.toByte()
        return nonce
    }

    fun zeroByteArray(array: ByteArray?) {
        if (array != null) {
            Arrays.fill(array, 0.toByte())
        }
    }

    fun encryptMetadata(plaintext: String): String {
        val masterKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        cipher.updateAAD(META_AAD)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = iv + ciphertext
        return META_PREFIX + VaultBase64.encodeNoWrap(payload)
    }

    fun decryptMetadata(encoded: String): String {
        if (!encoded.startsWith(META_PREFIX)) {
            throw IllegalArgumentException("Invalid encrypted vault metadata format")
        }
        val payload = VaultBase64.decode(encoded.removePrefix(META_PREFIX))
        if (payload.size <= 12) {
            throw IllegalArgumentException("Corrupted vault metadata payload")
        }
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val masterKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(META_AAD)
        val plainBytes = cipher.doFinal(ciphertext)
        return try {
            String(plainBytes, Charsets.UTF_8)
        } finally {
            zeroByteArray(plainBytes)
        }
    }

    /**
     * Reads a full chunk of exactly [targetSize] bytes from [inputStream] into [buffer],
     * handling partial stream reads and intermittent 0-byte non-blocking reads safely.
     * Prevents silent corruption and infinite loops:
     * - Only returns a partial chunk if the stream genuinely reaches EOF (bytesRead == -1).
     * - If the stream returns 0 bytes repeatedly without making forward progress,
     *   it throws an IOException to abort encryption cleanly and prevent writing malformed chunks.
     * Returns total bytes read, or -1 if genuine EOF occurred before reading any byte.
     */
    fun readChunkFully(inputStream: InputStream, buffer: ByteArray, targetSize: Int = buffer.size): Int {
        var totalBytesRead = 0
        var consecutiveZeroReads = 0
        val maxConsecutiveZeroReads = 50

        while (totalBytesRead < targetSize) {
            val bytesRead = inputStream.read(buffer, totalBytesRead, targetSize - totalBytesRead)
            if (bytesRead == -1) {
                // Genuine EOF reached
                break
            } else if (bytesRead == 0) {
                consecutiveZeroReads++
                if (consecutiveZeroReads >= maxConsecutiveZeroReads) {
                    throw java.io.IOException(
                        "Stream stalled: stream made no progress after $maxConsecutiveZeroReads consecutive 0-byte reads (read $totalBytesRead of $targetSize bytes)"
                    )
                }
                Thread.yield()
            } else {
                // Forward progress made, reset zero-read counter
                consecutiveZeroReads = 0
                totalBytesRead += bytesRead
            }
        }
        return if (totalBytesRead == 0) -1 else totalBytesRead
    }

    /**
     * Legacy single-stream encryption
     */
    fun encryptStream(outputStream: OutputStream): Pair<CipherOutputStream, ByteArray> {
        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherOutputStream = CipherOutputStream(outputStream, cipher)
        return Pair(cipherOutputStream, iv)
    }

    fun decryptStream(inputStream: InputStream, iv: ByteArray): CipherInputStream {
        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return CipherInputStream(inputStream, cipher)
    }

    fun encryptStream(
        inputStream: InputStream,
        destinationFile: File,
        totalBytesEstimated: Long,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): EncryptionResult {
        val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.tmp")
        val fileSalt = ByteArray(8).apply { secureRandom.nextBytes(this) }
        val dek = generateDataEncryptionKey()

        // Initial placeholder wrapped DEK (60 bytes: 12 IV + 32 key + 16 tag)
        val initialWrappedDek = wrapDataKey(dek)
        val buffer = ByteArray(chunkSize)
        var plaintextSize = 0L
        var raf: RandomAccessFile? = null

        try {
            val fileRaf = RandomAccessFile(tempFile, "rw")
            raf = fileRaf

            // Write magic and header
            fileRaf.write(MAGIC_HEADER.toByteArray(Charsets.UTF_8))
            fileRaf.write(fileSalt)
            fileRaf.writeInt(initialWrappedDek.size)
            val wrappedDekOffset = fileRaf.filePointer
            fileRaf.write(initialWrappedDek)
            fileRaf.writeInt(chunkSize)
            val plainSizeOffset = fileRaf.filePointer
            fileRaf.writeLong(0L) // Placeholder for total plain size
            val headerSize = fileRaf.filePointer.toInt()

            var chunkIndex = 0
            var bytesRead: Int

            while (readChunkFully(inputStream, buffer, chunkSize).also { bytesRead = it } != -1) {
                if (isCancelled()) {
                    fileRaf.close()
                    raf = null
                    tempFile.delete()
                    zeroByteArray(buffer)
                    throw InterruptedException("Encryption cancelled by user")
                }

                val nonce = deriveChunkNonce(fileSalt, chunkIndex)
                val chunkAad = buildChunkAad(fileSalt, chunkIndex)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
                cipher.updateAAD(chunkAad)

                val encryptedChunk = cipher.doFinal(buffer, 0, bytesRead)
                fileRaf.writeInt(encryptedChunk.size)
                fileRaf.write(encryptedChunk)

                plaintextSize += bytesRead
                chunkIndex++

                if (totalBytesEstimated > 0) {
                    onProgress((plaintextSize.toFloat() / totalBytesEstimated).coerceIn(0f, 1f))
                }
            }

            // Now that exact plaintextSize is known, bind header fields with AAD
            val headerAad = buildHeaderAad(MAGIC_HEADER, fileSalt, chunkSize, plaintextSize)
            val finalWrappedDek = wrapDataKey(dek, headerAad)

            // Seek back and rewrite authenticated wrapped DEK & actual plaintext size
            fileRaf.seek(wrappedDekOffset)
            fileRaf.write(finalWrappedDek)
            fileRaf.seek(plainSizeOffset)
            fileRaf.writeLong(plaintextSize)
            fileRaf.fd.sync()
            fileRaf.close()
            raf = null

            fileCommitter.commitTempFile(tempFile, destinationFile)

            return EncryptionResult(
                fileSalt = fileSalt,
                encryptedDek = finalWrappedDek,
                plaintextSizeBytes = plaintextSize,
                chunkSize = chunkSize,
                headerSize = headerSize
            )
        } catch (e: Exception) {
            try { raf?.close() } catch (_: Exception) {}
            raf = null
            if (tempFile.exists()) tempFile.delete()
            throw e
        } finally {
            try { raf?.close() } catch (_: Exception) {}
            zeroByteArray(buffer)
        }
    }

    fun parseEncryptedFileHeader(file: File): EncryptedFileHeader {
        val raf = RandomAccessFile(file, "r")
        raf.use {
            val magicBytes = ByteArray(8)
            it.readFully(magicBytes)
            val magic = String(magicBytes, Charsets.UTF_8)
            if (magic != MAGIC_HEADER) {
                throw IllegalArgumentException("Invalid vault magic header: $magic")
            }

            val fileSalt = ByteArray(8)
            it.readFully(fileSalt)

            val wrappedDekSize = it.readInt()
            if (wrappedDekSize !in 16..4096) {
                throw IOException("Corrupted vault wrapped DEK size: $wrappedDekSize")
            }
            val wrappedDek = ByteArray(wrappedDekSize)
            it.readFully(wrappedDek)

            val chunkSize = it.readInt()
            if (chunkSize !in 1024..(16 * 1024 * 1024)) {
                throw IOException("Corrupted vault chunk size: $chunkSize")
            }
            val plaintextSize = it.readLong()
            if (plaintextSize < 0L) {
                throw IOException("Corrupted vault plaintext size: $plaintextSize")
            }
            val headerSize = it.filePointer.toInt()

            return EncryptedFileHeader(
                fileSalt = fileSalt,
                encryptedDek = wrappedDek,
                plaintextSizeBytes = plaintextSize,
                chunkSize = chunkSize,
                headerSize = headerSize
            )
        }
    }

    private fun validateEncryptedChunkSize(encSize: Int, chunkSize: Int): Int {
        if (encSize <= 0 || encSize > chunkSize + 1024) {
            throw IOException("Corrupted encrypted chunk size: $encSize")
        }
        return encSize
    }

    fun verifyEncryptedFileIntegrity(encryptedFile: File) {
        if (!encryptedFile.exists() || encryptedFile.length() <= 0L) {
            throw IllegalStateException("Encrypted vault file verification failed")
        }
        val header = parseEncryptedFileHeader(encryptedFile)
        val headerAad = buildHeaderAad(
            MAGIC_HEADER,
            header.fileSalt,
            header.chunkSize,
            header.plaintextSizeBytes
        )
        val dek = unwrapDataKeyWithFallback(header.encryptedDek, headerAad)
        try {
            RandomAccessFile(encryptedFile, "r").use { raf ->
                raf.seek(header.headerSize.toLong())
                var totalPlaintext = 0L
                var chunkIndex = 0
                while (totalPlaintext < header.plaintextSizeBytes) {
                    val encSize = validateEncryptedChunkSize(raf.readInt(), header.chunkSize)
                    val encBytes = ByteArray(encSize)
                    raf.readFully(encBytes)

                    val nonce = deriveChunkNonce(header.fileSalt, chunkIndex)
                    val chunkAad = buildChunkAad(header.fileSalt, chunkIndex)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
                    cipher.updateAAD(chunkAad)

                    val plainChunk = try {
                        cipher.doFinal(encBytes)
                    } catch (e: AEADBadTagException) {
                        try {
                            val fallbackCipher = Cipher.getInstance(TRANSFORMATION)
                            fallbackCipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
                            fallbackCipher.doFinal(encBytes)
                        } catch (_: Exception) {
                            throw e
                        }
                    }

                    try {
                        totalPlaintext += plainChunk.size
                        chunkIndex++
                    } finally {
                        zeroByteArray(plainChunk)
                    }
                }
                if (totalPlaintext != header.plaintextSizeBytes) {
                    throw IOException("Encrypted vault plaintext size mismatch: expected ${header.plaintextSizeBytes}, got $totalPlaintext")
                }
                if (raf.filePointer != raf.length()) {
                    throw IOException("Unexpected trailing bytes after encrypted chunks at ${raf.filePointer}/${raf.length()}")
                }
            }
        } finally {
            runCatching { zeroByteArray(dek.encoded) }
            runCatching { dek.destroy() }
        }
    }

    fun readDecryptedRange(
        encryptedFile: File,
        fileSalt: ByteArray,
        wrappedDek: ByteArray,
        chunkSize: Int,
        plaintextSize: Long,
        headerSize: Int,
        requestedOffset: Long,
        outBuffer: ByteArray,
        outBufferOffset: Int,
        requestedLength: Int
    ): Int {
        if (requestedOffset >= plaintextSize || plaintextSize == 0L) {
            return -1
        }
        if (chunkSize <= 0) {
            throw IOException("Invalid chunk size: $chunkSize")
        }

        val headerAad = buildHeaderAad(MAGIC_HEADER, fileSalt, chunkSize, plaintextSize)
        val dek = unwrapDataKeyWithFallback(wrappedDek, headerAad)
        val bytesToRead = minOf(requestedLength.toLong(), plaintextSize - requestedOffset).toInt()

        val startChunk = (requestedOffset / chunkSize).toInt()
        val endChunk = ((requestedOffset + bytesToRead - 1) / chunkSize).toInt()

        var totalCopied = 0
        var currentOffsetInRead = requestedOffset

        try {
            val raf = RandomAccessFile(encryptedFile, "r")
            raf.use {
                val fullChunkEncryptedTotal = 4L + chunkSize + 16L
                val startChunkOffset = headerSize.toLong() + startChunk.toLong() * fullChunkEncryptedTotal
                var positionedViaFastSeek = false

                if (startChunkOffset >= headerSize.toLong() && startChunkOffset + 4 <= raf.length()) {
                    it.seek(startChunkOffset)
                    val probeSize = it.readInt()
                    if (probeSize in 1..(chunkSize + 1024) && it.filePointer + probeSize <= raf.length()) {
                        it.seek(startChunkOffset)
                        positionedViaFastSeek = true
                    }
                }

                if (!positionedViaFastSeek) {
                    it.seek(headerSize.toLong())
                    var currentChunk = 0
                    while (currentChunk < startChunk) {
                        val encSize = validateEncryptedChunkSize(it.readInt(), chunkSize)
                        val skipped = it.skipBytes(encSize)
                        if (skipped != encSize) {
                            throw IOException("Unexpected EOF while skipping encrypted chunk $currentChunk")
                        }
                        currentChunk++
                    }
                }

                var currentChunk = startChunk
                while (currentChunk <= endChunk && totalCopied < bytesToRead) {
                    val encSize = validateEncryptedChunkSize(it.readInt(), chunkSize)
                    val encBytes = ByteArray(encSize)
                    it.readFully(encBytes)

                    val nonce = deriveChunkNonce(fileSalt, currentChunk)
                    val chunkAad = buildChunkAad(fileSalt, currentChunk)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
                    cipher.updateAAD(chunkAad)

                    val plainChunk = try {
                        cipher.doFinal(encBytes)
                    } catch (e: AEADBadTagException) {
                        // Try fallback without chunk AAD for legacy chunks
                        try {
                            val fallbackCipher = Cipher.getInstance(TRANSFORMATION)
                            fallbackCipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
                            fallbackCipher.doFinal(encBytes)
                        } catch (_: Exception) {
                            throw e
                        }
                    }

                    try {
                        val chunkStartOffset = currentChunk.toLong() * chunkSize
                        val offsetInChunk = (currentOffsetInRead - chunkStartOffset).toInt()
                        val availableInChunk = plainChunk.size - offsetInChunk
                        val needToCopy = minOf(availableInChunk, bytesToRead - totalCopied)

                        System.arraycopy(plainChunk, offsetInChunk, outBuffer, outBufferOffset + totalCopied, needToCopy)
                        totalCopied += needToCopy
                        currentOffsetInRead += needToCopy
                        currentChunk++
                    } finally {
                        zeroByteArray(plainChunk)
                    }
                }
            }
        } finally {
            runCatching { zeroByteArray(dek.encoded) }
            runCatching { dek.destroy() }
        }

        return totalCopied
    }
}

