package com.videohatak.player.core.playlist.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.trash.model.TrashItem

interface TrashDatabaseStore {
    fun insertTrashItem(item: TrashItem): Long
    fun getAllTrashItems(): List<TrashItem>
    fun deleteTrashItemById(id: Long): Int
    fun clearAllTrashItems(): Int
}

interface BackupPlaylistStore {
    fun getPlaylists(): List<Playlist>
    fun getItemsForPlaylist(playlistId: Long): List<PlaylistItem>
    fun replaceAllPlaylistsAndItems(entries: List<Pair<Playlist, List<PlaylistItem>>>)
}

class VideoHatakDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), TrashDatabaseStore, BackupPlaylistStore {
    companion object {
        private const val DATABASE_NAME = "videohatak_playlists.db"
        const val DATABASE_VERSION = 3

        const val TABLE_PLAYLISTS = "playlists"
        const val TABLE_PLAYLIST_ITEMS = "playlist_items"
        const val TABLE_TRASH = "trash_items"

        // Playlists Columns
        const val COL_P_ID = "id"
        const val COL_P_TITLE = "title"
        const val COL_P_CREATED_AT = "created_at"
        const val COL_P_UPDATED_AT = "updated_at"

        // Playlist Items Columns
        const val COL_I_ID = "id"
        const val COL_I_PLAYLIST_ID = "playlist_id"
        const val COL_I_VIDEO_ID = "video_id"
        const val COL_I_ORDER_INDEX = "order_index"
        const val COL_I_TITLE = "title"
        const val COL_I_DURATION = "duration_ms"
        const val COL_I_SIZE = "size_bytes"
        const val COL_I_DATE_ADDED = "date_added_sec"
        const val COL_I_URI = "content_uri"
        const val COL_I_IS_AVAILABLE = "is_available"

        // Trash Items Columns
        const val COL_T_ID = "id"
        const val COL_T_VIDEO_ID = "video_id"
        const val COL_T_TITLE = "title"
        const val COL_T_DISPLAY_NAME = "display_name"
        const val COL_T_DURATION = "duration_ms"
        const val COL_T_SIZE = "size_bytes"
        const val COL_T_MIME_TYPE = "mime_type"
        const val COL_T_BUCKET_ID = "bucket_id"
        const val COL_T_BUCKET_NAME = "bucket_display_name"
        const val COL_T_WIDTH = "width"
        const val COL_T_HEIGHT = "height"
        const val COL_T_DATE_ADDED = "date_added_sec"
        const val COL_T_URI = "content_uri"
        const val COL_T_FILE_PATH = "file_path"
        const val COL_T_TRASHED_AT = "trashed_at_epoch_ms"
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTablesIfNotExists(db)
    }

