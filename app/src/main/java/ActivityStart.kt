package com.ollivolland.lemaitre2

import Analyzer
import MyTimer
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import datas.StartData
import mycamera2.MyCamera2
import mycamera2.MyReader
import mycamera2.MyRecorder
import kotlin.concurrent.thread

class ActivityStart : AppCompatActivity() {
    lateinit var timer:MyTimer
    lateinit var start: StartData
    lateinit var myCamera2: MyCamera2
    private lateinit var myRecorder: MyRecorder
    private lateinit var analyzer: Analyzer
    private var isCameraStarted = true; private var isCameraStopped = true
    private var isGateStarted = true; private var isGateStopped = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)

        timer = MyTimer()
        start = startData!!

        //  ui
        val vTexture = findViewById<TextureView>(R.id.start_vTexture)
        val vLog = findViewById<TextView>(R.id.start_tLog)
        val vGateLine = findViewById<View>(R.id.start_vGateLine)
        val vStop = findViewById<ImageButton>(R.id.start_bStop)

        vLog.text = "$start\n\n${start.config}"

        //  camera
        if(start.config.isCamera || start.config.isGate) {
            myCamera2 = MyCamera2(this)
            myCamera2.addPreview(vTexture)
        }
        
        //  video
        if(start.config.isCamera) {
            myRecorder = myCamera2.addRecorder(MyRecorder.RecordingProfileBuilder().apply {
                fps = start.config.fps
            })
            isCameraStarted = false
            isCameraStopped = false
        }
        
        //  Gate
        if(start.config.isGate) {
            analyzer = Analyzer(this, myCamera2, timer, start.timeStamp + start.timeToStart)
            vGateLine.visibility = View.VISIBLE
            isGateStarted = false
            isGateStopped = false
        }
        
        //  camera
        if(start.config.isCamera || start.config.isGate) {
            myCamera2.open()
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
                
                //  camera
                if(!isCameraStarted && timer.time >= start.timeStamp + start.timeToStart - DURATION_VIDEO_BEFORE_START) {
                    isCameraStarted = true
                    myRecorder.startRecord()
                    
                    runOnUiThread { vLog.text = "${vLog.text}\n\nvideo started" }
                }
                if(!isCameraStopped && timer.time >= start.timeStamp + start.timeToStart + start.videoLength) {
                    isCameraStopped = true
                    myRecorder.stopRecord()
                    
                    runOnUiThread { vLog.text = "${vLog.text}\n\nvideo stopped" }
                }
                
                //  gate
                if(!isGateStarted && timer.time >= start.timeStamp + start.timeToStart - DURATION_VIDEO_BEFORE_START) {
                    isGateStarted = true
                    analyzer.isWant = true
                    
                    runOnUiThread { vLog.text = "${vLog.text}\n\ngate started" }
                }
                if(!isGateStopped && timer.time >= start.timeStamp + start.timeToStart + start.videoLength) {
                    isGateStopped = true
                    analyzer.isWant = false
                    analyzer.postProcessing()
                    
                    runOnUiThread { vLog.text = "${vLog.text}\n\ngate stopped" }
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