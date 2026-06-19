package com.ollivolland.lemaitre

import MySocket
import MyWifiP2p
import MyWifiP2p.Companion.JSON_KEY_DEVICE_NAME
import MyWifiP2p.Companion.JSON_KEY_PORT
import MyWifiP2p.Companion.JSON_TAG_CLIENT_REPLY
import MyWifiP2p.Companion.JSON_TAG_CONFIG
import MyWifiP2pActionListener
import android.Manifest
import android.annotation.SuppressLint
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.annotation.RequiresPermission
import datas.Client
import datas.ClientData
import datas.HostData
import datas.Session
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MyConnectionManager(private val activity: MainActivity) {
    private var isFormationSocketReady = true
    var mySocketFormation: MySocket? = null
    val clients = mutableListOf<Client>()
    var myWifiP2p:MyWifiP2p = MyWifiP2p(activity, WifiP2pManager.ConnectionInfoListener(this::onConnectionInfo))
    var onInit:(() -> Unit)? = null
    val formationDevices = mutableListOf<WifiP2pDevice>()
    var hostMac:String? = null


    @SuppressLint("MissingPermission")
    fun init() {
//        myWifiP2p.myNSD.stopNSD()
        myWifiP2p.disconnectAll {
            Session.setState(Session.State.CLIENT)
            onInit?.invoke()

            //  NSD
            myWifiP2p.myNSD.discoverNSD {
                Session.log("NSD discovered")
                if(!activity.isDestroyed)
                    activity.runOnUiThread { activity.vHost.isEnabled = false }


                hostMac = it.deviceAddress
                val config = WifiP2pConfig().apply {
                    deviceAddress = hostMac
                    wps.setup = WpsInfo.PBC
                }
                myWifiP2p.manager.connect(myWifiP2p.channel, config, MyWifiP2pActionListener("connect"))
            }
        }


        myWifiP2p.onPeersChangedAction = {
//            Session.log("peerschanged")
            myWifiP2p.manager.requestPeers(myWifiP2p.channel) { list ->
                if(!isFinished) {
                    for(x in formationDevices - list.deviceList)
                        Session.log("lost ${x.deviceName}")
                    for(x in list.deviceList - formationDevices)
                        Session.log("found ${x.deviceName}")

                    formationDevices.clear()
                    formationDevices.addAll(list.deviceList)
                }
            }
        }
    }


    fun finish() {
        isFinished = true
        myWifiP2p.myNSD.stopNSD()
        myWifiP2p.stopDiscovery()
//		mySocketFormation?.close()
//		mySocketFormation = null
    }


    fun setAsHost() {
        myWifiP2p.myNSD.stopNSD()
        myWifiP2p.disconnectAll {
            Session.setState(Session.State.HOST)
            HostData.formationSocket = ServerSocket(MainActivity.PORT_FORMATION)
            createFormationSocketHost()

            myWifiP2p.createGroup {
                //  NSD
                myWifiP2p.myNSD.registerNSD()
            }
        }
    }


    fun launchHost() {
        HostData.set(myWifiP2p.deviceName, clients)
        finish()

        Session.log("formed with ${clients.size} clients")
    }


    fun createFormationSocketHost() {
        if (isFormationSocketReady) {
            isFormationSocketReady = false
            val port = MainActivity.PORT_COMMUNICATION + clients.count()
            var ip = ""

            thread {
                mySocketFormation = MySocket(HostData.formationSocket.accept(), "server").apply {
                    addOnConfigured {
                        ip = it.inetAddress.hostAddress!!

                        this.write(JSONObject().apply {
                            accumulate(JSON_KEY_PORT, port)
                        }, JSON_TAG_CONFIG)
                    }
                    addOnJson { jo, tag ->
                        if (tag != JSON_TAG_CLIENT_REPLY) return@addOnJson

                        val client = Client(
                            ip,
                            port,
                            jo[JSON_KEY_DEVICE_NAME] as String
                        )   //, jo["address"] as String)
                        client.create()
                        clients.add(client)
                        Session.log("client ${client.name} on [$port] => $ip")

                        this.close()
                    }
                    addOnClose {
                        isFormationSocketReady = true
                        createFormationSocketHost()
                    }
                    setSocketConfigured()
                }
            }
        }
    }


    fun createFormationSocketClient(info: WifiP2pInfo) {
        thread {
            val socket = Socket()
            mySocketFormation = MySocket(socket, "client").apply {
                addOnJson { jo, tag ->
                    if (tag != JSON_TAG_CONFIG) return@addOnJson

                    this.write(JSONObject().apply {
                        accumulate(JSON_KEY_DEVICE_NAME, myWifiP2p.deviceName)
                    }, JSON_TAG_CLIENT_REPLY)

                    ClientData.set(
                        jo[JSON_KEY_PORT] as Int,
                        info.groupOwnerAddress.hostAddress!!,
                        myWifiP2p.deviceName,
                        activity
                    )
                    Session.log("host = ${ClientData.get!!.port}")

                    this.close()

                    finish()
                }
            }
            socket.connect(InetSocketAddress(info.groupOwnerAddress.hostAddress!!, MainActivity.PORT_FORMATION), 60_000)
            mySocketFormation!!.setSocketConfigured()
        }
    }

    private var isConnected = false
    private var wantNewClientReconnectionTry = true
    private var isWantConnection = true
    var isFinished = false
    var isGroupFormed:Boolean = false;private set
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun onConnectionInfo(info: WifiP2pInfo) {
//        Session.log("onConnectionInfo: ${info.groupFormed} ${info.isGroupOwner} ${info.groupOwnerAddress}")

        if (!isWantConnection) return

        //  logs
        println("connection: formed = ${info.groupFormed}, isOwner = ${info.isGroupOwner}")
        if (!isConnected && info.groupFormed && info.groupOwnerAddress != null) {
            Session.log("CONNECTED (${info.groupOwnerAddress.hostAddress})")
            isGroupFormed = true
        }
        if (isConnected && !info.groupFormed) Session.log("DISCONNECTED")

        //  client formation        needs group formed, else ex
        if (!isFinished && Session.isClient && ClientData.get == null && mySocketFormation == null && info.groupFormed && info.groupOwnerAddress.hostAddress != null) {
            createFormationSocketClient(info)
        }

        isConnected = info.groupFormed

        if(!isFinished)
            return

        //  host reconnection
        if(HostData.get != null) {
            myWifiP2p.manager.requestPeers(myWifiP2p.channel) { list ->
                HostData.get!!.clients.forEachIndexed { i, cl ->
                    val now = list.deviceList.filter { it.deviceName == cl.name }
                    val isConnecting = now.size == 1 && now[0].status == WifiP2pDevice.CONNECTED

                    if (cl.isConnected && !isConnecting) {
                        Session.log("${cl.name} disconnected")
                        myWifiP2p.manager.discoverPeers(myWifiP2p.channel, MyWifiP2pActionListener("discoverPeers host reconnect"))
                        HostData.get!!.clients[i].socket?.socket?.close()
                        HostData.get!!.clients[i].socket?.close()
                    }
                    if (!cl.isConnected && isConnecting) {
                        Session.log("${cl.name} reconnected")
//                        HostData.get!!.socket[i].close()
                        if(HostData.get!!.clients.count { !it.isConnected } == 1)
                            myWifiP2p.manager.stopPeerDiscovery(myWifiP2p.channel, MyWifiP2pActionListener("stopPeerDiscovery host reconnect"))
                    }

                    cl.isConnected = isConnecting
                }
            }
        }

        //  client reconnect
        if (!isConnected && ClientData.get != null && wantNewClientReconnectionTry) {
            Session.log("client reconnect")
            wantNewClientReconnectionTry = false
            myWifiP2p.manager.discoverPeers(myWifiP2p.channel, MyWifiP2pActionListener("discoverPeers client reconnect"))
            thread {
                while (!isConnected) {
                    Thread.sleep(10000)
                    myWifiP2p.manager.connect(
                        myWifiP2p.channel,
                        WifiP2pConfig().apply {
                            deviceAddress = hostMac
                            wps.setup = WpsInfo.PBC
                        },
                        MyWifiP2pActionListener("connect"))
                }
                wantNewClientReconnectionTry = true
                ClientData.get!!.replaceSocket()
                myWifiP2p.manager.stopPeerDiscovery(
                    myWifiP2p.channel,
                    MyWifiP2pActionListener("stopPeerDiscovery client reconnect")
                )
            }
        }
    }
}