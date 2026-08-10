package rikka.shizuku.demo

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log

import org.lsposed.hiddenapibypass.HiddenApiBypass

import rikka.shizuku.demo.util.ApplicationUtils
import rikka.sui.Sui

class DemoApplication : Application() {

    companion object {
        private val isSui: Boolean = Sui.init(BuildConfig.APPLICATION_ID)

        fun isSui(): Boolean = isSui
    }

    override fun onCreate() {
        super.onCreate()

        Log.d("ShizukuSample", "${javaClass.simpleName} onCreate | Process=${ApplicationUtils.getProcessName()}")
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
        ApplicationUtils.setApplication(this)

        Log.d("ShizukuSample", "${javaClass.simpleName} attachBaseContext | Process=${ApplicationUtils.getProcessName()}")
    }
}
