package com.ollivolland.lemaitre2

import Analyzer
import MyTimer
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
import mycamera2.MyRecorder
import kotlin.concurrent.thread

class ActivityStart : AppCompatActivity() {
    lateinit var timer:MyTimer
    lateinit var start: StartData
    lateinit var myCamera2: MyCamera2
    private lateinit var myRecorder: MyRecorder
    private lateinit var analyzer: Analyzer
    private val mps = mutableListOf<MediaPlayer>()
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

        vLog.text = "$start\n${start.config}"

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
            analyzer.onStreakStartedListeners.add {
                val msg = "gate: ${it/1000}.${String.format("%03d", it%1000)} s"
                runOnUiThread { vLog.text = "${vLog.text}\n$msg" }
//                sendFeedback?.invoke(msg, true)
            }
            analyzer.onTriangulatedListeners.add{ tri, fra ->
                val msg = "gate: ${tri/1000}.${String.format("%03d", tri%1000)}s   (~${fra/1000}.${String.format("%03d", fra%1000)}s)"
                sendFeedback?.invoke(msg, true)
            }
            
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
            mps.addAll(Array(start.mpIds.size) { i -> MediaPlayer.create(this, start.mpIds[i]) })
            mps.add(MediaPlayer.create(this, R.raw.whitenoise_point_001db)) //  needs to play non-silent audio for box

//            thread {
            for (i in start.mpIds.indices) {
                thread {
                    timer.lock(start.mpStarts[i])
                    if(!this.isDestroyed) mps[i].start()
                }
            }
            
            mps.last().isLooping = true
            mps.last().start()
        }

        thread {
            while (isBusy) {
                if(timer.time >= start.timeStamp + start.timeToStart + start.videoLength + DURATION_WAIT_AFTER_FINISH) finish()
                
                //  camera
                if(!isCameraStarted && timer.time >= start.timeStamp + start.timeToStart - DURATION_VIDEO_BEFORE_START) {
                    isCameraStarted = true
                    myRecorder.startRecord()
                    
                    runOnUiThread { vLog.text = "${vLog.text}\nvideo started" }
                }
                if(!isCameraStopped && timer.time >= start.timeStamp + start.timeToStart + start.videoLength) {
                    isCameraStopped = true
                    myRecorder.stopRecord()
                    
                    runOnUiThread { vLog.text = "${vLog.text}\nvideo stopped" }
                }
                
                //  gate
                if(!isGateStarted && timer.time >= start.timeStamp + start.timeToStart) {
                    isGateStarted = true
                    analyzer.isWant = true
                    
                    runOnUiThread { vLog.text = "${vLog.text}\ngate started" }
                }
                if(!isGateStopped && timer.time >= start.timeStamp + start.timeToStart + start.videoLength) {
                    isGateStopped = true
                    analyzer.isWant = false
                    analyzer.postProcessing()
                    
                    runOnUiThread { vLog.text = "${vLog.text}\ngate stopped" }
                }

                Thread.sleep(20)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isBusy = false
        if(this::myCamera2.isInitialized) myCamera2.close()
        mps.forEach { it.release() }
    }

    companion object {
        const val DURATION_WAIT_AFTER_FINISH = 3000L
        const val DURATION_VIDEO_BEFORE_START = 3000L

        var startData: StartData? = null;private set
        var sendFeedback:((String, Boolean)->Unit)? = null;private set
        var isBusy = false;private set

        fun launch(activityHome: ActivityHome, startData: StartData) {
            if(isBusy) return

            isBusy = true
            this.startData = startData
            this.sendFeedback = { s,b -> activityHome.receiveFeedback(s,b) }

            activityHome.startActivity(Intent(activityHome, ActivityStart::class.java))
        }
    }
}