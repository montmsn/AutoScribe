package com.autoscribe.android

import android.app.Application

class App : Application() {
    val store by lazy { Store(this) }

    var pops: List<Pop> = emptyList()
        private set

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Kept so the next launch can show what went wrong.
            runCatching { java.io.File(filesDir, CRASH_FILE).writeText(error.stackTraceToString()) }
            previous?.uncaughtException(thread, error)
        }
        reloadPops()
        TestRunner.loadResults(store)
    }

    /** The stack trace of the last crash, removed once read. */
    fun takeCrash(): String? {
        val f = java.io.File(filesDir, CRASH_FILE)
        if (!f.exists()) return null
        return f.readText().also { f.delete() }
    }

    fun reloadPops() {
        pops = runCatching { Locations.load(this) }.getOrDefault(emptyList())
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"
    }
}
