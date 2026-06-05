package com.datalakeguard.faceauth

import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.*

class AuthSyncModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "AuthSyncModule"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val database = FaceDatabase(reactApplicationContext)
    private val syncManager = SyncManager(reactApplicationContext, database)
    private val networkObserver = NetworkSyncObserver(reactApplicationContext, syncManager)

    init {
        networkObserver.start()
    }

    override fun getName() = "AuthSyncModule"

    @ReactMethod
    fun logAuthEvent(workerId: String, confidence: Double, livenessPass: Boolean, promise: Promise) {
        try {
            syncManager.queueAuthLog(workerId, "authenticated", livenessPass, confidence.toFloat())
            promise.resolve("logged")
        } catch (e: Exception) {
            promise.reject("LOG_ERROR", e)
        }
    }

    @ReactMethod
    fun getPendingCount(promise: Promise) {
        try {
            val (pendingEmb, pendingLogs) = database.getPendingSyncCount()
            promise.resolve(pendingEmb + pendingLogs)
        } catch (e: Exception) {
            promise.reject("DB_ERROR", e)
        }
    }

    @ReactMethod
    fun syncToAWS(promise: Promise) {
        scope.launch {
            try {
                val result = syncManager.syncPending()

                val eventData = Arguments.createMap().apply {
                    putInt("syncedCount", result.uploaded)
                    putInt("pendingCount", result.remaining)
                }
                reactApplicationContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onSyncComplete", eventData)

                withContext(Dispatchers.Main) {
                    promise.resolve("Synced " + result.uploaded + " records. Failed: " + result.failed + ". Remaining: " + result.remaining)
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
            val items = database.getAuthLogs(100)
            val logs = Arguments.createArray()
            for (item in items) {
                val map = Arguments.createMap().apply {
                    putString("workerId", item.workerId ?: "unknown")
                    putString("result", item.result)
                    putBoolean("livenessPassed", item.livenessPassed)
                    putDouble("confidence", item.confidence.toDouble())
                    putDouble("timestamp", item.timestamp.toDouble())
                }
                logs.pushMap(map)
            }
            promise.resolve(logs)
        } catch (e: Exception) {
            promise.reject("DB_ERROR", e)
        }
    }

    @ReactMethod
    fun clearAllData(promise: Promise) {
        try {
            syncManager.clearQueue()
            promise.resolve(Arguments.createMap().apply {
                putBoolean("success", true)
                putString("message", "All local data cleared.")
            })
        } catch (e: Exception) {
            promise.reject("CLEAR_ERROR", e)
        }
    }

    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        networkObserver.stop()
        scope.cancel()
    }
}
