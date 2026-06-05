package com.datalakeguard.faceauth

import kotlin.math.pow
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.Handler
import android.os.Looper
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
    private var isStopped = false

    private val earHistory = mutableListOf<Float>()
    private val maxHistorySize = 15
    private var currentFrameBitmap: Bitmap? = null

    fun start() {
        if (isRunning.get()) return

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            return
        }

        isRunning.set(true)
        isStopped = false

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            tryStartCameraWithRetry(0)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun tryStartCameraWithRetry(retryCount: Int) {
        if (!isRunning.get() || isStopped) return

        try {
            cameraProvider = ProcessCameraProvider.getInstance(context).get()

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
            val preview = Preview.Builder().build()
            val surfaceTexture = CameraPreviewHolder.surfaceTexture

            if (surfaceTexture == null) {
                if (retryCount < 30) {
                    Log.w(TAG, "SurfaceTexture not ready, retrying in 200ms... ($retryCount/30)")
                    Handler(Looper.getMainLooper()).postDelayed({
                        tryStartCameraWithRetry(retryCount + 1)
                    }, 200)
                    return
                } else {
                    Log.e(TAG, "SurfaceTexture never became ready after 30 retries")
                    isRunning.set(false)
                    return
                }
            }

            val surfaceProvider = androidx.camera.core.Preview.SurfaceProvider { request ->
                val texture = surfaceTexture ?: return@SurfaceProvider
                val resolution = request.resolution
                texture.setDefaultBufferSize(resolution.width, resolution.height)
                val surface = android.view.Surface(texture)
                request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { result ->
                    Log.d(TAG, "Surface result: $result")
                }
            }

            preview.setSurfaceProvider(surfaceProvider)

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis!!
            )

            Log.i(TAG, "CameraX liveness camera started successfully with TextureView")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera", e)
            isRunning.set(false)
        }
    }

        private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            currentFrameBitmap?.recycle()
            currentFrameBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

            val detection = tfliteEngine.detectFace(bitmap)
            if (detection == null) {
                Log.d(TAG, "No face detected")
                emitFrameData("no_face", null, null, null, null, null, null)
                return
            }

            // Only check confidence — BlazeFace keypoint validation handles geometry
            if (detection.confidence < 0.5f) {
                Log.d(TAG, "Face rejected: low confidence ${detection.confidence}")
                emitFrameData("no_face", null, null, null, null, null, null)
                return
            }

            Log.d(TAG, "Face detected: confidence=${detection.confidence}")

            val landmarks = tfliteEngine.getLandmarks(bitmap, detection)
            if (landmarks == null) {
                Log.w(TAG, "Face detected but landmarks returned null")
                emitFrameData("face_detected", null, null, null, null, null, null)
                return
            }

            Log.d(TAG, "Landmarks detected: ${landmarks.points.size} points")

            val faceMeshLiveness = FaceMeshLiveness()
            val ear = faceMeshLiveness.computeEAR(landmarks)
            val mar = computeMAR(landmarks)
            val yaw = computeYaw(landmarks)

            addEarToHistory(ear)
            val blinkDetected = detectBlink()

            Log.d(TAG, "Metrics: EAR=$ear, MAR=$mar, Yaw=$yaw, Blink=$blinkDetected")

            emitFrameData(
                "landmarks_detected",
                ear,
                mar,
                yaw,
                blinkDetected,
                detection.confidence,
                detection.bbox.width() / bitmap.width.toFloat()
            )

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}", e)
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
        for (i in 2 until earHistory.size - 1) {
            val before = earHistory[i - 2]
            val current = earHistory[i]
            val after = earHistory[i + 1]
            if (before > 0.22f && current < 0.18f && after > 0.22f) {
                earHistory.clear()
                return true
            }
        }
        return false
    }

    private fun computeMAR(landmarks: FaceMeshLiveness.Landmarks): Float {
        val pts = landmarks.points
        if (pts.size <= 291) {
            Log.w(TAG, "Not enough landmarks for MAR: ${pts.size}")
            return 0f
        }
        val height = distance(pts[13], pts[14])
        val width = distance(pts[61], pts[291])
        val mar = if (width > 0.001f) height / width else 0f
        Log.d(TAG, "MAR computed: $mar (height=$height, width=$width)")
        return mar
    }

    private fun computeYaw(landmarks: FaceMeshLiveness.Landmarks): Float {
        val pts = landmarks.points
        if (pts.size <= 454) {
            Log.w(TAG, "Not enough landmarks for Yaw: ${pts.size}")
            return 0f
        }
        val noseTip = pts[1]
        val leftEar = pts[234]
        val rightEar = pts[454]
        val centerX = (leftEar.x + rightEar.x) / 2f
        return noseTip.x - centerX
    }

    private fun distance(a: FaceMeshLiveness.Point3D, b: FaceMeshLiveness.Point3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun emitFrameData(status: String, ear: Float?, mar: Float?, yaw: Float?, blink: Boolean?, faceScore: Float?, faceWidth: Float?) {
        val params = Arguments.createMap().apply {
            putString("status", status)
            ear?.let { putDouble("ear", it.toDouble()) }
            mar?.let { putDouble("mar", it.toDouble()) }
            yaw?.let { putDouble("yaw", it.toDouble()) }
            blink?.let { putBoolean("blinkDetected", it) }
            faceScore?.let { putDouble("faceScore", it.toDouble()) }
            faceWidth?.let { putDouble("faceWidth", it.toDouble()) }
        }
        onFrameData(params)
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val image = this.image ?: throw IllegalStateException("ImageProxy has no image")
        val planes = image.planes
        if (planes.size < 3) throw IllegalStateException("Expected 3 planes, got ${planes.size}")

        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

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
        if (isStopped) {
            Log.w(TAG, "stop() called but already stopped")
            return
        }
        isStopped = true
        isRunning.set(false)
        imageAnalysis?.clearAnalyzer()

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll failed: ${e.message}")
        }

        try {
            if (!analysisExecutor.isShutdown) {
                analysisExecutor.shutdown()
                if (!analysisExecutor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    analysisExecutor.shutdownNow()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Executor shutdown failed: ${e.message}")
        }

        earHistory.clear()
        currentFrameBitmap?.recycle()
        currentFrameBitmap = null
        Log.i(TAG, "CameraX liveness camera stopped")
    }
}