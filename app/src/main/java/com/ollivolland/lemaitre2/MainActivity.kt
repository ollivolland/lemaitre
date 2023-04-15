package com.ollivolland.lemaitre2

import ClientData
import HostData
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    var thisDeviceName: String = ""
    private val manager: WifiP2pManager by lazy { getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    private val wifiManager: WifiManager by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val locationManager: LocationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    var channel: WifiP2pManager.Channel? = null
    private var receiver: MyWiFiDirectBroadcastReceiver? = null
    lateinit var mConnectionInfoListener: WifiP2pManager.ConnectionInfoListener

    val formationDevices = mutableListOf<WifiP2pDevice>()
    private val clients = mutableListOf<Client>()
    private lateinit var hostMac:String
    private lateinit var mySocketFormation: MySocket
    var checkNeedAnotherSocket:() -> Unit ={}

    //  todo    config communication
    //  todo    start communication

    //  todo    cut raw mp3s
    //  todo    video
    //  todo    video timestamp
    //  todo    persistent socket

    private var isRunning = true
    private var isConnected = false
    private var isWantConnection = false
    var isFormationSocketReady = true
    private var isTriedConnecting = false
    var isWantUpdateFormationDevices = true

    private val logs = mutableListOf<String>()
    lateinit var vLogger:TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //  permissions
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        val toGrant = permissions.filter { s -> checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

        if (toGrant.isNotEmpty()) requestPermissions(toGrant, 1)

        //  ui
        val vHost = findViewById<Button>(R.id.buttonHost)
        val vClient = findViewById<Button>(R.id.buttonClient)
        val vFin = findViewById<Button>(R.id.buttonFin)
        vLogger = findViewById(R.id.logger)

        vHost.setOnClickListener {
            Session.state= SessionState.HOST
            startRegistration()

            vClient.visibility = View.GONE
            vFin.visibility = View.VISIBLE
            vHost.isEnabled=false
            vClient.isEnabled=false
        }

        vClient.setOnClickListener {
            Session.state= SessionState.CLIENT
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
            HostData.set(thisDeviceName, clients)
            log("finished with ${clients.size} clients")

            startActivity(Intent(this, ActivityHome::class.java))
            finish()
        }

        //  setup
        log("sdk ${Build.VERSION.SDK_INT}")
        channel = manager.initialize(this, mainLooper, null)
        receiver = MyWiFiDirectBroadcastReceiver(manager, channel!!, this)

        //  enable wifi
        if(!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "WIFI is off", Toast.LENGTH_SHORT).show()

            if (Build.VERSION.SDK_INT <= 28) wifiManager.isWifiEnabled = true
            else startActivityForResult(Intent(Settings.Panel.ACTION_WIFI), 1)
        }

        //  enable location
        if(!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            startActivityForResult(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), 1)
        }

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
            if (Session.state ==  SessionState.HOST && isFormationSocketReady && clients.count() < formationDevices.count()) {
                isFormationSocketReady=false
                val port = PORT_COMMUNICATION + clients.count()
                var ip = ""
                mySocketFormation = MyServerThread(PORT_FORMATION).apply {
                    addOnConfigured {
                        ip = it.inetAddress.hostAddress!!

                        this.write(JSONObject().apply {
                            accumulate("useport", port)
                        }.toString())
                    }
                    addOnRead { s ->
                        toast(s)
                        val jo = JSONObject(s)

                        if (jo.has("name")) {
                            val client = Client(ip, port, jo["name"] as String)
                            clients.add(client)
                            log("client $client")

                            this.close()
                        }
                    }
                    addOnClose {
                        isFormationSocketReady=true
                        checkNeedAnotherSocket()
                    }
                    log(this@MainActivity)
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
            if(Session.state ==  SessionState.CLIENT && !this::mySocketFormation.isInitialized) {
                mySocketFormation = MyClientThread(info.groupOwnerAddress.hostAddress!!, PORT_FORMATION).apply {
                    addOnRead { s ->
                        toast(s)
                        val jo = JSONObject(s)

                        if(jo.has("useport")) {
                            this.write(JSONObject().apply {
                                accumulate("name", thisDeviceName)
                            }.toString())
                            this.close()

                            ClientData.set(jo["useport"] as Int, hostMac, this@MainActivity)
                            log("host = ${ClientData.get!!.port}")
                        }
                    }
                    log(this@MainActivity)
                }
            }

            isConnected = info.groupFormed
        }
        manager.requestConnectionInfo(channel, mConnectionInfoListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false

        manager.clearLocalServices(channel, MyWifiP2pActionListener("clearLocalServices"))
        manager.clearServiceRequests(channel, MyWifiP2pActionListener("clearServiceRequests"))
        manager.stopPeerDiscovery(channel, MyWifiP2pActionListener("stopPeerDiscovery"))
    }

    override fun onResume() {
        super.onResume()
        receiver?.register()
    }

    override fun onPause() {
        super.onPause()
        receiver?.unregister()
    }

    private fun buildAlertMessageNoGps() {
        startActivityForResult(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), 1)

//        val locationRequest = LocationRequest.create().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY).setInterval(100).setFastestInterval(100)
//        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest).setAlwaysShow(true)
//        val client: SettingsClient = LocationServices.getSettingsClient(this)
//
//        client.checkLocationSettings(builder.build())
//            .addOnSuccessListener {
//                Toast.makeText(this, "Gps is enabled", Toast.LENGTH_LONG).show()
//            }
//            .addOnFailureListener { exception ->
//                if (exception is ResolvableApiException) {
//                    try {
//                        exception.startResolutionForResult(this, 1000)
//                    } catch (sendEx: SendIntentException) {
//                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
//                    }
//                } else startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
//            }
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
                thread {
                     while (isRunning) {
                         manager.discoverPeers(channel, MyWifiP2pActionListener("discoverPeers"))
                         Thread.sleep(3000)
                    }
                }
            })
        })
    }

    fun toast(s:String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    fun log(string: String) {
        println(string)
        logs.add(string)
        runOnUiThread {
            vLogger.text = logs.takeLast(20).reversed().joinToString("\n")
        }
    }

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
    val port:Int,
    val name:String,
)

class MyWiFiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action!!) {
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                println("WIFI_P2P_THIS_DEVICE_CHANGED_ACTION")

                val device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) as WifiP2pDevice?
                if(device != null && activity.thisDeviceName.isEmpty()) {
                    activity.thisDeviceName = device.deviceName
                    activity.log("deviceName = ${activity.thisDeviceName}")
                }
            }
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

                                if(Session.state ==  SessionState.HOST) {
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
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
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