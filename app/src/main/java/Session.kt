package com.ollivolland.lemaitre2

import ConfigData
import StartData

class Session {
    companion object {
        var state: SessionState = SessionState.NONE
        var mySocketCommunication: MySocket? = null
        val starts = mutableListOf<StartData>()
        var currentConfig:ConfigData = ConfigData("null")
    }
}
enum class SessionState { NONE, HOST, CLIENT }