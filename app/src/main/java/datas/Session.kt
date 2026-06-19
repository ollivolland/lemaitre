package datas

import Globals
import com.ollivolland.lemaitre.ActivityHome
import org.json.JSONObject
import java.io.File


class Session {
    companion object {
        private var mState: State = State.NONE
        private val mStarts = Globals.dirStarts.listFiles().asSequence().map { StartData.parse(JSONObject(File(it.path).readText())) }.mapNotNull { it }.sortedByDescending { it.time }.take(10).toMutableList()
        private val mLogs = mutableListOf<String>()
        private var mConfig: ConfigData = ConfigData("null")
        var config:ConfigData
            set(value) { synchronized(mConfig) { mConfig = value } }
            get() { synchronized(mConfig) { return mConfig.copy() } }
        
        var isHost:Boolean = false;private set
        var isClient:Boolean = false;private set


        fun setState(state: State) {
            synchronized(mState) {
                mState = state
                isHost = state == State.HOST
                isClient = state == State.CLIENT
            }
        }


        fun addStart(data: StartData) { synchronized(mStarts) {
            mStarts.add(data)
            data.save()
            ActivityHome.invalidateFeedback()
        } }
        fun getStarts():Array<StartData> { synchronized(mStarts) { return mStarts.toTypedArray() } }


        fun log(string: String) {
            println(string)
            synchronized(mLogs) { mLogs.add(string) }
        }
        fun getLogs():Array<String> { synchronized(mLogs) { return mLogs.toTypedArray() } }
    }
    
    enum class State { NONE, HOST, CLIENT }
}