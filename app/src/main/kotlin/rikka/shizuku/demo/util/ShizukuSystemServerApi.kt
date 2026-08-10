package rikka.shizuku.demo.util

import android.content.Context
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageManager
import android.content.pm.UserInfo
import android.os.Build
import android.os.IUserManager
import android.os.RemoteException

import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object ShizukuSystemServerApi {

    private val PACKAGE_MANAGER: Singleton<IPackageManager> = object : Singleton<IPackageManager>() {
        override fun create(): IPackageManager {
            return IPackageManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package")))
        }
    }

    private val USER_MANAGER: Singleton<IUserManager> = object : Singleton<IUserManager>() {
        override fun create(): IUserManager {
            return IUserManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.USER_SERVICE)))
        }
    }

    fun PackageManager_getPackageInstaller(): IPackageInstaller {
        val packageInstaller = PACKAGE_MANAGER.get().packageInstaller
        return IPackageInstaller.Stub.asInterface(ShizukuBinderWrapper(packageInstaller.asBinder()))
    }

    fun UserManager_getUsers(excludePartial: Boolean, excludeDying: Boolean, excludePreCreated: Boolean): List<UserInfo> {
        if (Build.VERSION.SDK_INT >= 30) {
            return USER_MANAGER.get().getUsers(excludePartial, excludeDying, excludePreCreated)
        } else {
            return try {
                USER_MANAGER.get().getUsers(excludeDying)
            } catch (e: NoSuchFieldError) {
                USER_MANAGER.get().getUsers(excludePartial, excludeDying, excludePreCreated)
            }
        }
    }
}
