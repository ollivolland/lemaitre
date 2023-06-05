import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import com.ollivolland.lemaitre2.MainActivity
import com.ollivolland.lemaitre2.MyClientThread
import com.ollivolland.lemaitre2.MyServerThread
import com.ollivolland.lemaitre2.MySocket
import com.ollivolland.lemaitre2.Session
import com.ollivolland.lemaitre2.SessionState
import datas.ClientData
import org.json.JSONObject
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
class MyWifiP2p(private val activity: MainActivity) {
	private val manager: WifiP2pManager by lazy { activity.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
	private val channel: WifiP2pManager.Channel = manager.initialize(activity, activity.mainLooper, null)
	val receiver: MyWiFiDirectBroadcastReceiver = MyWiFiDirectBroadcastReceiver(manager, channel, activity, this)
	val mConnectionInfoListener: WifiP2pManager.ConnectionInfoListener
	var log:(String)->Unit = activity::log
	val formationDevices = mutableListOf<WifiP2pDevice>()
	val clients = mutableListOf<Client>()
	private var checkNeedAnotherSocket:() -> Unit ={}
	private lateinit var hostMac:String
	private lateinit var mySocketFormation: MySocket
	var deviceName: String = ""
	var isGroupFormed:Boolean = false;private set
	
	var isWantUpdateFormationDevices = true
	private var isHasTriedConnectingToHost = false
	var isWantDiscoverPeers = true
	private var isWantConnection = false
	private var isConnected = false
	private var isFormationSocketReady = true
	
	init {
		disconnectAll()
		
		checkNeedAnotherSocket = {
			if (Session.state ==  SessionState.HOST && isFormationSocketReady && clients.count() < formationDevices.count()) {
				isFormationSocketReady = false
				val port = MainActivity.PORT_COMMUNICATION + clients.count()
				var ip = ""
				
				mySocketFormation = MyServerThread(MainActivity.PORT_FORMATION).apply {
					addOnConfigured {
						ip = it.inetAddress.hostAddress!!
						
						this.write(JSONObject().apply {
							accumulate("useport", port)
						}, JSON_TAG_CONFIG)
					}
					addOnJson { jo, tag ->
						if (tag != JSON_TAG_CLIENT_REPLY) return@addOnJson
						
						val client = Client(ip, port, jo["name"] as String)
						clients.add(client)
						//  activity.runOnUiThread { Toast.makeText(activity, "connected ${client.name}", Toast.LENGTH_LONG).show() }
						log("client ${client.name} on [$port] => $ip")
						
						this.close()
					}
					addOnClose {
						isFormationSocketReady=true
						checkNeedAnotherSocket()
					}
					log{ s -> log(s) }
				}
			}
		}
		
		mConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
			if(!isWantConnection) return@ConnectionInfoListener
			
			println("connection: formed = ${info.groupFormed}, isOwner = ${info.isGroupOwner}")
			
			if(!isConnected && info.groupFormed) {
				log("CONNECTED (${info.groupOwnerAddress.hostAddress})")
				isGroupFormed = true
			}
			if(isConnected && !info.groupFormed) log("DISCONNECTED")
			
			
			//  if connected check if another device wants to connect
			if(info.isGroupOwner) manager.discoverPeers(channel, MyWifiP2pActionListener("discoverPeers"))
			
			//  sockets
			checkNeedAnotherSocket()
			
			if(Session.state ==  SessionState.CLIENT && !this::mySocketFormation.isInitialized && info.groupOwnerAddress.hostAddress != null) {
				mySocketFormation = MyClientThread(info.groupOwnerAddress.hostAddress!!, MainActivity.PORT_FORMATION).apply {
					addOnJson { jo, tag ->
						if(tag != JSON_TAG_CONFIG) return@addOnJson
						
						this.write(JSONObject().apply {
							accumulate("name", deviceName)
						}, JSON_TAG_CLIENT_REPLY)
						this.close()
						
						ClientData.set(jo["useport"] as Int, hostMac, deviceName, activity)
						log("host = ${ClientData.get!!.port}")
						//  activity.runOnUiThread { Toast.makeText(activity, "connected to host", Toast.LENGTH_LONG).show() }
					}
					log{ s -> log(s) }
				}
			}
			
			isConnected = info.groupFormed
		}
		manager.requestConnectionInfo(channel, mConnectionInfoListener)
	}
	
