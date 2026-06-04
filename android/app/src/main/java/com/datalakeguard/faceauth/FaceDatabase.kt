package com.datalakeguard.faceauth

import android.content.Context
import android.content.ContentValues
import android.util.Log
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper

class FaceDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    
    companion object {
        private const val TAG = "FaceAuthDB"
        private const val DATABASE_NAME = "face_auth.db"
        private const val DATABASE_VERSION = 1
        private const val PASSWORD = "DatalakeGuard_AES256_Secure_Key_2026"
        
        private const val TABLE_EMBEDDINGS = "embeddings"
        private const val TABLE_AUTH_LOGS = "auth_logs"
        private const val TABLE_SYNC_QUEUE = "sync_queue"
    }
    
    init {
        SQLiteDatabase.loadLibs(context)
    }
    
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_EMBEDDINGS (
                worker_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                embedding BLOB NOT NULL,
                enrolled_at INTEGER NOT NULL,
                synced INTEGER DEFAULT 0,
                device_id TEXT NOT NULL
            )
        """.trimIndent())
        
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_AUTH_LOGS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id TEXT,
                result TEXT CHECK(result IN ('success', 'failure', 'timeout')),
                liveness_passed INTEGER,
                confidence REAL,
                timestamp INTEGER NOT NULL,
                device_id TEXT NOT NULL,
                synced INTEGER DEFAULT 0
            )
        """.trimIndent())
        
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SYNC_QUEUE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                payload_type TEXT CHECK(payload_type IN ('embedding', 'auth_log')),
                payload_json TEXT NOT NULL,
                retry_count INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL,
                last_attempt INTEGER
            )
        """.trimIndent())
        
        Log.i(TAG, "Database created with SQLCipher encryption")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EMBEDDINGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_AUTH_LOGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SYNC_QUEUE")
        onCreate(db)
    }
    
    private fun getWritableDb(): SQLiteDatabase {
        return super.getWritableDatabase(PASSWORD)
    }
    
    private fun getReadableDb(): SQLiteDatabase {
        return super.getReadableDatabase(PASSWORD)
    }
    
    fun insertEmbedding(workerId: String, name: String, embedding: FloatArray, deviceId: String): Boolean {
        return try {
            val db = getWritableDb()
            val values = ContentValues().apply {
                put("worker_id", workerId)
                put("name", name)
                put("embedding", embedding.toByteArray())
                put("enrolled_at", System.currentTimeMillis())
                put("synced", 0)
                put("device_id", deviceId)
            }
            db.insertWithOnConflict(TABLE_EMBEDDINGS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Insert embedding failed: ${e.message}")
            false
        }
    }
    
    fun getAllEmbeddings(): List<EmbeddingRecord> {
        val embeddings = mutableListOf<EmbeddingRecord>()
        val db = getReadableDb()
        val cursor = db.query(
            TABLE_EMBEDDINGS,
            arrayOf("worker_id", "name", "embedding", "enrolled_at", "synced", "device_id"),
            null, null, null, null, null
        )
        
        while (cursor.moveToNext()) {
            val blob = cursor.getBlob(cursor.getColumnIndexOrThrow("embedding"))
            embeddings.add(EmbeddingRecord(
                workerId = cursor.getString(cursor.getColumnIndexOrThrow("worker_id")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                embedding = blob.toFloatArray(),
                enrolledAt = cursor.getLong(cursor.getColumnIndexOrThrow("enrolled_at")),
                synced = cursor.getInt(cursor.getColumnIndexOrThrow("synced")) == 1,
                deviceId = cursor.getString(cursor.getColumnIndexOrThrow("device_id"))
            ))
        }
        cursor.close()
        db.close()
        return embeddings
    }
    
    fun findBestMatch(queryEmbedding: FloatArray, threshold: Float): MatchResult? {
        val allEmbeddings = getAllEmbeddings()
        if (allEmbeddings.isEmpty()) return null
        
        var bestMatch: EmbeddingRecord? = null
        var bestScore = -1f
        
        for (record in allEmbeddings) {
            val similarity = Utils.cosineSimilarity(queryEmbedding, record.embedding)
            if (similarity > bestScore) {
                bestScore = similarity
                bestMatch = record
            }
        }
        
        return if (bestScore >= threshold && bestMatch != null) {
            MatchResult(
                workerId = bestMatch.workerId,
                name = bestMatch.name,
                confidence = bestScore
            )
        } else null
    }
    
    fun markEmbeddingSynced(workerId: String) {
        val db = getWritableDb()
        val values = ContentValues().apply {
            put("synced", 1)
        }
        db.update(TABLE_EMBEDDINGS, values, "worker_id = ?", arrayOf(workerId))
        db.close()
    }
    
    fun logAuthAttempt(workerId: String?, result: String, livenessPassed: Boolean, confidence: Float, deviceId: String) {
        try {
            val db = getWritableDb()
            val values = ContentValues().apply {
                put("worker_id", workerId)
                put("result", result)
                put("liveness_passed", if (livenessPassed) 1 else 0)
                put("confidence", confidence)
                put("timestamp", System.currentTimeMillis())
                put("device_id", deviceId)
                put("synced", 0)
            }
            db.insert(TABLE_AUTH_LOGS, null, values)
            db.close()
        } catch (e: Exception) {
            Log.e(TAG, "Log auth attempt failed: ${e.message}")
        }
    }
    
    fun getAuthLogs(limit: Int): List<AuthLogRecord> {
        val logs = mutableListOf<AuthLogRecord>()
        val db = getReadableDb()
        val cursor = db.query(
            TABLE_AUTH_LOGS,
            arrayOf("worker_id", "result", "liveness_passed", "confidence", "timestamp"),
            null, null, null, null,
            "timestamp DESC",
            limit.toString()
        )
        
        while (cursor.moveToNext()) {
            logs.add(AuthLogRecord(
                workerId = cursor.getString(cursor.getColumnIndexOrThrow("worker_id")),
                result = cursor.getString(cursor.getColumnIndexOrThrow("result")),
                livenessPassed = cursor.getInt(cursor.getColumnIndexOrThrow("liveness_passed")) == 1,
                confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
            ))
        }
        cursor.close()
        db.close()
        return logs
    }
    
    fun markAuthLogSynced(id: Long) {
        val db = getWritableDb()
        val values = ContentValues().apply {
            put("synced", 1)
        }
        db.update(TABLE_AUTH_LOGS, values, "id = ?", arrayOf(id.toString()))
        db.close()
    }
    
    fun addToQueue(payloadType: String, payloadJson: String) {
        try {
            val db = getWritableDb()
            val values = ContentValues().apply {
                put("payload_type", payloadType)
                put("payload_json", payloadJson)
                put("retry_count", 0)
                put("created_at", System.currentTimeMillis())
                putNull("last_attempt")
            }
            db.insert(TABLE_SYNC_QUEUE, null, values)
            db.close()
        } catch (e: Exception) {
            Log.e(TAG, "Queue add failed: ${e.message}")
        }
    }
    
    fun getPendingQueueItems(limit: Int): List<QueueItem> {
        val items = mutableListOf<QueueItem>()
        val db = getReadableDb()
        val cursor = db.query(
            TABLE_SYNC_QUEUE,
            arrayOf("id", "payload_type", "payload_json", "retry_count", "created_at", "last_attempt"),
            "retry_count < ?",
            arrayOf("5"),
            null, null,
            "created_at ASC",
            limit.toString()
        )
        
        while (cursor.moveToNext()) {
            items.add(QueueItem(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                payloadType = cursor.getString(cursor.getColumnIndexOrThrow("payload_type")),
                payloadJson = cursor.getString(cursor.getColumnIndexOrThrow("payload_json")),
                retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                lastAttempt = cursor.getLong(cursor.getColumnIndexOrThrow("last_attempt"))
            ))
        }
        cursor.close()
        db.close()
        return items
    }
    
    fun updateQueueItemRetry(id: Long, retryCount: Int) {
        val db = getWritableDb()
        val values = ContentValues().apply {
            put("retry_count", retryCount)
            put("last_attempt", System.currentTimeMillis())
        }
        db.update(TABLE_SYNC_QUEUE, values, "id = ?", arrayOf(id.toString()))
        db.close()
    }
    
    fun removeFromQueue(id: Long) {
        val db = getWritableDb()
        db.delete(TABLE_SYNC_QUEUE, "id = ?", arrayOf(id.toString()))
        db.close()
    }
    
    fun getPendingSyncCount(): Pair<Int, Int> {
        val db = getReadableDb()
        
        val embCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_EMBEDDINGS WHERE synced = 0", null)
        embCursor.moveToFirst()
        val pendingEmbeddings = embCursor.getInt(0)
        embCursor.close()
        
        val logCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_AUTH_LOGS WHERE synced = 0", null)
        logCursor.moveToFirst()
        val pendingLogs = logCursor.getInt(0)
        logCursor.close()
        
        db.close()
        return Pair(pendingEmbeddings, pendingLogs)
    }
    
    fun clearAllData() {
        val db = getWritableDb()
        db.delete(TABLE_EMBEDDINGS, null, null)
        db.delete(TABLE_AUTH_LOGS, null, null)
        db.delete(TABLE_SYNC_QUEUE, null, null)
        db.close()
    }
    
    data class EmbeddingRecord(
        val workerId: String,
        val name: String,
        val embedding: FloatArray,
        val enrolledAt: Long,
        val synced: Boolean,
        val deviceId: String
    )
    
    data class MatchResult(
        val workerId: String,
        val name: String,
        val confidence: Float
    )
    
    data class AuthLogRecord(
        val workerId: String?,
        val result: String,
        val livenessPassed: Boolean,
        val confidence: Float,
        val timestamp: Long
    )
    
    data class QueueItem(
        val id: Long,
        val payloadType: String,
        val payloadJson: String,
        val retryCount: Int,
        val createdAt: Long,
        val lastAttempt: Long
    )
    
    private fun FloatArray.toByteArray(): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(this.size * 4)
        buffer.order(java.nio.ByteOrder.nativeOrder())
        for (value in this) {
            buffer.putFloat(value)
        }
        return buffer.array()
    }
    
    private fun ByteArray.toFloatArray(): FloatArray {
        val buffer = java.nio.ByteBuffer.wrap(this)
        buffer.order(java.nio.ByteOrder.nativeOrder())
        val floatArray = FloatArray(this.size / 4)
        for (i in floatArray.indices) {
            floatArray[i] = buffer.getFloat()
        }
        return floatArray
    }
}