package wakelock

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager

class MyWakeLock {
    private lateinit var mWakeLock:PowerManager.WakeLock
    
    fun acquire(activity: Activity) {
        if(activity.checkSelfPermission(Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED)
            throw Exception("missing WAKE_LOCK permission")
        
        mWakeLock = (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "ollivolland:${activity.componentName}")
        mWakeLock.acquire(2*60*60*1000L)    // 2 hours
    }

    fun release() {
        if (mWakeLock.isHeld)  mWakeLock.release()
    }
}