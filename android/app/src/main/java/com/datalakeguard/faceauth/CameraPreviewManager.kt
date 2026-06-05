package com.datalakeguard.faceauth

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext

class CameraPreviewManager : SimpleViewManager<TextureView>() {

    override fun getName(): String = "CameraPreviewView"

    override fun createViewInstance(reactContext: ThemedReactContext): TextureView {
        val textureView = TextureView(reactContext)
        textureView.isOpaque = true
        // NO setBackgroundColor — TextureView doesn't support it

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.i("CameraPreviewManager", "TextureView available: ${width}x${height}")
                CameraPreviewHolder.surfaceTexture = surface
                CameraPreviewHolder.textureView = textureView
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                Log.i("CameraPreviewManager", "TextureView size changed: ${width}x${height}")
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.i("CameraPreviewManager", "TextureView destroyed")
                CameraPreviewHolder.surfaceTexture = null
                CameraPreviewHolder.textureView = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // Frame rendered
            }
        }

        return textureView
    }
}