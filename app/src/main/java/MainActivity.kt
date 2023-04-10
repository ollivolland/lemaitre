package com.ollivolland.lemaitre2

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var isWantDiscoverServices = false
    private val manager: WifiP2pManager by lazy { getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    private val managerWifi: WifiManager by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    }
    val formationDevices = mutableListOf<WifiP2pDevice>()
    private val clients = mutableListOf<String>()
    lateinit var mConnectionInfoListener: WifiP2pManager.ConnectionInfoListener
    lateinit var mySocketFormation: MySocket

    private var isRunning = true
    private var isConnected = false
    var isFormationSocketReady = true
    var isTriedConnecting = false
    var isWantUpdateFormationDevices = false

    private var wifiP2pState:WifiP2pState = WifiP2pState.NONE
    var portCommunication = -1
    lateinit var mySocketCommunication: MySocket
    val myOwnerSocketsCommunication = mutableListOf<MySocket>()

    private val logs = mutableListOf<String>()
    lateinit var vLogger:TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //  permissions
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ).filter { this.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

        if (permissions.isNotEmpty()) this.requestPermissions(permissions, 0)

        //  ui
        val vHost = findViewById<Button>(R.id.buttonHost)
        val vClient = findViewById<Button>(R.id.buttonClient)
        val vPing = findViewById<Button>(R.id.buttonPing)
        vLogger = findViewById(R.id.logger)

        vHost.setOnClickListener {
            startRegistration()
            vHost.isEnabled=false
            vClient.isEnabled=false
        }

        vClient.setOnClickListener {
            discover()
            vHost.isEnabled=false
            vClient.isEnabled=false
        }

        vPing.setOnClickListener {
//            for(x in formationSockets)
//                x.write("ping".encodeToByteArray())
        }

        //  setup
        channel = manager.initialize(this, mainLooper, null)
        receiver = WiFiDirectBroadcastReceiver(manager, channel!!, this)

        manager.cancelConnect(channel, MyWifiP2pActionListener("cancelConnect"))
        manager.removeGroup(channel, MyWifiP2pActionListener("removeGroup"))
        manager.clearLocalServices(channel, MyWifiP2pActionListener("clearLocalServices"))
        manager.clearServiceRequests(channel, MyWifiP2pActionListener("clearServiceRequests"))
        manager.stopPeerDiscovery(channel, MyWifiP2pActionListener("stopPeerDiscovery"))

        mConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
            log("connection: formed = ${info.groupFormed}, isOwner = ${info.isGroupOwner}")

            if(!isConnected && info.groupFormed) log("CONNECTED (${info.groupOwnerAddress.hostAddress})")
            if(isConnected && !info.groupFormed) log("DISCONNECTED")

            if(wifiP2pState == WifiP2pState.NONE && info.groupFormed) wifiP2pState = if(info.isGroupOwner) WifiP2pState.OWNER else WifiP2pState.CLIENT

            //  if group formed, we don't need to discover services
            if(info.groupFormed) isWantDiscoverServices = false

            //  sockets
            var checkNeedAnotherSocket:() -> Unit ={}
            checkNeedAnotherSocket = {
                if (wifiP2pState == WifiP2pState.OWNER && isFormationSocketReady && clients.count() < formationDevices.count()) {
                    isFormationSocketReady=false
                    mySocketFormation = MyServerThread(this, PORT_FORMATION).apply {
                        addOnConfigured {
                            this.write("useport=${PORT_COMMUNICATION + clients.count()}".encodeToByteArray())
                            clients.add(it.inetAddress.hostAddress!!)
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
            if(wifiP2pState == WifiP2pState.CLIENT && isFormationSocketReady) {
                isFormationSocketReady=false
                MyClientThread(this, info.groupOwnerAddress.hostAddress!!, PORT_FORMATION).apply {
                    setOnRead { s ->
                        toast(s)

                        if(s.startsWith("useport=")) {
                            portCommunication = s.removePrefix("useport=").toInt()

                            log("useport=$portCommunication")
                            this.write("close".encodeToByteArray())
                            this.close()
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
        manager.discoverPeers(channel, MyWifiP2pActionListener("discoverPeers").setOnSuccess {
            manager.addLocalService(channel, serviceInfo, MyWifiP2pActionListener("addLocalService").setOnSuccess {
                isWantUpdateFormationDevices=true
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

    enum class WifiP2pState { NONE, OWNER, CLIENT }

    companion object {
        const val PORT_FORMATION = 8888
        const val PORT_COMMUNICATION = 8900
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
                if(activity.isWantUpdateFormationDevices)
                    manager.requestPeers(channel) { list ->
                        list.deviceList.forEach {
                            if(!activity.formationDevices.contains(it)) {
                                activity.formationDevices.add(it)
                                activity.log("formation found ${it.deviceName}")
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

abstract class MySocket(val mainActivity: MainActivity, val port: Int, val type:String) {
    protected val myOnSocketListener = mutableListOf<(Socket) -> Unit>()
    protected val myOnCloseListeners = mutableListOf<() -> Unit>()
    protected var myOnReadListener:(ByteArray, Int) -> Unit = { _, _->}
    protected lateinit var mOutputStream: OutputStream
    protected lateinit var mInputStream: InputStream
    protected var isRunning = true

    init {
        mainActivity.log("[$port] $type creating")
        myOnSocketListener.add { socket -> mainActivity.log("[$port] $type created ${socket.localAddress.hostAddress} => ${socket.inetAddress.hostAddress}") }
        myOnCloseListeners.add { mainActivity.log("[$port] $type closed") }
    }

    fun write(s:String) = write(s.encodeToByteArray())
    fun write(byteArray: ByteArray) {
        if(this::mOutputStream.isInitialized) mOutputStream.write(byteArray)
        else myOnSocketListener.add { mOutputStream.write(byteArray) }
    }

    fun close() {
        mInputStream.close()
        isRunning=false
    }

    fun addOnConfigured(action:(Socket) -> Unit) = myOnSocketListener.add(action)
    fun addOnClose(action:() -> Unit) = myOnCloseListeners.add(action)

    fun setOnRead(action:(ByteArray, Int) -> Unit) {
        myOnReadListener = action
    }
    fun setOnRead(action:(String) -> Unit) {
        myOnReadListener = { buffer, len->action(String(buffer,0,len)) }
    }
}

class MyClientThread(mainActivity: MainActivity, private val inetAddress: String, port: Int): MySocket(mainActivity, port, "client") {
    private val socket: Socket = Socket()

    init {
        thread {
            socket.connect(InetSocketAddress(inetAddress, port), 3000)
            mInputStream = socket.getInputStream()
            mOutputStream = socket.getOutputStream()
            for (x in myOnSocketListener) x(socket)

            val buffer = ByteArray(1024)
            var length:Int
            while(isRunning) {
                try {
                    length = mInputStream.read(buffer)
                    if (length > 0) myOnReadListener(buffer, length)
                } catch (e:Exception) {
                    e.printStackTrace()
                    isRunning = false
                }
            }

            socket.close()
            for (x in myOnCloseListeners) x()
        }
    }
}

class MyServerThread(mainActivity: MainActivity, port:Int): MySocket(mainActivity, port, "server") {
    private var serverSocket:ServerSocket = ServerSocket(port)
    lateinit var socket: Socket

    init {
        thread {
            socket  = serverSocket.accept()
            mInputStream = socket.getInputStream()
            mOutputStream = socket.getOutputStream()
            for (x in myOnSocketListener) x(socket)


            val buffer = ByteArray(1024)
            var length:Int
            while(isRunning) {
                try {
                    length = mInputStream.read(buffer)
                    if (length > 0) myOnReadListener(buffer, length)
                } catch (e:Exception) {
                    e.printStackTrace()
                    isRunning = false
                }
            }

            socket.close()
            serverSocket.close()
            for (x in myOnCloseListeners) x()
        }
    }
}