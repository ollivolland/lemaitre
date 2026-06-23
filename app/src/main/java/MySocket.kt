import android.util.Log
import com.ollivolland.lemaitre.MyApp
import datas.HostData.Companion.JSON_TAG_UPDATE
import datas.Session
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

open class MySocket(val socket: Socket, private val type:String) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val myOnSocketListener = mutableListOf<((Socket) -> Unit)?>()
    private val myOnCloseListeners = mutableListOf<(() -> Unit)?>()
    val myOnFileReceivedListeners = mutableListOf<((String) -> Unit)?>()
    private val myOnJSONListeners = mutableListOf<((jo:JSONObject) -> Unit)?>()
    private lateinit var mOutputStream: OutputStream
    private lateinit var mInputStream: InputStream
    private var isInputOpen = true
    private var isSocketConfigured = false
    var isWantOpen = true;protected set
    private var isClosed = false


    init {
        log("[${socket.port}] $type creating")
    }


    private fun receiveFromInputStream() {
        val buffer = ByteArray(1024)
        var length:Int
        var fos: FileOutputStream? = null
        var fileLength = 0L
        var fileName: String? = null
        
        //  read
        while(isWantOpen) {
            try {
                if(mInputStream.available() <= 0) continue
                length = mInputStream.read(buffer)
                if(length < 0) continue

                if(fileLength > 0L) {
                    fos!!.write(buffer, 0, min(fileLength, length.toLong()).toInt())
                    fileLength -= min(fileLength, length.toLong())

                    if(fileLength <= 0)
                    {
                        fos.flush()
                        fos.close()
                        myOnFileReceivedListeners.forEach { it?.invoke(fileName!!) }
                        fos = null
                        fileName = null
                        Session.log("File completed")
                    }
                    continue
                }
    
                //  listeners
                if(myOnJSONListeners.isNotEmpty()) {
                    try {
                        val carrier = JSONObject(String(buffer, 0, length))
                        if(!carrier.has(JSON_TAG_UPDATE))
                            Session.log("[${socket.port}] received JSON ${carrier.keys().asSequence().joinToString(", ")}")

                        carrier.optJSONObject(TAG_FILE)?.also { jo ->
                            fileLength = jo.optLong(TAG_SIZE, 0)
                            fileName = jo.optString(TAG_NAME, "_.txt")
                            val path = Globals.dirAppStorage.absolutePath + "/" + jo.optString(TAG_NAME, "_.txt")
                            Session.log("[${socket.port}] received File $fileLength at $path")
                            File(path).createNewFile()

                            if(path.contains(".mp4"))
                                fos = MyApp.appContext.contentResolver.openOutputStream(createVideoURI(MyApp.appContext, fileName)) as FileOutputStream?
                            else {
                                fos = FileOutputStream(path)
                            }
                        }
            
                        for (x in myOnJSONListeners)
                            try { x?.invoke(carrier) }
                            catch (e:Exception) { e.printStackTrace() }
                    } catch (e:Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e:Exception) {
                Log.e("SOCKET", "exception ${e.stackTrace}")
                e.printStackTrace()
                isWantOpen = false
            }
        }
    
        //  wait for input to close
        while (isInputOpen) Thread.sleep(1)
    }


    fun write(jo:JSONObject, tag:String) {
        if(tag != JSON_TAG_UPDATE)
            Session.log("[${socket.port}] sent JSON $tag")
        write(JSONObject().apply {
            put(tag, jo)
        }.toString().encodeToByteArray())
    }


    fun write(byteArray: ByteArray) {
        if(!isWantOpen) return
        if(!this::mOutputStream.isInitialized) {
            myOnSocketListener.add { write(byteArray) } //  broken
            return
        }

        synchronized(executor) {
            executor.execute {
                try {
                    mOutputStream.write(byteArray)
                    mOutputStream.flush()
                } catch (_: Exception) {
                }
            }
        }
    }


    fun writeFile(byteArray: ByteArray, name: String) {
        write(JSONObject().apply { put(TAG_NAME, name);put(TAG_SIZE, byteArray.size) }, TAG_FILE)
        write(byteArray)
    }

    
    fun setSocketConfigured() {
        if(isSocketConfigured) throw Exception()
        
        isSocketConfigured = true
        mInputStream = socket.getInputStream()
        mOutputStream = socket.getOutputStream()

        log("[${socket.port}] $type created ${socket.localAddress.hostAddress} => ${socket.inetAddress.hostAddress}")
        for (x in myOnSocketListener) x?.invoke(socket)
        
        //  now read
        receiveFromInputStream()

        log("[${socket.port}] $type closed")
        for (x in myOnCloseListeners) x?.invoke()
    }

    fun close() {
        if(isClosed)
            return

        isClosed = true
        isWantOpen = false
        executor.execute { isInputOpen = false }
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

    fun addOnJson(action: (jo:JSONObject) -> Unit):Int {
        myOnJSONListeners.add(action)
        return myOnJSONListeners.lastIndex
    }
    fun removeOnJson(index:Int) { myOnJSONListeners[index] = null }

    companion object {
        val log:((String)->Unit) = { it -> Session.log(it) }
        val TAG_FILE = "@File"
        val TAG_NAME = "@File-name"
        val TAG_SIZE = "@File-size"
    }
}


class MyQueue {
    private var i = 0
    private var socket:MySocket? = null
    private val queue = mutableMapOf<Int, ByteArray>()
    data class Listener(val i: Int, val f:()-> Unit)
    private val listeners = mutableListOf<Listener>()


    fun attach(socket: MySocket) {
        this.socket = socket
        socket.addOnClose { if(this.socket == socket) this.socket = null }
        socket.addOnJson { carrier ->
            carrier.optJSONObject(TAG_QUEUE_SEND)?.also { jo ->
                val received = jo.optInt(TAG_INDEX, -1)
                socket.write(JSONObject().apply { put(TAG_INDEX, received) }, TAG_QUEUE_RECEIVE)
            }
            carrier.optJSONObject(TAG_QUEUE_RECEIVE)?.also { jo ->
                val received = jo.optInt(TAG_INDEX, -1)
                queue.remove(received)
                listeners.filter { it.i == received }.forEach { it.f.invoke() }
                listeners.removeAll { it.i == received }
            }
        }
        resend()
    }


    private fun resend() {
        for ((i, x) in queue) {
            socket?.write(x)
            socket?.write(JSONObject().apply { put(TAG_INDEX, i) }, TAG_QUEUE_SEND)
        }
    }


    fun send(any: ByteArray) {
        queue[i] = any
        socket?.write(any)
        socket?.write(JSONObject().apply { put(TAG_INDEX, i) }, TAG_QUEUE_SEND)
        i++
    }


    fun sendJson(jo:JSONObject, tag:String) {
        send(JSONObject().apply {
            put(tag, jo)
        }.toString().encodeToByteArray())
    }
    fun sendJson(jo: JSONObject, tag: String, f:()-> Unit) {
        listeners.add(Listener(i, f))
        sendJson(jo, tag)
    }


    companion object {
        const val TAG_QUEUE_SEND = "@Queue-send"
        const val TAG_QUEUE_RECEIVE = "@Queue-receive"
        const val TAG_INDEX = "received"
    }
}