package datas

import MySocket
import org.json.JSONObject

class Session {
    companion object {
        private const val JSON_TAG_FEEDBACK = "feedback"
        
        private var mState: State = State.NONE
        private val mStarts = mutableListOf<StartData>()
        private val mLogs = mutableListOf<String>()
        private var mConfig: ConfigData = ConfigData("null")
        var config:ConfigData
            set(value) { synchronized(mConfig) { mConfig = value } }
            get() { synchronized(mConfig) { return mConfig.copy() } }
        
        var isHost:Boolean = false;private set
        var isClient:Boolean = false;private set
        
        fun setState(state: State) {
            synchronized(mState) {
                if(mState != State.NONE) throw Exception()
                
                mState = state
                isHost = state == State.HOST
                isClient = state == State.CLIENT
            }
        }
        
        fun sendFeedback(mySocket: MySocket, string:String) {
            mySocket.write(JSONObject().apply {
                accumulate("msg", string)
            }, JSON_TAG_FEEDBACK)
        }
        
        fun tryReceiveFeedback(jo:JSONObject, tag:String, action:(String)->Unit) {
            if(tag != JSON_TAG_FEEDBACK) return
            
            action(jo["msg"].toString())
        }
        
        fun addStart(data: StartData) { synchronized(mStarts) { mStarts.add(data) } }
        fun getStarts():Array<StartData> { synchronized(mStarts) { return mStarts.toTypedArray() } }
        
        fun log(string: String) {
            println(string)
            synchronized(mLogs) { mLogs.add(string) }
        }
        fun getLogs():Array<String> { synchronized(mLogs) { return mLogs.toTypedArray() } }
    }
    
    enum class State { NONE, HOST, CLIENT }
}