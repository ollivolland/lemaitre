package com.ollivolland.lemaitre

import android.os.Bundle
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import mycamera2.MyCamera2

class ActivityPreview: AppCompatActivity() {
	lateinit var myCamera2: MyCamera2
	
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_preview)
		
		val vTexture = findViewById<TextureView>(R.id.preview_vTexture)
		
		myCamera2 = MyCamera2(this)
		myCamera2.addPreview(vTexture)
		myCamera2.open()
	}
	
	override fun onDestroy() {
		super.onDestroy()
		if(this::myCamera2.isInitialized) myCamera2.close()
	}
}