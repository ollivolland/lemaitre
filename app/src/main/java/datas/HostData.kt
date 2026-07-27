package datas

import MyQueue
import MySocket
import MyTimer
import android.app.Dialog
import android.content.Context
import android.widget.Spinner
import android.widget.TextView
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ollivolland.lemaitre.ActivityHome
import com.ollivolland.lemaitre.R
import config
import datas.HostData.Companion.JSON_TAG_LAUNCH
import datas.HostData.Companion.JSON_TAG_UPDATE
import datas.StartData.Companion.tryReceiveFileRequest
import org.json.JSONObject
import setString
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class HostData private constructor(val hostName:String, val clients: Array<Client>) {
    val configClients:Array<ConfigData>
    var command:String = COMMAND_CHOICES[0]
    var flavor:Long = FLAVOR_CHOICES[0]
    var delta:Long = DELTA_CHOICES[0]
    var videoLength:Long = DURATION_CHOICES[0]
    var isInit = false


    init {
        //  set configs
        Session.config = ConfigData(hostName, true)
        configClients = Array(clients.size) { i -> ConfigData(clients[i].humanName) }
    }
    
    fun setClientConfig(i:Int, config: ConfigData) {
        synchronized(configClients) {
            configClients[i] = config
            configClients[i].send(clients[i].socket)
        }
    }
    
    fun getClientConfigs(): Array<ConfigData> {
        synchronized(configClients) {
            return configClients.toList().toTypedArray()
        }
    }
    
    fun createDialog(context:Context):Dialog {
        val d = Dialog(context)
        d.setContentView(R.layout.dialog_global)
        
        val vTitle = d.findViewById<TextView>(R.id.global_tTitle)
        val vSpinnerCommand = d.findViewById<Spinner>(R.id.home_sCommand)
        val vSpinnerFlavor = d.findViewById<Spinner>(R.id.home_sFlavor)
        val vSpinnerLength = d.findViewById<Spinner>(R.id.home_sVideoLength)
        val vSpinnerDelta = d.findViewById<Spinner>(R.id.home_sDelta)
        
        vTitle.setString("Kommando")
        vSpinnerCommand.config(COMMAND_DESCRIPTIONS, COMMAND_CHOICES.indexOf(command)) { i -> command = COMMAND_CHOICES[i] }
        vSpinnerFlavor.config(FLAVOR_DESCRIPTIONS, FLAVOR_CHOICES.indexOf(flavor)) { i -> flavor = FLAVOR_CHOICES[i] }
        vSpinnerLength.config(DURATION_DESCRIPTIONS, DURATION_CHOICES.indexOf(videoLength)) { i -> videoLength = DURATION_CHOICES[i] }
        vSpinnerDelta.config(DELTA_DESCRIPTIONS, DELTA_CHOICES.indexOf(delta)) { i -> delta = DELTA_CHOICES[i] }
        
        d.show()
        return d
    }

    companion object {
        const val TAG_REQUEST_FILE = "request-file"
        const val KEY_REQUEST_FILE_PATH = "request-file-path"
        const val JSON_TAG_UPDATE = "update"
        const val JSON_TAG_LAUNCH = "fin"

        const val COMMAND_KURZ = "kKurz"
        const val COMMAND_MITTEL = "kMittel"
        const val COMMAND_LANG = "kLang"
        const val COMMAND_BIEP = "biep"
        val COMMAND_CHOICES = arrayOf(COMMAND_KURZ, COMMAND_MITTEL, COMMAND_LANG, COMMAND_BIEP)
        val COMMAND_DESCRIPTIONS = arrayOf("Wettkampf 1-2s", "Kommando 1.5-3s", "Kommando 2-4s", "Biep")
        val FLAVOR_CHOICES = arrayOf(0L, 10_000L, 15_000L, 20_000L, 30_000L)
        val FLAVOR_DESCRIPTIONS = arrayOf("sofort", "+10s", "+15s", "+20s", "+30s")
        val DURATION_CHOICES = arrayOf(10_000L, 20_000L, 30_000L, 60_000L, 0L)
        val DURATION_DESCRIPTIONS = arrayOf("duration 10s", "duration 20s", "duration 30s", "duration 60s", "null")
        val DELTA_CHOICES = arrayOf(3_000L, 10_000L, 60_000L)
        val DELTA_DESCRIPTIONS = arrayOf("Δ3s", "Δ10s", "Δ60s")

        var get: HostData? = null; private set


        fun set(hostName: String, clients: MutableList<Client>) {
            if(ClientData.get != null) throw Exception()
            
            get = HostData(hostName, clients.toTypedArray())
        }


        lateinit var formationSocket: ServerSocket
    }
}


class Client(
    val ip:String,
    val wifiP2pName:String,
    val port:Int,
    val humanName:String,
    val fingerprint:String,
    val deviceAddress:String,
    var isConnected:Boolean = true,
    var socket: MySocket? = null) {
    var lastUpdate: Long = 0
    var isHasGpsTime = false
    val queue = MyQueue()


    fun create() {
        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(port))

        thread {
            while (true) {
                Session.log("[$port] waiting")
                var prev = socket
                try {
                    socket = MySocket(serverSocket.accept(), "server")
                    thread {
                        prev?.socket?.close()
                        prev?.close()
                        queue.attach(socket!!)
                        socket!!.addOnJson { carrier ->
                            //  update
                            carrier.optJSONObject(JSON_TAG_UPDATE)?.also { jo ->
                                lastUpdate = MyTimer.getTime()
                                isHasGpsTime = jo["isHasGps"].toString().toBoolean()
                            }

                            StartData.tryReceive(carrier)
                            tryReceiveFileRequest(carrier)
                        }
                        socket!!.myOnFileReceivedListeners.add { path ->
                            HostData.get!!.clients.filterNot { it.fingerprint == fingerprint }.forEach { it.socket?.writeFile(File(path).readBytes(), path) }
                            ActivityHome.invalidateFeedback()
                        }
                        socket!!.write(JSONObject(), JSON_TAG_LAUNCH)
                        Session.log("accepted")
                        socket!!.setSocketConfigured()
                    }
                } catch (e: Exception) {
                    Session.log("connection crashed")
                    println(e.printStackTrace())
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        }


        //host update clients
        thread(name = "socketHostDataSendUpdate") {
            while (true) {
                socket?.write(JSONObject().apply {
                    accumulate("time", MyTimer.getTime())
                    accumulate("isHasGps", MyTimer.isHasGpsTime())
                }, JSON_TAG_UPDATE)

                Thread.sleep(1000)
            }
        }
    }
}