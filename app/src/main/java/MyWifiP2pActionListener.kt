import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import datas.Session

class MyWifiP2pActionListener(private val message:String = ""): WifiP2pManager.ActionListener {
    private var mySuccess:() -> Unit = {}
    private var myFailure:() -> Unit = {}
    private var myComplete:() -> Unit = {}

    fun setOnSuccess(action:() -> Unit): MyWifiP2pActionListener {
        mySuccess=action
        return this
    }

    fun setOnFailure(action:() -> Unit): MyWifiP2pActionListener {
        myFailure=action
        return this
    }

    fun setOnComplete(action:() -> Unit): MyWifiP2pActionListener {
        myComplete=action
        return this
    }

    override fun onSuccess() {
        if(message.isNotEmpty()) println("$message success")
        mySuccess()
        myComplete()
    }

    override fun onFailure(p0: Int) {
        if(message.isNotEmpty()) {
            Log.e("System.out", "$message fail: $p0")
            Session.logE("ERROR: $message fail: $p0")
        }
        myFailure()
        myComplete()
    }
}