package com.ollivolland.lemaitre2

import MyTimer
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.TextureView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ollivolland.lemaitre.camera.MyCamera2
import com.ollivolland.lemaitre.camera.MyRequestPreview
import com.ollivolland.lemaitre.camera.MyRequestRecord
import datas.StartData
import kotlin.concurrent.thread

class ActivityStart : AppCompatActivity() {
    lateinit var timer:MyTimer
    lateinit var start: StartData
    lateinit var mycamera2: MyCamera2
    lateinit var myRequestRecord: MyRequestRecord

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)

        timer = MyTimer()
        start = startData!!

        //  ui
        val vTexture = findViewById<TextureView>(R.id.start_vTexture)
        val vLog = findViewById<TextView>(R.id.start_tLog)

        vLog.text = "$start\n\n${start.config}"

        //  camera
        if(start.config.isCamera) {
            val requestPreview = MyRequestPreview(vTexture)
            val myRequestRecord = MyRequestRecord()
            mycamera2 = MyCamera2(this)
                .addRequest(requestPreview)
                .open()
            requestPreview.start()
        }

        //  mps
        if(start.config.isCommand) {
            val mps = Array<MediaPlayer>(start.mpIds.size) { i -> MediaPlayer.create(this, start.mpIds[i]) }
            for (x in mps) x.setOnCompletionListener { x.release() }

            thread {
                for (i in mps.indices) {
                    timer.lock(start.mpStarts[i])
                    mps[i].start()
                }
            }
        }

        thread {
            while (isBusy) {
                if(timer.time >= start.timeStamp + start.commandLength + start.videoLength + DURATION_WAIT_AFTER_FINISH) finish()

                Thread.sleep(20)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isBusy = false
        if(this::mycamera2.isInitialized) mycamera2.close()
    }

    companion object {
        const val DURATION_WAIT_AFTER_FINISH = 3000L

        var startData: StartData? = null;private set
        var isBusy = false;private set

        fun launch(context: Context, startData: StartData) {
            if(isBusy) return

            isBusy = true
            this.startData = startData

            startData.isLaunched = true
            context.startActivity(Intent(context, ActivityStart::class.java))
        }
    }
}