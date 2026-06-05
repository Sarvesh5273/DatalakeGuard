package com.datalakeguard.faceauth

import android.util.Log
import com.facebook.react.bridge.*
import kotlinx.coroutines.*

class AuthSyncModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "AuthSyncModule"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncManager: SyncManager? = null

    override fun getName() = "AuthSyncModule"

    private fun getSyncManager(): SyncManager? {
        if (syncManager == null) {
            val db = FaceDatabase(reactApplicationContext)
            syncManager = SyncManager(reactApplicationContext, db)
        }
        return syncManager
    }

    @ReactMethod
    fun logAuthEvent(workerId: String, confidence: Double, livenessPass: Boolean, promise: Promise) {
        try {
            getSyncManager()?.queueAuthLog(workerId, "authenticated", livenessPass, confidence.toFloat())
            promise.resolve("logged")
        } catch (e: Exception) {
            promise.reject("LOG_ERROR", e)
        }
    }

    @ReactMethod
    fun getPendingCount(promise: Promise) {
        try {
            val db = FaceDatabase(reactApplicationContext)
            val count = db.getPendingQueueItems(9999).size
            promise.resolve(count)
        } catch (e: Exception) {
            promise.reject("DB_ERROR", e)
        }
    }

    @ReactMethod
    fun syncToAWS(promise: Promise) {
        scope.launch {
            try {
                val result = getSyncManager()?.syncPending()
                if (result != null) {
                    withContext(Dispatchers.Main) {
                        promise.resolve("Synced ${result.uploaded} records. Failed: ${result.failed}. Remaining: ${result.remaining}")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        promise.reject("SYNC_ERROR", "SyncManager not initialized")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                withContext(Dispatchers.Main) {
                    promise.reject("SYNC_ERROR", e)
                }
            }
        }
    }

    @ReactMethod
    fun getAllLogs(promise: Promise) {
        try {
            val db = FaceDatabase(reactApplicationContext)
            val items = db.getPendingQueueItems(100)
            val logs = Arguments.createArray()
            for (item in items) {
                val map = Arguments.createMap().apply {
                    putDouble("id", item.id.toDouble())
                    putString("type", item.payloadType)
                    putString("payload", item.payloadJson)
                    putInt("retryCount", item.retryCount)
                }
                logs.pushMap(map)
            }
            promise.resolve(logs)
        } catch (e: Exception) {
            promise.reject("DB_ERROR", e)
        }
    }
}