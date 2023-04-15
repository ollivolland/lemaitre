import com.ollivolland.lemaitre2.Session

data class StartData(val id:Long, val timeStamp:Long, val commandLength: Long, val videoLength: Long, val mpStarts:Array<Long>) {
    val config: ConfigData = Session.currentConfig.copy()
    var isLaunched = false

    override fun toString(): String {
        return "StartData(id=$id, timestamp=$timeStamp, isLaunched=$isLaunched)"
    }

    companion object {
        private const val DURATION_FERTIG_MS = 500  //  todo    inaccurate

        fun create(timeStamp: Long, command:String, flavor: Long, videoLength: Long):StartData {
            val deltas = mutableListOf(0, flavor)
            when (command) {
                "kKurz" -> deltas.add(deltas.last() + DURATION_FERTIG_MS + Globals.random.nextLong(1000, 2000))
                "kMittel" -> deltas.add(deltas.last() + DURATION_FERTIG_MS + Globals.random.nextLong(2000, 3000))
                "kLang" -> deltas.add(deltas.last() + DURATION_FERTIG_MS + Globals.random.nextLong(3000, 4000))
            }

            return StartData(System.currentTimeMillis(), timeStamp, deltas.last(), videoLength, Array(deltas.size) { i -> timeStamp + deltas[i] })
        }
    }
}