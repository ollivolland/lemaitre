package com.ollivolland.lemaitre2

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

class GpsTime {
    companion object {
        var numObservations = 0;private set
        var timeOfBoot = System.currentTimeMillis() - SystemClock.elapsedRealtime();private set
        
        private val timeToBootList:MutableList<Long> = mutableListOf()
        private lateinit var locationManager: LocationManager
        private val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val thisTimeToBoot = location.time - location.elapsedRealtimeNanos / 1_000_000L

                synchronized(timeToBootList) {
                    if (numObservations >= 1) timeToBootList.add(thisTimeToBoot)
                    if (timeToBootList.count() > 300) timeToBootList.removeAt(0)
                }

                Log.v("LOCATION", "Time GPS: ${Globals.FORMAT_TIME.format(location.time)}," +
                        "TimeToBoot = $thisTimeToBoot (${location.provider})")
    
                timeOfBoot = if (timeToBootList.isEmpty()) 0 else timeToBootList.average().toLong()

                if(numObservations > 100) unregister()
                numObservations++
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        @SuppressLint("MissingPermission")
        fun register(context: Context) {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener)
        }

        fun unregister() {
            if(this::locationManager.isInitialized) locationManager.removeUpdates(locationListener)
        }
    }
}