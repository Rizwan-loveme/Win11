package com.srizwan.win11

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import kotlin.system.exitProcess

/**
 * Runs in the launcher process AND in every virtual-app process (:black, :p0, ...),
 * so the clone engine is attached here, before anything else.
 * The launcher UI lives in MainActivity; this class also installs the
 * crash/debug-log setup so problems (like a black screen on startup) show a
 * readable cause instead of failing silently — see Logger / CrashActivity.
 */
class Win11App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Logger.init(base)
        installCrashHandler()
        Logger.d(TAG, "attachBaseContext: pid=${android.os.Process.myPid()}")
        try {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName
                override fun isEnableDaemonService(): Boolean = false
                // Never upload logcat to any server.
                override fun getLogSenderChatId(): String = ""
            })
            Logger.d(TAG, "engine attach OK")
        } catch (e: Throwable) {
            Logger.e(TAG, "engine attach failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            BlackBoxCore.get().doCreate()
            Logger.d(TAG, "engine create OK")
        } catch (e: Throwable) {
            Logger.e(TAG, "engine create failed", e)
        }
    }

    /**
     * Any uncaught exception, anywhere in this process (UI thread or background),
     * is logged in full to the debug log and opens [CrashActivity] with the trace
     * instead of leaving the launcher on a black screen or silently crash-looping.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Logger.e(TAG, "UNCAUGHT on thread '${thread.name}'", throwable)
                val trace = Log.getStackTraceString(throwable)
                val intent = Intent(this, CrashActivity::class.java).apply {
                    putExtra(CrashActivity.EXTRA_TRACE, trace)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
            } catch (inner: Throwable) {
                Log.e(TAG, "crash handler itself failed", inner)
            } finally {
                // Give the activity's startActivity() a moment to be dispatched, then
                // end this (now-broken) process cleanly instead of an undefined state.
                Thread.sleep(300)
                previous?.uncaughtException(thread, throwable)
                exitProcess(10)
            }
        }
    }

    private companion object {
        const val TAG = "Win11App"
    }
}
