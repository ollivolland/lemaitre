package datas

import Globals
import MyQueue
import MySocket
import com.ollivolland.lemaitre.ActivityHome
import com.ollivolland.lemaitre.MyApp
import org.json.JSONObject
import java.io.File


class Session {
    companion object {
        private var mState: State = State.NONE
        private val mStarts = mutableListOf<StartData>()
        val hasLaunched = mutableListOf<Long>()
        private val mLogs = mutableListOf<String>()
        private var mConfig: ConfigData = ConfigData("null")
        var config:ConfigData
            set(value) { synchronized(mConfig) { mConfig = value } }
            get() { synchronized(mConfig) { return mConfig.copy() } }
        
        var isHost:Boolean = false;private set
        var isClient:Boolean = false;private set


        init {
            for (s in Globals.dirStarts.listFiles()?.asSequence()?.map { StartData.parse(JSONObject(File(it.path).readText())) }?.mapNotNull { it }?.sortedByDescending { it.time }?.take(10) ?: sequenceOf()) {
                mStarts.add(s)
                hasLaunched.add(s.id)
            }
        }


        fun setState(state: State) {
            synchronized(mState) {
                mState = state
                isHost = state == State.HOST
                isClient = state == State.CLIENT
            }
        }


        fun addStart(data: StartData) { synchronized(mStarts) {
            mStarts.add(0, data)
            data.save()
            ActivityHome.invalidateFeedback()
        } }
        fun getStarts():Array<StartData> { synchronized(mStarts) { return mStarts.toTypedArray() } }


        fun log(string: String, importance: String = "V") {
            println(string)
            synchronized(mLogs) { mLogs.add(string) }

            val time = System.currentTimeMillis()
            File("${Globals.dirLogs.absolutePath}/log-${Globals.FORMAT_DAY_FILE.format(time)}.txt")
                .appendText("\n\n[${Globals.FORMAT_LOGCAT.format(time)} ${MyApp.packageName}:${android.os.Process.myPid()}:${android.os.Process.myTid()} $importance/TAG] \n\t$string")
        }
        fun logE(string: String) = log(string, "E")
        fun getLogs():Array<String> { synchronized(mLogs) { return mLogs.toTypedArray() } }


        fun queue(f:(MyQueue)->Unit) {
            HostData.get?.also { data ->
                data.clients.forEach { f(it.queue) }
            }
            ClientData.get?.also { data ->
                f(data.queue)
            }
        }

        fun socket(f:(MySocket)->Unit) {
            HostData.get?.also { data ->
                data.clients.forEach { if(it.socket != null) f(it.socket!!) }
            }
            ClientData.get?.also { data ->
                if(data.socket != null) f(data.socket!!)
            }
        }
    }
    
    enum class State { NONE, HOST, CLIENT }
}