class ClientData private constructor() {

    companion object {
        var get:ClientData = ClientData(); private set

        fun set(){
            get = ClientData()
        }
    }
}