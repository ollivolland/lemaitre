import android.content.Intent
import com.ollivolland.lemaitre2.ActivityHome
import com.ollivolland.lemaitre2.MainActivity
import com.ollivolland.lemaitre2.MyServerThread
import com.ollivolland.lemaitre2.MySocket

class ClientData private constructor(val port: Int, val hostMac:String, val mainActivity: MainActivity?) {
    var mySocket: MySocket? = null

    init {
        var finReader:(String) -> Unit = {}
        finReader = { s2 ->
            if(s2 == "fin") {
                mySocket?.removeOnRead(finReader)
                mainActivity!!.startActivity(Intent(mainActivity, ActivityHome::class.java))
                mainActivity.finish()
            }
        }
        mySocket = MyServerThread(port).apply {
            addOnRead(finReader)
            log(mainActivity!!)
        }
    }

    companion object {
        var get:ClientData? = null; private set

        fun set(port:Int, hostMac: String, mainActivity: MainActivity){
            get = ClientData(port, hostMac, mainActivity)
        }
    }
}