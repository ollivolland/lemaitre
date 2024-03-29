package datas

import MySocket
import android.app.Dialog
import android.content.Context
import android.view.View
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.ollivolland.lemaitre.R
import config
import org.json.JSONObject

class ConfigData(val deviceName:String, private val isHost:Boolean = false) {
    var fps = FPS_CHOICES[0];private set
    var quality = QUALITY_CHOICES[0];private set
    var isGate = false;private set
    var isCamera = false;private set
    var isCommand = false;private set
    
    init {
    	if (isHost) isCommand = true
    }

    fun dialog(context: Context, onCancel:(ConfigData)->Unit) {
        val copy = copy()
        
        val d = Dialog(context)
        d.setContentView(R.layout.dialog_config)
        val vTitle = d.findViewById<TextView>(R.id.config_tTitle)
        val vSwitchCommand = d.findViewById<SwitchCompat>(R.id.client_sCommand)
        val vSwitchCamera = d.findViewById<SwitchCompat>(R.id.client_sCamera)
        val vSwitchGate = d.findViewById<SwitchCompat>(R.id.client_sGate)
        val vSpinnerFps = d.findViewById<Spinner>(R.id.client_sFps)
        val vSpinnerQuality = d.findViewById<Spinner>(R.id.client_sQuality)

        vTitle.text = deviceName

        vSpinnerFps.config(FPS_DESCRIPTIONS, FPS_CHOICES.indexOf(fps)) { i -> copy.fps = FPS_CHOICES[i] }
        vSpinnerFps.visibility = if(copy.isCamera) View.VISIBLE else View.GONE
    
        vSpinnerQuality.config(QUALITY_DESCRIPTIONS, quality) { i -> copy.quality = i }
        vSpinnerQuality.visibility = if(copy.isCamera) View.VISIBLE else View.GONE
        
        vSwitchCommand.isChecked = copy.isCommand
        if(isHost) vSwitchCommand.isEnabled = false
        vSwitchCommand.setOnCheckedChangeListener { _, isChecked ->
            copy.isCommand = isChecked
        }
    
        vSwitchCamera.isChecked = copy.isCamera
        vSwitchCamera.setOnCheckedChangeListener { _, isChecked ->
            copy.isCamera = isChecked
            
            vSpinnerFps.visibility = if(copy.isCamera) View.VISIBLE else View.GONE
            vSpinnerQuality.visibility = if(copy.isCamera) View.VISIBLE else View.GONE
        }
    
        vSwitchGate.isChecked = copy.isGate
        vSwitchGate.setOnCheckedChangeListener { _, isChecked ->
            copy.isGate = isChecked
            
            if(copy.isGate) {
                vSpinnerFps.setSelection(1)    //  set to 60 fps
                vSpinnerFps.isEnabled = false
            }
            else vSpinnerFps.isEnabled = true
        }

        d.show()
        d.setOnCancelListener { onCancel(copy) }
    }

    fun copy(): ConfigData {
        return ConfigData(deviceName).also { copy ->
            copy.fps = fps
            copy.quality = quality
            copy.isCommand = isCommand
            copy.isCamera = isCamera
            copy.isGate = isGate
        }
    }

    fun send(mySocket: MySocket) {
        mySocket.write(JSONObject().apply {
            accumulate("isCommand", isCommand)
            accumulate("isCamera", isCamera)
            accumulate("isGate", isGate)
            accumulate("fps", fps)
            accumulate("quality", quality)
        }, JSON_TAG)
        Session.log("sent config $this")
    }

    override fun toString(): String {
        return "{ isCamera = $isCamera, isCommand = $isCommand, isGate = $isGate, fps = $fps, quality = $quality }"
    }

    companion object {
        val FPS_CHOICES = arrayOf(30, 60, 100)
        val QUALITY_CHOICES = arrayOf(0, 1, 2)
        val FPS_DESCRIPTIONS = arrayOf("30 fps", "60 fps", "100 fps")
        val QUALITY_DESCRIPTIONS = arrayOf("1080p", "1440p", "2160p")
        const val JSON_TAG = "config"

        fun tryReceive(jo:JSONObject, tag:String, deviceName: String) {
            if(tag != JSON_TAG) return
            
            Session.config = ConfigData(deviceName).apply {
                isCommand = jo["isCommand"] as Boolean
                isCamera = jo["isCamera"] as Boolean
                isGate = jo["isGate"] as Boolean
                fps = jo["fps"] as Int
                quality = jo["quality"] as Int
            }
            Session.log("received config ${Session.config}")
        }
    }
}