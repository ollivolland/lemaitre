package com.ollivolland.lemaitre2

import MyTimer
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import datas.ClientData
import datas.ConfigData
import datas.HostData
import datas.StartData
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
        val vButtons = findViewById<LinearLayout>(R.id.home_lButtons)

        //  *****   HOST
        if(Session.state == SessionState.HOST) {
            val data = HostData.get
            val configMe = ConfigData(data.hostName)
            val configClients = Array(data.clients.size) { i -> ConfigData(data.clients[i].name) }

            //  ui
            vConfig.visibility = View.VISIBLE
            vButtons.visibility = View.VISIBLE
            val vStart = findViewById<Button>(R.id.home_bStart)
            val vSchedule = findViewById<Button>(R.id.home_bSchedule)

            vStart.setOnClickListener {
                val start = StartData.create(MyTimer().time + data.delta, data.command, data.flavor, data.videoLength)
                Session.starts.add(start)
                log("sent start $start")
                for (x in data.mySockets) start.send(x)
            }

            layoutInflater.inflate(R.layout.view_device, vConfig).also { root ->
                root.findViewById<TextView>(R.id.device_tTitle).text = data.hostName
                root.findViewById<TextView>(R.id.device_tDesc).text = "host"
            }
            for (x in configClients) {
                layoutInflater.inflate(R.layout.view_device, vConfig, false).also { root ->
                    root.findViewById<TextView>(R.id.device_tTitle).text = x.deviceName
                    root.findViewById<TextView>(R.id.device_tDesc).text = "client"
                    vConfig.addView(root)
                }
            }

            //  dialogs
            var iClient = 0
            var dialogClient:()->Unit={}
            dialogClient = {
                if(iClient < configClients.size) {
                    configClients[iClient].createRoot(this).setOnCancelListener {
                        configClients[iClient].send(data.mySockets[iClient])
                        log("sent config ${configClients[iClient]}")
                        iClient++
                        dialogClient()
                    }
                }
            }
            HostData.createRoot(this).setOnCancelListener {
                Session.currentConfig = configMe

                configMe.createRoot(this).setOnCancelListener {
                    dialogClient()
                }
            }
        }

        //  *****   CLIENT
        else {
            val data = ClientData.get!!

            data.mySocket?.addOnRead {
                ConfigData.tryReceive(data.deviceName, it) { cfg ->
                    log("received config = $cfg")
                    Session.currentConfig = cfg
                }
                StartData.tryReceive(it) { cfg ->
                    log("received start = $cfg")
                    Session.starts.add(cfg)
                }
            }
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
                        {
                            ActivityStart.launch(this, x)
                            log("do start = $x")
                        }

                Thread.sleep(50)
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