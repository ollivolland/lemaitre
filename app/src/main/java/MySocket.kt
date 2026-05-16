import android.util.Log
import datas.HostData.Companion.JSON_TAG_UPDATE
import datas.Session
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

abstract class MySocket(val socket: Socket, val port: Int, private val type:String) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val myOnSocketListener = mutableListOf<((Socket) -> Unit)?>()
    private val myOnCloseListeners = mutableListOf<(() -> Unit)?>()
    private val myOnJSONListeners = mutableListOf<((jo:JSONObject, tag:String) -> Unit)?>()
    private lateinit var mOutputStream: OutputStream
    private lateinit var mInputStream: InputStream
    private var isInputOpen = true
    private var isSocketConfigured = false
    var isWantOpen = true;protected set
    private var isClosed = false


    init {
        log("[$port] $type creating")
    }


    private fun receiveFromInputStream() {
        val buffer = ByteArray(1024)
        var length:Int
        
        //  read
        while(isWantOpen) {
            try {
                length = mInputStream.read(buffer)
                if(length < 0) continue
    
                //  listeners
                if(myOnJSONListeners.isNotEmpty()) {
                    try {
                        val jo = JSONObject(String(buffer, 0, length))
                        val tag = jo["tag"].toString()
                        if(tag != JSON_TAG_UPDATE)
                            Session.log("[$port] received JSON $tag")
            
                        for (x in myOnJSONListeners)
                            try { x?.invoke(jo, tag) }
                            catch (e:Exception) { e.printStackTrace() }
                    } catch (_:Exception) { }
                }
            } catch (e:Exception) {
                Log.e("SOCKET", "exception ${e.stackTrace}")
                isWantOpen = false
            }
        }
    
        //  wait for input to close
        while (isInputOpen) Thread.sleep(1)
    }


    fun write(jo:JSONObject, tag:String) {
        if(tag != JSON_TAG_UPDATE)
            Session.log("[$port] sent JSON $tag")
        write(jo.apply {
            accumulate("tag", tag)
        }.toString().encodeToByteArray())
    }


    private fun write(byteArray: ByteArray) {
        if(!isWantOpen) return
        if(!this::mOutputStream.isInitialized) {
            myOnSocketListener.add { write(byteArray) } //  broken
            return
        }

        executor.execute {
            try {
                mOutputStream.write(byteArray)
            } catch (_:Exception) {
            }
        }
    }
    
    fun setSocketConfigured() {
        if(isSocketConfigured) throw Exception()
        
        isSocketConfigured = true
        mInputStream = socket.getInputStream()
        mOutputStream = socket.getOutputStream()

        log("[$port] $type created ${socket.localAddress.hostAddress} => ${socket.inetAddress.hostAddress}")
        for (x in myOnSocketListener) x?.invoke(socket)
        
        //  now read
        receiveFromInputStream()
    }

    fun close() {
        if(isClosed)
            return

        isClosed = true
        executor.execute { isInputOpen = false }
        isWantOpen = false

        log("[$port] $type closed")
        for (x in myOnCloseListeners) x?.invoke()
    }

    fun addOnConfigured(action:(Socket) -> Unit) {
        if(isSocketConfigured) action(socket)
        myOnSocketListener.add(action)
    }
    
    fun addOnClose(action:() -> Unit):Int {
        myOnCloseListeners.add(action)
        return myOnCloseListeners.lastIndex
    }
    fun removeOnClose(i: Int) { myOnCloseListeners[i] = null }

    fun addOnJson(action: (jo:JSONObject, tag:String) -> Unit):Int {
        myOnJSONListeners.add(action)
        return myOnJSONListeners.lastIndex
    }
    fun removeOnJson(index:Int) { myOnJSONListeners[index] = null }

    companion object {
        val log:((String)->Unit) = { it -> Session.log(it) }
    }
}

class MyClientThread(socket: Socket, private val inetAddress: String, port: Int): MySocket(socket, port, "client") {
    init {
    }
}

class MyServerThread(serverSocket: Socket, port:Int): MySocket(serverSocket, port, "server") {
    init {
    }
}