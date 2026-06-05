package com.datalakeguard.faceauth

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

class FaceMeshLiveness {

    companion object {
        private const val TAG = "FaceMeshLiveness"
    }

    data class Landmarks(
        val points: List<Point3D>,
        val imageWidth: Int,
        val imageHeight: Int
    )

    data class Point3D(
        val x: Float,
        val y: Float,
        val z: Float
    )

    fun extractLandmarks(bitmap: Bitmap, detection: BlazeFaceDetector.FaceDetection, interpreter: Interpreter): Landmarks? {
        Log.d(TAG, "extractLandmarks called")
        try {
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            val inputShape = inputTensor.shape()
            val outputShape = outputTensor.shape()

            Log.d(TAG, "FaceMesh input: ${inputShape.contentToString()}")
            Log.d(TAG, "FaceMesh output: ${outputShape.contentToString()}")

            // Dynamically read input size from model (yours is 256)
            val inputSize = if (inputShape.size >= 2) inputShape[1] else 192
            Log.d(TAG, "Using input size: $inputSize")

            val faceBitmap = cropAndResize(bitmap, detection.bbox, inputSize)
            val inputBuffer = preprocess(faceBitmap, inputSize)

            // SAFER: compute total elements, handling -1 or 0 dimensions
            var totalOutputElements = 1
            for (dim in outputShape) {
                if (dim <= 0) {
                    Log.e(TAG, "Invalid dimension in output shape: $dim")
                    return null
                }
                totalOutputElements *= dim
            }

            val numLandmarks = totalOutputElements / 3
            Log.d(TAG, "Total elements: $totalOutputElements, landmarks: $numLandmarks")

            if (numLandmarks < 468) {
                Log.e(TAG, "Too few landmarks: $numLandmarks")
                return null
            }

            // CRITICAL FIX: Match the EXACT output shape [1, 1, 1, 1434]
            // Use 4D array to match model's 4D output
            val output4D = Array(1) { Array(1) { Array(1) { FloatArray(totalOutputElements) } } }
            interpreter.run(inputBuffer, output4D)

            // Flatten the 4D output to 1D for easier processing
            val flatOutput = output4D[0][0][0]

            val points = (0 until numLandmarks).map { i ->
                Point3D(
                    x = flatOutput[i * 3],
                    y = flatOutput[i * 3 + 1],
                    z = flatOutput[i * 3 + 2]
                )
            }

            Log.d(TAG, "Extracted $numLandmarks landmarks")
            Log.d(TAG, "Raw landmark 0: ${points[0]}")
            Log.d(TAG, "Raw landmark 33: ${points.getOrNull(33)}")

            // Map to NORMALIZED coordinates (0..1) so thresholds work correctly
            val mappedPoints = points.map { pt ->
                Point3D(
                    x = (detection.bbox.left + (pt.x / inputSize) * detection.bbox.width()) / bitmap.width,
                    y = (detection.bbox.top + (pt.y / inputSize) * detection.bbox.height()) / bitmap.height,
                    z = pt.z
                )
            }

            Log.d(TAG, "Mapped landmark 33: ${mappedPoints.getOrNull(33)}")
            Log.d(TAG, "Mapped landmark 362: ${mappedPoints.getOrNull(362)}")

            return Landmarks(mappedPoints, bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e(TAG, "Landmark extraction error: ${e.message}", e)
            e.printStackTrace()
            return null
        }
    }

    fun computeEAR(landmarks: Landmarks): Float {
        val leftEAR = calculateEAR(landmarks, leftEyeIndices)
        val rightEAR = calculateEAR(landmarks, rightEyeIndices)
        return (leftEAR + rightEAR) / 2f
    }

    fun checkBlink(landmarks: Landmarks): Boolean {
        return computeEAR(landmarks) < 0.2f
    }

    fun checkSmile(landmarks: Landmarks): Boolean {
        val mar = calculateMAR(landmarks)
        return mar > 0.5f
    }

    fun checkHeadTurn(landmarks: Landmarks, direction: String): Boolean {
        val noseTip = landmarks.points[1]
        val centerX = 0.5f
        val threshold = 0.15f

        return when (direction) {
            "left" -> noseTip.x < centerX - threshold
            "right" -> noseTip.x > centerX + threshold
            else -> false
        }
    }

    private fun calculateEAR(landmarks: Landmarks, indices: IntArray): Float {
        val p = indices.map { landmarks.points.getOrNull(it) }.filterNotNull()
        if (p.size < 6) {
            Log.w(TAG, "Not enough points for EAR: ${p.size}")
            return 1f
        }

        val v1 = distance(p[1], p[5])
        val v2 = distance(p[2], p[4])
        val h = distance(p[0], p[3])

        return if (h > 0.001f) (v1 + v2) / (2f * h) else 1f
    }

    private fun calculateMAR(landmarks: Landmarks): Float {
        val p13 = landmarks.points.getOrNull(13)
        val p14 = landmarks.points.getOrNull(14)
        val p61 = landmarks.points.getOrNull(61)
        val p291 = landmarks.points.getOrNull(291)

        if (p13 == null || p14 == null || p61 == null || p291 == null) {
            Log.w(TAG, "Missing mouth landmarks")
            return 0f
        }

        val height = distance(p13, p14)
        val width = distance(p61, p291)

        return if (width > 0.001f) height / width else 0f
    }

    private fun distance(a: Point3D, b: Point3D): Float {
        return sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))
    }

    private fun cropAndResize(bitmap: Bitmap, bbox: RectF, inputSize: Int): Bitmap {
        val padding = 0.2f
        val left = kotlin.math.max(0f, bbox.left - bbox.width() * padding).toInt()
        val top = kotlin.math.max(0f, bbox.top - bbox.height() * padding).toInt()
        val right = kotlin.math.min(bitmap.width.toFloat(), bbox.right + bbox.width() * padding).toInt()
        val bottom = kotlin.math.min(bitmap.height.toFloat(), bbox.bottom + bbox.height() * padding).toInt()

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Invalid crop, resizing full bitmap")
            return Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        }

        val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
        return Bitmap.createScaledBitmap(cropped, inputSize, inputSize, true)
    }

    private fun preprocess(bitmap: Bitmap, inputSize: Int): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF) / 255.0f
            val g = (pixel shr 8 and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        return inputBuffer
    }

    private val leftEyeIndices = intArrayOf(33, 160, 158, 133, 153, 144)
    private val rightEyeIndices = intArrayOf(362, 385, 387, 263, 373, 380)
}