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
import datas.StartData
import kotlin.concurrent.thread

class ActivityStart : AppCompatActivity() {
    lateinit var timer:MyTimer
    lateinit var start: StartData
    lateinit var myCamera2: MyCamera2
//    lateinit var myRequestRecord: MyRequestRecord
    var isCameraStarted = true
    var isCameraStopped = true

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
            val preview = MyRequestPreview(vTexture)
//            myRequestRecord = MyRequestRecord(preview)
            myCamera2 = MyCamera2(this)
//                .addRequest(myRequestRecord)
                .addRequest(preview)
                .open()

            //  todo set fps

            preview.start()
//            myRequestRecord.start()

            isCameraStarted = false
            isCameraStopped = false
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
                if(timer.time >= start.timeStamp + start.timeToStart + start.videoLength + DURATION_WAIT_AFTER_FINISH) finish()
                if(!isCameraStarted && timer.time >= start.timeStamp + start.timeToStart - DURATION_VIDEO_BEFORE_START) {
                    isCameraStarted = true
//                    myRequestRecord.startRecord()
                }
                if(!isCameraStopped && timer.time >= start.timeStamp + start.timeToStart + start.videoLength) {
                    isCameraStarted = true
//                    myRequestRecord.stopRecord()
                }

                Thread.sleep(20)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isBusy = false
        if(this::myCamera2.isInitialized) myCamera2.close()
    }

    companion object {
        const val DURATION_WAIT_AFTER_FINISH = 3000L
        const val DURATION_VIDEO_BEFORE_START = 3000L

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