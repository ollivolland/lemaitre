package datas

import Globals
import MyQueue
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri
import com.ollivolland.lemaitre.ActivityHome
import com.ollivolland.lemaitre.R
import format
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date

data class StartData(val id:Long, val time:Long, val timeInitToCommand: Long, val videoLength: Long, val mpStartsBuild:String, val mpIdsBuild:String, val config: ConfigData) {
    val timeOfCommand = time + timeInitToCommand
    val mpStarts:Array<Long> get() = mpStartsBuild.split(",").map { it.toLong() }.toTypedArray()
    val mpIds:Array<Int> get() = mpIdsBuild.split(",").map { it.toInt() }.toTypedArray()
    var commandDelay:Long? = null
    val cameras = mutableListOf<String>()
    var isReceivedAll = false


    fun send(queues: Array<MyQueue>) {
        isReceivedAll = queues.isEmpty()
        val isReceived = BooleanArray(queues.size) { false }
        for (x in queues.indices)
            queues[x].sendJson(serialize(), TAG_START)
            {
                isReceived[x] = true
                if(isReceived.all { it })
                    isReceivedAll = true
                ActivityHome.invalidateFeedback()
            }

        Session.log("sent start $this")
        sendInfo(queues)
    }


    fun sendInfo(queues: Array<MyQueue>) {
        for (x in queues)
            x.sendJson(JSONObject().apply {
                put("id", id)
                put("info", serializeInfo())
            }, TAG_START_INFO)

        Session.log("sent start-info $this")
    }