    private fun createTablesIfNotExists(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLISTS (
                $COL_P_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_P_TITLE TEXT NOT NULL,
                $COL_P_CREATED_AT INTEGER NOT NULL,
                $COL_P_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLIST_ITEMS (
                $COL_I_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_I_PLAYLIST_ID INTEGER NOT NULL,
                $COL_I_VIDEO_ID INTEGER NOT NULL,
                $COL_I_ORDER_INDEX INTEGER NOT NULL,
                $COL_I_TITLE TEXT NOT NULL,
                $COL_I_DURATION INTEGER NOT NULL,
                $COL_I_SIZE INTEGER NOT NULL,
                $COL_I_DATE_ADDED INTEGER NOT NULL,
                $COL_I_URI TEXT NOT NULL,
                $COL_I_IS_AVAILABLE INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY ($COL_I_PLAYLIST_ID) REFERENCES $TABLE_PLAYLISTS($COL_P_ID) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TRASH (
                $COL_T_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_T_VIDEO_ID INTEGER NOT NULL,
                $COL_T_TITLE TEXT NOT NULL,
                $COL_T_DISPLAY_NAME TEXT NOT NULL,
                $COL_T_DURATION INTEGER NOT NULL,
                $COL_T_SIZE INTEGER NOT NULL,
                $COL_T_MIME_TYPE TEXT NOT NULL,
                $COL_T_BUCKET_ID TEXT NOT NULL,
                $COL_T_BUCKET_NAME TEXT NOT NULL,
                $COL_T_WIDTH INTEGER NOT NULL,
                $COL_T_HEIGHT INTEGER NOT NULL,
                $COL_T_DATE_ADDED INTEGER NOT NULL,
                $COL_T_URI TEXT NOT NULL UNIQUE,
                $COL_T_FILE_PATH TEXT NOT NULL,
                $COL_T_TRASHED_AT INTEGER NOT NULL
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
                this@VideoHatakDatabaseHelper.hasColumn(db, tableName, columnName)
        }
        upgradeSchema(adapter, oldVersion, newVersion)
    }

    internal fun upgradeSchema(db: SqlUpgradeAdapter, oldVersion: Int, newVersion: Int) {
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_PLAYLISTS (
                    $COL_P_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_P_TITLE TEXT NOT NULL,
                    $COL_P_CREATED_AT INTEGER NOT NULL,
                    $COL_P_UPDATED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_PLAYLIST_ITEMS (
                    $COL_I_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_I_PLAYLIST_ID INTEGER NOT NULL,
                    $COL_I_VIDEO_ID INTEGER NOT NULL,
                    $COL_I_ORDER_INDEX INTEGER NOT NULL,
                    $COL_I_TITLE TEXT NOT NULL,
                    $COL_I_DURATION INTEGER NOT NULL,
                    $COL_I_SIZE INTEGER NOT NULL,
                    $COL_I_DATE_ADDED INTEGER NOT NULL,
                    $COL_I_URI TEXT NOT NULL,
                    $COL_I_IS_AVAILABLE INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY ($COL_I_PLAYLIST_ID) REFERENCES $TABLE_PLAYLISTS($COL_P_ID) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_TRASH (
                    $COL_T_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_T_VIDEO_ID INTEGER NOT NULL,
                    $COL_T_TITLE TEXT NOT NULL,
                    $COL_T_DISPLAY_NAME TEXT NOT NULL,
                    $COL_T_DURATION INTEGER NOT NULL,
                    $COL_T_SIZE INTEGER NOT NULL,
                    $COL_T_MIME_TYPE TEXT NOT NULL,
                    $COL_T_BUCKET_ID TEXT NOT NULL,
                    $COL_T_BUCKET_NAME TEXT NOT NULL,
                    $COL_T_WIDTH INTEGER NOT NULL,
                    $COL_T_HEIGHT INTEGER NOT NULL,
                    $COL_T_DATE_ADDED INTEGER NOT NULL,
                    $COL_T_URI TEXT NOT NULL UNIQUE,
                    $COL_T_FILE_PATH TEXT NOT NULL,
                    $COL_T_TRASHED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )

            if (oldVersion < 2 && !db.hasColumn(TABLE_PLAYLIST_ITEMS, COL_I_IS_AVAILABLE)) {
                db.execSQL(
                    "ALTER TABLE $TABLE_PLAYLIST_ITEMS ADD COLUMN $COL_I_IS_AVAILABLE INTEGER NOT NULL DEFAULT 1"
                )
            }

            if (oldVersion < 3) {
                if (!db.hasColumn(TABLE_PLAYLIST_ITEMS, COL_I_IS_AVAILABLE)) {
                    db.execSQL(
                        "ALTER TABLE $TABLE_PLAYLIST_ITEMS ADD COLUMN $COL_I_IS_AVAILABLE INTEGER NOT NULL DEFAULT 1"
                    )
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

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun insertPlaylist(title: String): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_P_TITLE, title)
            put(COL_P_CREATED_AT, now)
            put(COL_P_UPDATED_AT, now)
        }
        return db.insert(TABLE_PLAYLISTS, null, values)
    }

    fun updatePlaylist(id: Long, title: String): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_P_TITLE, title)
            put(COL_P_UPDATED_AT, System.currentTimeMillis())
        }
        return db.update(TABLE_PLAYLISTS, values, "$COL_P_ID = ?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_PLAYLISTS, "$COL_P_ID = ?", arrayOf(id.toString()))
    }

    override fun getPlaylists(): List<Playlist> {
        val list = mutableListOf<Playlist>()
        val db = readableDatabase
        val query = """
            SELECT p.$COL_P_ID, p.$COL_P_TITLE, p.$COL_P_CREATED_AT, p.$COL_P_UPDATED_AT,
                   COUNT(i.$COL_I_ID) as item_count,
                   TOTAL(i.$COL_I_DURATION) as total_duration,
                   (SELECT $COL_I_URI FROM $TABLE_PLAYLIST_ITEMS WHERE $COL_I_PLAYLIST_ID = p.$COL_P_ID ORDER BY $COL_I_ORDER_INDEX ASC LIMIT 1) as first_uri
            FROM $TABLE_PLAYLISTS p
            LEFT JOIN $TABLE_PLAYLIST_ITEMS i ON p.$COL_P_ID = i.$COL_I_PLAYLIST_ID
            GROUP BY p.$COL_P_ID
            ORDER BY p.$COL_P_UPDATED_AT DESC
        """.trimIndent()

        val cursor = db.rawQuery(query, null)
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_P_ID)
            val titleIdx = it.getColumnIndexOrThrow(COL_P_TITLE)
            val createdIdx = it.getColumnIndexOrThrow(COL_P_CREATED_AT)
            val updatedIdx = it.getColumnIndexOrThrow(COL_P_UPDATED_AT)
            val countIdx = it.getColumnIndexOrThrow("item_count")
            val durIdx = it.getColumnIndexOrThrow("total_duration")
            val uriIdx = it.getColumnIndexOrThrow("first_uri")

            while (it.moveToNext()) {
                list.add(
                    Playlist(
                        id = it.getLong(idIdx),
                        title = it.getString(titleIdx),
                        createdAt = it.getLong(createdIdx),
                        updatedAt = it.getLong(updatedIdx),
                        itemCount = it.getInt(countIdx),
                        totalDurationMs = it.getLong(durIdx),
                        thumbnailUri = it.getString(uriIdx)
                    )
                )
            }
        }
        return list
    }

