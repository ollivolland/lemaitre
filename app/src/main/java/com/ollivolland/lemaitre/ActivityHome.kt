package com.ollivolland.lemaitre

import Globals
import MySocket
import MyTimer
import MyWifiP2p
import MyWifiP2pActionListener
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import datas.ClientData
import datas.HostData
import datas.Session
import datas.StartData
import setString
import java.util.Calendar
import kotlin.concurrent.thread


class ActivityHome : AppCompatActivity() {
    private lateinit var vLogger: TextView
    private lateinit var vRecycler: RecyclerView
    private lateinit var vImportant: TextView
    private lateinit var vPreview: ImageButton
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
        vRecycler = findViewById(R.id.home_tFeedback)
        vImportant = findViewById(R.id.home_tImportant)
        vPreview = findViewById(R.id.home_bPreview)
        val vBlinker = findViewById<View>(R.id.home_vBlinker)
        val vConfig = findViewById<LinearLayout>(R.id.home_lConfig)
        val vButtons = findViewById<LinearLayout>(R.id.home_lButtons)
        val vDisconnect = findViewById<ImageButton>(R.id.home_disconnect)

        vDisconnect.visibility = if(Session.isHost) View.GONE else View.VISIBLE
    
        vPreview.setOnClickListener {
            startActivity(Intent(this, ActivityPreview::class.java))
        }
    
        MyWifiP2p.get?.stopDiscovery()
        MyWifiP2p.get?.myNSD?.stopNSD()

        vRecycler.layoutManager = LinearLayoutManager(this)
        vRecycler.setItemViewCacheSize(30)
        vRecycler.adapter = object: RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                return object: RecyclerView.ViewHolder(this@ActivityHome.layoutInflater.inflate(R.layout.view_start, vRecycler, false)) {}
            }

            override fun getItemCount(): Int = starts.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, i: Int) = starts[i].bindFeed(holder.itemView)
        }

        //  *****   HOST
        if(Session.isHost) {
            val data = HostData.get!!

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
                            start.send(data.clients.map { it.queue }.toTypedArray())
                        }
                    }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE) + 1, true)
                    .show()
            }

            vStart.setOnClickListener {
                val start = StartData.create(MyTimer.getTime() + data.delta, data.command, data.flavor, data.videoLength)
                Session.addStart(start)
                start.send(data.clients.map { it.queue }.toTypedArray())
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
            
            viewConfigMe = ViewDevice(this, vConfig)
            viewConfigMe.vTitle.text = data.deviceName
            viewConfigMe.vSettings.visibility = View.GONE
            
            isDialogsFinished = true

            vDisconnect.setOnClickListener {
                Toast.makeText(this, "disconnect", Toast.LENGTH_SHORT).show()
                MyWifiP2p.get!!.manager.removeGroup(MyWifiP2p.get!!.channel, MyWifiP2pActionListener("manualCancelConnect"))
            }
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
                    if(!ActivityStart.isBusy && !hasLaunched.contains(x.id) && x.time < MyTimer.getTime() + TIME_START)
                    {
                        hasLaunched.add(x.id)
                        ActivityStart.launch(this, x)
                        invalidateFeedback()
                        Session.log("do start $x")
                    }

                //  feedback
                val all = Session.getStarts().filter { !hasLaunched.contains(it.id) }
                
                //  ui
                runOnUiThread {
                    vImportant.setString(
                    "${Globals.FORMAT_TIME.format(MyTimer.getTime())}\n\n"+ when {
                        all.isEmpty() -> "no start scheduled"
                        all.size < 5  -> "will start at\n${all.sortedBy { it.time }.joinToString("\n") { Globals.FORMAT_TIME.format(it.time) }}"
                        else          -> "will start at\n${all.sortedBy { it.time }.take(4).joinToString("\n") { Globals.FORMAT_TIME.format(it.time) }}\n + ${all.size-4} others"
                    })
                    vLogger.text = Session.getLogs().takeLast(20).reversed().joinToString("\n")

                    if (isDataUpdated) {
                        isDataUpdated = false
                        vRecycler.adapter!!.notifyDataSetChanged()
                    }
                    
                    //  host configs
                    if (Session.isHost) {
                        val data = HostData.get!!
                        //  clients
                        val configCopy = data.getClientConfigs()
                        for (i in configCopy.indices)
                            when {
                                MyTimer.getTime() - data.clients[i].lastUpdate > TIME_CONNECTION_TIMEOUT ->
                                    viewConfigClients[i].updateView(configCopy[i], "", "[DISCONNECTED]")
                                !data.clients[i].isHasGpsTime ->
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
//        MyWifiP2p.get?.close()
        
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


    companion object {
        const val TIME_START = 3_000L
        const val TIME_CONNECTION_TIMEOUT = 3_000L
        private var starts: Array<StartData> = Session.getStarts()
        private var isDataUpdated = true


        fun invalidateFeedback() {
            starts = Session.getStarts()
            isDataUpdated = true
        }
    }
}