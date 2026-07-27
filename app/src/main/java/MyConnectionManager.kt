import MyWifiP2p.Companion.JSON_KEY_PORT
import MyWifiP2p.Companion.JSON_TAG_CLIENT_REPLY
import MyWifiP2p.Companion.TAG_CONFIG_CLIENT
import android.Manifest
import android.annotation.SuppressLint
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.annotation.RequiresPermission
import com.ollivolland.lemaitre.MainActivity
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
    var mySocketFormation: MySocket? = null
    val clients = mutableListOf<Client>()
    var myWifiP2p:MyWifiP2p = MyWifiP2p(activity, WifiP2pManager.ConnectionInfoListener(this::onConnectionInfo))
    var onInit:(() -> Unit)? = null
    val formationDevices = mutableListOf<WifiP2pDevice>()
    var hostMac:String? = null


    @SuppressLint("MissingPermission")
    fun init() {
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
        HostData.set(Globals.deviceName, clients)
        finish()

        Session.log("formed with ${clients.size} clients")
    }


    fun createFormationSocketHost() {
        thread {
            while (true) {
                val port = MainActivity.PORT_COMMUNICATION + clients.count()
                var ip = ""
                mySocketFormation = MySocket(HostData.formationSocket.accept(), "server").apply {
                    addOnConfigured {
                        ip = it.inetAddress.hostAddress!!

                        this.write(JSONObject().apply {
                            put(JSON_KEY_PORT, port)
                        }, TAG_CONFIG_CLIENT)
                    }
                    addOnJson { carrier ->
                        carrier.optJSONObject(JSON_TAG_CLIENT_REPLY)?.also { jo ->
                            val client = Client(
                                ip,
                                jo.getString(JSON_KEY_P2P_NAME),
                                port,
                                jo.getString(JSON_KEY_DEVICE_NAME),
                                jo.getString(JSON_KEY_FINGERPRINT),
                                jo.getString(JSON_KEY_P2P_ADDRESS),
                            )
                            client.create()
                            clients.add(client)
                            Session.log("client ${client.humanName} on [$port] => $ip")

                            this.close()
                        }
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
                addOnJson { carrier ->
                    carrier.optJSONObject(TAG_CONFIG_CLIENT)?.also { jo ->
                        this.write(JSONObject().apply {
                            put(JSON_KEY_DEVICE_NAME, Globals.deviceName)
                            put(JSON_KEY_P2P_NAME, myWifiP2p.deviceName)
                            put(JSON_KEY_P2P_ADDRESS, myWifiP2p.deviceAddress)
                            put(JSON_KEY_FINGERPRINT, Globals.deviceFingerprint)
                        }, JSON_TAG_CLIENT_REPLY)

                        ClientData.set(
                            jo.getInt(JSON_KEY_PORT),
                            info.groupOwnerAddress.hostAddress!!,
                            Globals.deviceName,
                            activity
                        )
                        Session.log("host = ${ClientData.get!!.port}")

                        this.close()
                        finish()
                    }
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
                    val now = list.deviceList.filter { it.deviceAddress == cl.deviceAddress }
                    val isConnecting = now.size == 1 && now[0].status == WifiP2pDevice.CONNECTED

                    if (cl.isConnected && !isConnecting) {
                        Session.log("${cl.humanName} disconnected")
                        myWifiP2p.manager.discoverPeers(myWifiP2p.channel, MyWifiP2pActionListener("discoverPeers host reconnect"))
                        HostData.get!!.clients[i].socket?.socket?.close()
                        HostData.get!!.clients[i].socket?.close()
                    }
                    if (!cl.isConnected && isConnecting) {
                        Session.log("${cl.humanName} reconnected")
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


    companion object {
        const val JSON_KEY_DEVICE_NAME = "name"
        const val JSON_KEY_P2P_NAME = "p2pname"
        const val JSON_KEY_FINGERPRINT = "fingerprint"
        const val JSON_KEY_P2P_ADDRESS = "p2paddress"
    }
}