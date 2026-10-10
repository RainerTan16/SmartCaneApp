package com.tan.smartcane

import android.app.Application
import android.content.Intent
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val i = Intent(this, CrashActivity::class.java)
                i.putExtra("trace", Log.getStackTraceString(e))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(i)
            } catch (_: Throwable) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }
}
