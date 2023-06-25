package datas

import GpsTime
import MySocket
import com.ollivolland.lemaitre.MyApp
import org.json.JSONObject

class Session {
    companion object {
        private const val JSON_TAG_FEEDBACK = "feedback"
        
        private var mState: SessionState = SessionState.NONE
        private val mStarts = mutableListOf<StartData>()
        private var mConfig: ConfigData = ConfigData("null")
        var state:SessionState
            set(value) { synchronized(mState) { mState = value } }
            get() { synchronized(mState) { return mState } }
        var config:ConfigData
            set(value) { synchronized(mConfig) { mConfig = value } }
            get() { synchronized(mConfig) { return mConfig.copy() } }
        
        init {
            //  misc
            GpsTime.register(MyApp.appContext)
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
    }
}
enum class SessionState { NONE, HOST, CLIENT }