	@SuppressLint("MissingPermission")
	fun discover() {
		manager.setDnsSdResponseListeners(channel,
			{ instanceName, registrationType, resourceType ->
				log("service: $instanceName\n\t$registrationType ${resourceType.deviceName} ${resourceType.deviceAddress}")
				
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
//			{ fullDomain, record, _ -> log("DNS text record: $record $fullDomain") })
		
		manager.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(), MyWifiP2pActionListener("addServiceRequest").setOnSuccess {
			thread {
				while (!isHasTriedConnectingToHost) {
					manager.discoverServices(channel, MyWifiP2pActionListener("discoverServices"))  //  after service request
					Thread.sleep(3000)
				}
			}
		})
	}
	
	@SuppressLint("MissingPermission")
	fun startRegistration() {
		//  Pass it an instance name, service type (_protocol._transportlayer) , and the map containing information other devices will want once they connect to this one.
		val serviceInfo1 = WifiP2pDnsSdServiceInfo.newInstance(MainActivity.SERVICE_NAME, MainActivity.SERVICE_TYPE, mapOf())
		
		//  create service
		manager.createGroup(channel, MyWifiP2pActionListener("createGroup").setOnSuccess {
			manager.addLocalService(channel, serviceInfo1, MyWifiP2pActionListener("addLocalService").setOnSuccess {
				log("DNS added")
				
				thread {
					while (isWantDiscoverPeers) {
						manager.discoverPeers(channel, MyWifiP2pActionListener("discoverPeers"))
						Thread.sleep(3000)
					}
				}
			})
		})
	}
	
	private fun disconnectAll() {
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
	}
	
	fun stopNSD() {
		manager.clearLocalServices(channel, MyWifiP2pActionListener("clearLocalServices"))
		manager.clearServiceRequests(channel, MyWifiP2pActionListener("clearServiceRequests"))
		manager.stopPeerDiscovery(channel, MyWifiP2pActionListener("stopPeerDiscovery"))
	}
	
	companion object {
		const val JSON_TAG_CONFIG = "wifip2pconfig"
		const val JSON_TAG_CLIENT_REPLY = "wifip2preply"
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
	private val activity: MainActivity,
	private val myWifiP2p: MyWifiP2p
) : BroadcastReceiver() {
	@SuppressLint("MissingPermission")
	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action!!) {
			WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
				println("WIFI_P2P_THIS_DEVICE_CHANGED_ACTION")
				
				val device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) as WifiP2pDevice?
				if(device != null && myWifiP2p.deviceName.isEmpty()) {
					myWifiP2p.deviceName = device.deviceName
					myWifiP2p.log("deviceName = ${myWifiP2p.deviceName}")
				}
			}
			WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
				println("WIFI_P2P_CONNECTION_CHANGED_ACTION")
				
				// Respond to new connection or disconnections
				manager.requestConnectionInfo(channel, myWifiP2p.mConnectionInfoListener)
			}
			WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
				println("WIFI_P2P_PEERS_CHANGED_ACTION")
				
				// Respond to new connection or disconnections
				if(myWifiP2p.isWantUpdateFormationDevices) {
					manager.requestPeers(channel) { list ->
						list.deviceList.forEach {
							if (!myWifiP2p.formationDevices.contains(it)) {
								myWifiP2p.formationDevices.add(it)
								myWifiP2p.log("found ${it.deviceName}")
								
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