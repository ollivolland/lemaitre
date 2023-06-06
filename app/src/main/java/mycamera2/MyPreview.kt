package mycamera2

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs

class MyPreview internal constructor(private val myCamera2: MyCamera2, private val vTextureView: TextureView) {
	private val surfaceObservable:ValueObservable<Surface> = myCamera2.addSurface()
	
	init {
		if (vTextureView.isAvailable)
			processTextureView(vTextureView)
		else
			vTextureView.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
				override fun onSurfaceTextureAvailable(p0: SurfaceTexture, width: Int, height: Int) = processTextureView(vTextureView)
				override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) = Unit
				override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean = true
				override fun onSurfaceTextureUpdated(p0: SurfaceTexture) = Unit
			}
	}
	
	private fun processTextureView(vTextureView: TextureView) {
		val width = vTextureView.width
		val height = vTextureView.height
		
		Log.i(MyCamera2.TAG, "preview: TextureView available, ${width}x$height")
		
		myCamera2.cameraDeviceObservable + {
			val characteristics = myCamera2.cameraManager.getCameraCharacteristics(myCamera2.cameraDeviceObservable.value.id)
			val deviceRotation = SURFACE_ROTATION_TO_DEGREES[myCamera2.context.windowManager.defaultDisplay.rotation]!!
			val cameraSensorRotation = characteristics[CameraCharacteristics.SENSOR_ORIENTATION]!!
			val totalRotation = (cameraSensorRotation-deviceRotation+360)%360 //  minus instead of plus bc. https://stackoverflow.com/questions/48406497/camera2-understanding-the-sensor-and-device-orientations
			
			Log.i(MyCamera2.TAG, "preview: cameraSensorOrientation = $cameraSensorRotation,    windowOrientation = $deviceRotation,    totalRotation = $totalRotation")
			
			val map: StreamConfigurationMap = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
			val useWidth = if (totalRotation%180 == 0) width else height
			val useHeight = if (totalRotation%180 == 0) height else width
			val sizes = map.getOutputSizes(SurfaceTexture::class.java)
				.sortedBy { abs(1-it.width.toDouble()/useWidth)+abs(1-it.height.toDouble()/useHeight) }
			
			Log.i(MyCamera2.TAG, "preview: rotated size = ${useWidth}x${useHeight} / use size = ${sizes[0].width}x${sizes[0].height}")
			
			//  Transform
			val useSize = sizes[0]
			val rotation = myCamera2.context.windowManager.defaultDisplay.rotation
			val matrix = Matrix()
			val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
			val bufferRect = RectF(0f, 0f, useSize.height.toFloat(), useSize.width.toFloat())
			val centerX = viewRect.centerX()
			val centerY = viewRect.centerY()
			
			if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
				bufferRect.offset(centerX-bufferRect.centerX(), centerY-bufferRect.centerY())
				matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
				val scale: Float = (height.toFloat()/useSize.height).coerceAtLeast(width.toFloat()/useSize.width)
				
				matrix.postScale(scale, scale, centerX, centerY)
				matrix.postRotate((90*(rotation-2)).toFloat(), centerX, centerY)
			}
			else if (Surface.ROTATION_180 == rotation)
				matrix.postRotate(180f, centerX, centerY)
			
			vTextureView.setTransform(matrix)
			
			//  set surface
			surfaceObservable.value = Surface(vTextureView.surfaceTexture!!)
		}
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