import android.app.Dialog
import android.content.Context
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.ollivolland.lemaitre2.R

class ConfigData(val deviceName:String) {
    var fps = FPS_CHOICES[0]
    var isGate = false
    var isCamera = false
    var isCommand = false

    fun createRoot(context: Context):Dialog {
        val d = Dialog(context)
        d.setContentView(R.layout.dialog_config)

        val vTitle = d.findViewById<TextView>(R.id.config_tTitle)
        val vSwitchCommand = d.findViewById<SwitchMaterial>(R.id.client_sCommand)
        val vSwitchCamera = d.findViewById<SwitchMaterial>(R.id.client_sCamera)
        val vSwitchGate = d.findViewById<SwitchMaterial>(R.id.client_sGate)
        val vSpinnerFps = d.findViewById<Spinner>(R.id.client_sFps)


        vTitle.text = deviceName

        vSpinnerFps.config(FPS_DESCRITPTIONS) { i -> fps = FPS_CHOICES[i] }
        vSwitchCommand.setOnCheckedChangeListener { _, isChecked -> isCommand = isChecked }
        vSwitchCamera.setOnCheckedChangeListener { _, isChecked -> isCamera = isChecked }
        vSwitchGate.setOnCheckedChangeListener { _, isChecked -> isGate = isChecked }

        d.show()
        return d
    }

    fun copy():ConfigData {
        return ConfigData(deviceName).also { copy ->
            copy.fps = fps
            copy.isCommand = isCommand
            copy.isCamera = isCamera
            copy.isGate = isGate
        }
    }

    override fun toString(): String {
        return "isCamera = $isCamera, isCommand = $isCommand, isGate = $isGate, fps = $fps"
    }

    companion object {
        val FPS_CHOICES = arrayOf(30, 60, 100)
        val FPS_DESCRITPTIONS = arrayOf("30 fps", "60 fps", "100 fps")
    }
}