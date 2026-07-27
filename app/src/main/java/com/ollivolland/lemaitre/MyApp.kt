package com.ollivolland.lemaitre

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import datas.Session


class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Wird aufgerufen, wenn die App in den Vordergrund kommt
            override fun onStart(owner: LifecycleOwner) {
                super.onStart(owner)
                Session.log("App onStart")
            }

            // Wird aufgerufen, wenn die App in den Hintergrund wechselt
            override fun onStop(owner: LifecycleOwner) {
                super.onStop(owner)
                Session.log("App onStop")
            }
        })
    }


    companion object {
        var appContext: Context? = null
            private set
    }
}