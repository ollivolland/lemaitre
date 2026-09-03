package datas

import MyQueue
import MySocket
import MyTimer
import android.content.Intent
import android.os.SystemClock
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ollivolland.lemaitre.ActivityHome
import com.ollivolland.lemaitre.MainActivity
import datas.StartData.Companion.tryReceiveFileRequest
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class ClientData private constructor(val port: Int, val hostAddress:String, val deviceName:String, private var mainActivity: MainActivity?) {
    var socket: MySocket? = null
    var lastUpdateReceived = MyTimer.getTime()
    var lastUpdateSent = SystemClock.uptimeMillis()
    var isHasHostGps = false
    val queue = MyQueue()
    var threadUpDate: Thread? = null
    private var timeReplacedSocket = 0L


    init {
        println("ClientData init")
        get = this

        replaceSocket()
    }


    fun replaceSocket() {
        Session.log("replaceSocket")
        val myTimeReplaceSocket = SystemClock.uptimeMillis()
        timeReplacedSocket = myTimeReplaceSocket
        socket?.socket?.close()
        socket?.close()
        socket = MySocket(Socket(), "client")
        thread {
            try {
                socket!!.socket.connect(InetSocketAddress(hostAddress, port), 10_000)
                queue.attach(socket!!)
                socket!!.addOnJson { carrier ->
                    //  launch
                    carrier.optJSONObject(HostData.JSON_TAG_LAUNCH)?.also {
                        mainActivity?.startActivity(
                            Intent(
                                mainActivity,
                                ActivityHome::class.java
                            )
                        )
                        mainActivity?.finish()
                        mainActivity = null
                    }

                    //  config, start, file
                    ConfigData.tryReceive(carrier, deviceName)
                    StartData.tryReceive(carrier)
                    tryReceiveFileRequest(carrier)

                    //  update
                    carrier.optJSONObject(HostData.JSON_TAG_UPDATE)?.also { jo ->
                        lastUpdateReceived = MyTimer.getTime()
                        isHasHostGps = jo["isHasGps"].toString().toBoolean()
                    }
                }
                socket!!.myOnFileReceivedListeners.add { fName ->
                    ActivityHome.invalidateFeedback()
                }

                //  client update host
                threadUpDate = thread(name = "socketClientDataSendUpdate") {
                    while (socket?.isWantOpen == true) {
                        socket?.write(JSONObject().apply {
                            accumulate("time", MyTimer.getTime())
                            accumulate("isHasGps", MyTimer.isHasGpsTime())
                        }, HostData.JSON_TAG_UPDATE)
                        lastUpdateSent = SystemClock.uptimeMillis()

                        Thread.sleep(1000)
                    }
                }

                socket!!.setSocketConfigured()
            } catch (e: Exception) {
                Session.logE("client reconnection crashed")
                println(e.printStackTrace())
                FirebaseCrashlytics.getInstance().recordException(e)
                Thread.sleep(1000)

                if(timeReplacedSocket == myTimeReplaceSocket)
                    replaceSocket()
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