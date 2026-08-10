package com.lubui.shellbox

import android.app.Application
import android.content.Context
import android.os.Build

import org.lsposed.hiddenapibypass.HiddenApiBypass

import rikka.sui.Sui

class DemoApplication : Application() {

    companion object {
        private val isSui: Boolean = Sui.init(BuildConfig.APPLICATION_ID)

        fun isSui(): Boolean = isSui
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }
}
