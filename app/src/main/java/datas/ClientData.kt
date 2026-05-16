package datas

import MyClientThread
import MyTimer
import android.content.Intent
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ollivolland.lemaitre.ActivityHome
import com.ollivolland.lemaitre.MainActivity
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class ClientData private constructor(val port: Int, val hostAddress:String, val deviceName:String, private var mainActivity: MainActivity?) {
    var socket: MyClientThread? = null
    var lastUpdate = MyTimer.getTime()
    var isHasHostGps = false

    init {
        println("ClientData init")
        get = this

        replaceSocket()
    }
    
    fun replaceSocket() {
        Session.log("replaceSocket")
        socket?.socket?.close()
        socket?.close()
        socket = MyClientThread(Socket(), hostAddress, port)
        thread {
            Thread.sleep(1000)
            try {
                socket!!.socket.connect(InetSocketAddress(hostAddress, port), 60_000)
                socket!!.addOnJson { jo, tag ->
                    Session.tryReceiveFeedback(jo, tag, ActivityHome::showFeedback)
                }
                socket!!.addOnJson { jo, tag ->
                    println("socket received $tag")

                    //  launch
                    if (mainActivity != null && tag == HostData.JSON_TAG_LAUNCH) {
                        mainActivity?.startActivity(
                            Intent(
                                mainActivity,
                                ActivityHome::class.java
                            )
                        )
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
                    while (socket?.isWantOpen == true) {
                        socket?.write(JSONObject().apply {
                            accumulate("time", MyTimer.getTime())
                            accumulate("isHasGps", MyTimer.isHasGpsTime())
                        }, HostData.JSON_TAG_UPDATE)

                        Thread.sleep(1000)
                    }
                }

                socket!!.setSocketConfigured()
            } catch (e: Exception) {
                Session.log("reconnection crashed")
                println(e.printStackTrace())
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    companion object {
        var get: ClientData? = null; private set

        fun set(port:Int, hostMac: String, deviceName: String, mainActivity: MainActivity) {
            if(get != null) throw Exception()
            
            ClientData(port, hostMac, deviceName, mainActivity)
        }
    }
}