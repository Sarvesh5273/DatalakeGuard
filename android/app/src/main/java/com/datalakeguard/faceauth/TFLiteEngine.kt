package com.datalakeguard.faceauth

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.MappedByteBuffer

class TFLiteEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "TFLiteEngine"
    }
    
    private var blazeFaceInterpreter: Interpreter? = null
    private var faceMeshInterpreter: Interpreter? = null
    private var mobileFaceNetInterpreter: Interpreter? = null
    
    private val blazeFaceDetector: BlazeFaceDetector by lazy { BlazeFaceDetector() }
    private val faceMeshLiveness: FaceMeshLiveness by lazy { FaceMeshLiveness() }
    private val mobileFaceNetEmbedder: MobileFaceNetEmbedder by lazy { MobileFaceNetEmbedder() }
    
    init {
        loadModels()
    }
    
    private fun loadModels() {
        try {
            blazeFaceInterpreter = Interpreter(loadModelFile("blazeface_short_range.tflite"))
            faceMeshInterpreter = Interpreter(loadModelFile("face_landmark.tflite"))
            mobileFaceNetInterpreter = Interpreter(loadModelFile("mobilefacenet.tflite"))
            
            // LOG ACTUAL OUTPUT SHAPES
            blazeFaceInterpreter?.let { interp ->
                Log.i(TAG, "BlazeFace inputs:")
                listOf(interp.getInputTensor(0)).forEach { d ->
                    Log.i(TAG, "  shape=${d.shape().contentToString()}, type=${d.dataType()}")
                }
                Log.i(TAG, "BlazeFace outputs:")
                listOf(interp.getOutputTensor(0), interp.getOutputTensor(1)).forEachIndexed { i, d ->
                    Log.i(TAG, "  [$i] shape=${d.shape().contentToString()}, type=${d.dataType()}")
                }
            }
            
            Log.i(TAG, "All TFLite models loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite models: ${e.message}")
            throw RuntimeException("Model loading failed. Ensure .tflite files are in assets/models/", e)
        }
    }
    
    private fun loadModelFile(modelName: String): MappedByteBuffer {
        return FileUtil.loadMappedFile(context, "models/$modelName")
    }
    
    fun detectFace(bitmap: Bitmap): BlazeFaceDetector.FaceDetection? {
        val interpreter = blazeFaceInterpreter ?: return null
        return blazeFaceDetector.detect(bitmap, interpreter)
    }
    
    fun getLandmarks(bitmap: Bitmap, detection: BlazeFaceDetector.FaceDetection): FaceMeshLiveness.Landmarks? {
        val interpreter = faceMeshInterpreter ?: return null
        return faceMeshLiveness.extractLandmarks(bitmap, detection, interpreter)
    }
    
    fun getEmbedding(bitmap: Bitmap, detection: BlazeFaceDetector.FaceDetection): FloatArray? {
        val interpreter = mobileFaceNetInterpreter ?: return null
        return mobileFaceNetEmbedder.getEmbedding(bitmap, detection, interpreter)
    }
    
    fun close() {
        blazeFaceInterpreter?.close()
        faceMeshInterpreter?.close()
        mobileFaceNetInterpreter?.close()
    }
}