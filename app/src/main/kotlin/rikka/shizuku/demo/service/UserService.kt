package rikka.shizuku.demo.service

import android.content.Context
import android.os.RemoteException
import android.system.Os
import android.util.Log

import androidx.annotation.Keep

import rikka.shizuku.demo.IUserService

class UserService() : IUserService.Stub() {

    /**
     * Constructor is required.
     */
    init {
        Log.i("UserService", "constructor")
    }

    /**
     * Constructor with Context. This is only available from Shizuku API v13.
     *
     * This method need to be annotated with [Keep] to prevent ProGuard from removing it.
     *
     * @param context Context created with createPackageContextAsUser
     */
    @Keep
    constructor(context: Context) : this() {
        Log.i("UserService", "constructor with Context: context=$context")
    }

    /**
     * Reserved destroy method
     */
    override fun destroy() {
        Log.i("UserService", "destroy")
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun doSomething(): String {
        return "pid=${Os.getpid()}, uid=${Os.getuid()}, ${stringFromJNI()}"
    }

    companion object {
        init {
            System.loadLibrary("hello-jni")
        }

        @JvmStatic
        external fun stringFromJNI(): String
    }
}
