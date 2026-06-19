package com.ollivolland.lemaitre

import Globals
import GpsTime
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.SettingsClient
import datas.ClientData
import datas.Session
import setString
import java.io.File
import kotlin.concurrent.thread


class MainActivity : Activity() {
    //  by urgency
    //  todo    CRASHES CAUSE of gps permisson
    //  todo    check if cam profile is available
    //  todo    video timestamp
    //  todo    display images
    //  todo    stop start on all devices
    //  todo    dialog spinner info
    //  todo    separate home warnings and errors
    //  todo    home disable buttons on error
    //  todo    display storage space

    //  todo    hard logs

    //  big
    //  todo    audioTrack instead of MediaPlayer   https://stackoverflow.com/questions/12263671/audiotrack-android-playing-sounds-from-raw-folder

    //  BUGS
    
    private val wifiManager: WifiManager by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val locationManager: LocationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private var isRunning = true
    private lateinit var connectionManager: MyConnectionManager
    
    private lateinit var vLogger:TextView
    private lateinit var vFeedback:TextView
    lateinit var vHost: Button


    @SuppressLint("UnsafeOptInUsageError")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

//        if(BuildConfig.DEBUG)
            StrictMode.enableDefaults()

        //  permissions
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        val toGrant = permissions.filter { s -> checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

        if (toGrant.isNotEmpty()) requestPermissions(toGrant, 1)
        
        //  files
        if(!Globals.dirStarts.exists())
            Globals.dirStarts.mkdir()

        val dir = getExternalFilesDir(null)
        if(!File("$dir\\$PATH_SENSITIVITY").exists())
            File("$dir\\$PATH_SENSITIVITY").writeText("1000")

        //  ui
        vHost = findViewById(R.id.buttonHost)
        vLogger = findViewById(R.id.logger)
        vFeedback = findViewById(R.id.main_tFeedback)
        
        //  wifi
        connectionManager = MyConnectionManager(this)
        connectionManager.onInit = {
            vHost.isEnabled = true
        }
        connectionManager.init()


        vHost.setOnClickListener {
            connectionManager.setAsHost()

            vHost.setString("launch!")
            vHost.setOnClickListener {
                connectionManager.launchHost()

                startActivity(Intent(this, ActivityHome::class.java))
                finish()
            }
        }

        //  setup
        Session.log("sdk ${Build.VERSION.SDK_INT}")
        Session.log("version ${applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionCode}")

        //  enable wifi
        if(!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "WIFI is off", Toast.LENGTH_SHORT).show()

            if (Build.VERSION.SDK_INT <= 28) wifiManager.isWifiEnabled = true
            else startActivityForResult(Intent(Settings.Panel.ACTION_WIFI), 1)
        }

        //  enable location
        if(!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            buildAlertMessageNoGps()
        else
            GpsTime.register(MyApp.appContext)

        //  UIThread
        thread {
            while (isRunning) {
                runOnUiThread {
                    vLogger.text = Session.getLogs().takeLast(20).reversed().joinToString("\n")
                    vFeedback.text = when {
                        Session.isHost -> {
                            if(connectionManager.clients.isEmpty()) "no clients"
                            else connectionManager.clients.joinToString("\n") { it.humanName }
                        }
                        Session.isClient -> if(ClientData.get == null) "waiting for host" else "CONNECTED"
                        else -> "choose"
                    }
                }

                Thread.sleep(20)
            }
        }

        //  Todo
//        thread {
//            Velocity.extract(this, Globals.dirDownload.absolutePath + "/velocity-test-4.mp4")
//        }
    }


    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }


    private fun buildAlertMessageNoGps() {
        val locationRequest = LocationRequest.create().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY).setInterval(100).setFastestInterval(100)
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest).setAlwaysShow(true)
        val client: SettingsClient = LocationServices.getSettingsClient(this)

        client.checkLocationSettings(builder.build())
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        exception.startResolutionForResult(this, 1000)
                    } catch (sendEx: IntentSender.SendIntentException) {
                        startActivityForResult(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), 1001)
                    }
                } else
                    startActivityForResult(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), 1001)
            }
    }


    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if(requestCode == 1000 || requestCode == 1001) {
            println("GPS activity result")
            GpsTime.register(MyApp.appContext)
            connectionManager.myWifiP2p.requestConnectionInfo()
        }
    }


    companion object {
        const val PORT_FORMATION = 8888
        const val PORT_COMMUNICATION = 8900 //  +10
        const val SERVICE_NAME = "_ollivollandlemaitre"
        const val SERVICE_TYPE = "_presence._tcp"
        const val PATH_SENSITIVITY = "sensitivity.txt"
    }
}

