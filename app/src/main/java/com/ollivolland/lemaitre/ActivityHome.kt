package com.ollivolland.lemaitre

import Globals
import MySocket
import MyTimer
import MyWifiP2p
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import datas.ClientData
import datas.HostData
import datas.Session
import datas.StartData
import org.json.JSONObject
import setString
import java.util.*
import kotlin.concurrent.thread


class ActivityHome : AppCompatActivity() {
    private lateinit var vLogger: TextView
    private lateinit var vFeedback: TextView
    private lateinit var vImportant: TextView
    private lateinit var vPreview: ImageButton
    private val feedbacks:MutableList<String> = mutableListOf()
    private lateinit var viewGlobal:ViewDevice
    private lateinit var viewConfigMe:ViewDevice
    private lateinit var viewConfigClients:Array<ViewDevice>
    private val hasLaunched = mutableListOf<Long>()
    private val socketReadListeners = mutableListOf<Pair<MySocket, Int>>()
    private var isRunning = true
    private var isDialogsFinished = false

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        println("HOME CREATED")

        vLogger = findViewById(R.id.home_tLogger)
        vFeedback = findViewById(R.id.home_tFeedback)
        vImportant = findViewById(R.id.home_tImportant)
        vPreview = findViewById(R.id.home_bPreview)
        val vBlinker = findViewById<View>(R.id.home_vBlinker)
        val vConfig = findViewById<LinearLayout>(R.id.home_lConfig)
        val vButtons = findViewById<LinearLayout>(R.id.home_lButtons)
    
        vPreview.setOnClickListener {
            startActivity(Intent(this, ActivityPreview::class.java))
        }

        //  *****   HOST
        if(Session.isHost) {
            val data = HostData.get!!

            //  update
            addSocketListener(data.mySockets) { jo, tag ->
                Session.tryReceiveFeedback(jo, tag, this::showFeedback)
            }

            //  ui
            vButtons.visibility = View.VISIBLE
            val vStart = findViewById<ImageButton>(R.id.home_bStart)
            val vSchedule = findViewById<ImageButton>(R.id.home_bSchedule)
    
            vSchedule.setOnClickListener {
                val c = Calendar.getInstance()
                
                TimePickerDialog(this,
                    { _, hour, minute ->
                        val calendar = Calendar.getInstance()
                        calendar[Calendar.HOUR_OF_DAY] = hour
                        calendar[Calendar.MINUTE] = minute
                        calendar[Calendar.SECOND] = 0
                        
                        if(calendar.timeInMillis < System.currentTimeMillis())
                            Toast.makeText(this, "Time already passed", Toast.LENGTH_LONG).show()
                        else {
                            val start = StartData.create(calendar.timeInMillis, data.command, data.flavor, data.videoLength)
                            Session.addStart(start)
                            start.send(data.mySockets)
                        }
                    }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE) + 1, true)
                    .show()
            }

            vStart.setOnClickListener {
                val start = StartData.create(MyTimer.getTime() + data.delta, data.command, data.flavor, data.videoLength)
                Session.addStart(start)
                start.send(data.mySockets)
            }
    
            viewGlobal = ViewDevice(this, vConfig)
            viewGlobal.vSettings.setOnClickListener {
                data.createDialog(this).setOnCancelListener { updateViewGlobal(data) }
            }
            updateViewGlobal(data)
    
            viewConfigMe = ViewDevice(this, vConfig)
            viewConfigMe.initView(Session.config, "[host]")
            viewConfigMe.vSettings.setOnClickListener {
                Session.config.dialog(this) {
                    Session.config = it
                }
            }
    
            val configCopy = data.getClientConfigs()
            viewConfigClients = Array(configCopy.size) { ViewDevice(this, vConfig) }
            for (i in configCopy.indices) {
                viewConfigClients[i].initView(configCopy[i], "")
                viewConfigClients[i].vSettings.setOnClickListener {
                    data.getClientConfigs()[i].dialog(this) {
                        data.setClientConfig(i, it)
                    }
                }
            }

