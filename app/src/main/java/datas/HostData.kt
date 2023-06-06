package datas

import Client
import MyClientThread
import MySocket
import MyTimer
import android.app.Dialog
import android.content.Context
import android.widget.Spinner
import android.widget.TextView
import com.ollivolland.lemaitre.R
import config
import org.json.JSONObject
import setString
import kotlin.concurrent.thread

class HostData private constructor(val hostName:String, val clients: Array<Client>) {
    val mySockets:Array<MySocket> = Array(clients.size) { i -> MyClientThread(clients[i].ipWifiP2p, clients[i].port) }
    val lastUpdate:Array<Long> = Array(clients.size) { 0 }
    val isHasGpsTime:Array<Boolean> = Array(clients.size) { false }
    private val configClients:Array<ConfigData>
    var command:String = COMMAND_CHOICES[0]
    var flavor:Long = FLAVOR_CHOICES[0]
    var delta:Long = DELTA_CHOICES[0]
    var videoLength:Long = DURATION_CHOICES[0]
    var isInit = false

    init {
        //  set configs
        Session.config = ConfigData(hostName, true)
        configClients = Array(clients.size) { i -> ConfigData(clients[i].name) }
        
        //  launch home
        for (x in mySockets) x.write(JSONObject(), JSON_TAG_LAUNCH)
        
        //  reads
        for (i in mySockets.indices)
            mySockets[i].addOnJson { jo, tag ->
                println("socket[$i] received $tag")
                
                //  update
                if (tag == JSON_TAG_UPDATE) {
                    synchronized(lastUpdate) { lastUpdate[i] = jo["time"].toString().toLong() }
                    synchronized(isHasGpsTime) { isHasGpsTime[i] = jo["isHasGps"].toString().toBoolean() }
                }
            }
    
    
        //  host update clients
        thread(name = "socketHostDataSendUpdate") {
            while (mySockets.any { it.isOpen }) {
                for (x in mySockets.filter { it.isOpen })
                    x.write(JSONObject().apply {
                        accumulate("time", MyTimer.getTime())
                        accumulate("isHasGps", MyTimer.isHasGpsTime())
                    }, JSON_TAG_UPDATE)
            
                Thread.sleep(1000)
            }
        }
    }
    
    fun setClientConfig(i:Int, config: ConfigData, onSent:((String)->Unit)? = null) {
        synchronized(configClients) {
            configClients[i] = config
            configClients[i].send(mySockets[i], onSent)
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
        const val JSON_TAG_UPDATE = "update"
        const val JSON_TAG_LAUNCH = "fin"
        
        const val COMMAND_KURZ = "kKurz"
        const val COMMAND_MITTEL = "kMittel"
        const val COMMAND_LANG = "kLang"
        const val COMMAND_BIEP = "biep"
        val COMMAND_CHOICES = arrayOf(COMMAND_KURZ, COMMAND_MITTEL, COMMAND_LANG, COMMAND_BIEP)
        val COMMAND_DESCRIPTIONS = arrayOf("Wettkampf 1-2s", "Kommando 1.5-3s", "Kommando 2-4s", "Biep")
        val FLAVOR_CHOICES = arrayOf(10_000L, 20_000L, 30_000L)
        val FLAVOR_DESCRIPTIONS = arrayOf("flavor 10s", "flavor 20s", "flavor 30s")
        val DURATION_CHOICES = arrayOf(10_000L, 30_000L, 60_000L)
        val DURATION_DESCRIPTIONS = arrayOf("duration 10s", "duration 30s", "duration 60s")
        val DELTA_CHOICES = arrayOf(3_000L, 10_000L, 60_000L)
        val DELTA_DESCRIPTIONS = arrayOf("Δ3s", "Δ10s", "Δ60s")

        var get: HostData? = null; private set

        fun set(hostName: String, clients: MutableList<Client>) {
            if(ClientData.get != null) throw Exception()
            
            get = HostData(hostName, clients.toTypedArray())
        }
    }
}