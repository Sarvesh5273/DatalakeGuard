package com.datalakeguard.faceauth

import android.graphics.Matrix
import android.media.ExifInterface
import kotlin.math.sqrt

object Utils {
    
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA > 0 && normB > 0) {
            dot / (sqrt(normA) * sqrt(normB))
        } else 0f
    }
    
    fun getExifRotation(imagePath: String): Int {
        return try {
            val exif = ExifInterface(imagePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    fun l2Normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { it * it.toDouble() }.toFloat())
        return if (norm > 0) {
            FloatArray(vector.size) { i -> vector[i] / norm }
        } else {
            vector.clone()
        }
    }
}