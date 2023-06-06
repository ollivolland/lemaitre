import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*
import kotlin.concurrent.thread

abstract class MySocket(val port: Int, private val type:String) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val myOnSocketListener = mutableListOf<((Socket) -> Unit)?>()
    private val myOnCloseListeners = mutableListOf<(() -> Unit)?>()
    private val myOnJSONListeners = mutableListOf<((jo:JSONObject, tag:String) -> Unit)?>()
    private lateinit var mOutputStream: OutputStream
    private lateinit var mInputStream: InputStream
    private var isInputOpen = true
    protected lateinit var socket: Socket
    private var isSocketConfigured = false
    var isOpen = true;protected set
    
    init {
    	println("[$port] $type creating")
    }
    
    private fun receiveFromInputStream() {
        val buffer = ByteArray(1024)
        var length:Int
        
        //  read
        while(isOpen) {
            try {
                length = mInputStream.read(buffer)
                if(length < 0) continue
    
                //  listeners
                if(myOnJSONListeners.isNotEmpty()) {
                    try {
                        val jo = JSONObject(String(buffer, 0, length))
                        val tag = jo["tag"].toString()
            
                        for (x in myOnJSONListeners)
                            try { x?.invoke(jo, tag) }
                            catch (e:Exception) { e.printStackTrace() }
                    } catch (_:Exception) { }
                }
            } catch (e:Exception) {
                e.printStackTrace()
                isOpen = false
            }
        }
    
        //  wait for input to close
        while (isInputOpen) Thread.sleep(1)
    }

    fun log(f:(String)->Unit) {
        f("[$port] $type creating")
        myOnSocketListener.add { socket -> f("[$port] $type created ${socket.localAddress.hostAddress} => ${socket.inetAddress.hostAddress}") }
        myOnCloseListeners.add { f("[$port] $type closed") }
    }

    fun write(jo:JSONObject, tag:String) = write(jo.apply {
        accumulate("tag", tag)
    }.toString().encodeToByteArray())
    private fun write(byteArray: ByteArray) {
        if(!isOpen) return
        if(!this::mOutputStream.isInitialized) {
            myOnSocketListener.add { write(byteArray) } //  broken
            return
        }

        executor.execute {
            try {
                mOutputStream.write(byteArray)
            } catch (_:Exception) {
                isOpen = false
            }
        }
    }
    
    protected fun finish() {
        socket.close()
        
        println("[$port] $type closed")
        for (x in myOnCloseListeners) x?.invoke()
    }
    
    protected fun setSocketConfigured() {
        if(isSocketConfigured) throw Exception()
        
        isSocketConfigured = true
        mInputStream = socket.getInputStream()
        mOutputStream = socket.getOutputStream()
        
        println("[$port] $type created ${socket.localAddress.hostAddress} => ${socket.inetAddress.hostAddress}")
        for (x in myOnSocketListener) x?.invoke(socket)
        
        //  now read
        receiveFromInputStream()
    }

    fun close() {
        executor.execute { isInputOpen = false }
        isOpen = false
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
}

class MyClientThread(private val inetAddress: String, port: Int): MySocket(port, "client") {
    init {
        socket = Socket()
        
        thread {
            socket.connect(InetSocketAddress(inetAddress, port), 3000)
    
            setSocketConfigured()

//          Thread.sleep(100)
            finish()
        }
    }
}

class MyServerThread(port:Int): MySocket(port, "server") {
    private var serverSocket: ServerSocket = ServerSocket(port)

    init {
        thread {
            socket  = serverSocket.accept()
    
            setSocketConfigured()

            serverSocket.close()
            finish()
        }
    }
}