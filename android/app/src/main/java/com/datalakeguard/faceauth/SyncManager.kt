package com.datalakeguard.faceauth

import android.content.Context
import android.util.Log
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.amazonaws.services.dynamodbv2.model.PutItemRequest
import org.json.JSONObject
import java.io.File
import java.util.*

class SyncManager(
    private val context: Context,
    private val database: FaceDatabase
) {
    
    companion object {
        private const val TAG = "SyncManager"
        private const val MAX_RETRIES = 5
        private const val BATCH_SIZE = 10
        
        private const val AWS_ACCESS_KEY = "YOUR_AWS_ACCESS_KEY"
        private const val AWS_SECRET_KEY = "YOUR_AWS_SECRET_KEY"
        private const val AWS_REGION = "ap-south-1"
        private const val S3_BUCKET = "datalakeguard-embeddings"
        private const val S3_PREFIX = "face-embeddings/"
        private const val DYNAMO_TABLE = "auth_logs"
    }
    
    private var s3Client: AmazonS3Client? = null
    private var dynamoClient: AmazonDynamoDBClient? = null
    
    init {
        initializeAWS()
    }
    
    private fun initializeAWS() {
        try {
            if (AWS_ACCESS_KEY == "YOUR_AWS_ACCESS_KEY" || AWS_SECRET_KEY == "YOUR_AWS_SECRET_KEY") {
                Log.w(TAG, "AWS credentials not configured. Sync will queue but not upload.")
                return
            }
            
            val credentials = BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
            
            s3Client = AmazonS3Client(credentials)
            dynamoClient = AmazonDynamoDBClient(credentials)
            
            Log.i(TAG, "AWS clients initialized")
        } catch (e: Exception) {
            Log.e(TAG, "AWS initialization failed: ${e.message}")
        }
    }
    
    fun queueEmbedding(workerId: String, embedding: FloatArray) {
        val payload = JSONObject().apply {
            put("workerId", workerId)
            put("embedding", embedding.toList())
            put("timestamp", System.currentTimeMillis())
            put("deviceId", getDeviceId())
        }
        database.addToQueue("embedding", payload.toString())
        Log.i(TAG, "Queued embedding for worker: $workerId")
    }
    
    fun queueAuthLog(workerId: String, result: String, livenessPassed: Boolean, confidence: Float) {
        val payload = JSONObject().apply {
            put("workerId", workerId)
            put("result", result)
            put("livenessPassed", livenessPassed)
            put("confidence", confidence)
            put("timestamp", System.currentTimeMillis())
            put("deviceId", getDeviceId())
        }
        database.addToQueue("auth_log", payload.toString())
        Log.i(TAG, "Queued auth log: $result for worker: $workerId")
    }
    
    data class SyncResult(
        val uploaded: Int,
        val failed: Int,
        val remaining: Int
    )
    
    fun syncPending(): SyncResult {
        var uploaded = 0
        var failed = 0
        
        val pendingItems = database.getPendingQueueItems(BATCH_SIZE)
        
        if (pendingItems.isEmpty()) {
            return SyncResult(0, 0, 0)
        }
        
        if (s3Client == null || dynamoClient == null) {
            Log.w(TAG, "AWS not configured. ${pendingItems.size} items remain queued.")
            return SyncResult(0, 0, pendingItems.size)
        }
        
        for (item in pendingItems) {
            try {
                val success = when (item.payloadType) {
                    "embedding" -> uploadEmbedding(item)
                    "auth_log" -> uploadAuthLog(item)
                    else -> false
                }
                
                if (success) {
                    database.removeFromQueue(item.id)
                    uploaded++
                } else {
                    handleRetry(item)
                    failed++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync error for item ${item.id}: ${e.message}")
                handleRetry(item)
                failed++
            }
        }
        
        syncDirectDBRecords()
        
        val remaining = getPendingQueueCount()
        return SyncResult(uploaded, failed, remaining)
    }
    
    private fun uploadEmbedding(item: FaceDatabase.QueueItem): Boolean {
        return try {
            val payload = JSONObject(item.payloadJson)
            val workerId = payload.getString("workerId")
            val timestamp = payload.getLong("timestamp")
            val deviceId = payload.getString("deviceId")
            
            val tempFile = File(context.cacheDir, "embed_${workerId}_${timestamp}.json")
            tempFile.writeText(item.payloadJson)
            
            val key = "$S3_PREFIX$deviceId/$workerId/$timestamp.json"
            
            s3Client?.putObject(S3_BUCKET, key, tempFile)
            
            database.markEmbeddingSynced(workerId)
            
            tempFile.delete()
            Log.i(TAG, "Uploaded embedding to S3: $key")
            true
        } catch (e: Exception) {
            Log.e(TAG, "S3 upload failed: ${e.message}")
            false
        }
    }
    
    private fun uploadAuthLog(item: FaceDatabase.QueueItem): Boolean {
        return try {
            val payload = JSONObject(item.payloadJson)
            
            val itemValues = mutableMapOf<String, AttributeValue>().apply {
                put("log_id", AttributeValue(UUID.randomUUID().toString()))
                put("worker_id", AttributeValue(payload.getString("workerId")))
                put("result", AttributeValue(payload.getString("result")))
                put("liveness_passed", AttributeValue().withBOOL(payload.getBoolean("livenessPassed")))
                put("confidence", AttributeValue().withN(payload.getDouble("confidence").toString()))
                put("timestamp", AttributeValue().withN(payload.getLong("timestamp").toString()))
                put("device_id", AttributeValue(payload.getString("deviceId")))
            }
            
            val request = PutItemRequest(DYNAMO_TABLE, itemValues)
            dynamoClient?.putItem(request)
            
            Log.i(TAG, "Uploaded auth log to DynamoDB")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DynamoDB upload failed: ${e.message}")
            false
        }
    }
    
    private fun handleRetry(item: FaceDatabase.QueueItem) {
        val newRetryCount = item.retryCount + 1
        if (newRetryCount >= MAX_RETRIES) {
            Log.w(TAG, "Item ${item.id} exceeded max retries. Removing from queue.")
            database.removeFromQueue(item.id)
        } else {
            database.updateQueueItemRetry(item.id, newRetryCount)
            Log.w(TAG, "Item ${item.id} retry $newRetryCount/$MAX_RETRIES")
        }
    }
    
    private fun syncDirectDBRecords() {
        val pendingEmbeddings = database.getAllEmbeddings().filter { !it.synced }
        for (emb in pendingEmbeddings) {
            queueEmbedding(emb.workerId, emb.embedding)
        }
    }
    
    fun clearQueue() {
        // Queue is cleared via database.clearAllData()
    }
    
    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }
    
    private fun getPendingQueueCount(): Int {
        return database.getPendingQueueItems(9999).size
    }
}