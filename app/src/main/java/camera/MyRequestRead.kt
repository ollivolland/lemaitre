package com.ollivolland.lemaitre.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import camera.MyLog

class MyRequestRead(private val previewRequest: MyRequestPreview? = null): MyRequest() {

    private val width = 1920
    private val height = 1080
    private val info = MyLog(this::class.java.name)
    private val imReader: ImageReader = ImageReader.newInstance(width, height,  ImageFormat.YUV_420_888, 2)
    var onImage: (Image) -> Unit = { }
    private var isRead = false

    init {
        imReader.setOnImageAvailableListener({
            val image = imReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            
            if(isRead) {
                info += "image acquired from ${image.timestamp}, ${SystemClock.elapsedRealtimeNanos()}"
                onImage(image)
            }
            
            image.close()
        }, null)

        surface = imReader.surface
    }
    
    override fun onStart() {
        val captureBuilder = myCamera2.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        captureBuilder[CaptureRequest.CONTROL_AF_MODE] = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        captureBuilder.addTarget(surface)

        if(previewRequest != null) captureBuilder.addTarget(previewRequest.surface)

        captureSession.setRepeatingRequest(captureBuilder.build(), null, null)
    }
    
    override fun onClose() {
        stopRead()
        imReader.close()
    }
    
    fun startRead() { isRead = true }
    fun stopRead() { isRead = false }
}