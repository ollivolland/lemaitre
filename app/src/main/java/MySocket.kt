package com.ollivolland.lemaitre2

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

abstract class MySocket(val port: Int, private val type:String) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val myOnSocketListener = mutableListOf<((Socket) -> Unit)?>()
    private val myOnCloseListeners = mutableListOf<(() -> Unit)?>()
    protected val myOnReadListeners = mutableListOf<((String) -> Unit)?>()
    private lateinit var mOutputStream: OutputStream
    protected lateinit var mInputStream: InputStream
    protected var isInputOpen = true
    protected lateinit var socket: Socket
    private var isSocketConfigured = false
    var isOpen = true;protected set
    
    init {
    	println("[$port] $type creating")
    }

    fun log(f:(String)->Unit) {
        f("[$port] $type creating")
        myOnSocketListener.add { socket -> f("[$port] $type created ${socket.localAddress.hostAddress} => ${socket.inetAddress.hostAddress}") }
        myOnCloseListeners.add { f("[$port] $type closed") }
    }

    fun write(s:String) = write(s.encodeToByteArray())
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
    
    protected fun myClose() {
        socket.close()
        
        println("[$port] $type closed")
        for (x in myOnCloseListeners) x?.invoke()
    }
    
    protected fun mySocket() {
        if(isSocketConfigured) throw Exception()
        
        isSocketConfigured = true
        mInputStream = socket.getInputStream()
        mOutputStream = socket.getOutputStream()
        
        println("[$port] $type created ${socket.localAddress.hostAddress} => ${socket.inetAddress.hostAddress}")
        for (x in myOnSocketListener) x?.invoke(socket)
    }

    fun close() {
        executor.execute { isInputOpen = false }
        isOpen = false
    }

    fun addOnConfigured(action:(Socket) -> Unit) {
        if(isSocketConfigured) action(socket)
        myOnSocketListener.add(action)
    }
    
    fun addOnCloseIndex(action:() -> Unit):Int {
        myOnCloseListeners.add(action)
        return myOnCloseListeners.lastIndex
    }
    fun addOnClose(action:() -> Unit) = myOnCloseListeners.add(action)
    
    fun removeOnClose(i: Int) {
        myOnCloseListeners[i] = null
    }

    fun addOnReadIndex(action:(String) -> Unit):Int {
        myOnReadListeners.add(action)
        return myOnReadListeners.lastIndex
    }
    fun addOnRead(action:(String) -> Unit) = myOnReadListeners.add(action)
    fun removeOnRead(i: Int) {
        myOnReadListeners[i] = null
    }
}

class MyClientThread(private val inetAddress: String, port: Int): MySocket(port, "client") {
    init {
        socket = Socket()
        
        thread {
            socket.connect(InetSocketAddress(inetAddress, port), 3000)
            mySocket()

            val buffer = ByteArray(1024)
            var length:Int
            while(isOpen) {
                try {
                    length = mInputStream.read(buffer)

                    if (length > 0) {
                        val s = String(buffer, 0, length)
    
                        for (x in myOnReadListeners)
                            try { x?.invoke(s) }
                            catch (e:Exception) { e.printStackTrace() }
                    }
                } catch (e:Exception) {
                    e.printStackTrace()
                    isOpen = false
                }
            }

            while (isInputOpen) Thread.sleep(1)
//            Thread.sleep(100)

            myClose()
        }
    }
}

class MyServerThread(port:Int): MySocket(port, "server") {
    private var serverSocket: ServerSocket = ServerSocket(port)

    init {
        thread {
            socket  = serverSocket.accept()
            mySocket()

            val buffer = ByteArray(1024)
            var length:Int
            while(isOpen) {
                try {
                    length = mInputStream.read(buffer)

                    if (length > 0) {
                        val s = String(buffer, 0, length)

                        for (x in myOnReadListeners)
                            try { x?.invoke(s) }
                            catch (e:Exception) { e.printStackTrace() }
                    }
                } catch (e:Exception) {
                    e.printStackTrace()
                    isOpen = false
                }
            }

            while (isInputOpen) Thread.sleep(1)

            serverSocket.close()
            myClose()
        }
    }
}