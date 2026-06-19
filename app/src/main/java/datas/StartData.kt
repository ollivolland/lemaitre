package datas

import Globals
import MyQueue
import com.ollivolland.lemaitre.ActivityHome
import com.ollivolland.lemaitre.R
import format
import org.json.JSONObject
import java.io.File

data class StartData(val id:Long, val timeInit:Long, val timeInitToCommand: Long, val videoLength: Long, val mpStartsBuild:String, val mpIdsBuild:String) {
    val config: ConfigData = Session.config
    val timeOfCommand = timeInit + timeInitToCommand
    val mpStarts:Array<Long> get() = mpStartsBuild.split(",").map { it.toLong() }.toTypedArray()
    val mpIds:Array<Int> get() = mpIdsBuild.split(",").map { it.toInt() }.toTypedArray()
    var commandDelay:Long? = null
    var isReceivedAll = false


    fun send(queues: Array<MyQueue>) {
        val isReceived = BooleanArray(queues.size) { false }
        for (x in queues.indices)
            queues[x].sendJson(serialize(), JSON_TAG)
            {
                isReceived[x] = true
                if(isReceived.all { it })
                    isReceivedAll = true
                ActivityHome.invalidateFeedback()
            }

        Session.log("sent start $this")
    }


    fun sendInfo(queues: Array<MyQueue>) {
        for (x in queues)
            x.sendJson(JSONObject().apply {
                accumulate("id", id)
                accumulate("info", serializeInfo())
            }, JSON_TAG_INFO)

        Session.log("sent start-info $this")
    }


    fun serialize(): JSONObject {
        return JSONObject().apply {
            accumulate("id", id)
            accumulate("timeStamp", timeInit)
            accumulate("commandLength", timeInitToCommand)
            accumulate("videoLength", videoLength)
            accumulate("mpStarts", mpStartsBuild)
            accumulate("mps", mpIdsBuild)
            accumulate("info", serializeInfo())
        }
    }


    fun serializeInfo(): JSONObject {
        return JSONObject().apply {
            if(commandDelay != null)
                accumulate("commandDelay", commandDelay)
        }
    }


    fun save() {
        File(Globals.dirStarts.absolutePath + "/$id.json").writeText(serialize().toString())
    }


    override fun toString(): String {
        return "{ id=$id, timestamp=$timeInit }"
    }


    fun feedback():String {
        var s = "[${Globals.FORMAT_TIME.format(timeInit)}] start ${(if(isReceivedAll) "y" else "n")}"
        if(commandDelay != null)
            s += "\nshot: ${(commandDelay!! * .001).format(2)}s"

        return s
    }


    fun receiveInfo(jo: JSONObject?) {
        if(jo == null)
            return

        commandDelay = jo.opt("commandDelay")?.toString()?.toLong()

        save()
    }


    companion object {
        private const val DURATION_FERTIG_MS:Long = 150     //  duration of "f"
        private const val DURATION_TO_SHOT_MS:Long = 20
        const val JSON_TAG = "start"
        const val JSON_TAG_INFO = "start-info"


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

            return StartData(System.currentTimeMillis(), timeStamp, builder.lastEndMs, videoLength, builder.getBuiltDeltas(timeStamp), builder.getBuiltIds())
        }


        fun parse(jo:JSONObject): StartData? {
            val start = StartData(
                jo["id"].toString().toLong(),
                jo["timeStamp"].toString().toLong(),
                jo["commandLength"].toString().toLong(),
                jo["videoLength"].toString().toLong(),
                jo["mpStarts"].toString(),
                jo["mps"].toString(),
            )
            return start
        }


        fun tryReceive(jo:JSONObject, tag:String) {
            if(tag == JSON_TAG) {
                val start = parse(jo)!!
                Session.addStart(start)
                Session.log("received start $start")
            }
            if(tag == JSON_TAG_INFO) {
                val id = jo["id"].toString().toLong()
                val start = Session.getStarts().firstOrNull { it.id == id }
                if(start == null)
                {
                    Session.log("JSON_TAG_INFO ERROR DIDNT FIND ID")
                    return
                }

                start.receiveInfo(jo.optJSONObject("info"))
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