package com.videohatak.player.core.vault.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.videohatak.player.core.vault.model.VaultEntryState
import com.videohatak.player.core.vault.model.VaultHiddenOriginal
import com.videohatak.player.core.vault.model.VaultOriginalStorageType
import com.videohatak.player.core.vault.model.VaultVideo

interface VaultDatabaseStore {
    fun insertVideo(video: VaultVideo): Long
    fun updateEncryptedMetadata(
        id: Long,
        maskedDisplayName: String,
        internalPath: String,
        maskedMimeType: String,
        encryptedMetaBase64: String
    ): Int
    fun getAllVideos(): List<VaultVideo>
    fun getVideoById(id: Long): VaultVideo?
    fun deleteVideo(id: Long): Int
    fun getVideoCount(): Int = getAllVideos().size

    fun updateVaultState(
        id: Long,
        vaultState: VaultEntryState,
        isHiddenInLibrary: Boolean,
        stateUpdatedAtMs: Long = System.currentTimeMillis(),
        lastError: String? = null
    ): Int = if (getVideoById(id) != null) 1 else 0

    fun updateOriginalDeletionStatus(
        id: Long,
        vaultState: VaultEntryState,
        isHiddenInLibrary: Boolean,
        originalDeleteVerified: Boolean,
        publicRestoredUri: String = "",
        stateUpdatedAtMs: Long = System.currentTimeMillis(),
        lastError: String? = null
    ): Int = if (getVideoById(id) != null) 1 else 0

    fun getHiddenOriginalEntries(): List<VaultHiddenOriginal> =
        getAllVideos().mapNotNull { it.toHiddenOriginalOrNull() }
}

class VaultDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), VaultDatabaseStore {
    companion object {
        private const val DATABASE_NAME = "videohatak_vault.db"
        const val DATABASE_VERSION = 5

        const val TABLE_VAULT = "vault_videos"
        const val COLUMN_ID = "id"
        const val COLUMN_DISPLAY_NAME = "display_name"
        const val COLUMN_ORIGINAL_SIZE = "original_size_bytes"
        const val COLUMN_ENCRYPTED_SIZE = "encrypted_size_bytes"
        const val COLUMN_DURATION_MS = "duration_ms"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_WIDTH = "width"
        const val COLUMN_HEIGHT = "height"
        const val COLUMN_RELATIVE_PATH = "relative_encrypted_path"
        const val COLUMN_CREATED_AT = "created_at_epoch_ms"
        const val COLUMN_IV_BASE64 = "iv_base64"
        const val COLUMN_ENCRYPTED_META = "encrypted_meta_base64"

        // Option (A) reversible original reference & lifecycle columns (v4)
        const val COLUMN_ORIGINAL_URI = "original_uri"
        const val COLUMN_ORIGINAL_MEDIA_STORE_ID = "original_media_store_id"
        const val COLUMN_ORIGINAL_FILE_PATH = "original_file_path"
        const val COLUMN_ORIGINAL_DATE_MODIFIED_SEC = "original_date_modified_sec"
        const val COLUMN_ORIGINAL_SHA256 = "original_sha256"
        const val COLUMN_STORAGE_TYPE = "storage_type"
        const val COLUMN_URI_PERMISSION_FLAGS = "uri_permission_flags"
        const val COLUMN_VAULT_STATE = "vault_state"
        const val COLUMN_IS_HIDDEN_IN_LIBRARY = "is_hidden_in_library"
        const val COLUMN_STATE_UPDATED_AT_MS = "state_updated_at_ms"
        const val COLUMN_LAST_ERROR = "last_error"

        // True Private Vault deletion & restore tracking columns (v5)
        const val COLUMN_ORIGINAL_DELETE_VERIFIED = "original_delete_verified"
        const val COLUMN_PUBLIC_RESTORED_URI = "public_restored_uri"
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTablesIfNotExists(db)
    }

