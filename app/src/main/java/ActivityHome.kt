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
import setString
import wakelock.MyWakeLock
import java.util.*
import kotlin.concurrent.thread

class ActivityHome : AppCompatActivity() {
    private lateinit var vLogger: TextView
    private lateinit var vFeedback: TextView
    private val logs:MutableList<String> = mutableListOf()
    private val feedbacks:MutableList<String> = mutableListOf()
    private var isRunning = true
    private val wakeLock = MyWakeLock()
    private lateinit var viewGlobal:ViewDevice
    private lateinit var viewConfigMe:ViewDevice
    private lateinit var viewConfigClients:Array<ViewDevice>
    private lateinit var configClients:Array<ConfigData>
    private val hasLaunched = mutableListOf<Long>()
    private val socketReadListeners = mutableListOf<Pair<MySocket, (String) -> Unit>>()
    private val socketCloseListeners = mutableListOf<Pair<MySocket, () -> Unit>>()

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
            val data = HostData.get!!
            val configMe = ConfigData(data.hostName, true)
            configClients = Array(data.clients.size) { i -> ConfigData(data.clients[i].name) }

            //  update
            addSocketListener(data.mySockets) {
                println("socket received ${it.take(100)}")
                
                Session.tryReceiveFeedback(it) { msg -> receiveFeedback(msg, false) }
            }
            addSocketCloseListener(data.mySockets)

            //  ui
            vConfig.visibility = View.VISIBLE
            vButtons.visibility = View.VISIBLE
            val vStart = findViewById<Button>(R.id.home_bStart)
//            val vSchedule = findViewById<Button>(R.id.home_bSchedule)

            vStart.setOnClickListener {
                val start = StartData.create(MyTimer().time + data.delta, data.command, data.flavor, data.videoLength)
                Session.starts.add(start)
                start.send(data.mySockets) { log(it) }
                
                vStart.isEnabled = false
                thread {
                    Thread.sleep(500)
                    runOnUiThread { vStart.isEnabled = true }
                }
            }
    
            viewGlobal = ViewDevice(this, vConfig)
            viewGlobal.vSettings.setOnClickListener {
                data.createDialog(this).setOnCancelListener { tryUpdateViewGlobal(data) }
            }
            tryUpdateViewGlobal(data)
    
            viewConfigMe = ViewDevice(this, vConfig)
            viewConfigMe.initView(configMe, "[host}")
            viewConfigMe.vSettings.setOnClickListener {
                configMe.dialog(this).setOnCancelListener {
                    viewConfigMe.updateView(configMe, "[host]")
                }
            }
    
            viewConfigClients = Array(configClients.size) { ViewDevice(this, vConfig) }
            for (i in configClients.indices)
                viewConfigClients[i].initView(configClients[i], "")

            if(!data.isInit) {
                data.isInit = true
                
                //  dialogs
                var iClient = 0
                var dialogClient: () -> Unit = {}
                dialogClient = {
                    if (iClient < configClients.size) {
                        val i = iClient
    
                        configClients[i].dialog(this).setOnCancelListener {
                            viewConfigClients[i].vSettings.setOnClickListener {
                                configClients[i].dialog(this).setOnCancelListener {
                                    configClients[i].send(data.mySockets[i]) { log(it) }
                                }
                            }
                            configClients[i].send(data.mySockets[i]) { log(it) }
    
                            iClient++
                            dialogClient()
                        }
                    }
                }
                data.createDialog(this).setOnCancelListener {
                    tryUpdateViewGlobal(data)

                    Session.currentConfig = configMe
                    configMe.dialog(this).setOnCancelListener {
                        viewConfigMe.updateView(configMe, "[host]")

                        dialogClient()
                    }
                }
            }
        }

        //  *****   CLIENT
        else {
            val data = ClientData.get!!
    
            addSocketListener(arrayOf(data.mySocket)) {
                println("socket received ${it.take(100)}")
                
                ConfigData.tryReceive(data.deviceName, it) { cfg ->
                    log("received config = $cfg")
                    Session.currentConfig = cfg
                }
                StartData.tryReceive(it) { cfg ->
                    log("received start = $cfg")
                    Session.starts.add(cfg)
                }
                Session.tryReceiveFeedback(it) { msg -> receiveFeedback(msg, false) }
            }
            addSocketCloseListener(arrayOf(data.mySocket))
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

                //  feedback
                val feedback = if(Session.starts.any { !hasLaunched.contains(it.id) }) {
                    val all = Session.starts.filter { !hasLaunched.contains(it.id) }
                    
                    if(all.isEmpty()) "will start at ${Globals.FORMAT_TIME.format(all.minOf { it.timeOfInit })}"
                    else "will start at ${Globals.FORMAT_TIME.format(all.minOf { it.timeOfInit })} (+${all.size} others)"
                } else "no start scheduled"
                runOnUiThread {
                    vFeedback.setString("$feedback\n\n${feedbacks.reversed().joinToString("\n")}")
                }
                
                //  host configs
                if(Session.state == SessionState.HOST) {
                    for(i in configClients.indices)
                        updateClient(i)
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
        
        for(x in socketReadListeners) x.first.removeOnRead(x.second)
        for(x in socketCloseListeners) x.first.removeOnClose(x.second)
    }
    
    private fun tryUpdateViewGlobal(data: HostData) {
        if(this::viewGlobal.isInitialized) {
            viewGlobal.vTitle.text = data.command
            viewGlobal.vDesc.setString("flavor:${data.flavor/1000}s length:${data.videoLength/1000}s Δ:+${data.delta/1000}s")
        }
    }
    
    private fun updateClient(i:Int) {
        viewConfigClients[i].updateView(configClients[i], if(MyTimer().time - HostData.get!!.lastUpdate[i] < 3000) "[connected]" else "[DISCONNECTED]")
    }

    private fun addSocketListener(sockets: Array<MySocket>, action:(String) -> Unit) {
        for (x in sockets) {
            socketReadListeners.add(Pair(x, action))
            x.addOnRead(action)
        }
    }
    
    private fun addSocketCloseListener(sockets: Array<MySocket>) {
        for (x in sockets) {
            val action:() -> Unit = { log("[${x.port}] SOCKET CLOSED") }
            socketCloseListeners.add(Pair(x, action))
            x.addOnClose(action)
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
            HostData.get!!.mySockets.forEach {
                Session.sendFeedback(it, string)
            }
        }
        else {
            Session.sendFeedback(ClientData.get!!.mySocket, string)
        }
    }

    companion object {
        const val TIME_START = 3_000L
    }
}