import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import com.ollivolland.lemaitre.MainActivity
import datas.ClientData
import datas.HostData
import datas.Session
import org.json.JSONObject
import kotlin.concurrent.thread


@SuppressLint("MissingPermission")
class MyWifiP2p(private val activity: MainActivity) {
	private val manager: WifiP2pManager by lazy { activity.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
	private val channel: WifiP2pManager.Channel = manager.initialize(activity, activity.mainLooper, null)
	private val receiver: MyWiFiDirectBroadcastReceiver = MyWiFiDirectBroadcastReceiver(manager, channel, activity, this)
	val mConnectionInfoListener: WifiP2pManager.ConnectionInfoListener = WifiP2pManager.ConnectionInfoListener(this::onConnectionInfo)
	val formationDevices = mutableListOf<WifiP2pDevice>()
	val clients = mutableListOf<Client>()
	private lateinit var hostMac:String
	private var mySocketFormation: MySocket? = null
	var deviceName: String = ""
	private var isHasTriedConnectingToHost = false
	private var isWantDiscoverPeers = false
	private var isWantDiscoverServices = false
	private var isWantConnection = false
	private var isConnected = false
	private var isFormationSocketReady = true
	private var isHasClientReconnected = false
	private var isOpen = true
	var isFinished = false
	var isGroupFormed:Boolean = false;private set
	
	init {
		if(get != null) {
			get!!.close()
		}
		get = this
		receiver.register()
		
		//  vacuous thread
		thread(name = "wifip2p vacuous thread") {
			while (isOpen) {
				if(isWantDiscoverPeers)
					manager.discoverPeers(channel, MyWifiP2pActionListener("discoverPeers"))

				if(isWantDiscoverServices)
					manager.discoverServices(channel, MyWifiP2pActionListener("discoverServices"))

				Thread.sleep(100)
			}
		}
		
		requestConnectionInfo()
	}
	
	fun requestConnectionInfo() {
		manager.requestConnectionInfo(channel, mConnectionInfoListener)
	}
	
	private fun createFormationSocket() {
		if(isFinished) return
		
		if (Session.isHost && isFormationSocketReady) {
			isFormationSocketReady = false
			val port = MainActivity.PORT_COMMUNICATION + clients.count()
			var ip = ""
			
			mySocketFormation = MyServerThread(MainActivity.PORT_FORMATION).apply {
				addOnConfigured {
					ip = it.inetAddress.hostAddress!!
					
					this.write(JSONObject().apply {
						accumulate(JSON_KEY_PORT, port)
					}, JSON_TAG_CONFIG)
				}
				addOnJson { jo, tag ->
					if (tag != JSON_TAG_CLIENT_REPLY) return@addOnJson
					
					val client = Client(ip, port, jo[JSON_KEY_DEVICE_NAME] as String)   //, jo["address"] as String)
					clients.add(client)
					Session.log("client ${client.name} on [$port] => $ip")
					
					this.close()
				}
				addOnClose {
					isFormationSocketReady = true
					createFormationSocket()
				}
				log(Session.Companion::log)
			}
		}
	}

	fun finish() {
		isFinished = true
		stopNSD()
		stopDiscovery()
		mySocketFormation?.close()
		mySocketFormation = null
	}
	
	private fun onConnectionInfo(info: WifiP2pInfo) {
		if (!isWantConnection) return
		
		//  logs
		println("connection: formed = ${info.groupFormed}, isOwner = ${info.isGroupOwner}")
		if (!isConnected && info.groupFormed) {
			Session.log("CONNECTED (${info.groupOwnerAddress.hostAddress})")
			isGroupFormed = true
		}
		if (isConnected && !info.groupFormed) Session.log("DISCONNECTED")
		
		//  sockets
		createFormationSocket()
		
		//  client formation        needs group formed, else ex
		if (Session.isClient && this.mySocketFormation == null && ClientData.get == null && info.groupFormed && info.groupOwnerAddress.hostAddress != null) {
			mySocketFormation = MyClientThread(info.groupOwnerAddress.hostAddress!!, MainActivity.PORT_FORMATION).apply {
				addOnJson { jo, tag ->
					if (tag != JSON_TAG_CONFIG) return@addOnJson
					
					this.write(JSONObject().apply {
						accumulate(JSON_KEY_DEVICE_NAME, deviceName)
					}, JSON_TAG_CLIENT_REPLY)
					
					ClientData.set(jo[JSON_KEY_PORT] as Int, hostMac, deviceName, activity)
					Session.log("host = ${ClientData.get!!.port}")

					this.close()
				}
				log(Session.Companion::log)
			}
		}
		
		isConnected = info.groupFormed
		
		//  host reconnection
		if(isFinished && HostData.get != null)
			manager.requestPeers(channel) { list ->
				HostData.get!!.clients.forEachIndexed { i, cl ->
					val now = list.deviceList.filter { it.deviceName == cl.name }
					val isConnecting = now.size == 1 && now[0].status == WifiP2pDevice.CONNECTED
					
					if (!cl.isConnected && isConnecting) {
						Session.log("reconnect ${cl.name}")
						HostData.get!!.replaceSocket(i)
					}
					
					cl.isConnected = isConnecting
				}
				
				//  discovery
				if(isWantDiscoverPeers && (HostData.get!!.clients.isEmpty() || HostData.get!!.clients.all { it.isConnected })) {
					Session.log("PeerDiscovery stopped")
					stopDiscovery()
				}
				if(!isWantDiscoverPeers && HostData.get!!.clients.isNotEmpty() && HostData.get!!.clients.any { !it.isConnected }) {
					Session.log("PeerDiscovery restarted")
					isWantDiscoverPeers = true
				}
			}
		
		//  client reconnect socket
		if (isHasClientReconnected) {
			ClientData.get!!.replaceSocket()
			isHasClientReconnected = false
		}
		
		//  client reconnect
		if (!isConnected && ClientData.get != null && !isHasClientReconnected) {
			var tryReconnect: () -> Unit = { }
			tryReconnect = {
				if (!isConnected) {
					val config = WifiP2pConfig().apply {
						deviceAddress = ClientData.get!!.hostMac
						wps.setup = WpsInfo.PBC
					}
					manager.discoverPeers(channel, MyWifiP2pActionListener("discoverPeers"))
					manager.connect(channel, config, MyWifiP2pActionListener("connect").setOnSuccess {
						Session.log("try reconnect: success")
						isHasClientReconnected = true
					}.setOnFailure {
						Session.log("try reconnect: fail")
						thread {
							Thread.sleep(10_000)
							tryReconnect()
						}
					})
				}
			}
			
			tryReconnect()
		}
	}
	
	@SuppressLint("MissingPermission")
	fun discoverNSD(onConnected: () -> Unit) {
		manager.setDnsSdResponseListeners(channel,
			{ instanceName, registrationType, resourceType ->
				Session.log("service: $instanceName\n\t$registrationType ${resourceType.deviceName} ${resourceType.deviceAddress}")
				isWantDiscoverServices = false
				stopNSD()
				onConnected()
				
				//  connect from host
				if(!isHasTriedConnectingToHost) {
					isHasTriedConnectingToHost = true
					
					hostMac = resourceType.deviceAddress
					val config = WifiP2pConfig().apply {
						deviceAddress = hostMac
						wps.setup = WpsInfo.PBC
					}
					manager.connect(channel, config, MyWifiP2pActionListener("connect"))
				}
			},
			{ _, _, _ -> })
		
		manager.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(), MyWifiP2pActionListener("addServiceRequest").setOnSuccess {
			isWantDiscoverServices = true
		})
	}
	
