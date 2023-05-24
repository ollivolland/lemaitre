package com.ollivolland.lemaitre2

import Globals
import MyTimer
import MyTimer.Companion.MIN_OBSERVATIONS
import ViewDevice
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import androidx.appcompat.app.AppCompatActivity
import datas.ClientData
import datas.ConfigData
import datas.HostData
import datas.StartData
import org.json.JSONObject
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
            addSocketListener(data.mySockets) { jo, tag ->
                Session.tryReceiveFeedback(jo, tag) { msg -> showFeedback(msg) }
            }
            addSocketCloseListener(data.mySockets)

            //  ui
            vButtons.visibility = View.VISIBLE
            val vStart = findViewById<ImageButton>(R.id.home_bStart)
            val vSchedule = findViewById<ImageButton>(R.id.home_bSchedule)
    
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
                        Session.addStart(start)
                        start.send(data.mySockets) { log(it) }
                        
                        a.dismiss()
                    }
                    .show()
            }

            vStart.setOnClickListener {
                val start = StartData.create(MyTimer.getTime() + data.delta, data.command, data.flavor, data.videoLength)
                Session.addStart(start)
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

                    Session.config = configMe
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
    
            viewConfigMe = ViewDevice(this, vConfig)
            viewConfigMe.vTitle.text = data.deviceName
            viewConfigMe.vSettings.visibility = View.GONE
    
            addSocketListener(arrayOf(data.mySocket)) { jo, tag ->
                Session.tryReceiveFeedback(jo, tag) { msg -> showFeedback(msg) }
            }
            addSocketCloseListener(arrayOf(data.mySocket))
            
            isDialogsFinished = true
        }

        //  blinker
        thread(name = "blinkerUiThread") {
            while (isRunning) {
                val should = if(MyTimer.getTime() % 1000 <= 100) View.VISIBLE else View.INVISIBLE
                if(vBlinker.visibility != should) runOnUiThread { vBlinker.visibility = should }

                Thread.sleep(10)
            }
        }

        //  check for starts
        thread(name = "homeUiThread") {
            while (isRunning) {
                Thread.sleep(50)
                if(!isDialogsFinished) continue
                
                //  start starts
                for (x in Session.getStarts())
                    if(!ActivityStart.isBusy && !hasLaunched.contains(x.id) && x.timeOfInit < MyTimer.getTime() + TIME_START)
                    {
                        hasLaunched.add(x.id)
                        ActivityStart.launch(this, x)
                        showFeedback("[${Globals.FORMAT_TIME.format(x.timeOfInit)}] started\n")
                        log("do $x")
                    }

                //  feedback
                val all = Session.getStarts().filter { !hasLaunched.contains(it.id) }
                var feedback = "${Globals.FORMAT_TIME.format(MyTimer.getTime())}"
                if(all.isEmpty()) feedback += "\nno start scheduled"
                else {
                    if(all.size == 1) "\nwill start at ${Globals.FORMAT_TIME.format(all.minOf { it.timeOfInit })}"
                    else "\nwill start at ${Globals.FORMAT_TIME.format(all.minOf { it.timeOfInit })} (+${all.size} others)"
                }
                if(GpsTime.numObservations < MIN_OBSERVATIONS) feedback += "\nDEVICE HAS NO GPS CONNECTION"
                else if(Session.state == SessionState.HOST && HostData.get!!.isHasGpsTime.any{ !it }) feedback += "\nCLIENT HAS NO GPS CONNECTION"
                
                runOnUiThread {
                    synchronized(feedbacks) {
                        vFeedback.setString("$feedback\n\n\n${feedbacks.reversed().joinToString("\n")}")
                    }
                    
                    //  host configs
                    synchronized(configClients) {
                        if (Session.state == SessionState.HOST) {
                            for (i in configClients.indices)
                                updateClient(i, HostData.get!!)
                        }
                    }
                    
                    //  client config
                    if(Session.state == SessionState.CLIENT)
                        viewConfigMe.updateView(Session.config,
                            when {
                                !ClientData.get!!.isHasHostGps || !MyTimer.isHasGpsTime() -> "[NOGPS]"
                                MyTimer.getTime() - ClientData.get!!.lastUpdate < TIME_CONNECTION_TIMEOUT -> "[connected]"
                                else -> "[DISCONNECTED]"
                            }
                        )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        GpsTime.unregister()
        wakeLock.release()
        
        for(x in socketReadListeners) x.first.removeOnJson(x.second)
        for(x in socketCloseListeners) x.first.removeOnClose(x.second)
    }
    
    private fun updateViewGlobal(data: HostData) {
        viewGlobal.vTitle.text = data.command
        viewGlobal.vDesc.setString("flavor:${data.flavor/1000}s length:${data.videoLength/1000}s Δ:+${data.delta/1000}s")
    }
    
    private fun updateClient(i:Int, data: HostData) {
        viewConfigClients[i].updateView(configClients[i], when {
                !data.isHasGpsTime[i] || !MyTimer.isHasGpsTime()                 -> "[NOGPS]"
                MyTimer.getTime() - data.lastUpdate[i] < TIME_CONNECTION_TIMEOUT -> "[connected]"
                else -> "[DISCONNECTED]"
            }
        )
    }

    private fun addSocketListener(sockets: Array<MySocket>, action:(jo:JSONObject, tag:String) -> Unit) {
        for (x in sockets)
            socketReadListeners.add(Pair(x, x.addOnJson(action)))
    }
    
    private fun addSocketCloseListener(sockets: Array<MySocket>) {
        for (x in sockets)
            socketCloseListeners.add(Pair(x, x.addOnClose { log("[${x.port}] SOCKET CLOSED") }))
    }

    private fun log(string: String) {
        println(string)
        logs.add(string)
        runOnUiThread {
            vLogger.text = logs.takeLast(20).reversed().joinToString("\n")
        }
    }
    
    fun showFeedback(string: String) {
        synchronized(feedbacks) { feedbacks.add(string) }
    }
    
    fun broadcastFeedback(string: String) {
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
        const val TIME_CONNECTION_TIMEOUT = 5_000L
    }
}