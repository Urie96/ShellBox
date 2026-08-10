package rikka.shizuku.demo.util

import android.content.Context
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageInstallerSession
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build

object PackageInstallerUtils {

    fun createPackageInstaller(
        installer: IPackageInstaller, installerPackageName: String, installerAttributionTag: String?, userId: Int
    ): PackageInstaller {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PackageInstaller::class.java.getConstructor(
                IPackageInstaller::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType
            ).newInstance(installer, installerPackageName, installerAttributionTag, userId)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PackageInstaller::class.java.getConstructor(
                IPackageInstaller::class.java, String::class.java, Int::class.javaPrimitiveType
            ).newInstance(installer, installerPackageName, userId)
        } else {
            PackageInstaller::class.java.getConstructor(
                Context::class.java, PackageManager::class.java, IPackageInstaller::class.java,
                String::class.java, Int::class.javaPrimitiveType
            ).newInstance(ApplicationUtils.getApplication(), ApplicationUtils.getApplication()!!.packageManager, installer, installerPackageName, userId)
        }
    }

    fun createSession(session: IPackageInstallerSession): PackageInstaller.Session {
        return PackageInstaller.Session::class.java.getConstructor(IPackageInstallerSession::class.java)
            .newInstance(session)
    }

    fun getInstallFlags(params: PackageInstaller.SessionParams): Int {
        return PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags").get(params) as Int
    }

    fun setInstallFlags(params: PackageInstaller.SessionParams, newValue: Int) {
        PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags").set(params, newValue)
    }
}
