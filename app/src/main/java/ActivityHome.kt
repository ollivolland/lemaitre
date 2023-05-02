package com.ollivolland.lemaitre2

import Globals
import MyTimer
import MyTimer.Companion.MIN_OBSERVATIONS
import ViewDevice
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
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
    private val wakeLock = MyWakeLock()
    private lateinit var viewGlobal:ViewDevice
    private lateinit var viewConfigMe:ViewDevice
    private lateinit var viewConfigClients:Array<ViewDevice>
    private lateinit var configClients:Array<ConfigData>
    private val hasLaunched = mutableListOf<Long>()
    private val socketReadListeners = mutableListOf<Pair<MySocket, Int>>()
    private val socketCloseListeners = mutableListOf<Pair<MySocket, Int>>()
    private var isRunning = true
    private var isDialogsFinished = false

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
    
        //  misc
        GpsTime.register(this)
        wakeLock.acquire(this)

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
            val vSchedule = findViewById<Button>(R.id.home_bSchedule)
    
            vSchedule.setOnClickListener {
                val viewDialog = layoutInflater.inflate(R.layout.view_schedule, null)
                val vTimePicker = viewDialog.findViewById<TimePicker>(R.id.schedule_vTimePicker)
        
                vTimePicker.setIs24HourView(true)
                AlertDialog.Builder(this)
                    .setView(viewDialog)
                    .setPositiveButton("ok") { a, _ ->
                        val calendar = Calendar.getInstance()
                        calendar.time = Date()
                        calendar[Calendar.HOUR_OF_DAY] = vTimePicker.hour
                        calendar[Calendar.MINUTE] = vTimePicker.minute
                        calendar[Calendar.SECOND] = 0
    
                        val start = StartData.create(calendar.timeInMillis, data.command, data.flavor, data.videoLength)
                        Session.starts.add(start)
                        start.send(data.mySockets) { log(it) }
                        
                        a.dismiss()
                    }
                    .show()
            }

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
                data.createDialog(this).setOnCancelListener { updateViewGlobal(data) }
            }
            updateViewGlobal(data)
    
            viewConfigMe = ViewDevice(this, vConfig)
            viewConfigMe.initView(configMe, "[host]")
            viewConfigMe.vSettings.setOnClickListener {
                configMe.dialog(this).setOnCancelListener {
                    viewConfigMe.updateView(configMe, "[host]")
                }
            }
    
            viewConfigClients = Array(configClients.size) { ViewDevice(this, vConfig) }
            for (i in configClients.indices) {
                viewConfigClients[i].initView(configClients[i], "")
                viewConfigClients[i].vSettings.setOnClickListener {
                    configClients[i].dialog(this).setOnCancelListener {
                        configClients[i].send(data.mySockets[i]) { log(it) }
                    }
                }
            }

            if(!data.isInit) {
                data.isInit = true
                
                //  client dialogs
                var dialogClient: (Int) -> Unit = {}
                dialogClient = { i ->
                    if (i < configClients.size) {
                        configClients[i].dialog(this).setOnCancelListener {
                            configClients[i].send(data.mySockets[i]) { log(it) }
                            dialogClient(i+1)
                        }
                    }
                    else isDialogsFinished = true
                }
                
                //  create dialogs
                data.createDialog(this).setOnCancelListener {
                    updateViewGlobal(data)

                    Session.currentConfig = configMe
                    configMe.dialog(this).setOnCancelListener {
                        viewConfigMe.updateView(configMe, "[host]")

                        dialogClient(0)
                    }
                }
            }
        }

        //  *****   CLIENT
        else {
            val data = ClientData.get!!
    
            addSocketListener(arrayOf(data.mySocket)) {
                println("socket received ${it.take(100)}")
                
                Session.tryReceiveFeedback(it) { msg -> receiveFeedback(msg, false) }
            }
            addSocketCloseListener(arrayOf(data.mySocket))
            
            isDialogsFinished = true
        }

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
                Thread.sleep(50)
                if(!isDialogsFinished) continue
                
                //  start starts
                for (x in Session.starts)
                    if(!ActivityStart.isBusy && !hasLaunched.contains(x.id) && x.timeOfInit < MyTimer().time + TIME_START)
                    {
                        hasLaunched.add(x.id)
                        ActivityStart.launch(this, x)
                        receiveFeedback("[${Globals.FORMAT_TIME.format(x.timeOfInit)}] started\n", false)
                        log("do $x")
                    }

                //  feedback
                val all = Session.starts.filter { !hasLaunched.contains(it.id) }
                var feedback = if(all.isEmpty()) "no start scheduled"
                else {
                    if(all.size == 1) "will start at ${Globals.FORMAT_TIME.format(all.minOf { it.timeOfInit })}"
                    else "will start at ${Globals.FORMAT_TIME.format(all.minOf { it.timeOfInit })} (+${all.size} others)"
                }
                if(GpsTime.numObservations < MIN_OBSERVATIONS) feedback = "NO GPS CONNECTION\n$feedback"
                
                runOnUiThread {
                    vFeedback.setString("$feedback\n\n\n${feedbacks.reversed().joinToString("\n")}")
                }
                
                //  host configs
                if(Session.state == SessionState.HOST) {
                    for(i in configClients.indices)
                        updateClient(i)
                }
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
    
    private fun updateViewGlobal(data: HostData) {
        viewGlobal.vTitle.text = data.command
        viewGlobal.vDesc.setString("flavor:${data.flavor/1000}s length:${data.videoLength/1000}s Δ:+${data.delta/1000}s")
    }
    
    private fun updateClient(i:Int) {
        viewConfigClients[i].updateView(configClients[i], if(MyTimer().time - HostData.get!!.lastUpdate[i] < TIME_CONNECTION_TIMEOUT) "[connected]" else "[DISCONNECTED]")
    }

    private fun addSocketListener(sockets: Array<MySocket>, action:(String) -> Unit) {
        for (x in sockets)
            socketReadListeners.add(Pair(x, x.addOnReadIndex(action)))
    }
    
    private fun addSocketCloseListener(sockets: Array<MySocket>) {
        for (x in sockets)
            socketCloseListeners.add(Pair(x, x.addOnCloseIndex { log("[${x.port}] SOCKET CLOSED") }))
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
        const val TIME_CONNECTION_TIMEOUT = 3_000L
    }
}