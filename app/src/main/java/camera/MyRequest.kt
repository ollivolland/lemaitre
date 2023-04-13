package com.ollivolland.lemaitre.camera

import android.hardware.camera2.CameraCaptureSession
import android.view.Surface
import camera.ValueObservable

abstract class MyRequest {

    val surfaceObservable = ValueObservable<Surface>()
    var surface: Surface by surfaceObservable
    var myCamera2: MyCamera2 by ValueObservable({ onAssignedCamera() })
    private var isDoStartWhenReady = false
    private val sessionObservable = ValueObservable<CameraCaptureSession>({ if(isDoStartWhenReady) start() })
    var captureSession: CameraCaptureSession by sessionObservable

    fun start() {
        if(!sessionObservable.isInitialized) {
            isDoStartWhenReady = true
            return
        }
        
        if(!myCamera2.isCameraOpen) return  //  Camera already closed
        
        onStart()
    }
    
    fun close() {
        if(surfaceObservable.isInitialized) surface.release()
        
        onClose()
    }
    
    open fun onAssignedCamera() { }
    open fun onStart() { }
    open fun onClose() { }
}

