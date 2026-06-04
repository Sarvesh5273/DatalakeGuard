package com.datalakeguard.faceauth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory          // ADD THIS
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.bridge.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LivenessCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val tfliteEngine: TFLiteEngine,
    private val onFrameData: (WritableMap) -> Unit
) {
    companion object {
        private const val TAG = "LivenessCamera"
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isRunning = AtomicBoolean(false)

    // Frame history for blink detection
    private val earHistory = mutableListOf<Float>()
    private val maxHistorySize = 15
    
    // Store current frame for matching
    private var currentFrameBitmap: Bitmap? = null

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            processFrame(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis!!
                )

                Log.i(TAG, "CameraX liveness camera started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                isRunning.set(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            
            // Store copy for matching
            currentFrameBitmap?.recycle()
            currentFrameBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            
            val detection = tfliteEngine.detectFace(bitmap)

            if (detection == null) {
                emitFrameData("no_face", null, null, null)
                return
            }

            val landmarks = tfliteEngine.getLandmarks(bitmap, detection)
            if (landmarks == null) {
                emitFrameData("face_detected", null, null, null)
                return
            }

            val faceMeshLiveness = FaceMeshLiveness()
            val ear = faceMeshLiveness.computeEAR(landmarks)
            val mar = computeMAR(landmarks)
            val yaw = computeYaw(landmarks)

            addEarToHistory(ear)

            val blinkDetected = detectBlink()

            val params = Arguments.createMap().apply {
                putString("status", "landmarks_detected")
                putDouble("ear", ear.toDouble())
                putDouble("mar", mar.toDouble())
                putDouble("yaw", yaw.toDouble())
                putBoolean("blinkDetected", blinkDetected)
                putDouble("faceX", detection.bbox.centerX().toDouble())
                putDouble("faceY", detection.bbox.centerY().toDouble())
                putDouble("faceWidth", detection.bbox.width().toDouble())
                putDouble("faceHeight", detection.bbox.height().toDouble())
            }

            onFrameData(params)

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    fun getCurrentFrame(): Bitmap? {
        return currentFrameBitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun addEarToHistory(ear: Float) {
        earHistory.add(ear)
        if (earHistory.size > maxHistorySize) {
            earHistory.removeAt(0)
        }
    }

    private fun detectBlink(): Boolean {
        if (earHistory.size < 5) return false

        // Pattern: open → closed → open
        for (i in 2 until earHistory.size - 1) {
            val before = earHistory[i - 2]
            val current = earHistory[i]
            val after = earHistory[i + 1]

            if (before > 0.25 && current < 0.2 && after > 0.25) {
                earHistory.clear()
                return true
            }
        }
        return false
    }

    private fun computeMAR(landmarks: FaceMeshLiveness.Landmarks): Float {
        val height = distance(landmarks.points[13], landmarks.points[14])
        val width = distance(landmarks.points[61], landmarks.points[291])
        return if (width > 0) height / width else 0f
    }

    private fun computeYaw(landmarks: FaceMeshLiveness.Landmarks): Float {
        val noseTip = landmarks.points[1]
        val leftEar = landmarks.points[234]
        val rightEar = landmarks.points[454]
        val centerX = (leftEar.x + rightEar.x) / 2f
        return noseTip.x - centerX
    }

    private fun distance(a: FaceMeshLiveness.Point3D, b: FaceMeshLiveness.Point3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun emitFrameData(status: String, ear: Float?, mar: Float?, yaw: Float?) {
        val params = Arguments.createMap().apply {
            putString("status", status)
            ear?.let { putDouble("ear", it.toDouble()) }
            mar?.let { putDouble("mar", it.toDouble()) }
            yaw?.let { putDouble("yaw", it.toDouble()) }
        }
        onFrameData(params)
    }

    // FIXED: Proper YUV to Bitmap conversion
    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val imageBytes = out.toByteArray()
        
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalStateException("Failed to decode YUV to Bitmap")
    }

    fun stop() {
        isRunning.set(false)
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        earHistory.clear()
        currentFrameBitmap?.recycle()
        currentFrameBitmap = null
        Log.i(TAG, "CameraX liveness camera stopped")
    }
}