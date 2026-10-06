package com.mudasir.smartledger

import android.app.Application
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlin.system.exitProcess

class SmartLedgerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    /**
     * 全局崩溃处理：
     * - 主线程异常：无法恢复，走系统默认崩溃流程（保留 ANR/崩溃报告）
     * - 后台线程异常（通知监听器/短信接收器/协程等）：仅记录日志，绝不杀进程。
     *   否则用户在前台编辑时，任何后台组件的小异常都会导致整个 App 闪退。
     */
    private fun installCrashHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("SmartLedger", "Uncaught exception on ${thread.name}", throwable)
            if (thread === Looper.getMainLooper().thread) {
                android.os.Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(this, "发生错误，请重试", Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {}
                }
                Thread.sleep(1200)
                default?.uncaughtException(thread, throwable)
                exitProcess(2)
            }
            // 后台线程：吞掉异常，保住前台体验
        }
    }
}
