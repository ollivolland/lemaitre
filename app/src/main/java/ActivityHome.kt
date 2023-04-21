package com.ollivolland.lemaitre2

import Globals
import MyTimer
import ViewDevice
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import datas.ClientData
import datas.ConfigData
import datas.HostData
import datas.StartData
import wakelock.MyWakeLock
import java.util.*
import kotlin.concurrent.thread

class ActivityHome : AppCompatActivity() {
    lateinit var vLogger: TextView
    lateinit var vFeedback: TextView
    private val logs:MutableList<String> = mutableListOf()
    private val feedbacks:MutableList<String> = mutableListOf()
    var isRunning = true
    var sentLastUpdate = 0L
    private val wakeLock = MyWakeLock()
    private lateinit var viewGlobal:ViewDevice
    private lateinit var viewConfigMe:ViewDevice
    private lateinit var viewConfigClients:Array<ViewDevice>
    val hasLaunched = mutableListOf<Long>()

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        println("HOME CREATED")

        vLogger = findViewById(R.id.home_tLogger)
        vFeedback = findViewById(R.id.home_tFeedback)
        val vBlinker = findViewById<View>(R.id.home_vBlinker)
        val vConfig = findViewById<LinearLayout>(R.id.home_lConfig)
        val vButtons = findViewById<LinearLayout>(R.id.home_lButtons)

        //  *****   HOST
        if(Session.state == SessionState.HOST) {
            val data = HostData.get
            val configMe = ConfigData(data.hostName)
            configMe.setAsHost()
            val configClients = Array(data.clients.size) { i -> ConfigData(data.clients[i].name) }

            //  update
            for(i in data.mySockets.indices) {
                //  possible reassignment before lambda call
                data.mySockets[i].addOnRead {
                    try {
                        if (it.startsWith("update=")) data.lastUpdate[i] = it.removePrefix("update=").toLong()
                    } catch (_:Exception) {}
                }
    
                Session.receiveFeedback(data.mySockets[i]) { receiveFeedback(it, false) }
            }

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
                
                vStart.isEnabled = false
                thread {
                    Thread.sleep(500)
                    runOnUiThread { vStart.isEnabled = true }
                }
            }
    
            viewGlobal = ViewDevice(this, vConfig)
            viewGlobal.vSettings.setOnClickListener {
                HostData.createRoot(this).setOnCancelListener { tryUpdateViewGlobal() }
            }
            tryUpdateViewGlobal()
    
            viewConfigMe = ViewDevice(this, vConfig)
            viewConfigMe.vTitle.text = configMe.deviceName
            viewConfigMe.vSettings.setOnClickListener {
                configMe.dialog(this).setOnCancelListener {
                    configMe.updateView(viewConfigMe, "host")
                }
            }
            configMe.updateView(viewConfigMe, "host")
    
            viewConfigClients = Array(configClients.size) { ViewDevice(this, vConfig) }
            viewConfigClients.indices.forEach { configClients[it].updateView(viewConfigClients[it], "client") }

            //  dialogs
            var iClient = 0
            var dialogClient:()->Unit={}
            dialogClient = {
                if(iClient < configClients.size) {
                    val config = configClients[iClient]
                    val view = viewConfigClients[iClient]
                    val socket = data.mySockets[iClient]
                    
                    config.dialog(this).setOnCancelListener {
                        view.vTitle.text = config.deviceName
                        view.vSettings.setOnClickListener {
                            config.dialog(this).setOnCancelListener {
                                config.updateView(view, "client")
                                config.send(socket)
                                log("sent config $config")
                            }
                        }
                        config.updateView(view, "client")
                        config.send(socket)
                        log("sent config $config")
                        
                        iClient++
                        dialogClient()
                    }
                }
            }
            HostData.createRoot(this).setOnCancelListener {
                tryUpdateViewGlobal()
    
                Session.currentConfig = configMe
                configMe.dialog(this).setOnCancelListener {
                    configMe.updateView(viewConfigMe, "host")
                    
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
            if(data.mySocket != null)
                Session.receiveFeedback(data.mySocket!!) { receiveFeedback(it, false) }
        }

        //  misc
        GpsTime.register(this)
        wakeLock.acquire(this)

        //  blinker
        thread {
            while (isRunning) {
                val should = if(MyTimer().time % 1000 <= 100) View.VISIBLE else View.INVISIBLE
                if(vBlinker.visibility != should) runOnUiThread { vBlinker.visibility = should }

                Thread.sleep(1)
            }
        }

        //  check for starts
        thread {
            while (isRunning) {
                //  start starts
                if(!ActivityStart.isBusy)
                    for (x in Session.starts)
                        if(!hasLaunched.contains(x.id) && x.timeOfInit < MyTimer().time + TIME_START)
                        {
                            hasLaunched.add(x.id)
                            ActivityStart.launch(this, x)
                            receiveFeedback("started ${Globals.FORMAT_TIME.format(x.timeOfInit)}\n", false)
                            log("do $x")
                            
                            break
                        }

                //  client update host
                if(Session.state == SessionState.CLIENT && MyTimer().time > sentLastUpdate + 1000)
                    ClientData.get!!.mySocket?.write("update=${MyTimer().time}")

                //  feedback
                val feedback = if(Session.starts.any { !hasLaunched.contains(it.id) }) {
                    val all = Session.starts.filter { !hasLaunched.contains(it.id) }
                    
                    if(all.isEmpty()) "will start at ${Globals.FORMAT_TIME.format(all.minOf { it.timeOfInit })}"
                    else "will start at ${Globals.FORMAT_TIME.format(all.minOf { it.timeOfInit })} (+${all.size} others)"
                } else "no start scheduled"
                runOnUiThread {
                    vFeedback.text = "$feedback\n\n${feedbacks.reversed().joinToString("\n")}"
                }

                Thread.sleep(20)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        GpsTime.unregister()
        wakeLock.release()
    }
    
    private fun tryUpdateViewGlobal() {
        if(this::viewGlobal.isInitialized) {
            viewGlobal.vTitle.text = HostData.get.command
            viewGlobal.vDesc.text = "flavor:${HostData.get.flavor/1000}s length:${HostData.get.videoLength/1000}s Δ:+${HostData.get.delta/1000}s"
        }
    }

    private fun log(string: String) {
        println(string)
        logs.add(string)
        runOnUiThread {
            vLogger.text = logs.takeLast(20).reversed().joinToString("\n")
        }
    }
    
    fun receiveFeedback(string: String, isBroadCast:Boolean) {
        feedbacks.add(string)
        
        if(!isBroadCast) return
        
        if(Session.state == SessionState.HOST) {
            val data = HostData.get
            
            data.mySockets.forEach {
                Session.sendFeedback(it, string)
            }
        }
        else {
            val data = ClientData.get!!
            
            if(data.mySocket != null) Session.sendFeedback(data.mySocket!!, string)
        }
    }

    companion object {
        const val TIME_START = 3_000L
    }
}