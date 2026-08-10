package rikka.shizuku.demo.util

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build

object ApplicationUtils {

    @Volatile
    private var application: Application? = null

    fun getApplication(): Application? = application

    fun setApplication(application: Application?) {
        ApplicationUtils.application = application
    }

    fun getProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28)
            return Application.getProcessName()
        else {
            @SuppressLint("PrivateApi")
            val activityThread = Class.forName("android.app.ActivityThread")
            @SuppressLint("DiscouragedPrivateApi")
            val method = activityThread.getDeclaredMethod("currentProcessName")
            return method.invoke(null) as String
        }
    }
}
