package com.datalakeguard.faceauth

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class MobileFaceNetEmbedder {
    
    companion object {
        private const val TAG = "MobileFaceNetEmbedder"
        private const val INPUT_SIZE = 112
        private const val EMBEDDING_DIM = 192  // FIXED: model outputs 192, not 128
    }
    
    fun getEmbedding(bitmap: Bitmap, detection: BlazeFaceDetector.FaceDetection, interpreter: Interpreter): FloatArray? {
        try {
            Log.d(TAG, "Getting embedding. Bitmap: ${bitmap.width}x${bitmap.height}")
            
            val faceBitmap = cropAndAlign(bitmap, detection)
            Log.d(TAG, "Cropped face: ${faceBitmap.width}x${faceBitmap.height}")
            
            val inputBuffer = preprocess(faceBitmap)
            
            val output = Array(1) { FloatArray(EMBEDDING_DIM) }
            interpreter.run(inputBuffer, output)
            
            val embedding = output[0]
            val norm = sqrt(embedding.sumOf { it * it.toDouble() }.toFloat())
            if (norm > 0) {
                for (i in embedding.indices) {
                    embedding[i] = embedding[i] / norm
                }
            }
            
            Log.d(TAG, "Embedding extracted: ${embedding.size} dims, norm=$norm")
            return embedding
        } catch (e: Exception) {
            Log.e(TAG, "Embedding extraction error: ${e.message}", e)
            return null
        }
    }
    
    private fun cropAndAlign(bitmap: Bitmap, detection: BlazeFaceDetector.FaceDetection): Bitmap {
        val padding = 0.2f
        val left = kotlin.math.max(0f, detection.bbox.left - detection.bbox.width() * padding).toInt()
        val top = kotlin.math.max(0f, detection.bbox.top - detection.bbox.height() * padding).toInt()
        val right = kotlin.math.min(bitmap.width.toFloat(), detection.bbox.right + detection.bbox.width() * padding).toInt()
        val bottom = kotlin.math.min(bitmap.height.toFloat(), detection.bbox.bottom + detection.bbox.height() * padding).toInt()
        
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
            val r = ((pixel shr 16 and 0xFF) - 127.5f) / 128.0f
            val g = ((pixel shr 8 and 0xFF) - 127.5f) / 128.0f
            val b = ((pixel and 0xFF) - 127.5f) / 128.0f
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }
        
        return inputBuffer
    }
}