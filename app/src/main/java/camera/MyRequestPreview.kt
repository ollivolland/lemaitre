package com.ollivolland.lemaitre.camera

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import android.view.Surface
import android.view.TextureView
import camera.MyLog
import kotlin.math.abs

class MyRequestPreview(private val vTextureView: TextureView): MyRequest() {

    val info = MyLog(this::class.java.name)
    var totalRotation: Int = -1
    var cameraSensorRotation: Int = -1
    var deviceRotation: Int = -1
    
    override fun onAssignedCamera() {
        vTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {

            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, width: Int, height: Int) {
                info += "TextureView available, ${width}x$height"
                
                val characteristics = myCamera2.cameraManager.getCameraCharacteristics(myCamera2.cameraDevice.id)
                deviceRotation = SURFACE_ROTATION_TO_DEGREES[myCamera2.context.windowManager.defaultDisplay.rotation]!!
                cameraSensorRotation = characteristics[CameraCharacteristics.SENSOR_ORIENTATION]!!
                totalRotation = (cameraSensorRotation - deviceRotation+360)%360 //  minus instead of plus bc. https://stackoverflow.com/questions/48406497/camera2-understanding-the-sensor-and-device-orientations
    
                info += "cameraSensorOrientation = $cameraSensorRotation"
                info += "deviceOrientation = $deviceRotation"
                info += "totalRotation = $totalRotation"
                
                val map: StreamConfigurationMap = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
                val useWidth = if(totalRotation % 180 == 0) width else height
                val useHeight = if(totalRotation % 180 == 0) height else width

                val sizes = map.getOutputSizes(SurfaceTexture::class.java)
                    .sortedBy { abs(1-it.width.toDouble()/useWidth)+abs(1-it.height.toDouble()/useHeight) }

                info += "rotated size = ${useWidth}x${useHeight}"
                info += "use size = ${sizes[0].width}x${sizes[0].height}"

                configureTransform(vTextureView, width, height, sizes[0])

                surface = Surface(vTextureView.surfaceTexture!!)
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) = Unit
            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) = Unit
        }
    }
    
    override fun onStart() {
        val captureBuilder = myCamera2.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureBuilder[CaptureRequest.CONTROL_AF_MODE] = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        captureBuilder.addTarget(surface)

        captureSession.setRepeatingRequest(captureBuilder.build(), null, null)
    }

    private fun configureTransform(mTextureView:TextureView, viewWidth: Int, viewHeight: Int, useSize: Size) {  //  copied from ???
        val rotation = myCamera2.context.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, useSize.height.toFloat(), useSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX-bufferRect.centerX(), centerY-bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale: Float = (viewHeight.toFloat()/useSize.height).coerceAtLeast(viewWidth.toFloat()/useSize.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90*(rotation-2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) matrix.postRotate(180f, centerX, centerY)

        mTextureView.setTransform(matrix)
    }
    
    companion object {
        val SURFACE_ROTATION_TO_DEGREES = mapOf(
            Surface.ROTATION_0 to 0,
            Surface.ROTATION_90 to 90,
            Surface.ROTATION_180 to 180,
            Surface.ROTATION_270 to 270,
        )
    }
}