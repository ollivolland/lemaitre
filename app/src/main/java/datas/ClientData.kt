package datas

import MyTimer
import android.content.Intent
import com.ollivolland.lemaitre2.ActivityHome
import com.ollivolland.lemaitre2.MainActivity
import com.ollivolland.lemaitre2.MyServerThread
import com.ollivolland.lemaitre2.MySocket
import com.ollivolland.lemaitre2.Session
import org.json.JSONObject
import kotlin.concurrent.thread

class ClientData private constructor(val port: Int, val hostMac:String, val deviceName:String, private var mainActivity: MainActivity?) {
    val mySocket: MySocket
    var lastUpdate = MyTimer.getTime()
    var isHasHostGps = false

    init {
        mySocket = MyServerThread(port)
        mySocket.log { s -> mainActivity?.log(s) }
        
        //  launch host
        mySocket.addOnJson { jo, tag ->
            println("socket received $tag")
            
            //  launch
            if(mainActivity != null && tag == HostData.JSON_TAG_LAUNCH) {
                mainActivity?.startActivity(Intent(mainActivity, ActivityHome::class.java))
                mainActivity?.finish()
                mainActivity = null
            }
            
            //  config
            ConfigData.tryReceive(jo, tag, deviceName)
            
            //  start
            StartData.tryReceive(jo, tag)
            
            //  update
            if (tag == HostData.JSON_TAG_UPDATE) {
                lastUpdate = jo["time"].toString().toLong()
                isHasHostGps = jo["isHasGps"].toString().toBoolean()
            }
        }
    
        //  client update host
        thread(name = "socketClientDataSendUpdate") {
            while (mySocket.isOpen) {
                mySocket.write(JSONObject().apply {
                    accumulate("time", MyTimer.getTime())
                    accumulate("isHasGps", MyTimer.isHasGpsTime())
                }, HostData.JSON_TAG_UPDATE)
                
                Thread.sleep(1000)
            }
        }
    }

    companion object {
        var get: ClientData? = null; private set

        fun set(port:Int, hostMac: String, deviceName: String, mainActivity: MainActivity) {
            if(get != null) throw Exception()
            
            get = ClientData(port, hostMac, deviceName, mainActivity)
        }
    }
}