    private fun createTablesIfNotExists(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_VAULT (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_DISPLAY_NAME TEXT NOT NULL,
                $COLUMN_ORIGINAL_SIZE INTEGER NOT NULL,
                $COLUMN_ENCRYPTED_SIZE INTEGER NOT NULL,
                $COLUMN_DURATION_MS INTEGER NOT NULL,
                $COLUMN_MIME_TYPE TEXT NOT NULL,
                $COLUMN_WIDTH INTEGER NOT NULL,
                $COLUMN_HEIGHT INTEGER NOT NULL,
                $COLUMN_RELATIVE_PATH TEXT NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_IV_BASE64 TEXT NOT NULL,
                $COLUMN_ENCRYPTED_META TEXT NOT NULL DEFAULT '',
                $COLUMN_ORIGINAL_URI TEXT NOT NULL DEFAULT '',
                $COLUMN_ORIGINAL_MEDIA_STORE_ID INTEGER,
                $COLUMN_ORIGINAL_FILE_PATH TEXT NOT NULL DEFAULT '',
                $COLUMN_ORIGINAL_DATE_MODIFIED_SEC INTEGER NOT NULL DEFAULT 0,
                $COLUMN_ORIGINAL_SHA256 TEXT NOT NULL DEFAULT '',
                $COLUMN_STORAGE_TYPE TEXT NOT NULL DEFAULT 'UNKNOWN',
                $COLUMN_URI_PERMISSION_FLAGS INTEGER NOT NULL DEFAULT 0,
                $COLUMN_VAULT_STATE TEXT NOT NULL DEFAULT 'ENCRYPTED_VERIFIED',
                $COLUMN_IS_HIDDEN_IN_LIBRARY INTEGER NOT NULL DEFAULT 0,
                $COLUMN_STATE_UPDATED_AT_MS INTEGER NOT NULL DEFAULT 0,
                $COLUMN_LAST_ERROR TEXT NOT NULL DEFAULT '',
                $COLUMN_ORIGINAL_DELETE_VERIFIED INTEGER NOT NULL DEFAULT 0,
                $COLUMN_PUBLIC_RESTORED_URI TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        upgradeSchema(db, oldVersion, newVersion)
    }

    internal interface SqlUpgradeAdapter {
        fun beginTransaction()
        fun setTransactionSuccessful()
        fun endTransaction()
        fun execSQL(sql: String)
        fun hasColumn(tableName: String, columnName: String): Boolean
    }

    internal fun upgradeSchema(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        val adapter = object : SqlUpgradeAdapter {
            override fun beginTransaction() = db.beginTransaction()
            override fun setTransactionSuccessful() = db.setTransactionSuccessful()
            override fun endTransaction() = db.endTransaction()
            override fun execSQL(sql: String) = db.execSQL(sql)
            override fun hasColumn(tableName: String, columnName: String): Boolean =
                this@VaultDatabaseHelper.hasColumn(db, tableName, columnName)
        }
        upgradeSchema(adapter, oldVersion, newVersion)
    }

    internal fun upgradeSchema(db: SqlUpgradeAdapter, oldVersion: Int, newVersion: Int) {
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_VAULT (
                    $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COLUMN_DISPLAY_NAME TEXT NOT NULL,
                    $COLUMN_ORIGINAL_SIZE INTEGER NOT NULL,
                    $COLUMN_ENCRYPTED_SIZE INTEGER NOT NULL,
                    $COLUMN_DURATION_MS INTEGER NOT NULL,
                    $COLUMN_MIME_TYPE TEXT NOT NULL,
                    $COLUMN_WIDTH INTEGER NOT NULL,
                    $COLUMN_HEIGHT INTEGER NOT NULL,
                    $COLUMN_RELATIVE_PATH TEXT NOT NULL,
                    $COLUMN_CREATED_AT INTEGER NOT NULL,
                    $COLUMN_IV_BASE64 TEXT NOT NULL,
                    $COLUMN_ENCRYPTED_META TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            if (oldVersion < 3 && !db.hasColumn(TABLE_VAULT, COLUMN_ENCRYPTED_META)) {
                db.execSQL(
                    "ALTER TABLE $TABLE_VAULT ADD COLUMN $COLUMN_ENCRYPTED_META TEXT NOT NULL DEFAULT ''"
                )
            }
            if (oldVersion < 4 && newVersion >= 4) {
                val v4Columns = listOf(
                    COLUMN_ORIGINAL_URI to "TEXT NOT NULL DEFAULT ''",
                    COLUMN_ORIGINAL_MEDIA_STORE_ID to "INTEGER",
                    COLUMN_ORIGINAL_FILE_PATH to "TEXT NOT NULL DEFAULT ''",
                    COLUMN_ORIGINAL_DATE_MODIFIED_SEC to "INTEGER NOT NULL DEFAULT 0",
                    COLUMN_ORIGINAL_SHA256 to "TEXT NOT NULL DEFAULT ''",
                    COLUMN_STORAGE_TYPE to "TEXT NOT NULL DEFAULT 'UNKNOWN'",
                    COLUMN_URI_PERMISSION_FLAGS to "INTEGER NOT NULL DEFAULT 0",
                    COLUMN_VAULT_STATE to "TEXT NOT NULL DEFAULT 'ENCRYPTED_VERIFIED'",
                    COLUMN_IS_HIDDEN_IN_LIBRARY to "INTEGER NOT NULL DEFAULT 0",
                    COLUMN_STATE_UPDATED_AT_MS to "INTEGER NOT NULL DEFAULT 0",
                    COLUMN_LAST_ERROR to "TEXT NOT NULL DEFAULT ''"
                )
                for ((colName, colDef) in v4Columns) {
                    if (!db.hasColumn(TABLE_VAULT, colName)) {
                        db.execSQL("ALTER TABLE $TABLE_VAULT ADD COLUMN $colName $colDef")
                    }
                }
            }
            if (oldVersion < 5 && newVersion >= 5) {
                val v5Columns = listOf(
                    COLUMN_ORIGINAL_DELETE_VERIFIED to "INTEGER NOT NULL DEFAULT 0",
                    COLUMN_PUBLIC_RESTORED_URI to "TEXT NOT NULL DEFAULT ''"
                )
                for ((colName, colDef) in v5Columns) {
                    if (!db.hasColumn(TABLE_VAULT, colName)) {
                        db.execSQL("ALTER TABLE $TABLE_VAULT ADD COLUMN $colName $colDef")
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun hasColumn(db: SQLiteDatabase, tableName: String, columnName: String): Boolean {
        val cursor = db.rawQuery("PRAGMA table_info($tableName)", null)
        cursor.use {
            val nameIdx = it.getColumnIndex("name")
            if (nameIdx == -1) return false
            while (it.moveToNext()) {
                if (it.getString(nameIdx) == columnName) {
                    return true
                }
            }
        }
        return false
    }

    override fun insertVideo(video: VaultVideo): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            if (video.id > 0) {
                put(COLUMN_ID, video.id)
            }
            put(COLUMN_DISPLAY_NAME, video.displayName)
            put(COLUMN_ORIGINAL_SIZE, video.originalSizeBytes)
            put(COLUMN_ENCRYPTED_SIZE, video.encryptedSizeBytes)
            put(COLUMN_DURATION_MS, video.durationMs)
            put(COLUMN_MIME_TYPE, video.mimeType)
            put(COLUMN_WIDTH, video.width)
            put(COLUMN_HEIGHT, video.height)
            put(COLUMN_RELATIVE_PATH, video.relativeEncryptedPath)
            put(COLUMN_CREATED_AT, video.createdAtEpochMs)
            put(COLUMN_IV_BASE64, video.ivBase64)
            put(COLUMN_ENCRYPTED_META, video.encryptedMetaBase64 ?: "")
            put(COLUMN_ORIGINAL_URI, video.originalUri)
            if (video.originalMediaStoreId != null && video.originalMediaStoreId > 0L) {
                put(COLUMN_ORIGINAL_MEDIA_STORE_ID, video.originalMediaStoreId)
            } else {
                putNull(COLUMN_ORIGINAL_MEDIA_STORE_ID)
            }
            put(COLUMN_ORIGINAL_FILE_PATH, video.originalFilePath)
            put(COLUMN_ORIGINAL_DATE_MODIFIED_SEC, video.originalDateModifiedSec)
            put(COLUMN_ORIGINAL_SHA256, video.originalSha256)
            put(COLUMN_STORAGE_TYPE, video.storageType.storageValue)
            put(COLUMN_URI_PERMISSION_FLAGS, video.uriPermissionFlags)
            put(COLUMN_VAULT_STATE, video.vaultState.storageValue)
            put(COLUMN_IS_HIDDEN_IN_LIBRARY, if (video.isHiddenInLibrary) 1 else 0)
            put(COLUMN_STATE_UPDATED_AT_MS, video.stateUpdatedAtMs)
            put(COLUMN_LAST_ERROR, video.lastError ?: "")
            put(COLUMN_ORIGINAL_DELETE_VERIFIED, if (video.originalDeleteVerified) 1 else 0)
            put(COLUMN_PUBLIC_RESTORED_URI, video.publicRestoredUri)
        }
        return db.insertWithOnConflict(TABLE_VAULT, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun updateEncryptedMetadata(
        id: Long,
        maskedDisplayName: String,
        internalPath: String,
        maskedMimeType: String,
        encryptedMetaBase64: String
    ): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_DISPLAY_NAME, maskedDisplayName)
            put(COLUMN_RELATIVE_PATH, internalPath)
            put(COLUMN_MIME_TYPE, maskedMimeType)
            put(COLUMN_ENCRYPTED_META, encryptedMetaBase64)
        }
        return db.update(TABLE_VAULT, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    override fun updateVaultState(
        id: Long,
        vaultState: VaultEntryState,
        isHiddenInLibrary: Boolean,
        stateUpdatedAtMs: Long,
        lastError: String?
    ): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_VAULT_STATE, vaultState.storageValue)
            put(COLUMN_IS_HIDDEN_IN_LIBRARY, if (isHiddenInLibrary) 1 else 0)
            put(COLUMN_STATE_UPDATED_AT_MS, stateUpdatedAtMs)
            put(COLUMN_LAST_ERROR, lastError ?: "")
        }
        return db.update(TABLE_VAULT, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    override fun updateOriginalDeletionStatus(
        id: Long,
        vaultState: VaultEntryState,
        isHiddenInLibrary: Boolean,
        originalDeleteVerified: Boolean,
        publicRestoredUri: String,
        stateUpdatedAtMs: Long,
        lastError: String?
    ): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_VAULT_STATE, vaultState.storageValue)
            put(COLUMN_IS_HIDDEN_IN_LIBRARY, if (isHiddenInLibrary) 1 else 0)
            put(COLUMN_ORIGINAL_DELETE_VERIFIED, if (originalDeleteVerified) 1 else 0)
            if (publicRestoredUri.isNotBlank()) {
                put(COLUMN_PUBLIC_RESTORED_URI, publicRestoredUri)
            }
            put(COLUMN_STATE_UPDATED_AT_MS, stateUpdatedAtMs)
            put(COLUMN_LAST_ERROR, lastError ?: "")
        }
        return db.update(TABLE_VAULT, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    override fun getHiddenOriginalEntries(): List<VaultHiddenOriginal> {
        val list = mutableListOf<VaultHiddenOriginal>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_VAULT,
            arrayOf(
                COLUMN_ID,
                COLUMN_ORIGINAL_URI,
                COLUMN_ORIGINAL_MEDIA_STORE_ID,
                COLUMN_ORIGINAL_FILE_PATH,
                COLUMN_STORAGE_TYPE,
                COLUMN_VAULT_STATE
            ),
            "$COLUMN_IS_HIDDEN_IN_LIBRARY = 1 OR $COLUMN_VAULT_STATE IN ('ORIGINAL_HIDDEN', 'ORIGIN_DELETE_PENDING', 'AWAITING_ORIGINAL_DELETE_CONSENT', 'ORIGINAL_DELETE_FAILED', 'ORIGINAL_DELETED_VERIFIED', 'RESTORING', 'RESTORE_FAILED', 'ORIGINAL_MISSING', 'ORIGINAL_CHANGED', 'REQUIRES_PERMISSION')",
            null,
            null,
            null,
            "$COLUMN_CREATED_AT DESC"
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COLUMN_ID)
            val uriIdx = it.getColumnIndexOrThrow(COLUMN_ORIGINAL_URI)
            val msIdIdx = it.getColumnIndexOrThrow(COLUMN_ORIGINAL_MEDIA_STORE_ID)
            val pathIdx = it.getColumnIndexOrThrow(COLUMN_ORIGINAL_FILE_PATH)
            val storageIdx = it.getColumnIndexOrThrow(COLUMN_STORAGE_TYPE)
            val stateIdx = it.getColumnIndexOrThrow(COLUMN_VAULT_STATE)
            while (it.moveToNext()) {
                val origUri = it.getString(uriIdx).orEmpty()
                val origPath = it.getString(pathIdx).orEmpty()
                val msId = if (it.isNull(msIdIdx)) null else it.getLong(msIdIdx)
                val state = VaultEntryState.fromStorageValue(
                    it.getString(stateIdx),
                    fallback = VaultEntryState.ORIGINAL_HIDDEN
                )
                if (state.shouldHideOriginalInLibrary && (origUri.isNotBlank() || origPath.isNotBlank() || msId != null)) {
                    list.add(
                        VaultHiddenOriginal(
                            vaultId = it.getLong(idIdx),
                            originalUri = origUri,
                            originalMediaStoreId = msId,
                            originalFilePath = origPath,
                            storageType = VaultOriginalStorageType.fromStorageValue(it.getString(storageIdx)),
                            vaultState = state
                        )
                    )
                }
            }
        }
        return list
    }

    override fun getAllVideos(): List<VaultVideo> {
        val list = mutableListOf<VaultVideo>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_VAULT,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_CREATED_AT DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(readCursorRow(it))
            }
        }
        return list
    }

    override fun getVideoById(id: Long): VaultVideo? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_VAULT,
            null,
            "$COLUMN_ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return readCursorRow(it)
            }
        }
        return null
    }

    private fun readCursorRow(cursor: Cursor): VaultVideo {
        val metaIdx = cursor.getColumnIndex(COLUMN_ENCRYPTED_META)
        val metaRaw = if (metaIdx != -1) cursor.getString(metaIdx)?.ifBlank { null } else null

        val origUriIdx = cursor.getColumnIndex(COLUMN_ORIGINAL_URI)
        val origMsIdIdx = cursor.getColumnIndex(COLUMN_ORIGINAL_MEDIA_STORE_ID)
        val origPathIdx = cursor.getColumnIndex(COLUMN_ORIGINAL_FILE_PATH)
        val origModIdx = cursor.getColumnIndex(COLUMN_ORIGINAL_DATE_MODIFIED_SEC)
        val origShaIdx = cursor.getColumnIndex(COLUMN_ORIGINAL_SHA256)
        val storageTypeIdx = cursor.getColumnIndex(COLUMN_STORAGE_TYPE)
        val permFlagsIdx = cursor.getColumnIndex(COLUMN_URI_PERMISSION_FLAGS)
        val stateIdx = cursor.getColumnIndex(COLUMN_VAULT_STATE)
        val hiddenIdx = cursor.getColumnIndex(COLUMN_IS_HIDDEN_IN_LIBRARY)
        val stateUpdatedIdx = cursor.getColumnIndex(COLUMN_STATE_UPDATED_AT_MS)
        val lastErrorIdx = cursor.getColumnIndex(COLUMN_LAST_ERROR)
        val origDelVerifiedIdx = cursor.getColumnIndex(COLUMN_ORIGINAL_DELETE_VERIFIED)
        val pubRestoredUriIdx = cursor.getColumnIndex(COLUMN_PUBLIC_RESTORED_URI)

        val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT))
        return VaultVideo(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DISPLAY_NAME)),
            originalSizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ORIGINAL_SIZE)),
            encryptedSizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ENCRYPTED_SIZE)),
            durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DURATION_MS)),
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MIME_TYPE)),
            width = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_WIDTH)),
            height = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_HEIGHT)),
            relativeEncryptedPath = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_RELATIVE_PATH)),
            createdAtEpochMs = createdAt,
            ivBase64 = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IV_BASE64)),
            encryptedMetaBase64 = metaRaw,
            originalUri = if (origUriIdx != -1) cursor.getString(origUriIdx).orEmpty() else "",
            originalMediaStoreId = if (origMsIdIdx != -1 && !cursor.isNull(origMsIdIdx)) cursor.getLong(origMsIdIdx) else null,
            originalFilePath = if (origPathIdx != -1) cursor.getString(origPathIdx).orEmpty() else "",
            originalDateModifiedSec = if (origModIdx != -1) cursor.getLong(origModIdx) else 0L,
            originalSha256 = if (origShaIdx != -1) cursor.getString(origShaIdx).orEmpty() else "",
            storageType = VaultOriginalStorageType.fromStorageValue(
                if (storageTypeIdx != -1) cursor.getString(storageTypeIdx) else null
            ),
            uriPermissionFlags = if (permFlagsIdx != -1) cursor.getInt(permFlagsIdx) else 0,
            vaultState = VaultEntryState.fromStorageValue(
                if (stateIdx != -1) cursor.getString(stateIdx) else null,
                fallback = VaultEntryState.ENCRYPTED_VERIFIED
            ),
            isHiddenInLibrary = if (hiddenIdx != -1) cursor.getInt(hiddenIdx) == 1 else false,
            stateUpdatedAtMs = if (stateUpdatedIdx != -1) {
                cursor.getLong(stateUpdatedIdx).takeIf { it > 0L } ?: createdAt
            } else createdAt,
            lastError = if (lastErrorIdx != -1) cursor.getString(lastErrorIdx)?.ifBlank { null } else null,
            originalDeleteVerified = if (origDelVerifiedIdx != -1) cursor.getInt(origDelVerifiedIdx) == 1 else false,
            publicRestoredUri = if (pubRestoredUriIdx != -1) cursor.getString(pubRestoredUriIdx).orEmpty() else ""
        )
    }

    fun renameVideo(id: Long, newTitle: String): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_DISPLAY_NAME, newTitle)
        }
        return db.update(TABLE_VAULT, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    override fun deleteVideo(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_VAULT, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    override fun getVideoCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_VAULT", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}
