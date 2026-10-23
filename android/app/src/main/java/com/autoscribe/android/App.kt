package com.autoscribe.android

import android.app.Application

class App : Application() {
    val store by lazy { Store(this) }

    var pops: List<Pop> = emptyList()
        private set

    override fun onCreate() {
        super.onCreate()
        reloadPops()
        TestRunner.loadResults(store)
    }

    fun reloadPops() {
        pops = runCatching { Locations.load(this) }.getOrDefault(emptyList())
    }
}
