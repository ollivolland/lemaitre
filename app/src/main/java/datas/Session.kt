package com.ollivolland.lemaitre2

import datas.ConfigData
import datas.StartData
import org.json.JSONObject

class Session {
    companion object {
        var state: SessionState = SessionState.NONE
        val starts = mutableListOf<StartData>()
        var currentConfig: ConfigData = ConfigData("null")
        
        fun sendFeedback(mySocket: MySocket, string:String) {
            mySocket.write(JSONObject().apply {
                accumulate("key", KEY_FEEDBACK)
                accumulate("msg", string)
            }.toString())
        }
        
        fun tryReceive(string: String, action:(String)->Unit) {
            if(string.contains("\"key\":\"$KEY_FEEDBACK\"")) {
                val jo = JSONObject(string)

                if (jo.has("msg")) action(jo["msg"].toString())
            }
        }
        
        const val KEY_FEEDBACK = "feedback"
    }
}
enum class SessionState { NONE, HOST, CLIENT }