            if(!data.isInit) {
                data.isInit = true
                
                //  client dialogs
                var dialogClient: (Int) -> Unit = {}
                dialogClient = { i ->
                    if (i < configCopy.size) {
                        configCopy[i].dialog(this) {
                            data.setClientConfig(i, it)
                            dialogClient(i+1)
                        }
                    }
                    else isDialogsFinished = true
                }
                
                //  create dialogs
                data.createDialog(this).setOnCancelListener {
                    updateViewGlobal(data)
    
                    Session.config.dialog(this) {
                        Session.config = it

                        dialogClient(0)
                    }
                }
            }
        }

        //  *****   CLIENT
        else {
            val data = ClientData.get!!
    
            MyWifiP2p.get?.stopDiscovery()
            MyWifiP2p.get?.stopNSD()
            
            viewConfigMe = ViewDevice(this, vConfig)
            viewConfigMe.vTitle.text = data.deviceName
            viewConfigMe.vSettings.visibility = View.GONE
    
            addSocketListener(arrayOf(data.mySocket)) { jo, tag ->
                Session.tryReceiveFeedback(jo, tag) { msg -> showFeedback(msg) }
            }
            
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
                        Session.log("do start $x")
                    }

                //  feedback
                val all = Session.getStarts().filter { !hasLaunched.contains(it.id) }
                
                //  ui
                runOnUiThread {
                    vImportant.setString(
                    "${Globals.FORMAT_TIME.format(MyTimer.getTime())}\n\n"+ when {
                        all.isEmpty() -> "no start scheduled"
                        all.size < 5  -> "will start at\n${all.sortedBy { it.timeOfInit }.joinToString("\n") { Globals.FORMAT_TIME.format(it.timeOfInit) }}"
                        else          -> "will start at\n${all.sortedBy { it.timeOfInit }.take(4).joinToString("\n") { Globals.FORMAT_TIME.format(it.timeOfInit) }}\n + ${all.size-4} others"
                    })
                    synchronized(feedbacks) {
                        vFeedback.setString(feedbacks.reversed().joinToString("\n"))
                    }
                    vLogger.text = Session.getLogs().takeLast(20).reversed().joinToString("\n")
                    
                    //  host configs
                    if (Session.isHost) {
                        val data = HostData.get!!
                        //  clients
                        val configCopy = data.getClientConfigs()
                        for (i in configCopy.indices)
                            when {
                                MyTimer.getTime() - data.lastUpdate[i] > TIME_CONNECTION_TIMEOUT ->
                                    viewConfigClients[i].updateView(configCopy[i], "", "[DISCONNECTED]")
                                !data.isHasGpsTime[i] ->
                                    viewConfigClients[i].updateView(configCopy[i], "", "[NOGPS]")
                                else ->
                                    viewConfigClients[i].updateView(configCopy[i], "[connected]")
                            }
                    }
                    
                    updateOwnConfig()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        MyWifiP2p.get?.close()
        
        for(x in socketReadListeners) x.first.removeOnJson(x.second)
    }
    
    private fun updateViewGlobal(data: HostData) {
        viewGlobal.vTitle.text = data.command
        viewGlobal.vDesc.setString("flavor:${data.flavor/1000}s length:${data.videoLength/1000}s Δ:+${data.delta/1000}s")
    }
    
    private fun updateOwnConfig() {
        //  host config
        if (Session.isHost)
            when {
                !MyTimer.isHasGpsTime() -> viewConfigMe.updateView(Session.config, "", "[NOGPS]")
                else -> viewConfigMe.updateView(Session.config, "[host]")
            }
        
        //  client config
        if(Session.isClient)
            when {
                MyTimer.getTime() - ClientData.get!!.lastUpdate > TIME_CONNECTION_TIMEOUT ->
                    viewConfigMe.updateView(Session.config, "", "[DISCONNECTED]")
                !MyTimer.isHasGpsTime() ->
                    viewConfigMe.updateView(Session.config, "", "[NOGPS]")
                !ClientData.get!!.isHasHostGps ->
                    viewConfigMe.updateView(Session.config, "", "[HOST-NOGPS]")
                else ->
                    viewConfigMe.updateView(Session.config, "[connected]")
            }
        
        vPreview.visibility = if(Session.config.isCamera || Session.config.isGate) View.VISIBLE else View.GONE
    }

    private fun addSocketListener(sockets: Array<MySocket>, action:(jo:JSONObject, tag:String) -> Unit) {
        for (x in sockets)
            socketReadListeners.add(Pair(x, x.addOnJson(action)))
    }
    
    fun showFeedback(string: String) {
        synchronized(feedbacks) { feedbacks.add(string) }
    }
    
    fun broadcastFeedback(string: String) {
        if(Session.isHost) {
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