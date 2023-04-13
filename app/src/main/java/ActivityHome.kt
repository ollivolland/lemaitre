package com.ollivolland.lemaitre2

import ClientConfig
import ConfigContainer
import HostData
import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class ActivityHome : AppCompatActivity() {
    lateinit var vLogger: TextView
    private val logs:MutableList<String> = mutableListOf()
    var isRunning = true

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        vLogger = findViewById(R.id.home_tLogger)
        val vBlinker = findViewById<View>(R.id.home_vBlinker)
        val vConfig = findViewById<LinearLayout>(R.id.home_lConfig)

        if(Session.state == SessionState.HOST) {
            val data = HostData.get
            val configMe = ClientConfig(false, false, false, ClientConfig.FPS_CHOICES[0])
            vConfig.visibility = View.VISIBLE

            val x = ConfigContainer(vConfig)
            HostData.createRoot(x.layout)

            val y = ConfigContainer(vConfig)
            configMe.createRoot(y.layout)

//            val ll = LinearLayout(this)
//            configMe.createRoot(ll)
            val d = Dialog(this)
            d.setContentView(R.layout.view_client)
            d.show()
        }

        GpsTime.register(this)

        log("launch")

        thread {
            while (isRunning) {
                val should = if(getTime() % 1000 < 200) View.VISIBLE else View.INVISIBLE
                if(vBlinker.visibility != should) runOnUiThread { vBlinker.visibility = should }

                Thread.sleep(1)
            }
        }

        //  check for starts
        thread {
            while (isRunning) {
                if(!ActivityStart.isBusy)
                    for (x in Session.starts)
                        if(!x.isLaunched && x.timeStamp < getTime() + TIME_START)
                            ActivityStart.launch(this, x)

                Thread.sleep(50)
            }
        }

        //  log
        thread {
            while (isRunning) {
                for (x in Session.starts) log(x.toString())

                Thread.sleep(10000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        GpsTime.unregister()
    }

//    fun getNetworkTime():Long {
//        val timeClient = NTPUDPClient()
//    }

    fun getTime():Long {
        return GpsTime.timeOfBoot + SystemClock.elapsedRealtime()
    }

    fun log(string: String) {
        println(string)
        logs.add(string)
        runOnUiThread {
            vLogger.text = logs.takeLast(20).reversed().joinToString("\n")
        }
    }

    companion object {
        const val TIME_START = 3_000L
    }
}