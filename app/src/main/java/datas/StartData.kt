package datas

import Globals
import com.ollivolland.lemaitre2.MySocket
import com.ollivolland.lemaitre2.R
import com.ollivolland.lemaitre2.Session
import org.json.JSONObject

data class StartData(val id:Long, val timeStamp:Long, val timeToStart: Long, val videoLength: Long, val mpStartsBuild:String, val mpIdsBuild:String) {
    val config: ConfigData = Session.currentConfig.copy()
    var isLaunched = false

    fun send(mySocket: MySocket) {
        mySocket.write(JSONObject().apply {
            accumulate("id", id)
            accumulate("timeStamp", timeStamp)
            accumulate("commandLength", timeToStart)
            accumulate("videoLength", videoLength)
            accumulate("mpStarts", mpStartsBuild)
            accumulate("mps", mpIdsBuild)
        }.toString())
    }

    val mpStarts:Array<Long> get() = mpStartsBuild.split(",").map { it.toLong() }.toTypedArray()
    val mpIds:Array<Int> get() = mpIdsBuild.split(",").map { it.toInt() }.toTypedArray()

    override fun toString(): String {
        return "id=$id, timestamp=$timeStamp, isLaunched=$isLaunched"
    }

    companion object {
        private const val DURATION_FERTIG_MS = 500  //  todo    inaccurate

        fun create(timeStamp: Long, command:String, flavor: Long, videoLength: Long): StartData {
            val deltas = mutableListOf(0, flavor)
            when (command) {
                "kKurz" -> deltas.add(deltas.last() + DURATION_FERTIG_MS + Globals.random.nextLong(1000, 2000))
                "kMittel" -> deltas.add(deltas.last() + DURATION_FERTIG_MS + Globals.random.nextLong(2000, 3000))
                "kLang" -> deltas.add(deltas.last() + DURATION_FERTIG_MS + Globals.random.nextLong(3000, 4000))
            }

            val mps = arrayOf(R.raw.aufdieplaetze, R.raw.fertig, R.raw.gunshot_10db_1s_delayed)

            return StartData(System.currentTimeMillis(), timeStamp, deltas.last(), videoLength,
                Array(deltas.size) { i -> timeStamp + deltas[i] }.joinToString(","),
                mps.joinToString(","))
        }

        fun tryReceive(s:String, action:(StartData)->Unit) {
            val jo = JSONObject(s)
            if(jo.has("id")
                && jo.has("timeStamp")
                && jo.has("commandLength")
                && jo.has("videoLength")
                && jo.has("mpStarts")
                && jo.has("mps"))
            {
                action(
                    StartData(
                        jo["id"].toString().toLong(),
                        jo["timeStamp"].toString().toLong(),
                        jo["commandLength"].toString().toLong(),
                        jo["videoLength"].toString().toLong(),
                        jo["mpStarts"].toString(),
                        jo["mps"].toString(),
                    )
                )
            }
        }
    }
}