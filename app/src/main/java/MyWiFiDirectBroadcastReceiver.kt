import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import com.ollivolland.lemaitre.MainActivity
import datas.Session

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
					Session.log("deviceName = ${myWifiP2p.deviceName}")
				}
			}
			WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION  -> {
				println("WIFI_P2P_CONNECTION_CHANGED_ACTION")
				
				// Respond to new connection or disconnections
				manager.requestConnectionInfo(channel, myWifiP2p.mConnectionInfoListener)
			}
			WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION       -> {
				println("WIFI_P2P_PEERS_CHANGED_ACTION")
				
				if(!myWifiP2p.isFormed)
					manager.requestPeers(channel) { list ->
						// Respond to new connection or disconnections
						if(myWifiP2p.isWantUpdateFormationDevices) {
							list.deviceList.forEach {
								if (!myWifiP2p.formationDevices.contains(it)) {
									myWifiP2p.formationDevices.add(it)
									Session.log("found ${it.deviceName}")
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