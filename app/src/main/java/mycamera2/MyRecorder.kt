package mycamera2

import Globals
import Vec
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import createVideoURI
import datas.Session
import datas.StartData
import java.io.File
import java.util.Date
import kotlin.math.atan

class MyRecorder internal constructor(private val myCamera2: MyCamera2, private val recordingProfileBuilder: RecordingProfileBuilder, private val ts: Long) {
	private val codec: MediaCodec
	private var isWrite = false
	private var isWantStart = false
	private var isWantStop = false
	private val muxer: MediaMuxer
	private val recordingProfile = recordingProfileBuilder.build()


	
	init {
		val surfaceObservable = myCamera2.addSurface()
		
		//  Format
		val path = "${Globals.dirDCIM}/VID_${Globals.formatToSeconds.format(Date(ts))}_${Globals.deviceFingerprint}.mp4"
		val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
		val pdf: ParcelFileDescriptor = myCamera2.context.contentResolver.openFileDescriptor(createVideoURI(myCamera2.context, path), "w")!!
		val format = MediaFormat.createVideoFormat(mimeType, recordingProfile.width, recordingProfile.height)
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
		format.setInteger(MediaFormat.KEY_BIT_RATE, recordingProfile.bytesPerSecond * 8)
		format.setInteger(MediaFormat.KEY_FRAME_RATE, recordingProfile.fps)
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)


		val focalLength = myCamera2.characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
		val sensorSize = myCamera2.characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
		val arraySize = myCamera2.characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
		val activeArrayPreCorrective = myCamera2.characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
		val activeArray = myCamera2.characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
		if(focalLength != null && sensorSize != null && arraySize != null && activeArray != null && activeArrayPreCorrective != null) {
			val fovPreCorrective = Vec.create(
				atan(sensorSize.width * (activeArrayPreCorrective.width().toDouble() / arraySize.width) / (focalLength * 2f)),
				atan(sensorSize.height * (activeArrayPreCorrective.height().toDouble() / arraySize.height) / (focalLength * 2f))
			) * 2.0 * 180.0 / Math.PI
			val fov = Vec.create(
				atan(sensorSize.width * (activeArray.width().toDouble() / arraySize.width) / (focalLength * 2f)),
				atan(sensorSize.height * (activeArray.height().toDouble() / arraySize.height) / (focalLength * 2f))
			) * 2.0 * 180.0 / Math.PI

			Session.log("fov preCorrective ${fovPreCorrective[0]}x${fovPreCorrective[1]}")
			Session.log("fov ${fov[0]}x${fov[1]}")
			Session.log("sensor array size ${arraySize.width}x${arraySize.height}")
			Session.log("active array ${activeArray.width()}x${activeArray.height()}")
			Session.log("active array preCorrective ${activeArrayPreCorrective.width()}x${activeArrayPreCorrective.height()}")
		}

		//  Muxer
		muxer = MediaMuxer(pdf.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
		var track:Int = -1
		
		//  Codec
		var initialUs = -1L
		var indexFrame = 0
		codec = MediaCodec.createEncoderByType(mimeType)
		codec.setCallback(object: MediaCodec.Callback() {
			
			override fun onOutputBufferAvailable(p0: MediaCodec, indexOutputBuffer: Int, p2: MediaCodec.BufferInfo) {
				if(!isWrite && isWantStart && p2.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
					isWrite = true
					Log.i(MyCamera2.TAG, "codec: registered start")
				}
				
				if(isWrite) {       //  write buffer to muxer
					if(isWantStop) codec.signalEndOfInputStream()
					
					muxer.writeSampleData(track, codec.getOutputBuffer(indexOutputBuffer)!!, p2)
					
					//  stop all if EOS
					if(isWantStop) {
						Log.i(MyCamera2.TAG, "codec: registered stop")
						
						muxer.stop()
						muxer.release()
						isWrite = false
					}
					
					if (initialUs == -1L) initialUs = p2.presentationTimeUs
					indexFrame++
					
					if(p2.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) Log.i(MyCamera2.TAG, "codec: wrote ${if(p2.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) "keyframe" else "frame"} $indexFrame at ${p2.presentationTimeUs - initialUs} Us")
				}
				
				//  release buffer
				codec.releaseOutputBuffer(indexOutputBuffer, false)
				
				if(isWantStop) {
					codec.stop()
					codec.release()
				}
			}
			override fun onOutputFormatChanged(p0: MediaCodec, p1: MediaFormat) {
				Log.i(MyCamera2.TAG, "muxer: started")
				
				track = muxer.addTrack(p0.outputFormat)     //  NEEDS FORMAT FROM CODEC.OUTPUT_FORMAT
				muxer.start()
			}
			
			override fun onInputBufferAvailable(p0: MediaCodec, p1: Int) = Unit
			override fun onError(p0: MediaCodec, p1: MediaCodec.CodecException) { Log.e(MyCamera2.TAG, p1.stackTraceToString()) }
		})
		
		codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
		surfaceObservable.value = codec.createInputSurface()
		codec.start()
		
		//  on close
		myCamera2.onCloseListeners.add {
			stopRecord()
			
			if(!isWantStart)
				File(path).delete()
			else
				StartData.writeFileFromDCIM(path)
		}
		
		//  rotation
		myCamera2.cameraDeviceObservable + {
			val characteristics = myCamera2.cameraManager.getCameraCharacteristics(it.id)
			val deviceRotation = MyPreview.SURFACE_ROTATION_TO_DEGREES[myCamera2.context.windowManager.defaultDisplay.rotation]!!
			val cameraSensorRotation = characteristics[CameraCharacteristics.SENSOR_ORIENTATION]!!
			val totalRotation = (cameraSensorRotation-deviceRotation+360)%360
			muxer.setOrientationHint(totalRotation) //  todo    totalRotation is 90° wrong
		}
	}
	
	fun startRecord() {
		if(isWantStart || isWantStop) return
		
		isWantStart = true
		codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
	}
	
	fun stopRecord() {
		if(isWantStop) return
		
		isWantStop = true
	}
	
	internal data class RecordingProfile(val width: Int, val height: Int, val bytesPerSecond: Int, val fps: Int)
	
	class RecordingProfileBuilder {
		var width = 1920
		var height = 1080
		var bytesPerSecond = 2_000_000
		var fps = 30
		
		internal fun build():RecordingProfile = RecordingProfile(width, height, bytesPerSecond, fps)
	}
}