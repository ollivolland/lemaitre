package datas

import MyServerThread
import MySocket
import MyTimer
import android.content.Intent
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ollivolland.lemaitre.ActivityHome
import com.ollivolland.lemaitre.MainActivity
import org.json.JSONObject
import kotlin.concurrent.thread

class ClientData private constructor(val port: Int, val hostMac:String, val deviceName:String, private var mainActivity: MainActivity?) {
    var mySocket: MySocket
    var lastUpdate = MyTimer.getTime()
    var isHasHostGps = false

    init {
        mySocket = createSocket()
    }
    
    fun replaceSocket() {
        try {
            mySocket.addOnClose {
                mySocket = createSocket()
            }
            mySocket.close()
        }
        catch (e:Exception) {
            Session.log("reconnection crashed")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
    
    private fun createSocket(): MyServerThread {
        val socket = MyServerThread(port)
        socket.log(Session.Companion::log)
    
        //  launch host
        socket.addOnJson { jo, tag ->
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
                lastUpdate = MyTimer.getTime()
//                lastUpdate = jo["time"].toString().toLong()
                isHasHostGps = jo["isHasGps"].toString().toBoolean()
            }
        }
    
        //  client update host
        thread(name = "socketClientDataSendUpdate") {
            while (mySocket.isWantOpen) {
                mySocket.write(JSONObject().apply {
                    accumulate("time", MyTimer.getTime())
                    accumulate("isHasGps", MyTimer.isHasGpsTime())
                }, HostData.JSON_TAG_UPDATE)
            
                Thread.sleep(1000)
            }
        }
    
        return socket
    }

    companion object {
        var get: ClientData? = null; private set

        fun set(port:Int, hostMac: String, deviceName: String, mainActivity: MainActivity) {
            if(get != null) throw Exception()
            
            get = ClientData(port, hostMac, deviceName, mainActivity)
        }
    }
}