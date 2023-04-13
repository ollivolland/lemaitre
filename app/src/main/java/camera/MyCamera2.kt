package com.ollivolland.lemaitre.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import camera.MyLog


@SuppressLint("MissingPermission")
class MyCamera2(val context: Activity) {

    val info = MyLog(this::class.java.name)
    private val warn = MyLog(this::class.java.name, Log.WARN)
    private val backgroundThread: HandlerThread = HandlerThread("myCamera2 thread").apply { start() }
    private val backgroundHandler: Handler = Handler(backgroundThread.looper)
    lateinit var cameraDevice: CameraDevice; private set
    lateinit var captureSession: CameraCaptureSession; private set
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val requests = mutableListOf<MyRequest>()
    private var isFinishedInitialization = false
    var isCameraOpen = false; private set

    init {
        //  get camera id
        val cameraId = cameraManager.cameraIdList
            .filter { cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }[0]

        //  open
        cameraManager.openCamera(cameraId, object: CameraDevice.StateCallback() {

            override fun onOpened(p0: CameraDevice) {
                info += "camera_${p0.id} opened"
                cameraDevice = p0
                isCameraOpen = true
            }

            override fun onClosed(p0: CameraDevice) {
                info += "camera_${p0.id} closed"
                isCameraOpen = false
            }

            override fun onDisconnected(p0: CameraDevice) {
                info += "camera_${p0.id} disconnected"
                p0.close()
            }

            override fun onError(p0: CameraDevice, p1: Int) { info += "camera_${p0.id} error" }
        }, backgroundHandler)
    }

    fun addRequest(myRequest: MyRequest): MyCamera2 {
        requests.add(myRequest)     //  needs to be called before callBack
        myRequest.myCamera2 = this
        myRequest.surfaceObservable + { callBackForSessionCreation() }

        return this
    }

    fun open(): MyCamera2 {
        isFinishedInitialization = true
        callBackForSessionCreation()

        return this
    }

    fun close() {
        if(this::cameraDevice.isInitialized) cameraDevice.close()
        if(this::captureSession.isInitialized) captureSession.close() //  we can call close() multiple times

        //  stop background thread
        backgroundThread.quitSafely()
        try { backgroundThread.join() }
        catch (e: Exception) { info += e }
        
        requests.forEach { it.close() }
    }

    private fun callBackForSessionCreation() {
        if(!isFinishedInitialization || !this::cameraDevice.isInitialized || requests.any { !it.surfaceObservable.isInitialized }) return

        info += "all surfaces ready, launching session creation"
        cameraDevice.createCaptureSession(requests.map { it.surface }, object: CameraCaptureSession.StateCallback() {

            override fun onConfigured(p0: CameraCaptureSession) {
                info += "session configured"
                captureSession = p0

                requests.forEach { req -> req.captureSession = captureSession }
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {
                warn += "session creation failed"
            }

        }, null)
    }
}