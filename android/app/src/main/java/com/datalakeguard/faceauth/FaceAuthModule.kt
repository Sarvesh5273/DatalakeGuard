package com.datalakeguard.faceauth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class FaceAuthModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private val tfliteEngine: TFLiteEngine = TFLiteEngine(reactContext)
    private val database: FaceDatabase = FaceDatabase(reactContext)
    private val syncManager: SyncManager = SyncManager(reactContext, database)
    
    private var authPromise: Promise? = null
    private var enrollPromise: Promise? = null
    private var enrollmentFrames = mutableListOf<FloatArray>()
    private var enrollmentWorkerId: String? = null
    private var livenessChallenge: LivenessChallenge? = null
    private var authStartTime: Long = 0
    private var consecutiveFaceFrames = 0
    private var lastProcessedTime = 0L
    
    // CameraX live liveness
    private var livenessCameraManager: LivenessCameraManager? = null
    private var livenessEventEmitter: DeviceEventManagerModule.RCTDeviceEventEmitter? = null
    
    private val MIN_FRAME_INTERVAL_MS = 100L

    override fun getName(): String = "FaceAuthModule"

    // Required by RN's NativeEventEmitter — without these the emitter throws a warning
    // and silently drops all events in the new architecture
    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}

    @ReactMethod
    fun startEnrollment(workerId: String, promise: Promise) {
        enrollmentFrames.clear()
        enrollmentWorkerId = workerId
        enrollPromise = promise
        consecutiveFaceFrames = 0
        promise.resolve(Arguments.createMap().apply {
            putString("status", "started")
            putString("message", "Enrollment started. Capture 5 frames.")
        })
    }

    @ReactMethod
    fun processEnrollmentFrame(imagePath: String, promise: Promise) {
        try {
            if (enrollmentWorkerId == null) {
                promise.reject("NOT_STARTED", "Enrollment not started. Call startEnrollment first.")
                return
            }

            val bitmap = loadBitmap(imagePath) ?: run {
                promise.reject("LOAD_ERROR", "Failed to load image from path: $imagePath")
                return
            }

            val detection = tfliteEngine.detectFace(bitmap)
            if (detection == null) {
                promise.resolve(Arguments.createMap().apply {
                    putString("status", "no_face")
                    putString("message", "No face detected. Please center your face.")
                    putInt("framesCaptured", enrollmentFrames.size)
                })
                return
            }

            val embedding = tfliteEngine.getEmbedding(bitmap, detection)
            if (embedding == null) {
                promise.reject("EMBED_ERROR", "Failed to extract face embedding.")
                return
            }

            enrollmentFrames.add(embedding)
            val frameCount = enrollmentFrames.size

            if (frameCount >= 5) {
                val avgEmbedding = averageEmbeddings(enrollmentFrames)
                
                val success = database.insertEmbedding(
                    workerId = enrollmentWorkerId!!,
                    name = enrollmentWorkerId!!,
                    embedding = avgEmbedding,
                    deviceId = getDeviceId()
                )

                syncManager.queueEmbedding(enrollmentWorkerId!!, avgEmbedding)

                val workerId = enrollmentWorkerId!!
                enrollmentFrames.clear()
                enrollmentWorkerId = null
                val enrollPromiseRef = enrollPromise
                enrollPromise = null

                if (success) {
                    val result = Arguments.createMap().apply {
                        putBoolean("success", true)
                        putString("workerId", workerId)
                        putString("message", "Enrollment complete. Face stored securely.")
                    }
                    enrollPromiseRef?.resolve(result)
                    promise.resolve(result)
                } else {
                    enrollPromiseRef?.reject("DB_ERROR", "Failed to store embedding in database.")
                    promise.reject("DB_ERROR", "Failed to store embedding in database.")
                }
            } else {
                promise.resolve(Arguments.createMap().apply {
                    putString("status", "capturing")
                    putString("message", "Frame captured. ${5 - frameCount} more needed.")
                    putInt("framesCaptured", frameCount)
                    putInt("framesNeeded", 5)
                })
            }
        } catch (e: Exception) {
            promise.reject("PROCESS_ERROR", e.message ?: "Unknown error during enrollment")
        }
    }

    @ReactMethod
    fun startAuthentication(promise: Promise) {
        authPromise = promise
        authStartTime = System.currentTimeMillis()
        livenessChallenge = LivenessChallenge.createRandom()
        consecutiveFaceFrames = 0
        
        promise.resolve(Arguments.createMap().apply {
            putString("status", "started")
            putString("challenge1", livenessChallenge!!.challenge1.name)
            putString("challenge2", livenessChallenge!!.challenge2.name)
            putString("message", "Authentication started. ${livenessChallenge!!.challenge1.displayName} then ${livenessChallenge!!.challenge2.displayName}.")
        })
    }

    @ReactMethod
    fun processAuthFrame(imagePath: String, promise: Promise) {
        try {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProcessedTime < MIN_FRAME_INTERVAL_MS) {
                promise.resolve(Arguments.createMap().apply {
                    putString("status", "throttled")
                })
                return
            }
            lastProcessedTime = currentTime

            if (livenessChallenge == null) {
                promise.reject("NOT_STARTED", "Authentication not started. Call startAuthentication first.")
                return
            }

            val bitmap = loadBitmap(imagePath) ?: run {
                promise.reject("LOAD_ERROR", "Failed to load image: $imagePath")
                return
            }

            val detection = tfliteEngine.detectFace(bitmap)
            if (detection == null) {
                consecutiveFaceFrames = 0
                promise.resolve(Arguments.createMap().apply {
                    putString("status", "no_face")
                    putString("currentChallenge", livenessChallenge!!.currentChallenge.name)
                    putString("message", "No face detected. Please center your face.")
                })
                return
            }
            consecutiveFaceFrames++

            if (consecutiveFaceFrames < 3) {
                promise.resolve(Arguments.createMap().apply {
                    putString("status", "stabilizing")
                    putString("message", "Hold still...")
                    putString("currentChallenge", livenessChallenge!!.currentChallenge.name)
                })
                return
            }

            val landmarks = tfliteEngine.getLandmarks(bitmap, detection)
            if (landmarks == null) {
                promise.resolve(Arguments.createMap().apply {
                    putString("status", "processing")
                    putString("message", "Processing face...")
                })
                return
            }

            val livenessResult = livenessChallenge!!.checkChallenge(landmarks)
            
            if (livenessResult == LivenessResult.PASSED) {
                if (livenessChallenge!!.isComplete()) {
                    val embedding = tfliteEngine.getEmbedding(bitmap, detection)
                    if (embedding == null) {
                        promise.reject("EMBED_ERROR", "Failed to extract embedding for matching.")
                        return
                    }

                    val matchResult = database.findBestMatch(embedding, threshold = 0.65f)
                    val authTime = System.currentTimeMillis() - authStartTime

                    database.logAuthAttempt(
                        workerId = matchResult?.workerId,
                        result = if (matchResult != null) "success" else "failure",
                        livenessPassed = true,
                        confidence = matchResult?.confidence ?: 0f,
                        deviceId = getDeviceId()
                    )

                    if (matchResult != null) {
                        syncManager.queueAuthLog(matchResult.workerId, "success", true, matchResult.confidence)
                    } else {
                        syncManager.queueAuthLog("unknown", "failure", true, 0f)
                    }

                    val result = Arguments.createMap().apply {
                        putString("status", "complete")
                        putBoolean("matched", matchResult != null)
                        putString("workerId", matchResult?.workerId)
                        putDouble("confidence", (matchResult?.confidence ?: 0f).toDouble())
                        putBoolean("livenessPass", true)
                        putDouble("authTimeMs", authTime.toDouble())
                        putString("message", if (matchResult != null) "Authenticated!" else "Face not recognized.")
                    }

                    val authPromiseRef = authPromise
                    authPromise = null
                    livenessChallenge = null
                    authPromiseRef?.resolve(result)
                    promise.resolve(result)
                } else {
                    promise.resolve(Arguments.createMap().apply {
                        putString("status", "challenge_passed")
                        putString("currentChallenge", livenessChallenge!!.currentChallenge.name)
                        putString("message", "Good! Now ${livenessChallenge!!.currentChallenge.displayName}.")
                    })
                }
            } else if (livenessResult == LivenessResult.FAILED) {
                val elapsed = System.currentTimeMillis() - authStartTime
                if (elapsed > 30000) {
                    database.logAuthAttempt(
                        workerId = null,
                        result = "timeout",
                        livenessPassed = false,
                        confidence = 0f,
                        deviceId = getDeviceId()
                    )
                    syncManager.queueAuthLog("unknown", "timeout", false, 0f)

                    val result = Arguments.createMap().apply {
                        putString("status", "timeout")
                        putBoolean("matched", false)
                        putBoolean("livenessPass", false)
                        putString("message", "Authentication timed out. Please try again.")
                    }
                    
                    val authPromiseRef = authPromise
                    authPromise = null
                    livenessChallenge = null
                    authPromiseRef?.reject("TIMEOUT", "Authentication timed out after 30 seconds.")
                    promise.resolve(result)
                } else {
                    promise.resolve(Arguments.createMap().apply {
                        putString("status", "challenge_active")
                        putString("currentChallenge", livenessChallenge!!.currentChallenge.name)
                        putString("message", "Please ${livenessChallenge!!.currentChallenge.instruction}.")
                    })
                }
            } else {
                promise.resolve(Arguments.createMap().apply {
                    putString("status", "challenge_active")
                    putString("currentChallenge", livenessChallenge!!.currentChallenge.name)
                    putString("message", "Please ${livenessChallenge!!.currentChallenge.instruction}.")
                })
            }
        } catch (e: Exception) {
            promise.reject("PROCESS_ERROR", e.message ?: "Unknown error during authentication")
        }
    }

    // ============ CAMERAX LIVE LIVENESS METHODS ============

    @ReactMethod
    fun startLivenessCamera(promise: Promise) {
        try {
            val activity = currentActivity as? LifecycleOwner
                ?: run {
                    promise.reject("NO_ACTIVITY", "No lifecycle owner available")
                    return
                }

            livenessEventEmitter = reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)

            livenessCameraManager = LivenessCameraManager(
                context = reactApplicationContext,
                lifecycleOwner = activity,
                tfliteEngine = tfliteEngine,
                onFrameData = { params ->
                    livenessEventEmitter?.emit("onLivenessFrame", params)
                }
            )

            livenessCameraManager?.start()

            promise.resolve(Arguments.createMap().apply {
                putBoolean("success", true)
                putString("message", "Liveness camera started")
            })
        } catch (e: Exception) {
            promise.reject("CAMERA_ERROR", e.message ?: "Failed to start liveness camera")
        }
    }

        @ReactMethod
        fun stopLivenessCamera() {
        livenessCameraManager?.stop()
        livenessCameraManager = null
    
    }

    @ReactMethod
    fun captureLivenessFrame(promise: Promise) {
        promise.resolve(Arguments.createMap().apply {
            putString("status", "camera_active")
            putString("message", "Use onLivenessFrame events for real-time data")
        })
    }

    @ReactMethod
    fun processLivenessFrameForMatching(promise: Promise) {
        try {
            if (livenessChallenge == null) {
                promise.reject("NOT_STARTED", "Authentication not started")
                return
            }

            // Get current frame from camera manager
            val currentBitmap = livenessCameraManager?.getCurrentFrame()
                ?: run {
                    promise.reject("NO_FRAME", "No frame available from camera")
                    return
                }

            val detection = tfliteEngine.detectFace(currentBitmap)
            if (detection == null) {
                promise.resolve(Arguments.createMap().apply {
                    putString("status", "no_face")
                    putString("message", "No face detected in current frame")
                })
                return
            }

            val embedding = tfliteEngine.getEmbedding(currentBitmap, detection)
            if (embedding == null) {
                promise.reject("EMBED_ERROR", "Failed to extract embedding")
                return
            }

            val matchResult = database.findBestMatch(embedding, threshold = 0.65f)
            val authTime = System.currentTimeMillis() - authStartTime

            database.logAuthAttempt(
                workerId = matchResult?.workerId,
                result = if (matchResult != null) "success" else "failure",
                livenessPassed = true,
                confidence = matchResult?.confidence ?: 0f,
                deviceId = getDeviceId()
            )

            val result = Arguments.createMap().apply {
                putString("status", "complete")
                putBoolean("matched", matchResult != null)
                putString("workerId", matchResult?.workerId)
                putDouble("confidence", (matchResult?.confidence ?: 0f).toDouble())
                putBoolean("livenessPass", true)
                putDouble("authTimeMs", authTime.toDouble())
                putString("message", if (matchResult != null) "Authenticated!" else "Face not recognized.")
            }

            val authPromiseRef = authPromise
            authPromise = null
            livenessChallenge = null
            authPromiseRef?.resolve(result)
            promise.resolve(result)

        } catch (e: Exception) {
            promise.reject("PROCESS_ERROR", e.message ?: "Error processing liveness frame")
        }
    }

    // ============ EXISTING METHODS ============

    @ReactMethod
    fun syncPendingRecords(promise: Promise) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val result = syncManager.syncPending()
                val map = Arguments.createMap().apply {
                    putInt("uploaded", result.uploaded)
                    putInt("failed", result.failed)
                    putInt("remaining", result.remaining)
                    putString("message", "Sync complete. ${result.uploaded} uploaded, ${result.failed} failed.")
                }
                promise.resolve(map)
            } catch (e: Exception) {
                promise.reject("SYNC_ERROR", e.message ?: "Sync failed")
            }
        }
    }

    @ReactMethod
    fun getPendingCount(promise: Promise) {
        val count = database.getPendingSyncCount()
        promise.resolve(Arguments.createMap().apply {
            putInt("pendingEmbeddings", count.first)
            putInt("pendingLogs", count.second)
        })
    }

    @ReactMethod
    fun getAuthLogs(promise: Promise) {
        val logs = database.getAuthLogs(limit = 50)
        val array = Arguments.createArray()
        logs.forEach { log ->
            array.pushMap(Arguments.createMap().apply {
                putString("workerId", log.workerId)
                putString("result", log.result)
                putBoolean("livenessPassed", log.livenessPassed)
                putDouble("confidence", log.confidence.toDouble())
                putDouble("timestamp", log.timestamp.toDouble())
            })
        }
        promise.resolve(array)
    }

    @ReactMethod
    fun clearAllData(promise: Promise) {
        database.clearAllData()
        syncManager.clearQueue()
        promise.resolve(Arguments.createMap().apply {
            putBoolean("success", true)
            putString("message", "All local data cleared.")
        })
    }

    private fun loadBitmap(path: String): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            BitmapFactory.decodeFile(path)?.let { bitmap ->
                val rotation = Utils.getExifRotation(path)
                if (rotation != 0) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        val dim = embeddings[0].size
        val avg = FloatArray(dim) { 0f }
        embeddings.forEach { emb ->
            emb.forEachIndexed { i, v -> avg[i] += v }
        }
        avg.forEachIndexed { i: Int, v: Float -> avg[i] = v / embeddings.size }
        val norm = kotlin.math.sqrt(avg.map { it * it }.sum().toFloat())
        if (norm > 0) avg.forEachIndexed { i: Int, v: Float -> avg[i] = v / norm }
        return avg
    }

    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            reactApplicationContext.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }
}