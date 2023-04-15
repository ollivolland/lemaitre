package com.ollivolland.lemaitre.camera

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Environment
import android.util.Range
import camera.MyLog
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.pow

class MyRequestRecord(private val previewRequest: MyRequestPreview? = null): MyRequest() {

    private val width = 1920
    private val height = 1080
    private var bitrate = (10 * 2.0.pow(20) * 8).toInt()
    private var fps = 100
    val codec: MediaCodec
    val info = MyLog(this::class.java.name)
    private var isWrite = false
    private var isWantRecord = false
    private var isWantEOS = false
    private val muxer: MediaMuxer
    private val formatToSeconds = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH)

    init {
        val path = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera/VID_${formatToSeconds.format(Date(System.currentTimeMillis()))}.mp4"

        //  Format
        val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        //  Muxer
        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track:Int = -1

        //  Codec
        codec = MediaCodec.createEncoderByType(mimeType)
        var initialUs = -1L
        var indexFrame = 0
        codec.setCallback(object: MediaCodec.Callback() {

            override fun onOutputBufferAvailable(p0: MediaCodec, indexOutputBuffer: Int, p2: MediaCodec.BufferInfo)
            {
                if(!isWrite && isWantRecord && p2.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
                    isWrite = true
                    isWantRecord = false
                }
                
                //  write buffer to muxer
                if(isWrite) {
                    muxer.writeSampleData(track, codec.getOutputBuffer(indexOutputBuffer)!!, p2)
                    
                    //  stop all if EOS
                    if(isWantEOS) {
                        info += "Codec EOS"
        
                        muxer.stop()
                        muxer.release()
                        isWrite = false
                    }
    
                    if (initialUs == -1L) initialUs = p2.presentationTimeUs
                    indexFrame++
                    
                    if(p2.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) info += "wrote ${if(p2.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) "keyframe" else "frame"} $indexFrame at ${p2.presentationTimeUs - initialUs} Us"
                }
                
                //  release buffer
                codec.releaseOutputBuffer(indexOutputBuffer, false)
            }

            override fun onOutputFormatChanged(p0: MediaCodec, p1: MediaFormat) {
                info += "muxer started"
                track = muxer.addTrack(p0.outputFormat)     //  NEEDS FORMAT FROM CODEC.OUTPUT_FORMAT
                muxer.start()
            }
    
            override fun onInputBufferAvailable(p0: MediaCodec, p1: Int) = Unit
            override fun onError(p0: MediaCodec, p1: MediaCodec.CodecException) = Unit
        })

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }
    
    override fun onStart() {
        val captureBuilder = myCamera2.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        captureBuilder[CaptureRequest.CONTROL_AF_MODE] = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        captureBuilder[CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE] = Range(fps, fps)
//        captureBuilder[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        captureBuilder.addTarget(surface)

        if(previewRequest != null) {
            captureBuilder.addTarget(previewRequest.surface)
            muxer.setOrientationHint(previewRequest.totalRotation)
        }

        captureSession.setRepeatingRequest(captureBuilder.build(), null, null)
    }
    
    override fun onClose() {
        stopRecord()
        codec.stop()
        codec.release()
    }

    fun startRecord() { isWantRecord = true; codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    
    fun stopRecord() {
        if(isWrite && !isWantEOS) {
            codec.signalEndOfInputStream()
            isWantEOS = true
        }
    }
}