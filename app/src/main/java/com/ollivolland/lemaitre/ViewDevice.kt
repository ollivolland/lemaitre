package com.ollivolland.lemaitre

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import datas.ConfigData

class ViewDevice(activity: Activity, viewGroup: ViewGroup) {
	val root: View
	val vTitle: TextView
	val vError: TextView
	val vDesc: TextView
	val vSettings: ImageButton
	
	init {
		root = activity.layoutInflater.inflate(R.layout.view_device, viewGroup, false)
		vTitle = root.findViewById(R.id.device_tTitle)
		vError = root.findViewById(R.id.device_tError)
		vDesc = root.findViewById(R.id.device_tDesc)
		vSettings = root.findViewById(R.id.device_bSettings)
		viewGroup.addView(root)
	}
	
	fun initView(configData: ConfigData, desc:String) {
		vTitle.text = configData.deviceName
		updateView(configData, desc)
	}
	
	fun updateView(configData: ConfigData, desc:String, error:String = "") {
		val has = mutableListOf<String>()
		if(configData.isCommand) has.add("command")
		if(configData.isCamera) has.add("camera")
		if(configData.isGate) has.add("gate")
		val status = if(has.isEmpty()) "useless" else has.joinToString("&")
		
		vError.text = error
		vDesc.text = "$desc   $status"
	}
}