package com.datalakeguard.faceauth

import android.content.Context
import android.util.Log
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.amazonaws.services.dynamodbv2.model.PutItemRequest
import com.amazonaws.services.s3.AmazonS3Client
import com.datalakeguard.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.UUID

class SyncManager(
    private val context: Context,
    private val database: FaceDatabase
) {
    companion object {
        private const val TAG = "SyncManager"
        private const val MAX_RETRIES = 5
        private const val BATCH_SIZE = 10
        private const val S3_BUCKET = "datalakeguard-embeddings"
        private const val S3_PREFIX = "face-embeddings/"
        private const val DYNAMO_TABLE = "auth_logs"
    }

    private val AWS_ACCESS_KEY = BuildConfig.AWS_ACCESS_KEY
    private val AWS_SECRET_KEY = BuildConfig.AWS_SECRET_KEY

    private var s3Client: AmazonS3Client? = null
    private var dynamoClient: AmazonDynamoDBClient? = null

    init {
        initializeAWS()
    }

    private fun initializeAWS() {
        try {
            if (AWS_ACCESS_KEY.isBlank() || AWS_SECRET_KEY.isBlank()) {
                Log.w(TAG, "AWS credentials not configured.")
                return
            }
            val credentials = BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
            s3Client = AmazonS3Client(credentials).also {
                it.setRegion(Region.getRegion(Regions.AP_SOUTH_1))
            }
            dynamoClient = AmazonDynamoDBClient(credentials).also {
                it.setRegion(Region.getRegion(Regions.AP_SOUTH_1))
            }
            Log.i(TAG, "AWS clients initialized for ap-south-1")
        } catch (e: Exception) {
            Log.e(TAG, "AWS initialization failed: ${e.message}")
        }
    }

    data class SyncResult(val uploaded: Int, val failed: Int, val remaining: Int)

    fun syncPending(): SyncResult {
        var uploaded = 0
        var failed = 0

        val pendingItems = database.getPendingQueueItems(BATCH_SIZE)

        if (pendingItems.isEmpty()) {
            return SyncResult(0, 0, 0)
        }

        if (s3Client == null || dynamoClient == null) {
            Log.w(TAG, "AWS not configured. " + pendingItems.size + " items queued.")
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
                Log.e(TAG, "Sync error item " + item.id + ": " + e.message)
                handleRetry(item)
                failed++
            }
        }

        // Auto-purge disabled for demo verification
        // if (uploaded > 0 && failed == 0) {
        //     clearQueue()
        // }

        val remaining = getPendingQueueCount()
        return SyncResult(uploaded, failed, remaining)
    }

    fun queueEmbedding(workerId: String, embedding: FloatArray) {
        val payload = JSONObject().apply {
            put("workerId", workerId)
            put("embedding", embedding.toList())
            put("timestamp", System.currentTimeMillis())
            put("deviceId", getDeviceId())
        }
        database.addToQueue("embedding", payload.toString())
        Log.i(TAG, "Queued embedding for: " + workerId)
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
        Log.i(TAG, "Queued auth log: " + result + " for " + workerId)
    }

    private fun uploadEmbedding(item: FaceDatabase.QueueItem): Boolean {
        return try {
            val payload = JSONObject(item.payloadJson)
            val workerId = payload.getString("workerId")
            val timestamp = payload.getLong("timestamp")
            val deviceId = payload.getString("deviceId")

            val tempFile = File(context.cacheDir, "embed_" + workerId + "_" + timestamp + ".json")
            tempFile.writeText(item.payloadJson)

            val key = S3_PREFIX + deviceId + "/" + workerId + "/" + timestamp + ".json"
            s3Client?.putObject(S3_BUCKET, key, tempFile)
            database.markEmbeddingSynced(workerId)
            tempFile.delete()

            Log.i(TAG, "Uploaded embedding to S3: " + key)
            true
        } catch (e: Exception) {
            Log.e(TAG, "S3 upload failed: " + e.message)
            false
        }
    }

    private fun uploadAuthLog(item: FaceDatabase.QueueItem): Boolean {
        return try {
            val payload = JSONObject(item.payloadJson)
            Log.d(TAG, "Uploading auth log payload: " + payload.toString())

            val itemValues = mutableMapOf<String, AttributeValue>().apply {
                put("log_id", AttributeValue(UUID.randomUUID().toString()))
                put("worker_id", AttributeValue(payload.getString("workerId")))
                put("result", AttributeValue(payload.getString("result")))
                put("liveness_passed", AttributeValue().withBOOL(payload.getBoolean("livenessPassed")))
                put("confidence", AttributeValue().withN(payload.getDouble("confidence").toString()))
                put("timestamp", AttributeValue().withN(payload.getLong("timestamp").toString()))
                put("device_id", AttributeValue(payload.getString("deviceId")))
            }

            Log.d(TAG, "DynamoDB table: " + DYNAMO_TABLE + ", client: " + (dynamoClient != null))
            dynamoClient?.putItem(PutItemRequest(DYNAMO_TABLE, itemValues))
            Log.i(TAG, "Uploaded auth log to DynamoDB SUCCESS")

            val dbLogId = payload.optLong("dbLogId", -1L)
            if (dbLogId != -1L) {
                database.markAuthLogSynced(dbLogId)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "DynamoDB upload FAILED: " + e.message)
            Log.e(TAG, "Exception class: " + e.javaClass.name)
            e.printStackTrace()
            false
        }
    }

    private fun handleRetry(item: FaceDatabase.QueueItem) {
        val newRetry = item.retryCount + 1
        if (newRetry >= MAX_RETRIES) {
            Log.w(TAG, "Item " + item.id + " exceeded max retries. Dropping.")
            database.removeFromQueue(item.id)
        } else {
            database.updateQueueItemRetry(item.id, newRetry)
            Log.w(TAG, "Item " + item.id + " retry " + newRetry + "/" + MAX_RETRIES)
        }
    }

    fun clearQueue() {
        database.clearAllData()
        Log.i(TAG, "Local face data purged after sync")
    }

    private fun getDeviceId(): String =
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"

    private fun getPendingQueueCount(): Int {
        val (emb, logs) = database.getPendingSyncCount()
        return emb + logs
    }
}