    fun serialize(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("timeStamp", time)
            put("commandLength", timeInitToCommand)
            put("videoLength", videoLength)
            put("mpStarts", mpStartsBuild)
            put("mps", mpIdsBuild)
            put("info", serializeInfo())
        }
    }


    fun serializeInfo(): JSONObject {
        return JSONObject().apply {
            if(commandDelay != null)
                put("commandDelay", commandDelay)

            put("cameras", JSONArray(cameras))
        }
    }


    fun save() {
        File("${Globals.dirStarts.absolutePath}/$id.json").writeText(serialize().toString())
    }


    override fun toString(): String {
        return "{ id=$id, timestamp=$time }"
    }


    fun receiveInfo(jo: JSONObject?) {
        if(jo == null)
            return

        commandDelay = jo.opt("commandDelay")?.toString()?.toLong() ?: commandDelay

        val arr = jo.optJSONArray("cameras")
        for (i in 0 until (arr?.length() ?: 0))
            if(!cameras.contains(arr!!.optString(i)))
                cameras.add(arr.optString(i))
    }


    fun bindFeed(itemView: View) {
        val vTitle = itemView.findViewById<TextView>(R.id.start_tTitle)
        val vText = itemView.findViewById<TextView>(R.id.start_tText)
        val vButtons = itemView.findViewById<LinearLayout>(R.id.start_buttons)

        vButtons.removeAllViews()

        var desc = "start ${(if(isReceivedAll) "y" else "n")}"
        if(commandDelay != null)
            desc += "\nshot: ${(commandDelay!! * .001).format(2)}s"
        if(cameras.isNotEmpty()) {
            desc += "\n" + cameras.joinToString(", ")

            for (c in cameras) {
                val b = LayoutInflater.from(vButtons.context).inflate(R.layout.view_video, vButtons, false)
                val bb = b.findViewById<ImageButton>(R.id.video_play)
                val file = File("${Globals.dirDCIM}/VID_${Globals.formatToSeconds.format(Date(time))}_${c}.mp4")
                val isFileExists = file.exists()
                if(!isFileExists && c == Globals.deviceFingerprint)
                    continue

                bb.tooltipText = c
                if(isFileExists) {
                    bb.setImageResource(R.drawable.outline_play_48)
                    bb.setOnClickListener {
                        val videoUri = "${Globals.dirDCIM}/VID_${Globals.formatToSeconds.format(Date(time))}_${c}.mp4".toUri()
                        itemView.context.startActivity(Intent(Intent.ACTION_VIEW, videoUri).apply {
                            setDataAndType(videoUri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }
                    vButtons.addView(b)


                    val c = LayoutInflater.from(vButtons.context).inflate(R.layout.view_video, vButtons, false)
                    val cc = c.findViewById<ImageButton>(R.id.video_play)
                    cc.setImageResource(R.drawable.sharp_android_wifi_3_bar_24)
                    cc.setOnClickListener {
                        Session.queue { send(arrayOf(it)) }
                        Session.socket { it.writeFile(file.readBytes(), file.path) }
                        cc.isEnabled = false
                    }
                    vButtons.addView(c)
                }
                else {
                    bb.setImageResource(R.drawable.sharp_restart_alt_24)
                    bb.setOnClickListener {
                        Session.queue { it.sendJson(JSONObject().apply { put(HostData.KEY_REQUEST_FILE_PATH, file.path) }, HostData.TAG_REQUEST_FILE) }
                        bb.isEnabled = false
                    }
                    vButtons.addView(b)
                }
            }
        }

        var title = Globals.FORMAT_TIME.format(time)
        if(Globals.FORMAT_DAY_FILE.format(time) != Globals.FORMAT_DAY_FILE.format(System.currentTimeMillis()))
            title = Globals.FORMAT_DAY_FILE.format(time) + " " + title
        vTitle.text = title
        vText.text = desc
    }


    companion object {
        private const val DURATION_FERTIG_MS:Long = 150     //  duration of "f"
        private const val DURATION_TO_SHOT_MS:Long = 20
        const val TAG_START = "start"
        const val TAG_START_INFO = "start-info"


        fun writeFileFromDCIM(path:String) {
            Session.socket { it.writeFile(File(path).readBytes(), path) }
            ActivityHome.invalidateFeedback()
        }


        fun tryReceiveFileRequest(carrier: JSONObject?) {
            val jo = carrier?.optJSONObject(HostData.TAG_REQUEST_FILE) ?: return

            val path = jo.optString(HostData.KEY_REQUEST_FILE_PATH, "")
            Session.log("received File request for $path")
            if(path == "")
                return

            writeFileFromDCIM(path)
        }


        fun create(timeStamp: Long, command:String, flavor: Long, videoLength: Long): StartData {
            val builder = Mp3Builder()
            
            when (command) {
                HostData.COMMAND_KURZ -> {
                    if(flavor > 0)
                        builder[R.raw.aufdieplaetze] = 0
                    builder[R.raw.fertig, flavor] = DURATION_FERTIG_MS
                    builder[R.raw.shot_700ms, Globals.RANDOM.nextLong(1000, 2000)] = DURATION_TO_SHOT_MS
                }
                HostData.COMMAND_MITTEL -> {
                    if(flavor > 0)
                        builder[R.raw.aufdieplaetze] = 0
                    builder[R.raw.fertig, flavor] = DURATION_FERTIG_MS
                    builder[R.raw.shot_700ms, Globals.RANDOM.nextLong(1500, 3000)] = DURATION_TO_SHOT_MS
                }
                HostData.COMMAND_LANG -> {
                    if(flavor > 0)
                        builder[R.raw.aufdieplaetze] = 0
                    builder[R.raw.fertig, flavor] = DURATION_FERTIG_MS
                    builder[R.raw.shot_700ms, Globals.RANDOM.nextLong(2000, 4000)] = DURATION_TO_SHOT_MS
                }
                HostData.COMMAND_BIEP -> {
                    if(flavor > 0)
                        builder[R.raw.aufdieplaetze] = 0
                    builder[R.raw.beep_middle] = flavor
                }
            }

            val s = StartData(System.currentTimeMillis(), timeStamp, builder.lastEndMs, videoLength, builder.getBuiltDeltas(timeStamp), builder.getBuiltIds(), Session.config)
            s.cameras.addAll(HostData.get!!.clients.indices.filter { HostData.get!!.configClients[it].isCamera }.map { HostData.get!!.clients[it].fingerprint })
            if(Session.config.isCamera)
                s.cameras.add(Globals.deviceFingerprint)
            return s
        }


        fun parse(jo:JSONObject?, configData: ConfigData = ConfigData("", false)): StartData? {
            if(jo == null)
                return null

            val start = StartData(
                jo.getLong("id"),
                jo.getLong("timeStamp"),
                jo.getLong("commandLength"),
                jo.getLong("videoLength"),
                jo["mpStarts"].toString(),
                jo["mps"].toString(), configData
            )
            start.receiveInfo(jo.optJSONObject("info"))
            return start
        }


        fun tryReceive(carrier:JSONObject) {
            var start = parse(carrier.optJSONObject(TAG_START), Session.config.copy()) ?: return

            val startSaved = Session.getStarts().firstOrNull { it.id == start.id }
            if (startSaved == null) {
                Session.addStart(start)
                Session.log("received start $start")
            } else {
                start = startSaved
                Session.log("start already present $start")
            }

            carrier.optJSONObject(TAG_START_INFO)?.also { jo ->
                start.receiveInfo(jo.optJSONObject("info"))
                start.save()
            }

            ActivityHome.invalidateFeedback()
        }
    }
}


internal class Mp3Builder {
    private val deltas = mutableListOf<Long>()
    private val ids = mutableListOf<Int>()
    var lastEndMs = 0L
    
    operator fun set(id:Int, beforeMs:Long) = set(id, beforeMs, 0)
    
    operator fun set(id:Int, beforeMs:Long, afterMS: Long) {
        ids.add(id)
        lastEndMs += beforeMs
        deltas.add(lastEndMs)
        lastEndMs += afterMS
    }
    
    fun getBuiltDeltas(timeStamp: Long):String {
        return deltas.joinToString(",") { delta -> (timeStamp + delta).toString() }
    }
    
    fun getBuiltIds():String {
        return ids.joinToString(",")
    }
}