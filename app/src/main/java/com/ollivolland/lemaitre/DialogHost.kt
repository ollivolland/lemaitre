package com.ollivolland.lemaitre

import android.app.Dialog
import android.net.wifi.p2p.WifiP2pDevice
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

class DialogHost(private val context: MainActivity) : Dialog(context) {
    private lateinit var vList: LinearLayout
    init {
        setContentView(R.layout.dialog_host)

        vList = findViewById<LinearLayout>(R.id.host_vList)
    }

    var onDevice:((WifiP2pDevice) -> Unit)? = null

    fun peers(list:Collection<WifiP2pDevice>) {
        vList.removeAllViews()
        for (x in list) {
            val root = context.layoutInflater.inflate(R.layout.view_device, vList, false)
            val vTitle = root.findViewById<TextView>(R.id.device_tTitle)
            val vSettings = root.findViewById<ImageButton>(R.id.device_bSettings)
            vSettings.visibility = View.GONE
            vTitle.text = x.deviceName
            vList.addView(root)
            root.setOnClickListener { onDevice?.invoke(x) }
        }
    }
}