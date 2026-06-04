package com.datalakeguard.faceauth

import kotlin.math.pow
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class FaceMeshLiveness {
    
    companion object {
        private const val TAG = "FaceMeshLiveness"
        private const val INPUT_SIZE = 192
        private const val NUM_LANDMARKS = 468
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
        try {
            val faceBitmap = cropAndResize(bitmap, detection.bbox)
            val inputBuffer = preprocess(faceBitmap)
            
            val output = Array(1) { Array(NUM_LANDMARKS) { FloatArray(3) } }
            interpreter.run(inputBuffer, output)
            
            val points = output[0].map { pt ->
                Point3D(
                    x = pt[0] / INPUT_SIZE,
                    y = pt[1] / INPUT_SIZE,
                    z = pt[2]
                )
            }
            
            val mappedPoints = points.map { pt ->
                Point3D(
                    x = detection.bbox.left / bitmap.width + pt.x * detection.bbox.width() / bitmap.width,
                    y = detection.bbox.top / bitmap.height + pt.y * detection.bbox.height() / bitmap.height,
                    z = pt.z
                )
            }
            
            return Landmarks(mappedPoints, bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e(TAG, "Landmark extraction error: ${e.message}")
            return null
        }
    }
    
    // FIXED: Expose raw EAR for frame history
    fun computeEAR(landmarks: Landmarks): Float {
        val leftEAR = calculateEAR(landmarks, leftEyeIndices)
        val rightEAR = calculateEAR(landmarks, rightEyeIndices)
        return (leftEAR + rightEAR) / 2f
    }
    
    // Single-frame blink check (eyes currently closed)
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
    
    // FIXED: 6-point EAR formula (p1-p6)
    private fun calculateEAR(landmarks: Landmarks, indices: IntArray): Float {
        val p = indices.map { landmarks.points[it] }
        if (p.size < 6) return 1f
        
        // Vertical distances
        val v1 = distance(p[1], p[5])  // p2 to p6
        val v2 = distance(p[2], p[4])  // p3 to p5
        // Horizontal distance
        val h = distance(p[0], p[3])   // p1 to p4
        
        return if (h > 0.001f) (v1 + v2) / (2f * h) else 1f
    }
    
    // FIXED: Proper MAR using correct mouth indices
    private fun calculateMAR(landmarks: Landmarks): Float {
        // Mouth height: top lip (13) to bottom lip (14)
        val height = distance(landmarks.points[13], landmarks.points[14])
        // Mouth width: left corner (61) to right corner (291)
        val width = distance(landmarks.points[61], landmarks.points[291])
        
        return if (width > 0.001f) height / width else 0f
    }
    
    private fun distance(a: Point3D, b: Point3D): Float {
        return sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))
    }
    
    private fun cropAndResize(bitmap: Bitmap, bbox: RectF): Bitmap {
        val padding = 0.2f
        val left = kotlin.math.max(0f, bbox.left - bbox.width() * padding).toInt()
        val top = kotlin.math.max(0f, bbox.top - bbox.height() * padding).toInt()
        val right = kotlin.math.min(bitmap.width.toFloat(), bbox.right + bbox.width() * padding).toInt()
        val bottom = kotlin.math.min(bitmap.height.toFloat(), bbox.bottom + bbox.height() * padding).toInt()
        
        val width = right - left
        val height = bottom - top
        
        if (width <= 0 || height <= 0) {
            return Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }
        
        val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
        return Bitmap.createScaledBitmap(cropped, INPUT_SIZE, INPUT_SIZE, true)
    }
    
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
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
    
    // FIXED: Standard 6-point eye landmarks for EAR
    private val leftEyeIndices = intArrayOf(33, 160, 158, 133, 153, 144)
    private val rightEyeIndices = intArrayOf(362, 385, 387, 263, 373, 380)
}