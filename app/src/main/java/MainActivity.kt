package com.ollivolland.lemaitre2

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
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
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val manager: WifiP2pManager by lazy { getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    private val managerWifi: WifiManager by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val intentFilter = IntentFilter().apply {
//        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
//        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }
    val connected = mutableListOf<WifiP2pDevice>()
    private lateinit var mysocket:WiFiDirectBroadcastReceiver.MySocket
    lateinit var mConnectionInfoListener: WifiP2pManager.ConnectionInfoListener

    var isRunning = true
    var isWantDiscoverPeers = false
    var isShouldBeConnected = false
    var isConnected = false
    var isSocketCreated = false
    var isWantPeerUpdate = false

    val logs = mutableListOf<String>()
    lateinit var vLogger:TextView

    val thread = thread {
        while (isRunning) {
            Thread.sleep(1_000)

            if(isWantDiscoverPeers) {
                manager.discoverPeers(
                    channel,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            println("discoverPeers success")

                            manager.requestPeers(channel) {
                                it.deviceList.forEach {
                                    if(!connected.contains(it)) {
                                        log("found device ${it.deviceName} ${it.deviceAddress}")
                                        connected.add(it)

                                        //  connect from host
                                        val config = WifiP2pConfig().apply {
                                            deviceAddress = it.deviceAddress
                                            wps.setup = WpsInfo.PBC
                                        }
                                        manager.connect(channel!!, config, object : WifiP2pManager.ActionListener {
                                            override fun onSuccess() {
                                                println("connection success")
                                            }

                                            override fun onFailure(reason: Int) {
                                                println("connection failure $reason")
                                            }
                                        })
                                    }
                                }
                            }
                        }

                        override fun onFailure(error: Int) {}
                    })
            }
        }
    }

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
            setHost()
            vHost.isEnabled=false
            vClient.isEnabled=false
            isShouldBeConnected=true
            isWantPeerUpdate=true
        }

        vClient.setOnClickListener {
            discover()
            vHost.isEnabled=false
            vClient.isEnabled=false
            isShouldBeConnected=true
        }

        vPing.setOnClickListener {
            if(this::mysocket.isInitialized)
                mysocket.write("ping".encodeToByteArray())
        }

        //  setup
        val ipAddress = managerWifi.connectionInfo.ipAddress
        log("${ipAddress and 0xff}.${(ipAddress shr 8) and 0xff}.${(ipAddress shr 16) and 0xff}.${(ipAddress shr 24) and 0xff}")
        channel = manager.initialize(this, mainLooper, null)
        receiver = WiFiDirectBroadcastReceiver(manager, channel!!, this)

        mConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
            log("connection: formed = ${info.groupFormed}, isOwner = ${info.isGroupOwner}")

            if(!isConnected && info.groupFormed) {
                log("CONNECTED (${info.groupOwnerAddress.hostAddress})")
            }
            if(isConnected && !info.groupFormed) {
                log("DISCONNECTED")
            }

            if(info.groupFormed && !isShouldBeConnected) {
                manager.requestGroupInfo(channel) { group ->
                    if (group != null) {
                        manager.removeGroup(
                            channel,
                            object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    println("cancelConnect success")
                                }

                                override fun onFailure(reason: Int) {
                                    println("cancelConnect failure $reason")
                                }
                            })
                    }
                }
            }

            if(isShouldBeConnected && info.groupFormed && !isSocketCreated) {
                mysocket =
                    if(info.isGroupOwner) WiFiDirectBroadcastReceiver.ServerThread(this)
                    else WiFiDirectBroadcastReceiver.ClientThread(this, info.groupOwnerAddress.hostAddress!!)
                isSocketCreated=true
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

    fun setHost() {
        startRegistration()
    }

    fun discover() {
        discoverNSD()
        WiFiDirectBroadcastReceiver.ServerThread(this)
    }

    private fun discoverNSD() {
        manager.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, resourceType ->
                println("servListener $instanceName")
                // Update the device name with the human-friendly version from
                // the DnsTxtRecord, assuming one arrived.
//                resourceType.deviceName = buddies[resourceType.deviceAddress] ?: resourceType.deviceName
            },
            { fullDomain, record, device ->
                println("DnsSdTxtRecord available -$record")
                println("device ${device.deviceAddress} ${device.deviceName}")
//                record["buddyname"]?.also {
//                    buddies[device.deviceAddress] = it
//                }
        })

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()

        manager.removeServiceRequest(channel, serviceRequest, object: WifiP2pManager.ActionListener {
            override fun onSuccess() {
                println("removeServiceRequest success")

                manager.addServiceRequest(
                    channel,
                    serviceRequest,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            println("addServiceRequest success")

                            manager.discoverServices(
                                channel,
                                object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {
                                        println("discoverServices success")
                                    }

                                    override fun onFailure(code: Int) {
                                        println("discoverServices failure ${
                                            when (code) {
                                                WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct isn't supported on this device."
                                                WifiP2pManager.ERROR -> "ERROR"
                                                WifiP2pManager.BUSY -> "BUSY"
                                                else -> ""
                                            }
                                        }")
                                    }
                                }
                            )
                        }

                        override fun onFailure(code: Int) {
                            println("addServiceRequest failure ${
                                when (code) {
                                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct isn't supported on this device."
                                    WifiP2pManager.ERROR -> "ERROR"
                                    WifiP2pManager.BUSY -> "BUSY"
                                    else -> ""
                                }
                            }")
                        }
                    }
                )
            }

            override fun onFailure(p0: Int) {
                println("removeServiceRequest failure $p0")
            }
        })
    }

    private fun startRegistration() {
        val txtMap: Map<String, String> = mapOf(
            "listenport" to "49000",
            "buddyname" to "John Doe${(Math.random() * 1000).toInt()}",
            "available" to "visible"
        )

        // Service information.  Pass it an instance name, service type
        // _protocol._transportlayer , and the map containing
        // information other devices will want once they connect to this one.
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("_test", "_presence._tcp", txtMap)

        //  create service
        manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            // service broadcasting started
                            isWantDiscoverPeers=true
                        }

                        override fun onFailure(error: Int) {
                            // react to failure of adding the local service
                        }
                    })
            }

            override fun onFailure(error: Int) {
                // react to failure of clearing the local services
            }
        })
    }

    fun onRead(buffer: ByteArray, len:Int) {
        val s = String(buffer, 0, len)
        runOnUiThread {
            Toast.makeText(this, s, Toast.LENGTH_LONG).show()
        }
    }

    fun log(string: String) {
        println(string)
        logs.add(string)
        runOnUiThread {
            vLogger.text = logs.takeLast(20).joinToString("\n")
        }
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

                if(activity.isWantPeerUpdate) {
                    manager.requestPeers(channel) { list ->
                        activity.connected.filter { !list.deviceList.contains(it) }
                            .forEach {
                                activity.log("deice lost ${it.deviceName}")
                            }
                    }
                }
            }
        }
    }

    interface MySocket {
        val onReadListener:(buffer:ByteArray, len:Int) -> Unit
        fun write(byteArray: ByteArray)
    }

    class ClientThread(private val mainActivity: MainActivity, private val inetAddress: String): MySocket {
        private lateinit var mOutputStream: OutputStream
        private lateinit var mInputStream: InputStream
        private val socket:Socket = Socket()
        override val onReadListener: (buffer: ByteArray, len: Int) -> Unit = { buffer, len -> mainActivity.onRead(buffer, len) }

        init {
            thread {
                mainActivity.log("socket client creating ${inetAddress}")
                socket.connect(InetSocketAddress(inetAddress, 8888), 3000)
                mInputStream = socket.getInputStream()
                mOutputStream = socket.getOutputStream()
                mainActivity.log("socket client created")

                val executor = Executors.newSingleThreadExecutor()

                executor.execute {
                    val buffer = ByteArray(1024)
                    var bytes:Int

                    while(mainActivity.isRunning) {
                        try {
                            bytes = mInputStream.read(buffer)
                            if(bytes > 0) onReadListener(buffer, bytes)
                        } catch (e:Exception) {
                            e.printStackTrace()
                            break;
                            mainActivity.log("socket broken")
                        }
                    }
                }
            }
        }

        override fun write(byteArray: ByteArray) {
            thread {
                try {
                    mOutputStream.write(byteArray)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    class ServerThread(private val mainActivity: MainActivity): MySocket {
        private var serverSocket:ServerSocket = ServerSocket(8888)
        private lateinit var mOutputStream: OutputStream
        private lateinit var mInputStream: InputStream
        override val onReadListener: (buffer: ByteArray, len: Int) -> Unit = { buffer, len -> mainActivity.onRead(buffer, len) }

        init {
            thread {
                mainActivity.log("socket server creating")
                val socket  = serverSocket.accept()
                mInputStream = socket.getInputStream()
                mOutputStream = socket.getOutputStream()
                mainActivity.log("socket server created")

                val executor = Executors.newSingleThreadExecutor()

                executor.execute {
                    val buffer = ByteArray(1024)
                    var bytes:Int

                    while(mainActivity.isRunning) {
                        try {
                            bytes = mInputStream.read(buffer)
                            if(bytes > 0) onReadListener(buffer, bytes)
                        } catch (e:Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        override fun write(byteArray: ByteArray) {
            thread {
                try {
                    mOutputStream.write(byteArray)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}