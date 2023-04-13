package com.ollivolland.lemaitre2

import MyTimer
import StartData
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import com.ollivolland.lemaitre.camera.MyCamera2
import com.ollivolland.lemaitre.camera.MyRequestPreview
import kotlin.concurrent.thread

class ActivityStart : AppCompatActivity() {
    lateinit var timer:MyTimer
    lateinit var data: StartData
    lateinit var mycamera2: MyCamera2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)

        timer = MyTimer()
        data = startData!!

        val vTexture = findViewById<TextureView>(R.id.start_vTexture)

        //  camera
        val requestPreview = MyRequestPreview(vTexture)
        mycamera2 = MyCamera2(this)
            .addRequest(requestPreview)
            .open()
        requestPreview.start()

        //  mps
        val ids = arrayOf(R.raw.aufdieplaetze, R.raw.fertig, R.raw.gunshot_10db_1s_delayed)
        val mps = Array<MediaPlayer>(ids.size) { i -> MediaPlayer.create(this, ids[i]) }
        for (x in mps)
            x.setOnCompletionListener { x.release() }

        thread {
            for (i in mps.indices) {
                timer.lock(data.mpStarts[i])
                mps[i].start()
            }
        }

        thread {
            timer.lock(data.timeStamp + data.commandLength + data.videoLength + DURATION_WAIT_AFTER_FINISH)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isBusy = false
        mycamera2.close()
    }

    companion object {
        const val DURATION_WAIT_AFTER_FINISH = 3000L

        var startData:StartData? = null;private set
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