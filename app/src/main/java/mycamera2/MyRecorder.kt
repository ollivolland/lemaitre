package mycamera2

import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MyRecorder(private val myCamera2: MyCamera2, private val recordingProfileBuilder: RecordingProfileBuilder) {
	private val codec: MediaCodec
	private var isWrite = false
	private var isWantStart = false
	private var isWantStop = false
	private val muxer: MediaMuxer
	private val formatToSeconds = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH)
	private val recordingProfile = recordingProfileBuilder.build()
	
	init {
		val surfaceObservable = myCamera2.addSurface()
		
		//  Format
		val path = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera/VID_${formatToSeconds.format(Date(System.currentTimeMillis()))}.mp4"
		val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
		val format = MediaFormat.createVideoFormat(mimeType, recordingProfile.width, recordingProfile.height)
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
		format.setInteger(MediaFormat.KEY_BIT_RATE, recordingProfile.bytesPerSecond * 8)
		format.setInteger(MediaFormat.KEY_FRAME_RATE, recordingProfile.fps)
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
		
		//  Muxer
		muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
		}
		
		//  rotation
		myCamera2.cameraDeviceObservable + {
			val characteristics = myCamera2.cameraManager.getCameraCharacteristics(it.id)
			val deviceRotation = MyPreview.SURFACE_ROTATION_TO_DEGREES[myCamera2.context.windowManager.defaultDisplay.rotation]!!
			val cameraSensorRotation = characteristics[CameraCharacteristics.SENSOR_ORIENTATION]!!
			val totalRotation = (cameraSensorRotation-deviceRotation+360)%360
			muxer.setOrientationHint(totalRotation) //  totalRotation is 90° wrong
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