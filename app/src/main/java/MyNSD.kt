import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import com.ollivolland.lemaitre.MainActivity
import datas.Session
import kotlin.concurrent.thread

class MyNSD(private val myWifiP2p: MyWifiP2p) {
    var isWantDiscoverServices = false


    @SuppressLint("MissingPermission")
    fun discoverNSD(onConnected: (WifiP2pDevice) -> Unit) {
        myWifiP2p.manager.setDnsSdResponseListeners(myWifiP2p.channel,
            { instanceName, registrationType, resourceType ->
                Session.log("service: $instanceName\n\t$registrationType ${resourceType.deviceName} ${resourceType.deviceAddress}")
                myWifiP2p.requestConnectionInfo()
                isWantDiscoverServices = false
                thread {
                    stopNSD()
                    onConnected(resourceType)
                }
            },
            { a, b, c -> Session.log("textListener: ${c.deviceName}") })

        myWifiP2p.manager.addServiceRequest(myWifiP2p.channel, WifiP2pDnsSdServiceRequest.newInstance(), MyWifiP2pActionListener("addServiceRequest").setOnSuccess {
            isWantDiscoverServices = true
            Session.log("discoverNSD")
        })

        myWifiP2p.manager.discoverServices(myWifiP2p.channel, MyWifiP2pActionListener("discoverServices"))
    }


    @SuppressLint("MissingPermission")
    fun registerNSD() {
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(MainActivity.SERVICE_NAME, MainActivity.SERVICE_TYPE, mapOf())

        myWifiP2p.manager.addLocalService(myWifiP2p.channel, serviceInfo, MyWifiP2pActionListener("addLocalService").setOnSuccess {
            Session.log("NSD registered")
        })
    }


    fun stopNSD() {
        Session.log("stop NSD")
        myWifiP2p.manager.clearLocalServices(myWifiP2p.channel, MyWifiP2pActionListener("clearLocalServices"))
        myWifiP2p.manager.clearServiceRequests(myWifiP2p.channel, MyWifiP2pActionListener("clearServiceRequests"))
        isWantDiscoverServices = false
    }
}