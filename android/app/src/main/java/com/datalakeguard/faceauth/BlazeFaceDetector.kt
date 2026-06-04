package com.datalakeguard.faceauth

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class BlazeFaceDetector {

    companion object {
        private const val TAG = "BlazeFaceDetector"
        private const val INPUT_SIZE = 128
        private const val NUM_ANCHORS = 896
        private const val NUM_COORDS = 16
        private const val NUM_KEYPOINTS = 6
    }

    data class Keypoint(val x: Float, val y: Float)
    data class FaceDetection(
        val bbox: RectF,
        val confidence: Float,
        val keypoints: List<Keypoint>
    )

    private val anchors: Array<FloatArray> by lazy { generateAnchors() }

    fun detect(bitmap: Bitmap, interpreter: Interpreter): FaceDetection? {
        try {
            Log.d(TAG, "detect on ${bitmap.width}x${bitmap.height}")
            for (pass in 0..1) {
                val input = preprocess(bitmap, useBgr = (pass == 1))
                // 3D shapes [1, 896, 16] and [1, 896, 1] to match model output
                val reg: Array<Array<FloatArray>> = Array(1) { Array(NUM_ANCHORS) { FloatArray(NUM_COORDS) } }
                val cls: Array<Array<FloatArray>> = Array(1) { Array(NUM_ANCHORS) { FloatArray(1) } }
                val outputMap = HashMap<Int, Any>()
                outputMap[0] = reg
                outputMap[1] = cls
                interpreter.runForMultipleInputsOutputs(arrayOf(input), outputMap)
                val topRaw = cls[0].maxOf { it[0] }
                val maxSigmoid = 1f / (1f + exp(-topRaw))
                Log.d(TAG, "pass=$pass cls.maxRaw=$topRaw cls.maxSigmoid=$maxSigmoid")
                val decoded = decode(reg[0], cls[0])
                Log.d(TAG, "pass=$pass raw=${decoded.size}")
                if (decoded.isNotEmpty()) {
                    val best = decoded.first()
                    Log.d(TAG, "pass=$pass best.conf=${best.confidence}")
                    return scaleToBitmap(best, bitmap)
                }
            }
            return null
        } catch (e: Throwable) {
            Log.e(TAG, "detect error: ${e.message}", e)
            return null
        }
    }

    private fun preprocess(bitmap: Bitmap, useBgr: Boolean): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buf = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f
            if (useBgr) { buf.putFloat(b); buf.putFloat(g); buf.putFloat(r) }
            else        { buf.putFloat(r); buf.putFloat(g); buf.putFloat(b) }
        }
        buf.rewind()
        return buf
    }

    private fun generateAnchors(): Array<FloatArray> {
        val list = ArrayList<FloatArray>(NUM_ANCHORS)
        for (y in 0 until 16) for (x in 0 until 16) {
            val cx = (x + 0.5f) / 16f
            val cy = (y + 0.5f) / 16f
            list.add(floatArrayOf(cx, cy, 0.10f, 0.10f))
            list.add(floatArrayOf(cx, cy, 0.20f, 0.20f))
        }
        for (y in 0 until 8) for (x in 0 until 8) {
            val cx = (x + 0.5f) / 8f
            val cy = (y + 0.5f) / 8f
            list.add(floatArrayOf(cx, cy, 0.30f, 0.45f))
            list.add(floatArrayOf(cx, cy, 0.45f, 0.60f))
            list.add(floatArrayOf(cx, cy, 0.60f, 0.75f))
            list.add(floatArrayOf(cx, cy, 0.75f, 0.90f))
            list.add(floatArrayOf(cx, cy, 0.90f, 1.05f))
            list.add(floatArrayOf(cx, cy, 1.05f, 1.20f))
        }
        return list.toTypedArray()
    }

    private fun decode(
        regressors: Array<FloatArray>,
        classifiers: Array<FloatArray>
    ): List<FaceDetection> {
        val scoreThreshold = 0.30f
        val results = ArrayList<FaceDetection>()
        for (i in 0 until NUM_ANCHORS) {
            val rawScore = classifiers[i][0]
            val score = 1f / (1f + exp(-rawScore))
            if (score < scoreThreshold) continue

            val a = anchors[i]
            val r = regressors[i]
            val dx = r[0]
            val dy = r[1]
            val dw = r[2]
            val dh = r[3]
            val cx = a[0] + dx * 0.1f
            val cy = a[1] + dy * 0.1f
            val w = a[2] * Math.exp(dw.toDouble()).toFloat()
            val h = a[3] * Math.exp(dh.toDouble()).toFloat()

            val left = max(0f, cx - w / 2f)
            val top = max(0f, cy - h / 2f)
            val right = min(1f, cx + w / 2f)
            val bottom = min(1f, cy + h / 2f)

            val kps = ArrayList<Keypoint>(NUM_KEYPOINTS)
            for (k in 0 until NUM_KEYPOINTS) {
                val kx = a[0] + r[4 + k * 2] * 0.1f
                val ky = a[1] + r[5 + k * 2] * 0.1f
                kps.add(Keypoint(kx, ky))
            }
            results.add(
                FaceDetection(
                    bbox = RectF(left, top, right, bottom),
                    confidence = score,
                    keypoints = kps
                )
            )
        }
        return applyNMS(results, 0.3f).sortedByDescending { it.confidence }
    }

    private fun scaleToBitmap(d: FaceDetection, bmp: Bitmap): FaceDetection {
        val sx = bmp.width.toFloat()
        val sy = bmp.height.toFloat()
        return FaceDetection(
            bbox = RectF(d.bbox.left * sx, d.bbox.top * sy, d.bbox.right * sx, d.bbox.bottom * sy),
            confidence = d.confidence,
            keypoints = d.keypoints.map { Keypoint(it.x * sx, it.y * sy) }
        )
    }

    private fun applyNMS(dets: List<FaceDetection>, iouThreshold: Float): List<FaceDetection> {
        if (dets.isEmpty()) return dets
        val sorted = dets.sortedByDescending { it.confidence }
        val keep = ArrayList<FaceDetection>(sorted.size)
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            keep.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(sorted[i].bbox, sorted[j].bbox) > iouThreshold) suppressed[j] = true
            }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix1 = max(a.left, b.left); val iy1 = max(a.top, b.top)
        val ix2 = min(a.right, b.right); val iy2 = min(a.bottom, b.bottom)
        val iw = ix2 - ix1; val ih = iy2 - iy1
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }
}