	@SuppressLint("MissingPermission")
	fun registerNSD() {
		val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(MainActivity.SERVICE_NAME, MainActivity.SERVICE_TYPE, mapOf())
		
		//  create service
		manager.createGroup(channel, MyWifiP2pActionListener("createGroup").setOnSuccess {
			manager.addLocalService(channel, serviceInfo, MyWifiP2pActionListener("addLocalService").setOnSuccess {
				Session.log("NSD registered")
			})
		})
	}
	
	fun disconnectAll(onDisconnected:()->Unit={}) {
		stopNSD()
		manager.stopPeerDiscovery(channel, MyWifiP2pActionListener("stopPeerDiscovery").setOnComplete {
			manager.cancelConnect(channel, MyWifiP2pActionListener("cancelConnect").setOnComplete {
				manager.removeGroup(channel, MyWifiP2pActionListener("removeGroup").setOnComplete {
					isWantConnection = true
					Session.log("all connections reset")
					onDisconnected()
				})
			})
		})
	}
	
	fun stopNSD() {
		manager.clearLocalServices(channel, MyWifiP2pActionListener("clearLocalServices"))
		manager.clearServiceRequests(channel, MyWifiP2pActionListener("clearServiceRequests"))
	}
	
	fun stopDiscovery() {
		manager.stopPeerDiscovery(channel, MyWifiP2pActionListener("stopPeerDiscovery"))
		isWantDiscoverPeers = false
	}
	
	fun close() {
		isOpen = false
		receiver.unregister()
		get = null
	}
	
	companion object {
		const val JSON_TAG_CONFIG = "wifip2pconfig"
		const val JSON_TAG_CLIENT_REPLY = "wifip2preply"
		const val JSON_KEY_PORT = "useport"
		const val JSON_KEY_DEVICE_NAME = "name"
		
		var get:MyWifiP2p? = null
	}
}

data class Client(
	val ipWifiP2p:String,
	val port:Int,
	val name:String,
	var isConnected:Boolean = false
)