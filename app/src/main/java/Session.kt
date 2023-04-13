package com.ollivolland.lemaitre2

import StartData

class Session {
    companion object {
        var state: SessionState = SessionState.NONE
        var mySocketCommunication: MySocket? = null
        val starts = mutableListOf<StartData>()
    }
}
enum class SessionState { NONE, HOST, CLIENT }