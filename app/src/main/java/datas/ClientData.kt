package datas

import MyTimer
import android.content.Intent
import com.ollivolland.lemaitre2.ActivityHome
import com.ollivolland.lemaitre2.MainActivity
import com.ollivolland.lemaitre2.MyServerThread
import com.ollivolland.lemaitre2.MySocket
import com.ollivolland.lemaitre2.Session
import com.ollivolland.lemaitre2.SessionState
import kotlin.concurrent.thread

class ClientData private constructor(val port: Int, val hostMac:String, val deviceName:String, private val mainActivity: MainActivity?) {
    val mySocket: MySocket
    private var sentLastUpdate = 0L

    init {
        mySocket = MyServerThread(port)
        mySocket.log { s -> mainActivity?.log(s) }
        
        //  launch host
        var finReader:(String) -> Unit = {}
        finReader = { s2 ->
            if(s2 == "fin") {
                mySocket.removeOnRead(finReader)
                mainActivity!!.startActivity(Intent(mainActivity, ActivityHome::class.java))
                mainActivity.finish()
            }
        }
        mySocket.addOnRead(finReader)
    
        //  client update host
        thread {
            while (mySocket.isOpen) {
                if(MyTimer().time > sentLastUpdate + 1000) {
                    sentLastUpdate = MyTimer().time
                    mySocket.write("update=${sentLastUpdate}")
                }
                
                Thread.sleep(20)
            }
        }
    }

    companion object {
        var get: ClientData? = null; private set

        fun set(port:Int, hostMac: String, mainActivity: MainActivity) {
            if(get != null) throw Exception()
            
            get = ClientData(port, hostMac, mainActivity.thisDeviceName, mainActivity)
        }
    }
}