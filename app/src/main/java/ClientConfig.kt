import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Spinner
import com.google.android.material.switchmaterial.SwitchMaterial
import com.ollivolland.lemaitre2.R

class ClientConfig(var isCommand:Boolean, var isCamera:Boolean, var isGate:Boolean, var fps:Int) {

    fun createRoot(viewGroup: ViewGroup) {
        val root = LayoutInflater.from(viewGroup.context).inflate(R.layout.view_client, viewGroup)

        val vSwitchCommand = root.findViewById<SwitchMaterial>(R.id.client_sCommand)
        val vSwitchCamera = root.findViewById<SwitchMaterial>(R.id.client_sCamera)
        val vSwitchGate = root.findViewById<SwitchMaterial>(R.id.client_sGate)
        val vSpinnerFps = root.findViewById<Spinner>(R.id.client_sFps)

        vSpinnerFps.config(FPS_DESCRITPTIONS) { i -> fps = FPS_CHOICES[i] }
    }

    companion object {
        val FPS_CHOICES = arrayOf(30, 60, 100)
        val FPS_DESCRITPTIONS = arrayOf("30 fps", "60 fps", "100 fps")
    }
}