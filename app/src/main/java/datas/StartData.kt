package datas

import Globals
import MySocket
import com.ollivolland.lemaitre.R
import org.json.JSONObject

data class StartData(val id:Long, val timeInit:Long, val timeInitToCommand: Long, val videoLength: Long, val mpStartsBuild:String, val mpIdsBuild:String) {
    val config: ConfigData = Session.config
    val timeOfCommand = timeInit + timeInitToCommand

    fun send(mySockets: Array<MySocket>) {
        for (x in mySockets)
            x.write(JSONObject().apply {
                accumulate("id", id)
                accumulate("timeStamp", timeInit)
                accumulate("commandLength", timeInitToCommand)
                accumulate("videoLength", videoLength)
                accumulate("mpStarts", mpStartsBuild)
                accumulate("mps", mpIdsBuild)
            }, JSON_TAG)
        
        Session.log("sent start $this")
    }

    val mpStarts:Array<Long> get() = mpStartsBuild.split(",").map { it.toLong() }.toTypedArray()
    val mpIds:Array<Int> get() = mpIdsBuild.split(",").map { it.toInt() }.toTypedArray()

    override fun toString(): String {
        return "{ id=$id, timestamp=$timeInit }"
    }

    companion object {
        private const val DURATION_FERTIG_MS:Long = 150     //  duration of "f"
        private const val DURATION_TO_SHOT_MS:Long = 20
        const val JSON_TAG = "start"

        fun create(timeStamp: Long, command:String, flavor: Long, videoLength: Long): StartData {
            val builder = Mp3Builder()
            
            when (command) {
                HostData.COMMAND_KURZ -> {
                    builder[R.raw.aufdieplaetze_5db] = 0
                    builder[R.raw.fertig_5db, flavor] = DURATION_FERTIG_MS
                    builder[R.raw.shot_700ms_15db, Globals.RANDOM.nextLong(1000, 2000)] = DURATION_TO_SHOT_MS
                }
                HostData.COMMAND_MITTEL -> {
                    builder[R.raw.aufdieplaetze_5db] = 0
                    builder[R.raw.fertig_5db, flavor] = DURATION_FERTIG_MS
                    builder[R.raw.shot_700ms_15db, Globals.RANDOM.nextLong(1500, 3000)] = DURATION_TO_SHOT_MS
                }
                HostData.COMMAND_LANG -> {
                    builder[R.raw.aufdieplaetze_5db] = 0
                    builder[R.raw.fertig_5db, flavor] = DURATION_FERTIG_MS
                    builder[R.raw.shot_700ms_15db, Globals.RANDOM.nextLong(2000, 4000)] = DURATION_TO_SHOT_MS
                }
                HostData.COMMAND_BIEP -> {
                    builder[R.raw.aufdieplaetze_5db] = 0
                    builder[R.raw.beep_5db] = flavor
                }
            }

            return StartData(System.currentTimeMillis(), timeStamp, builder.lastEndMs, videoLength, builder.getBuiltDeltas(timeStamp), builder.getBuiltIds())
        }

        fun tryReceive(jo:JSONObject, tag:String) {
            if(tag != JSON_TAG) return
    
            val start = StartData(
                jo["id"].toString().toLong(),
                jo["timeStamp"].toString().toLong(),
                jo["commandLength"].toString().toLong(),
                jo["videoLength"].toString().toLong(),
                jo["mpStarts"].toString(),
                jo["mps"].toString(),
            )
            Session.addStart(start)
            Session.log("received start $start")
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