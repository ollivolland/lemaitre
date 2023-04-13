import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import com.ollivolland.lemaitre2.*

class HostData private constructor(clients: MutableList<Client>) {
    val mySockets:Array<MySocket>
    var command:String = COMMAND_CHOICES[0]
    var flavor:Long = FLAVOR_CHOICES[0]
    var delta:Long = DELTA_CHOICES[0]
    var videoLength:Long = DURATION_CHOICES[0]

    companion object {
        val COMMAND_CHOICES = arrayOf("kKurz", "kLang", "kMittel", "biep")
        val COMMAND_DESCRIPTIONS = arrayOf("Wettkampf 1-2s", "Wettkampf 2-3s", "Wettkampf 3-4s", "Biep")
        val FLAVOR_CHOICES = arrayOf(10_000L, 20_000L, 30_000L)
        val FLAVOR_DESCRIPTIONS = arrayOf("10s", "20s", "30s")
        val DURATION_CHOICES = arrayOf(10_000L, 30_000L, 60_000L)
        val DURATION_DESCRIPTIONS = arrayOf("10s", "30s", "60s")
        val DELTA_CHOICES = arrayOf(3_000L, 10_000L, 30_000L)
        val DELTA_DESCRIPTIONS = arrayOf("3s", "10s", "30s")

        var get:HostData = HostData(mutableListOf()); private set

        fun set(clients: MutableList<Client>){
            get = HostData(clients)
        }

        fun createRoot(viewGroup: ViewGroup) {
            val root = LayoutInflater.from(viewGroup.context).inflate(R.layout.view_global, viewGroup)

            val vSpinnerCommand = root.findViewById<Spinner>(R.id.home_sCommand)
            val vSpinnerFlavor = root.findViewById<Spinner>(R.id.home_sFlavor)
            val vSpinnerLength = root.findViewById<Spinner>(R.id.home_sVideoLength)
            val vSpinnerDelta = root.findViewById<Spinner>(R.id.home_sDelta)
            val vStart = root.findViewById<Button>(R.id.home_bStart)
            val vSchedule = root.findViewById<Button>(R.id.home_bSchedule)

            vSpinnerCommand.config(COMMAND_DESCRIPTIONS) { i -> get.command = COMMAND_CHOICES[i] }
            vSpinnerFlavor.config(FLAVOR_DESCRIPTIONS) { i -> get.flavor = FLAVOR_CHOICES[i] }
            vSpinnerLength.config(DURATION_DESCRIPTIONS) { i -> get.videoLength = DURATION_CHOICES[i] }
            vSpinnerDelta.config(DELTA_DESCRIPTIONS) { i -> get.delta = DELTA_CHOICES[i] }

            vStart.setOnClickListener {
                val start = StartData.create(MyTimer().time + get.delta, get.command, get.flavor, get.videoLength)
                Session.starts.add(start)
            }
        }
    }

    init {
        mySockets = Array(clients.size) { i -> MyClientThread(clients[i].ipWifiP2p, clients[i].port) }
        for (x in mySockets) x.write("fin")
    }
}