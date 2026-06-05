package com.datalakeguard.faceauth

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.sqrt

/**
 * Multi-angle enrollment: stores 3 embeddings per worker (front, left, right)
 * Auth: compares against all 3, returns max cosine similarity
 * Target: >95% accuracy via ensemble + L2 normalization
 */
class FaceEnrollmentManager(context: Context) {

    companion object {
        private const val TAG = "FaceEnrollment"
        private const val PREFS_NAME = "face_enrollment"
        private const val KEY_WORKERS = "workers"
    }

    data class WorkerProfile(
        val workerId: String,
        val name: String,
        val embeddings: List<FloatArray>, // 3 embeddings: front, left, right
        val enrolledAt: Long
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun enrollWorker(
        workerId: String,
        name: String,
        frontEmbedding: FloatArray,
        leftEmbedding: FloatArray,
        rightEmbedding: FloatArray
    ): Boolean {
        try {
            val normalized = listOf(
                l2Normalize(frontEmbedding),
                l2Normalize(leftEmbedding),
                l2Normalize(rightEmbedding)
            )
            val profile = WorkerProfile(workerId, name, normalized, System.currentTimeMillis())
            saveProfile(profile)
            Log.i(TAG, "Enrolled worker $workerId with ${normalized.size} angles")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Enrollment failed", e)
            return false
        }
    }

    fun findBestMatch(embedding: FloatArray): MatchResult? {
        val query = l2Normalize(embedding)
        val workers = getAllWorkers()

        var bestScore = -1f
        var bestWorker: WorkerProfile? = null

        for (worker in workers) {
            for (emb in worker.embeddings) {
                val score = cosineSimilarity(query, emb)
                if (score > bestScore) {
                    bestScore = score
                    bestWorker = worker
                }
            }
        }

        return if (bestWorker != null) {
            MatchResult(bestWorker.workerId, bestWorker.name, bestScore)
        } else null
    }

    data class MatchResult(val workerId: String, val name: String, val confidence: Float)

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sum = 0f
        for (v in vec) sum += v * v
        val norm = sqrt(sum)
        return if (norm > 0) FloatArray(vec.size) { vec[it] / norm } else vec
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // Already normalized, so dot product = cosine similarity
    }

    private fun saveProfile(profile: WorkerProfile) {
        val workers = getAllWorkers().toMutableList()
        workers.removeAll { it.workerId == profile.workerId }
        workers.add(profile)
        val json = gson.toJson(workers)
        prefs.edit().putString(KEY_WORKERS, json).apply()
    }

    fun getAllWorkers(): List<WorkerProfile> {
        val json = prefs.getString(KEY_WORKERS, null) ?: return emptyList()
        val type = object : TypeToken<List<WorkerProfile>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun deleteWorker(workerId: String) {
        val workers = getAllWorkers().toMutableList()
        workers.removeAll { it.workerId == workerId }
        prefs.edit().putString(KEY_WORKERS, gson.toJson(workers)).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
