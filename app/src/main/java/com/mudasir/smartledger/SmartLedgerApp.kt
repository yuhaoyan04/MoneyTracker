package com.mudasir.smartledger

import android.app.Application
import android.util.Log
import android.widget.Toast
import kotlin.system.exitProcess

class SmartLedgerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("SmartLedger", "Uncaught exception on ${thread.name}", throwable)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    Toast.makeText(this, "发生错误，请重试", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
            // 给 Toast 时间显示，然后退出
            Thread.sleep(1500)
            default?.uncaughtException(thread, throwable)
            exitProcess(2)
        }
    }
}
