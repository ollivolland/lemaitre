package mycamera2

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.app.ActivityCompat
import kotlin.math.max


/**
 *   (1)    get CameraDevice
 *
 *   (2)    create CameraCaptureSession - requires all surfaces to be ready
 *
 *   (3)    create CaptureRequest
 */
//  TODO improve log statements

class MyCamera2(val context: Activity) {
	val cameraDeviceObservable = ValueObservable<CameraDevice>({ tryCreateSession() })
	val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
	val onCloseListeners = mutableListOf<() -> Unit>()
	private val backgroundThread: HandlerThread = HandlerThread("myCamera2 thread").apply { start() }
	private val backgroundHandler: Handler = Handler(backgroundThread.looper)
	private var captureSession:CameraCaptureSession? = null
	private val surfaces = mutableListOf<ValueObservable<Surface>>()
	private var isWantSessionOpen = false
	private var isSessionOpen = false
	private var fps = 30
	val sizes:Array<Size>
	
	init {
		//  get camera id
		val cameraId = cameraManager.cameraIdList
			.filter { cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }[0]
		
		//  capabilities
		val characteristics = cameraManager.getCameraCharacteristics(cameraId)
		val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
		val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
		
//		val cams = characteristics.physicalCameraIds
//		val maxZoom = characteristics[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM]
		sizes = streamConfigurationMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
//		val ss = characteristics[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]!!
//		val foc = characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]!![0]
//		val angleW = 2 * atan(ss.width * .5 * foc)
//		val angleH = 2 * atan(ss.height * .5 * foc)
		
		//  (1) open cameraDevice
		if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
			cameraManager.openCamera(cameraId, object: CameraDevice.StateCallback() {
				override fun onOpened(p0: CameraDevice) {
					Log.i(TAG, "camera: opened ${p0.id}")
					
					cameraDeviceObservable.value = p0
					tryCreateSession()
				}
				
				override fun onClosed(p0: CameraDevice) {
					Log.i(TAG, "camera: closed ${p0.id}")
				}
				
				override fun onDisconnected(p0: CameraDevice) {
					Log.i(TAG, "camera: disconnected ${p0.id}")
					
					p0.close()
				}
				
				override fun onError(p0: CameraDevice, p1: Int) {
					Log.i(TAG, "camera: error in ${p0.id}")
				}
			}, backgroundHandler)   //  open in backgroundThread
	}
	
	internal fun addSurface():ValueObservable<Surface> {
		val observable = ValueObservable<Surface>({ tryCreateSession() })
		surfaces.add(observable)
		
		return observable
	}
	
	fun addPreview(vTextureView: TextureView) = MyPreview(this, vTextureView)
	
	fun addRecorder(recordingProfile: MyRecorder.RecordingProfileBuilder):MyRecorder {
		fps = max(fps, recordingProfile.fps)
		return MyRecorder(this, recordingProfile)
	}
	
	fun addReader(readerProfileBuilder: MyReader.ReaderProfileBuilder, listener:ImageReader.OnImageAvailableListener):MyReader = MyReader(this, readerProfileBuilder, listener)
	
	fun open() {
		isWantSessionOpen = true
		tryCreateSession()
	}
	
	fun close() {
		Log.i(TAG,"camera: closing")
		
		if (cameraDeviceObservable.isInitialized) cameraDeviceObservable.value.close()
		if (captureSession != null) captureSession!!.close() //  we can call close() multiple times
		
		for (listener in onCloseListeners) listener()
		for (surface in surfaces.filter { it.isInitialized }) surface.value.release()
		
		//  stop background thread
		backgroundThread.quitSafely()
		try { backgroundThread.join() }
		catch (e: Exception) { /*info += e*/ }
	}
	
	private fun tryCreateSession() {
		if (!cameraDeviceObservable.isInitialized) { Log.i(TAG, "session creation not ready, cameraDevice not created"); return }
		if (!isWantSessionOpen) { Log.i(TAG, "session creation not ready, not opened by user"); return }
		if (isSessionOpen) { Log.i(TAG, "session creation skipping, session already opening"); return }
		if (surfaces.any { !it.isInitialized }) { Log.i(TAG, "session creation not ready, surfaces [${surfaces.map { it.isInitialized }.joinToString()}]"); return }
		
		isSessionOpen = true
		Log.i(TAG,"session creation launching with ${surfaces.size} surfaces [${surfaces.map { it.isInitialized }.joinToString()}]")
		
		//  (2) create session
		cameraDeviceObservable.value.createCaptureSession(surfaces.map { it.value }.toList(),
			object: CameraCaptureSession.StateCallback() {
				override fun onConfigured(session: CameraCaptureSession) {
					Log.i(TAG, "session creation: successful")
					captureSession = session

					//  (3) create Request, only ONE Request is allowed!
					val captureBuilder = cameraDeviceObservable.value.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
					captureBuilder[CaptureRequest.CONTROL_AF_MODE] = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
					captureBuilder[CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE] = Range(fps, fps)

					for (surface in surfaces)
						captureBuilder.addTarget(surface.value)

					session.setRepeatingRequest(captureBuilder.build(), null, null)
				}
				
				override fun onConfigureFailed(p0: CameraCaptureSession) { Log.i(TAG, "session creation: failed") }
			},
			backgroundHandler)
	}
	
	companion object {
		const val TAG = "myCamera2"
	}
}