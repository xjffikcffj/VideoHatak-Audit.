package com.videohatak.player.core.vault.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
}

class VaultDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), VaultDatabaseStore {
    companion object {
        private const val DATABASE_NAME = "videohatak_vault.db"
        const val DATABASE_VERSION = 3

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
                $COLUMN_ENCRYPTED_META TEXT NOT NULL DEFAULT ''
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
            val idIdx = it.getColumnIndexOrThrow(COLUMN_ID)
            val nameIdx = it.getColumnIndexOrThrow(COLUMN_DISPLAY_NAME)
            val origSizeIdx = it.getColumnIndexOrThrow(COLUMN_ORIGINAL_SIZE)
            val encSizeIdx = it.getColumnIndexOrThrow(COLUMN_ENCRYPTED_SIZE)
            val durIdx = it.getColumnIndexOrThrow(COLUMN_DURATION_MS)
            val mimeIdx = it.getColumnIndexOrThrow(COLUMN_MIME_TYPE)
            val widthIdx = it.getColumnIndexOrThrow(COLUMN_WIDTH)
            val heightIdx = it.getColumnIndexOrThrow(COLUMN_HEIGHT)
            val pathIdx = it.getColumnIndexOrThrow(COLUMN_RELATIVE_PATH)
            val createdIdx = it.getColumnIndexOrThrow(COLUMN_CREATED_AT)
            val ivIdx = it.getColumnIndexOrThrow(COLUMN_IV_BASE64)
            val metaIdx = it.getColumnIndex(COLUMN_ENCRYPTED_META)

            while (it.moveToNext()) {
                val metaRaw = if (metaIdx != -1) it.getString(metaIdx)?.ifBlank { null } else null
                list.add(
                    VaultVideo(
                        id = it.getLong(idIdx),
                        displayName = it.getString(nameIdx),
                        originalSizeBytes = it.getLong(origSizeIdx),
                        encryptedSizeBytes = it.getLong(encSizeIdx),
                        durationMs = it.getLong(durIdx),
                        mimeType = it.getString(mimeIdx),
                        width = it.getInt(widthIdx),
                        height = it.getInt(heightIdx),
                        relativeEncryptedPath = it.getString(pathIdx),
                        createdAtEpochMs = it.getLong(createdIdx),
                        ivBase64 = it.getString(ivIdx),
                        encryptedMetaBase64 = metaRaw
                    )
                )
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
                val metaIdx = it.getColumnIndex(COLUMN_ENCRYPTED_META)
                val metaRaw = if (metaIdx != -1) it.getString(metaIdx)?.ifBlank { null } else null
                return VaultVideo(
                    id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID)),
                    displayName = it.getString(it.getColumnIndexOrThrow(COLUMN_DISPLAY_NAME)),
                    originalSizeBytes = it.getLong(it.getColumnIndexOrThrow(COLUMN_ORIGINAL_SIZE)),
                    encryptedSizeBytes = it.getLong(it.getColumnIndexOrThrow(COLUMN_ENCRYPTED_SIZE)),
                    durationMs = it.getLong(it.getColumnIndexOrThrow(COLUMN_DURATION_MS)),
                    mimeType = it.getString(it.getColumnIndexOrThrow(COLUMN_MIME_TYPE)),
                    width = it.getInt(it.getColumnIndexOrThrow(COLUMN_WIDTH)),
                    height = it.getInt(it.getColumnIndexOrThrow(COLUMN_HEIGHT)),
                    relativeEncryptedPath = it.getString(it.getColumnIndexOrThrow(COLUMN_RELATIVE_PATH)),
                    createdAtEpochMs = it.getLong(it.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
                    ivBase64 = it.getString(it.getColumnIndexOrThrow(COLUMN_IV_BASE64)),
                    encryptedMetaBase64 = metaRaw
                )
            }
        }
        return null
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
