import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import com.ollivolland.lemaitre.MainActivity
import com.ollivolland.lemaitre.MyNSD
import datas.Session
import kotlin.concurrent.thread


@SuppressLint("MissingPermission")
class MyWifiP2p(private val activity: MainActivity, private val mConnectionInfoListener: WifiP2pManager.ConnectionInfoListener) {
	val manager: WifiP2pManager by lazy { activity.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
	val channel: WifiP2pManager.Channel = manager.initialize(activity, activity.mainLooper, null)
	var onPeersChangedAction:(()-> Unit)? = null
	private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(p0: Context?, intent: Intent) {
			val device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) as WifiP2pDevice?
			val isWifiP2pEnabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
			val networkInfo: NetworkInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as NetworkInfo?

			when (intent.action!!) {
				WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
				}
				WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
					println("WIFI_P2P_THIS_DEVICE_CHANGED_ACTION")
					if(device != null && deviceName.isEmpty()) {
						deviceName = device.deviceName
						deviceAddress = device.deviceAddress
						Session.log("deviceName = ${deviceName}:${isWifiP2pEnabled}")
					}

					requestConnectionInfo()
				}
				WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION  -> {
					println("WIFI_P2P_CONNECTION_CHANGED_ACTION")

					Session.log("networkInfo = ${networkInfo?.state}")
					if(networkInfo?.state == NetworkInfo.State.UNKNOWN) {
//						AlertDialog.Builder(activity)
//							.setTitle("Network bugged")
//							.setMessage("restart?")
//							.setPositiveButton(R.string.ok) { dialog, which -> {
//								activity.finish()
//								System.exit(0)
//							} }
//							.setIconAttribute(R.attr.alertDialogIcon)
//							.show()
						Session.log("RESTART THE APP; NETWORK IS BUGGED")
//						manager.
					}

					// Respond to new connection or disconnections
					requestConnectionInfo()
				}
				WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION       -> {
					println("WIFI_P2P_PEERS_CHANGED_ACTION")

					onPeersChangedAction?.invoke()
					requestConnectionInfo()
				}
			}
		}
	}
	val myNSD = MyNSD(this)
	var deviceName: String = ""
	var deviceAddress: String = ""
	private var isOpen = true
	var lastConnectionInfo = 0L


	init {
		if(get != null) {
			get!!.close()
		}
		get = this
		activity.applicationContext.registerReceiver(receiver, INTENT_FILTER)
		
		//  vacuous thread
		thread(name = "wifip2p vacuous thread") {
			while (isOpen) {
				if(myNSD.isWantDiscoverServices)
					manager.discoverServices(channel, MyWifiP2pActionListener("discoverServices"))

//				if(lastConnectionInfo + 1000L < System.currentTimeMillis()) {
//					lastConnectionInfo = System.currentTimeMillis()
//					requestConnectionInfo()
//				}

				Thread.sleep(3000)
			}
		}
		
		requestConnectionInfo()
	}


	fun requestConnectionInfo() {
		manager.requestConnectionInfo(channel, mConnectionInfoListener)
	}


	fun connectDevice(device: WifiP2pDevice) {
		val config = WifiP2pConfig().apply {
			deviceAddress = device.deviceAddress
			wps.setup = WpsInfo.PBC
		}
		manager.connect(channel, config, MyWifiP2pActionListener("connect"))
	}


	fun disconnectAll(onDisconnected:()->Unit={}) {
		manager.cancelConnect(channel, MyWifiP2pActionListener("cancelConnect").setOnComplete {
			manager.stopPeerDiscovery(channel, MyWifiP2pActionListener("stopPeerDiscovery").setOnComplete {
				manager.removeGroup(channel, MyWifiP2pActionListener("removeGroup").setOnComplete {
					Session.log("all connections reset")
					onDisconnected()
				})
			})
		})
	}


	fun createGroup(onComplete:(()->Unit)? = null) {
		//  create service
		manager.createGroup(channel, MyWifiP2pActionListener("createGroup").setOnSuccess {
			onComplete?.invoke()
		})
	}


	fun stopDiscovery() {
		manager.stopPeerDiscovery(channel, MyWifiP2pActionListener("stopPeerDiscovery"))
	}


	fun close() {
		isOpen = false
		get = null
	}
	
	companion object {
		const val JSON_TAG_CONFIG = "wifip2pconfig"
		const val JSON_TAG_CLIENT_REPLY = "wifip2preply"
		const val JSON_KEY_PORT = "useport"

		var get:MyWifiP2p? = null

		private val INTENT_FILTER = IntentFilter().apply {
			addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
			addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
			addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
			addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
		}
	}
}