    fun insertItem(item: PlaylistItem): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_I_PLAYLIST_ID, item.playlistId)
            put(COL_I_VIDEO_ID, item.videoId)
            put(COL_I_ORDER_INDEX, item.orderIndex)
            put(COL_I_TITLE, item.videoTitle)
            put(COL_I_DURATION, item.durationMs)
            put(COL_I_SIZE, item.sizeBytes)
            put(COL_I_DATE_ADDED, item.dateAddedSec)
            put(COL_I_URI, item.contentUri)
            put(COL_I_IS_AVAILABLE, if (item.isAvailable) 1 else 0)
        }
        val res = db.insert(TABLE_PLAYLIST_ITEMS, null, values)
        touchPlaylist(item.playlistId)
        return res
    }

    fun deleteItem(itemId: Long, playlistId: Long): Int {
        val db = writableDatabase
        val rows = db.delete(TABLE_PLAYLIST_ITEMS, "$COL_I_ID = ?", arrayOf(itemId.toString()))
        touchPlaylist(playlistId)
        return rows
    }

    override fun getItemsForPlaylist(playlistId: Long): List<PlaylistItem> {
        val list = mutableListOf<PlaylistItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PLAYLIST_ITEMS,
            null,
            "$COL_I_PLAYLIST_ID = ?",
            arrayOf(playlistId.toString()),
            null,
            null,
            "$COL_I_ORDER_INDEX ASC"
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_I_ID)
            val pIdIdx = it.getColumnIndexOrThrow(COL_I_PLAYLIST_ID)
            val vIdIdx = it.getColumnIndexOrThrow(COL_I_VIDEO_ID)
            val ordIdx = it.getColumnIndexOrThrow(COL_I_ORDER_INDEX)
            val titleIdx = it.getColumnIndexOrThrow(COL_I_TITLE)
            val durIdx = it.getColumnIndexOrThrow(COL_I_DURATION)
            val sizeIdx = it.getColumnIndexOrThrow(COL_I_SIZE)
            val dateIdx = it.getColumnIndexOrThrow(COL_I_DATE_ADDED)
            val uriIdx = it.getColumnIndexOrThrow(COL_I_URI)
            val availIdx = it.getColumnIndexOrThrow(COL_I_IS_AVAILABLE)

            while (it.moveToNext()) {
                list.add(
                    PlaylistItem(
                        id = it.getLong(idIdx),
                        playlistId = it.getLong(pIdIdx),
                        videoId = it.getLong(vIdIdx),
                        orderIndex = it.getInt(ordIdx),
                        videoTitle = it.getString(titleIdx),
                        durationMs = it.getLong(durIdx),
                        sizeBytes = it.getLong(sizeIdx),
                        dateAddedSec = it.getLong(dateIdx),
                        contentUri = it.getString(uriIdx),
                        isAvailable = it.getInt(availIdx) == 1
                    )
                )
            }
        }
        return list
    }

    fun updateItemOrder(itemId: Long, newIndex: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_I_ORDER_INDEX, newIndex)
        }
        db.update(TABLE_PLAYLIST_ITEMS, values, "$COL_I_ID = ?", arrayOf(itemId.toString()))
    }

    fun updateItemAvailability(itemId: Long, isAvailable: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_I_IS_AVAILABLE, if (isAvailable) 1 else 0)
        }
        db.update(TABLE_PLAYLIST_ITEMS, values, "$COL_I_ID = ?", arrayOf(itemId.toString()))
    }

    fun updateItemUri(itemId: Long, newUri: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_I_URI, newUri)
            put(COL_I_IS_AVAILABLE, 1)
        }
        db.update(TABLE_PLAYLIST_ITEMS, values, "$COL_I_ID = ?", arrayOf(itemId.toString()))
    }

    fun updateItemsTitleForVideo(videoId: Long, newTitle: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_I_TITLE, newTitle)
        }
        db.update(TABLE_PLAYLIST_ITEMS, values, "$COL_I_VIDEO_ID = ?", arrayOf(videoId.toString()))
    }

    private fun touchPlaylist(playlistId: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_P_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_PLAYLISTS, values, "$COL_P_ID = ?", arrayOf(playlistId.toString()))
    }

    override fun insertTrashItem(item: TrashItem): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_T_VIDEO_ID, item.videoId)
            put(COL_T_TITLE, item.title)
            put(COL_T_DISPLAY_NAME, item.displayName)
            put(COL_T_DURATION, item.durationMs)
            put(COL_T_SIZE, item.sizeBytes)
            put(COL_T_MIME_TYPE, item.mimeType)
            put(COL_T_BUCKET_ID, item.bucketId)
            put(COL_T_BUCKET_NAME, item.bucketDisplayName)
            put(COL_T_WIDTH, item.width)
            put(COL_T_HEIGHT, item.height)
            put(COL_T_DATE_ADDED, item.dateAddedSec)
            put(COL_T_URI, item.contentUri)
            put(COL_T_FILE_PATH, item.filePath)
            put(COL_T_TRASHED_AT, item.trashedAtEpochMs)
        }
        return db.insertWithOnConflict(TABLE_TRASH, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getAllTrashItems(): List<TrashItem> {
        val list = mutableListOf<TrashItem>()
        val db = readableDatabase
        val cursor = db.query(TABLE_TRASH, null, null, null, null, null, "$COL_T_TRASHED_AT DESC")
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_T_ID)
            val vIdIdx = it.getColumnIndexOrThrow(COL_T_VIDEO_ID)
            val titleIdx = it.getColumnIndexOrThrow(COL_T_TITLE)
            val nameIdx = it.getColumnIndexOrThrow(COL_T_DISPLAY_NAME)
            val durIdx = it.getColumnIndexOrThrow(COL_T_DURATION)
            val sizeIdx = it.getColumnIndexOrThrow(COL_T_SIZE)
            val mimeIdx = it.getColumnIndexOrThrow(COL_T_MIME_TYPE)
            val bIdIdx = it.getColumnIndexOrThrow(COL_T_BUCKET_ID)
            val bNameIdx = it.getColumnIndexOrThrow(COL_T_BUCKET_NAME)
            val wIdx = it.getColumnIndexOrThrow(COL_T_WIDTH)
            val hIdx = it.getColumnIndexOrThrow(COL_T_HEIGHT)
            val dateIdx = it.getColumnIndexOrThrow(COL_T_DATE_ADDED)
            val uriIdx = it.getColumnIndexOrThrow(COL_T_URI)
            val pathIdx = it.getColumnIndexOrThrow(COL_T_FILE_PATH)
            val trashedIdx = it.getColumnIndexOrThrow(COL_T_TRASHED_AT)

            while (it.moveToNext()) {
                list.add(
                    TrashItem(
                        id = it.getLong(idIdx),
                        videoId = it.getLong(vIdIdx),
                        title = it.getString(titleIdx),
                        displayName = it.getString(nameIdx),
                        durationMs = it.getLong(durIdx),
                        sizeBytes = it.getLong(sizeIdx),
                        mimeType = it.getString(mimeIdx),
                        bucketId = it.getString(bIdIdx),
                        bucketDisplayName = it.getString(bNameIdx),
                        width = it.getInt(wIdx),
                        height = it.getInt(hIdx),
                        dateAddedSec = it.getLong(dateIdx),
                        contentUri = it.getString(uriIdx),
                        filePath = it.getString(pathIdx),
                        trashedAtEpochMs = it.getLong(trashedIdx)
                    )
                )
            }
        }
        return list
    }

    override fun deleteTrashItemById(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_TRASH, "$COL_T_ID = ?", arrayOf(id.toString()))
    }

    override fun clearAllTrashItems(): Int {
        val db = writableDatabase
        return db.delete(TABLE_TRASH, null, null)
    }

    override fun replaceAllPlaylistsAndItems(entries: List<Pair<Playlist, List<PlaylistItem>>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_PLAYLIST_ITEMS, null, null)
            db.delete(TABLE_PLAYLISTS, null, null)
            for ((playlist, items) in entries) {
                val pValues = ContentValues().apply {
                    put(COL_P_TITLE, playlist.title)
                    put(COL_P_CREATED_AT, playlist.createdAt)
                    put(COL_P_UPDATED_AT, playlist.updatedAt)
                }
                val newPlaylistId = db.insert(TABLE_PLAYLISTS, null, pValues)
                if (newPlaylistId <= 0L) {
                    throw IllegalStateException("تعذر إدراج قائمة التشغيل \"${playlist.title}\" في قاعدة البيانات")
                }
                items.forEachIndexed { idx, item ->
                    val iValues = ContentValues().apply {
                        put(COL_I_PLAYLIST_ID, newPlaylistId)
                        put(COL_I_VIDEO_ID, item.videoId)
                        put(COL_I_ORDER_INDEX, idx)
                        put(COL_I_TITLE, item.videoTitle)
                        put(COL_I_DURATION, item.durationMs)
                        put(COL_I_SIZE, item.sizeBytes)
                        put(COL_I_DATE_ADDED, item.dateAddedSec)
                        put(COL_I_URI, item.contentUri)
                        put(COL_I_IS_AVAILABLE, if (item.isAvailable) 1 else 0)
                    }
                    val newItemId = db.insert(TABLE_PLAYLIST_ITEMS, null, iValues)
                    if (newItemId <= 0L) {
                        throw IllegalStateException("تعذر إدراج المقطع \"${item.videoTitle}\" في قائمة التشغيل")
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
