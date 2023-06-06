package com.ollivolland.lemaitre

import Analyzer
import MyTimer
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import datas.Session
import datas.SessionState
import datas.StartData
import mycamera2.MyCamera2
import mycamera2.MyRecorder
import kotlin.concurrent.thread
import kotlin.math.abs

class ActivityStart : AppCompatActivity() {
    lateinit var timer:MyTimer
    lateinit var start: StartData
    lateinit var myCamera2: MyCamera2
    private lateinit var myRecorder: MyRecorder
    private lateinit var analyzer: Analyzer
    private val mps = mutableListOf<MediaPlayer>()
    private var isCameraStarted = true; private var isCameraStopped = true
    private var isGateStarted = true; private var isGateStopped = true
    private var isGateMpReady = true
    private lateinit var gateMp:MediaPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        timer = MyTimer()
        start = startData!!

        //  ui
        val vTexture = findViewById<TextureView>(R.id.start_vTexture)
        val vLog = findViewById<TextView>(R.id.start_tLog)
        val vGateLine = findViewById<View>(R.id.start_vGateLine)
        val vStop = findViewById<ImageButton>(R.id.start_bStop)

        vLog.text = "$start\n${start.config}"
        
        vStop.setOnClickListener {
            finish()
        }

        //  camera
        if(start.config.isCamera || start.config.isGate) {
            myCamera2 = MyCamera2(this)
            myCamera2.addPreview(vTexture)
        }
        
        //  video
        if(start.config.isCamera) {
            myRecorder = myCamera2.addRecorder(MyRecorder.RecordingProfileBuilder().apply {
                fps = start.config.fps
                bytesPerSecond = 5_000_000
            })
            isCameraStarted = false
            isCameraStopped = false
        }
        
        //  Gate
        if(start.config.isGate) {
            analyzer = Analyzer(this, myCamera2, timer, start.timeOfInit + start.timeToCommand)
            analyzer.onStreakStartedListeners.add {
                val msg = "gate: ${it/1000}.${String.format("%03d", it%1000)} s"
                runOnUiThread { vLog.text = "${vLog.text}\n$msg" }
                
                if(isGateMpReady) {
                    isGateMpReady = false
                    gateMp.start()
                }
            }
            analyzer.onTriangulatedListeners.add { triangleMs, frameMs, deltas ->
                val gate = if(triangleMs == 0L) "invalid" else "${String.format("%.3f", triangleMs * 0.001)}s"
                val msg = "gate: $gate   (${String.format("%.2f", frameMs * 0.001)}Δ${if(deltas<0) "-" else "+"}${String.format("%.1f", abs(deltas))})"
                
                showFeedback?.invoke(msg)
                broadcastFeedback?.invoke(msg)
            }
            
            vGateLine.visibility = View.VISIBLE
            isGateStarted = false
            isGateStopped = false
    
            gateMp = MediaPlayer.create(this, R.raw.beep_middle_5db)
            gateMp.setOnCompletionListener {
                gateMp.pause()
                gateMp.seekTo(0)
                isGateMpReady = true
            }
        }
        
        //  camera
        if(start.config.isCamera || start.config.isGate) {
            myCamera2.open()
        }

        //  mps
        if(start.config.isCommand) {
            mps.addAll(Array(start.mpIds.size) { i -> MediaPlayer.create(this, start.mpIds[i]) })
            if(Session.state == SessionState.HOST) {
                val duration = mps.last().duration
                val audioShouldStartAtMs = start.mpStarts.last()
                mps.last().setOnCompletionListener {
                    val delta = timer.time - duration - audioShouldStartAtMs
                    showFeedback?.invoke("delay: $delta ms")
                }
            }
            mps.add(MediaPlayer.create(this, R.raw.whitenoise_point_001db)) //  needs to play non-silent audio for box

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
                if(timer.time >= start.timeOfInit + start.timeToCommand + start.videoLength + DURATION_WAIT_AFTER_FINISH) finish()
                
                //  camera
                if(!isCameraStarted && timer.time >= start.timeOfInit + start.timeToCommand - DURATION_VIDEO_BEFORE_START) {
                    isCameraStarted = true
                    myRecorder.startRecord()
                    
                    runOnUiThread { vLog.text = "${vLog.text}\nvideo started" }
                }
                if(!isCameraStopped && timer.time >= start.timeOfInit + start.timeToCommand + start.videoLength) {
                    isCameraStopped = true
                    myRecorder.stopRecord()
                    
                    runOnUiThread { vLog.text = "${vLog.text}\nvideo stopped" }
                }
                
                //  gate
                if(!isGateStarted && timer.time >= start.timeOfInit + start.timeToCommand) {
                    isGateStarted = true
                    analyzer.isWant = true
                    
                    runOnUiThread { vLog.text = "${vLog.text}\ngate started" }
                }
                if(!isGateStopped && timer.time >= start.timeOfInit + start.timeToCommand + start.videoLength) {
                    isGateStopped = true
                    analyzer.stop()
                    
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
        if(this::gateMp.isInitialized) {
            gateMp.pause()
            gateMp.release()
        }
        mps.forEach {
            it.pause()
            it.release()
        }
    }

    companion object {
        const val DURATION_WAIT_AFTER_FINISH = 3000L
        const val DURATION_VIDEO_BEFORE_START = 3000L

        var startData: StartData? = null;private set
        var broadcastFeedback:((String)->Unit)? = null;private set
        var showFeedback:((String)->Unit)? = null;private set
        var isBusy = false;private set

        fun launch(activityHome: ActivityHome, startData: StartData) {
            if(isBusy) return

            isBusy = true
            this.startData = startData
            this.broadcastFeedback = activityHome::broadcastFeedback
            this.showFeedback = activityHome::showFeedback

            activityHome.startActivity(Intent(activityHome, ActivityStart::class.java))
        }
    }
}