package com.ollivolland.lemaitre2

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val manager: WifiP2pManager by lazy { getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    }
    val formationDevices = mutableListOf<WifiP2pDevice>()
    private val clients = mutableListOf<String>()
    lateinit var mConnectionInfoListener: WifiP2pManager.ConnectionInfoListener
    private lateinit var mySocketFormation: MySocket

    //  todo    encapsulate

    private var isRunning = true
    private var isConnected = false
    private var isWantConnection = false
    var isFormationSocketReady = true
    private var isTriedConnecting = false
    var isWantUpdateFormationDevices = true

    private var hostState:HostState = HostState.NONE
    var portCommunication = -1
    lateinit var mySocketCommunication: MySocket
    private val myOwnerSocketsCommunication = mutableListOf<MySocket>()

    private val logs = mutableListOf<String>()
    lateinit var vLogger:TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //  permissions
        var permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permissions = permissions.filter { this.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toMutableList()

        if (permissions.isNotEmpty()) this.requestPermissions(permissions.toTypedArray(), 0)

        //  ui
        val vHost = findViewById<Button>(R.id.buttonHost)
        val vClient = findViewById<Button>(R.id.buttonClient)
        val vFin = findViewById<Button>(R.id.buttonFin)
        val vPing = findViewById<Button>(R.id.buttonPing)
        vLogger = findViewById(R.id.logger)

        vHost.setOnClickListener {
            hostState=HostState.HOST
            startRegistration()

            vClient.visibility = View.GONE
            vFin.visibility = View.VISIBLE
            vHost.isEnabled=false
            vClient.isEnabled=false
        }

        vClient.setOnClickListener {
            hostState=HostState.CLIENT
            discover()

            vHost.visibility = View.GONE
            vFin.visibility = View.GONE
            vHost.isEnabled=false
            vClient.isEnabled=false
        }

        vFin.setOnClickListener {
            vClient.visibility = View.GONE
            vHost.visibility = View.GONE

            vFin.isEnabled=false
            for (i in clients.indices) {
                myOwnerSocketsCommunication.add(MyClientThread(this, clients[i], PORT_COMMUNICATION + i).apply {
                    setOnRead { s -> toast(s) }
                })
            }
        }

        vPing.setOnClickListener {
            for (x in myOwnerSocketsCommunication) x.write("ping")
            if(this::mySocketCommunication.isInitialized) mySocketCommunication.write("ping")
        }

        //  setup
        channel = manager.initialize(this, mainLooper, null)
        receiver = WiFiDirectBroadcastReceiver(manager, channel!!, this)

        manager.cancelConnect(channel, MyWifiP2pActionListener("cancelConnect"))
        manager.removeGroup(channel, MyWifiP2pActionListener("removeGroup").setOnSuccess {
            isWantConnection = true
            log("wantconnection")
        }.setOnFailure {
            isWantConnection = true
            log("wantconnection")
        })
        manager.clearLocalServices(channel, MyWifiP2pActionListener("clearLocalServices"))
        manager.clearServiceRequests(channel, MyWifiP2pActionListener("clearServiceRequests"))
        manager.stopPeerDiscovery(channel, MyWifiP2pActionListener("stopPeerDiscovery"))

        //  todo    require WIFI & Location

        mConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
            if(!isWantConnection) return@ConnectionInfoListener

            log("connection: formed = ${info.groupFormed}, isOwner = ${info.isGroupOwner}")

            if(!isConnected && info.groupFormed) log("CONNECTED (${info.groupOwnerAddress.hostAddress})")
            if(isConnected && !info.groupFormed) log("DISCONNECTED")


            //  if connected check if another device wants to connect
            if(info.isGroupOwner) manager.discoverPeers(channel, MyWifiP2pActionListener("discoverPeers"))

            //  sockets
            var checkNeedAnotherSocket:() -> Unit ={}
            checkNeedAnotherSocket = {
                if (hostState == HostState.HOST && isFormationSocketReady && clients.count() < formationDevices.count()) {
                    isFormationSocketReady=false
                    mySocketFormation = MyServerThread(this, PORT_FORMATION).apply {
                        addOnConfigured {
                            this.write("useport=${PORT_COMMUNICATION + clients.count()}".encodeToByteArray())
                            clients.add(it.inetAddress.hostAddress!!)
                            log("client ${clients.last()}")
                        }
                        setOnRead { s ->
                            toast(s)

                            if (s == "close") this.close()
                        }
                        addOnClose {
                            isFormationSocketReady=true
                            checkNeedAnotherSocket()
                        }
                    }
                }
            }
            checkNeedAnotherSocket()
            if(hostState == HostState.CLIENT && !this::mySocketFormation.isInitialized) {
                mySocketFormation = MyClientThread(this, info.groupOwnerAddress.hostAddress!!, PORT_FORMATION).apply {
                    setOnRead { s ->
                        toast(s)

                        if(s.startsWith("useport=")) {
                            portCommunication = s.removePrefix("useport=").toInt()
                            log("useport=$portCommunication")

                            this.write("close".encodeToByteArray())
                            this.close()

                            mySocketCommunication = MyServerThread(this@MainActivity, portCommunication).apply {
                                setOnRead { s -> toast(s) }
                            }
                        }
                    }
                }
            }

            isConnected = info.groupFormed
        }
        manager.requestConnectionInfo(channel, mConnectionInfoListener)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning=false
    }

    private fun discover() {
        manager.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, resourceType -> log("servlistener: $instanceName") },
            { fullDomain, record, device ->
                log("DNS: $record $fullDomain")

                //  connect from host
                if(!isTriedConnecting) {
                    isTriedConnecting = true

                    val config = WifiP2pConfig().apply {
                        deviceAddress = device.deviceAddress
                        wps.setup = WpsInfo.PBC
                    }
                    manager.connect(channel!!, config, MyWifiP2pActionListener("connect"))
                }
            })

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        manager.discoverPeers(channel, MyWifiP2pActionListener("discoverPeers").setOnSuccess {
        manager.addServiceRequest(channel, serviceRequest, MyWifiP2pActionListener("addServiceRequest").setOnSuccess {
        manager.discoverServices(channel, MyWifiP2pActionListener("discoverServices").setOnSuccess {
        })
        })
        })
    }

    private fun startRegistration() {
        val txtMap: Map<String, String> = mapOf(
            "listenport" to "8888",
            "buddyname" to "Gaijinhunter${(Math.random() * 1000).toInt()}",
            "available" to "visible",
        )
        log("DNS add $txtMap")

        // Service information.  Pass it an instance name, service type
        // _protocol._transportlayer , and the map containing
        // information other devices will want once they connect to this one.
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("_test", "_presence._tcp", txtMap)

        //  create service
        manager.createGroup(channel, MyWifiP2pActionListener("createGroup").setOnSuccess {
        manager.addLocalService(channel, serviceInfo, MyWifiP2pActionListener("addLocalService").setOnSuccess {
        manager.discoverPeers(channel, MyWifiP2pActionListener("discoverPeers").setOnSuccess {
        })
        })
        })
    }

    fun toast(s:String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    fun log(string: String) {
        println(string)
        logs.add(string)
        runOnUiThread {
            vLogger.text = logs.takeLast(20).joinToString("\n")
        }
    }

    enum class HostState { NONE, HOST, CLIENT }

    companion object {
        const val PORT_FORMATION = 8888
        const val PORT_COMMUNICATION = 8900 //  +10
    }
}

class WiFiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action!!) {
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                println("WIFI_P2P_CONNECTION_CHANGED_ACTION")

                // Respond to new connection or disconnections
                manager.requestConnectionInfo(channel, activity.mConnectionInfoListener)
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                println("WIFI_P2P_PEERS_CHANGED_ACTION")

                // Respond to new connection or disconnections
                if(activity.isWantUpdateFormationDevices) {
                    manager.requestPeers(channel) { list ->
                        list.deviceList.forEach {
                            if (!activity.formationDevices.contains(it)) {
                                activity.formationDevices.add(it)
                                activity.log("formation found ${it.deviceName}")
                            }
                        }
                    }
                }
            }
        }
    }
}

class MyWifiP2pActionListener(private val message:String = ""):WifiP2pManager.ActionListener {
    private var mySuccess:() -> Unit = {}
    private var myFailure:() -> Unit = {}

    fun setOnSuccess(action:() -> Unit):MyWifiP2pActionListener {
        mySuccess=action
        return this
    }

    fun setOnFailure(action:() -> Unit):MyWifiP2pActionListener {
        myFailure=action
        return this
    }

    override fun onSuccess() {
        if(message.isNotEmpty()) println("$message success")
        mySuccess()
    }

    override fun onFailure(p0: Int) {
        if(message.isNotEmpty()) println("$message success")
        myFailure()
    }
}