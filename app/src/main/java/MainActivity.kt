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
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val manager: WifiP2pManager by lazy { getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    var channel: WifiP2pManager.Channel? = null
    private var receiver: MyWiFiDirectBroadcastReceiver? = null
    lateinit var mConnectionInfoListener: WifiP2pManager.ConnectionInfoListener

    val formationDevices = mutableListOf<WifiP2pDevice>()
    private val clients = mutableListOf<Client>()
    private lateinit var host: Host
    private lateinit var hostMac:String
    private lateinit var mySocketFormation: MySocket
    var checkNeedAnotherSocket:() -> Unit ={}

    //  todo    encapsulate

    private var isRunning = true
    private var isConnected = false
    private var isWantConnection = false
    var isFormationSocketReady = true
    private var isTriedConnecting = false
    var isWantUpdateFormationDevices = true

    var hostState:HostState = HostState.NONE
    lateinit var mySocketCommunication: MySocket
    private val myOwnerSocketsCommunication = mutableListOf<MySocket>()

    private val logs = mutableListOf<String>()
    lateinit var vLogger:TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //  permissions
        var permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
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
            vPing.visibility = View.VISIBLE

            vFin.isEnabled=false
            for (i in clients.indices) {
                myOwnerSocketsCommunication.add(MyClientThread(this, clients[i].ipWifiP2p, clients[i].port).apply {
                    setOnRead { s -> toast(s) }
                })
            }
            log("finished with ${clients.size} clients")
        }

        vPing.setOnClickListener {
            for (x in myOwnerSocketsCommunication) x.write("ping")
            if(this::mySocketCommunication.isInitialized) mySocketCommunication.write("ping")
        }

        //  setup
        log("sdk ${Build.VERSION.SDK_INT}")
        channel = manager.initialize(this, mainLooper, null)
        receiver = MyWiFiDirectBroadcastReceiver(manager, channel!!, this)

        //  todo    require WIFI & Location

        //  reset all wifiP2p connections, groups and services
        manager.stopPeerDiscovery(channel, MyWifiP2pActionListener("stopPeerDiscovery").setOnComplete {
            manager.clearServiceRequests(channel, MyWifiP2pActionListener("clearServiceRequests").setOnComplete {
                manager.clearLocalServices(channel, MyWifiP2pActionListener("clearLocalServices").setOnComplete {
                    manager.cancelConnect(channel, MyWifiP2pActionListener("cancelConnect").setOnComplete {
                        manager.removeGroup(channel, MyWifiP2pActionListener("removeGroup").setOnComplete {
                            isWantConnection = true
                            log("all connections reset")
                        })
                    })
                })
            })
        })

        checkNeedAnotherSocket = {
            if (hostState == HostState.HOST && isFormationSocketReady && clients.count() < formationDevices.count()) {
                isFormationSocketReady=false
                mySocketFormation = MyServerThread(this, PORT_FORMATION).apply {
                    addOnConfigured {
                        val client = Client(it.inetAddress.hostAddress!!, PORT_COMMUNICATION + clients.count())
                        clients.add(client)
                        log("client $client")

                        val jo = JSONObject().apply {
                            accumulate("useport", client.port)
                        }
                        this.write(jo.toString())
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

        mConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
            if(!isWantConnection) return@ConnectionInfoListener

            log("connection: formed = ${info.groupFormed}, isOwner = ${info.isGroupOwner}")

            if(!isConnected && info.groupFormed) log("CONNECTED (${info.groupOwnerAddress.hostAddress})")
            if(isConnected && !info.groupFormed) log("DISCONNECTED")


            //  if connected check if another device wants to connect
            if(info.isGroupOwner) manager.discoverPeers(channel, MyWifiP2pActionListener("discoverPeers"))

            //  sockets
            checkNeedAnotherSocket()
            if(hostState == HostState.CLIENT && !this::mySocketFormation.isInitialized) {
                mySocketFormation = MyClientThread(this, info.groupOwnerAddress.hostAddress!!, PORT_FORMATION).apply {
                    setOnRead { s ->
                        toast(s)

                        val jo = JSONObject(s)

                        if(jo.has("useport")) {
                            host = Host(hostMac, jo["useport"] as Int)
                            log("host = $host")

                            this.write("close")
                            this.close()

                            mySocketCommunication = MyServerThread(this@MainActivity, host.port).apply {
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
        receiver?.register()
    }

    override fun onPause() {
        super.onPause()
        receiver?.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning=false
    }

    private fun discover() {
        manager.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, resourceType -> log("servlistener: $instanceName $registrationType ${resourceType.deviceName} ${resourceType.deviceAddress}") },
            { fullDomain, record, device ->
                log("DNS: $record $fullDomain")

                //  connect from host
                if(!isTriedConnecting) {
                    isTriedConnecting = true

                    hostMac = device.deviceAddress
                    val config = WifiP2pConfig().apply {
                        deviceAddress = hostMac
                        wps.setup = WpsInfo.PBC
                    }
                    manager.connect(channel!!, config, MyWifiP2pActionListener("connect"))
                }
            })
        manager.setUpnpServiceResponseListener(channel) { list, device -> log("UPNP: $list $device") }
        manager.setServiceResponseListener(channel) { p0, p1, p2 -> log("service $p0 $p1 $p2") }

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        manager.addServiceRequest(channel, serviceRequest, MyWifiP2pActionListener("addServiceRequest").setOnSuccess {
            thread {
                while (!isTriedConnecting) {
                    manager.discoverServices(channel, MyWifiP2pActionListener("discoverServices"))  //  after service request
                    Thread.sleep(3000)
                }
            }
        })

//        val nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
//        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, object :DiscoveryListener{
//            override fun onStartDiscoveryFailed(p0: String?, p1: Int) {
//                log("onStartDiscoveryFailed")
//            }
//
//            override fun onStopDiscoveryFailed(p0: String?, p1: Int) {
//                log("onStopDiscoveryFailed")
//            }
//
//            override fun onDiscoveryStarted(p0: String?) {
//                log("onDiscoveryStarted")
//            }
//
//            override fun onDiscoveryStopped(p0: String?) {
//                log("onDiscoveryStopped")
//            }
//
//            override fun onServiceFound(p0: NsdServiceInfo?) {
//                log("onServiceFound $p0")
//            }
//
//            override fun onServiceLost(p0: NsdServiceInfo?) {
//                log("onServiceLost")
//            }
//
//        })
    }

    private fun startRegistration() {
        val txtMap: Map<String, String> = mapOf(
            "listenport" to "8887",
            "buddyname" to "Gaijinhunter${(Math.random() * 1000).toInt()}",
            "available" to "visible",
        )
        log("DNS added $txtMap")

        //  Pass it an instance name, service type (_protocol._transportlayer) , and the map containing information other devices will want once they connect to this one.
        val serviceInfo1 = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_NAME, SERVICE_TYPE, txtMap)

        //  create service
        manager.createGroup(channel, MyWifiP2pActionListener("createGroup").setOnSuccess {
            manager.addLocalService(channel, serviceInfo1, MyWifiP2pActionListener("addLocalService").setOnSuccess {
                manager.discoverPeers(channel, MyWifiP2pActionListener("discoverPeers"))
            })
        })

//        val servInfo = NsdServiceInfo().apply {
//            serviceName = SERVICE_NAME
//            serviceType = "_presence._tcp"
//            port = 8877
//        }
//        val nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
//        nsdManager.registerService(servInfo, NsdManager.PROTOCOL_DNS_SD, object :RegistrationListener{
//            override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) {
//                log("Not yet implemented")
//            }
//
//            override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) {
//                log("Not yet implemented")
//            }
//
//            override fun onServiceRegistered(p0: NsdServiceInfo?) {
//                log("onServiceRegistered")
//            }
//
//            override fun onServiceUnregistered(p0: NsdServiceInfo?) {
//                log("Not yet implemented")
//            }
//
//        })
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
        const val SERVICE_NAME = "_ollivollandlemaitre"
//        const val SERVICE_TYPE = "_http._tcp."
//        const val SERVICE_TYPE = "_services._dsn-sd._udp"
        const val SERVICE_TYPE = "_presence._tcp"
    }
}

data class Client(
    val ipWifiP2p:String,
    val port:Int
)

data class Host(
    val mac:String,
    val port:Int
)

class MyWiFiDirectBroadcastReceiver(
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

                                if(activity.hostState == MainActivity.HostState.HOST) {
                                    val config = WifiP2pConfig().apply {
                                        deviceAddress = it.deviceAddress
                                        wps.setup = WpsInfo.PBC
                                    }
                                    manager.connect(channel, config, MyWifiP2pActionListener("connect"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun register() {
        activity.registerReceiver(this, INTENT_FILTER)
    }

    fun unregister() {
        activity.unregisterReceiver(this)
    }

    companion object {
        private val INTENT_FILTER = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
    }
}

class MyWifiP2pActionListener(private val message:String = ""):ActionListener {
    private var mySuccess:() -> Unit = {}
    private var myFailure:() -> Unit = {}
    private var myComplete:() -> Unit = {}

    fun setOnSuccess(action:() -> Unit):MyWifiP2pActionListener {
        mySuccess=action
        return this
    }

    fun setOnFailure(action:() -> Unit):MyWifiP2pActionListener {
        myFailure=action
        return this
    }

    fun setOnComplete(action:() -> Unit):MyWifiP2pActionListener {
        myComplete=action
        return this
    }

    override fun onSuccess() {
        if(message.isNotEmpty()) println("$message success")
        mySuccess()
        myComplete()
    }

    override fun onFailure(p0: Int) {
        if(message.isNotEmpty()) println("$message success")
        myFailure()
        myComplete()
    }
}