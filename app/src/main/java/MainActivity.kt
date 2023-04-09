package com.ollivolland.lemaitre2

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread


class MainActivity : AppCompatActivity() {
    private val manager: WifiP2pManager by lazy { getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val intentFilter = IntentFilter().apply {
//        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
//        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
//        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }
    private val connected = mutableListOf<WifiP2pDevice>()
    lateinit var mysocket:WiFiDirectBroadcastReceiver.MySocket
    lateinit var mConnectionInfoListener: WifiP2pManager.ConnectionInfoListener

    var isRunning = true
    var isWantDiscoverPeers = false
    var isWantCheckConnection = false
    var isShouldBeConnected = false

    val threadHost = thread {
        while (isRunning) {
            Thread.sleep(10_000)

            if(isWantDiscoverPeers) {
                manager.discoverPeers(
                    channel,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            println("discoverPeers success")

                            manager.requestPeers(channel) {
                                it.deviceList.forEach {
                                    if(Build.VERSION.SDK_INT >= 29 && !connected.contains(it)) {
                                        println("device ${it.deviceName}")
                                        connected.add(it)

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

            if(isWantCheckConnection) {
                manager.requestConnectionInfo(channel, mConnectionInfoListener)
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

        channel = manager.initialize(this, mainLooper, null)
        receiver = WiFiDirectBroadcastReceiver(manager, channel!!, this)

        mConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
            println("connection changed: formed = ${info.groupFormed}, isOwner = ${info.isGroupOwner}")

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

            if(isShouldBeConnected && info.groupFormed) {
                mysocket =
                    if(info.isGroupOwner) WiFiDirectBroadcastReceiver.ServerThread(this)
                    else WiFiDirectBroadcastReceiver.ClientThread(this, info.groupOwnerAddress)

                isWantCheckConnection=false
            }
        }

        val vHost = findViewById<Button>(R.id.buttonHost)
        val vClient = findViewById<Button>(R.id.buttonClient)

        vHost.setOnClickListener {
            setHost()
            vHost.isEnabled=false
            vClient.isEnabled=false
            isShouldBeConnected=true
        }

        vClient.setOnClickListener {
            discover()
            vHost.isEnabled=false
            vClient.isEnabled=false
            isShouldBeConnected=true
        }
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
    }

    private fun discoverNSD() {
//        manager.setDnsSdResponseListeners(channel,
//            { instanceName, registrationType, resourceType ->
//                println("servListener $instanceName")
//                // Update the device name with the human-friendly version from
//                // the DnsTxtRecord, assuming one arrived.
//                resourceType.deviceName = buddies[resourceType.deviceAddress] ?: resourceType.deviceName
//            },
//            { fullDomain, record, device ->
//                println("DnsSdTxtRecord available -$record")
//                println("device ${device.deviceAddress} ${device.deviceName}")
//                record["buddyname"]?.also {
//                    buddies[device.deviceAddress] = it
//                }
//        })

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
                                        isWantCheckConnection=true
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

        //  create group

//        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
//            override fun onSuccess() {
//                println("createGroup success")
//            }
//
//            override fun onFailure(reason: Int) {
//                println("createGroup failure $reason")
//                manager.removeGroup(channel, object :WifiP2pManager.ActionListener {
//                    override fun onSuccess() {
//                        println("removeGroup success")
//
//                        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
//                            override fun onSuccess() {
//                                println("createGroup success")
//                            }
//
//                            override fun onFailure(reason: Int) {
//                                println("createGroup failure $reason")
//                            }
//                        })
//                    }
//
//                    override fun onFailure(p0: Int) {
//                        println("removeGroup failure")
//                    }
//                })
//            }
//        })

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
        }
    }

    interface MySocket {
        val onReadListeners:MutableList<(buffer:ByteArray, len:Int) -> Unit>
        fun write(byteArray: ByteArray)
    }

    class ClientThread(private val mainActivity: MainActivity, inetAddress: InetAddress): Thread(),MySocket {
        private val hostAddress:String
        private lateinit var mOutputStream: OutputStream
        private lateinit var mInputStream: InputStream
        val socket:Socket
        override val onReadListeners: MutableList<(buffer: ByteArray, len: Int) -> Unit> = mutableListOf()

        init {
            hostAddress = inetAddress.hostAddress!!
            socket = Socket()
        }

        override fun write(byteArray: ByteArray) {
            try {
                mOutputStream.write(byteArray)
            } catch (e:Exception) {
                e.printStackTrace()
            }
        }

        override fun run() {
            socket.connect(InetSocketAddress(hostAddress, 8888), 3000)
            mInputStream = socket.getInputStream()
            mOutputStream = socket.getOutputStream()

            val executor = Executors.newSingleThreadExecutor()

            executor.execute {
                val buffer = ByteArray(1024)
                var bytes:Int

                while(mainActivity.isRunning) {
                    try {
                        bytes = mInputStream.read(buffer)
                        if(bytes > 0) {
                            val finalBytes = bytes
                            //buffer, bytes
                        }
                    } catch (e:Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    class ServerThread(private val mainActivity: MainActivity): Thread(), MySocket {
        private var serverSocket:ServerSocket = ServerSocket(8888)
        private lateinit var mOutputStream: OutputStream
        private lateinit var mInputStream: InputStream
        override val onReadListeners: MutableList<(buffer: ByteArray, len: Int) -> Unit> = mutableListOf()

        override fun write(byteArray: ByteArray) {
            try {
                mOutputStream.write(byteArray)
            } catch (e:Exception) {
                e.printStackTrace()
            }
        }

        override fun run() {
            val socket  = serverSocket.accept()
            mInputStream = socket.getInputStream()
            mOutputStream = socket.getOutputStream()

            val executor = Executors.newSingleThreadExecutor()

            executor.execute {
                val buffer = ByteArray(1024)
                var bytes:Int

                while(mainActivity.isRunning) {
                    try {
                        bytes = mInputStream.read(buffer)
                        if(bytes > 0) {
                            val finalBytes = bytes

                        }
                    } catch (e:Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}