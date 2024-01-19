package com.ollivolland.lemaitre

import Analyzer
import MyTimer
import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import datas.Session
import datas.StartData
import format
import mycamera2.MyCamera2
import mycamera2.MyRecorder
import java.io.File
import java.util.*
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

    @SuppressLint("MissingPermission")
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
                bytesPerSecond = 5_000_000
                fps = start.config.fps
                
                when (start.config.quality) {
                    1 -> {
                        width = 2560
                        height = 1440
                        bytesPerSecond = 20_000_000
                    }
                    2 -> {
                        width = 3840
                        height = 2160
                        bytesPerSecond = 20_000_000
                    }
                }
            })
            isCameraStarted = false
            isCameraStopped = false
        }
        
        //  Gate
        if(start.config.isGate) {
            val sens = File("${getExternalFilesDir(null)}\\${MainActivity.PATH_SENSITIVITY}").readText().toInt()
            runOnUiThread { vLog.text = "${vLog.text}\nsens = $sens" }
            analyzer = Analyzer(this, myCamera2, timer, start.timeOfCommand, sens)
            analyzer.onStreakStartedListeners.add {
                val msg = "gate: ${(it * .001).format(2)}s"
                runOnUiThread { vLog.text = "${vLog.text}\n$msg" }
                
                if(isGateMpReady) {
                    isGateMpReady = false
                    gateMp.start()
                }
            }
            analyzer.onTriangulatedListeners.add { triangleMs, frameMs, deltas ->
                val gate = if(triangleMs == 0L) "${(frameMs * .001).format(2)}s?" else "${(triangleMs * .001).format(2)}s"
                val msg = "gate: $gate   (${(frameMs * .001).format(2)}Δ${if(deltas<0) "-" else "+"}${abs(deltas).format(1)})"
                
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
//            val MAX_VOLUME = 100.0 + 1
            val volumeShot:Float = 1.00f  //(1 - (ln(MAX_VOLUME - 100) / ln(MAX_VOLUME))).toFloat()
            val volumeMisc:Float = 0.30f    //(1 - (ln(MAX_VOLUME - 75) / ln(MAX_VOLUME))).toFloat()
            
            mps.addAll(Array(start.mpIds.size) { i ->
                MediaPlayer.create(this, start.mpIds[i]).apply {
                    if(i == start.mpIds.size - 1)
                        setVolume(volumeShot, volumeShot)
                    else
                        setVolume(volumeMisc, volumeMisc)
                }
            })
            
            //  delay checker
            if(Session.isHost)
                thread {
                    try {
                        val sampleRate = 8000
                        val bufferSize = AudioRecord.getMinBufferSize(
                            sampleRate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        val audio = AudioRecord(
                            MediaRecorder.AudioSource.MIC, sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, bufferSize
                        )
                        val buffer = ShortArray(bufferSize)
                        var bufferReadResult: Int
    
                        timer.lock(start.timeOfCommand - 500)
                        audio.startRecording()
                        val levels = mutableListOf<Short>()
                        val startAtMs: Long
    
                        while (true)
                        {
                            bufferReadResult = audio.read(buffer, 0, bufferSize)
                            for (ii in 0 until bufferReadResult)
                                levels.add(buffer[ii])
                            
                            if(levels.size >= sampleRate * 1.5)
                            {
                                startAtMs = timer.time - (levels.size * 1000L / sampleRate)
                                break
                            }
                        }
    
                        audio.stop()
                        audio.release()
                        
                        val hundreds = Array(levels.size / 80) { 0L }
                        for (i in hundreds.indices)
                            for (ii in 0 until 80)
                                hundreds[i] += abs(levels[i*80+ii].toLong())
                        
                        val threshold = hundreds.max() / 2
                        var isHasFound = false
                        for (i in hundreds.indices)
                        {
                            val time = startAtMs + i*10 - start.timeOfCommand
                            
                            if(!isHasFound && hundreds[i] >= threshold) {
                                isHasFound = true
                                println("FOUND level [${"%05d".format(time)} ms] = ${hundreds[i]}")
                                showFeedback?.invoke("shot: ${(time * .001).format(2)}s")
                            }
                            else
                                println("level [${"%05d".format(time)} ms] = ${hundreds[i]}")
                        }
                    } catch (e: Exception) {
                        Log.e("TrackingFlow", "Exception", e)
                    }
                }
            
            //  audios
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
                if(timer.time >= start.timeOfCommand + start.videoLength + DURATION_WAIT_AFTER_FINISH) finish()
                
                //  camera
                if(!isCameraStarted && timer.time >= start.timeOfCommand - DURATION_VIDEO_BEFORE_START) {
                    isCameraStarted = true
                    myRecorder.startRecord()
                    
                    runOnUiThread { vLog.text = "${vLog.text}\nvideo started" }
                }
                if(!isCameraStopped && timer.time >= start.timeOfCommand + start.videoLength) {
                    isCameraStopped = true
                    myRecorder.stopRecord()
                    
                    runOnUiThread { vLog.text = "${vLog.text}\nvideo stopped" }
                }
                
                //  gate
                if(!isGateStarted && timer.time >= start.timeOfCommand) {
                    isGateStarted = true
                    analyzer.isWant = true
                    
                    runOnUiThread { vLog.text = "${vLog.text}\ngate started" }
                }
                if(!isGateStopped && timer.time >= start.timeOfCommand + start.videoLength) {
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