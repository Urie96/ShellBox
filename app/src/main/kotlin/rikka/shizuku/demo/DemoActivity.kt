package rikka.shizuku.demo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageInstallerSession
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch

import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuSystemProperties
import rikka.shizuku.demo.databinding.MainActivityBinding
import rikka.shizuku.demo.service.UserService
import rikka.shizuku.demo.util.ApplicationUtils
import rikka.shizuku.demo.util.IIntentSenderAdaptor
import rikka.shizuku.demo.util.IntentSenderUtils
import rikka.shizuku.demo.util.PackageInstallerUtils
import rikka.shizuku.demo.util.SettingsGlobalUtils
import rikka.shizuku.demo.util.ShizukuSystemServerApi

@SuppressLint("SetTextI18n")
class DemoActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_BUTTON1 = 1
        private const val REQUEST_CODE_BUTTON2 = 2
        private const val REQUEST_CODE_BUTTON3 = 3
        private const val REQUEST_CODE_BUTTON4 = 4
        private const val REQUEST_CODE_BUTTON5 = 5
        private const val REQUEST_CODE_BUTTON6 = 6
        private const val REQUEST_CODE_BUTTON7 = 7
        private const val REQUEST_CODE_BUTTON8 = 8
        private const val REQUEST_CODE_BUTTON9 = 9
        private const val REQUEST_CODE_PICK_APKS = 1000
    }

    private lateinit var binding: MainActivityBinding

    private val BINDER_RECEIVED_LISTENER = Shizuku.OnBinderReceivedListener {
        if (Shizuku.isPreV11()) {
            binding.text1.text = "Shizuku pre-v11 is not supported"
        } else {
            binding.text1.text = "Binder received"
        }
    }
    private val BINDER_DEAD_LISTENER = Shizuku.OnBinderDeadListener { binding.text1.text = "Binder dead" }
    private val REQUEST_PERMISSION_RESULT_LISTENER = Shizuku.OnRequestPermissionResultListener(::onRequestPermissionsResult)

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("ShizukuSample", "${javaClass.simpleName} onCreate | Process=${ApplicationUtils.getProcessName()}")

        super.onCreate(savedInstanceState)

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.text2.text = "Using " + (if (DemoApplication.isSui()) "Sui" else "Shizuku or nothing is installed") + "."

        binding.text1.text = "Waiting for binder"
        binding.button1.setOnClickListener { if (checkPermission(REQUEST_CODE_BUTTON1)) getUsers() }
        binding.button2.setOnClickListener { if (checkPermission(REQUEST_CODE_BUTTON2)) installApks() }
        binding.button3.setOnClickListener { if (checkPermission(REQUEST_CODE_BUTTON3)) abandonMySessions() }
        binding.button4.setOnClickListener { if (checkPermission(REQUEST_CODE_BUTTON4)) getSystemProperty() }
        binding.button5.setOnClickListener { if (checkPermission(REQUEST_CODE_BUTTON5)) setProxy() }
        binding.button6.setOnClickListener { if (checkPermission(REQUEST_CODE_BUTTON6)) clearProxy() }
        binding.button7.setOnClickListener { if (checkPermission(REQUEST_CODE_BUTTON7)) bindUserService() }
        binding.button8.setOnClickListener { if (checkPermission(REQUEST_CODE_BUTTON8)) unbindUserService() }
        binding.button9.setOnClickListener { if (checkPermission(REQUEST_CODE_BUTTON9)) peekUserService() }

        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER)
        Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER)
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }

    override fun onDestroy() {
        super.onDestroy()

        Shizuku.removeBinderReceivedListener(BINDER_RECEIVED_LISTENER)
        Shizuku.removeBinderDeadListener(BINDER_DEAD_LISTENER)
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }

    private fun onRequestPermissionsResult(requestCode: Int, grantResult: Int) {
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            when (requestCode) {
                REQUEST_CODE_BUTTON1 -> getUsers()
                REQUEST_CODE_BUTTON2 -> installApks()
                REQUEST_CODE_BUTTON3 -> abandonMySessions()
                REQUEST_CODE_BUTTON4 -> getSystemProperty()
                REQUEST_CODE_BUTTON5 -> setProxy()
                REQUEST_CODE_BUTTON6 -> clearProxy()
                REQUEST_CODE_BUTTON7 -> bindUserService()
                REQUEST_CODE_BUTTON8 -> unbindUserService()
                REQUEST_CODE_BUTTON9 -> peekUserService()
            }
        } else {
            binding.text1.text = "User denied permission"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_PICK_APKS && resultCode == RESULT_OK) {
            val uris: MutableList<Uri>
            val clipData = data?.clipData
            if (clipData != null) {
                uris = ArrayList(clipData.itemCount)
                for (i in 0 until clipData.itemCount) {
                    val uri = clipData.getItemAt(i).uri
                    if (uri != null) {
                        uris.add(uri)
                    }
                }
            } else {
                uris = ArrayList()
                uris.add(data!!.data!!)
            }
            doInstallApks(uris)
        }
    }

    private fun checkPermission(code: Int): Boolean {
        if (Shizuku.isPreV11()) {
            return false
        }
        return try {
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                true
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                binding.text3.text = "User denied permission (shouldShowRequestPermissionRationale=true)"
                false
            } else {
                Shizuku.requestPermission(code)
                false
            }
        } catch (e: Throwable) {
            binding.text3.text = Log.getStackTraceString(e)
            false
        }
    }

    private fun getUsers() {
        val res: String? = try {
            ShizukuSystemServerApi.UserManager_getUsers(true, true, true).toString()
        } catch (tr: Throwable) {
            tr.printStackTrace()
            tr.message
        }
        binding.text3.text = res
    }

    private fun installApks() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.type = "application/vnd.android.package-archive"

        startActivityForResult(intent, REQUEST_CODE_PICK_APKS)
    }

    private fun doInstallApks(uris: List<Uri>) {
        var session: PackageInstaller.Session? = null
        val cr: ContentResolver = contentResolver
        val res = StringBuilder()

        try {
            val _packageInstaller = ShizukuSystemServerApi.PackageManager_getPackageInstaller()
            val isRoot = Shizuku.getUid() == 0

            // the reason for use "com.android.shell" as installer package under adb is that getMySessions will check installer package's owner
            val installerPackageName = if (isRoot) packageName else "com.android.shell"
            val installerAttributionTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) attributionTag else null
            val userId = if (isRoot) Process.myUserHandle().hashCode() else 0
            val packageInstaller = PackageInstallerUtils.createPackageInstaller(_packageInstaller, installerPackageName, installerAttributionTag, userId)
            res.append("createSession: ")

            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            var installFlags = PackageInstallerUtils.getInstallFlags(params)
            installFlags = installFlags or 0x00000004/*PackageManager.INSTALL_ALLOW_TEST*/ or 0x00000002/*PackageManager.INSTALL_REPLACE_EXISTING*/
            PackageInstallerUtils.setInstallFlags(params, installFlags)

            val sessionId = packageInstaller.createSession(params)
            res.append(sessionId).append('\n')

            res.append('\n').append("write: ")

            val _session = IPackageInstallerSession.Stub.asInterface(ShizukuBinderWrapper(_packageInstaller.openSession(sessionId).asBinder()))
            session = PackageInstallerUtils.createSession(_session)

            var i = 0
            for (uri in uris) {
                val name = "$i.apk"

                val `is`: InputStream = cr.openInputStream(uri)!!
                val os: OutputStream = session.openWrite(name, 0, -1)

                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val len = `is`.read(buf)
                        if (len <= 0) break
                        os.write(buf, 0, len)
                        os.flush()
                        session.fsync(os)
                    }
                } finally {
                    try {
                        `is`.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    try {
                        os.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }

                i++

                Thread.sleep(1000)
            }

            res.append('\n').append("commit: ")

            val results = arrayOfNulls<Intent>(1)
            val countDownLatch = CountDownLatch(1)
            val intentSender = IntentSenderUtils.newInstance(object : IIntentSenderAdaptor() {
                override fun send(intent: Intent?) {
                    results[0] = intent
                    countDownLatch.countDown()
                }
            })
            session.commit(intentSender)

            countDownLatch.await()
            val result = results[0]
            val status = result!!.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            res.append('\n').append("status: ").append(status).append(" (").append(message ?: "null").append(")")

        } catch (tr: Throwable) {
            tr.printStackTrace()
            res.append(tr)
        } finally {
            if (session != null) {
                try {
                    session.close()
                } catch (tr: Throwable) {
                    res.append(tr)
                }
            }
        }

        binding.text3.text = res.toString().trim()
    }

    private fun abandonMySessions() {
        val res = StringBuilder()

        try {
            val packageInstaller = ShizukuSystemServerApi.PackageManager_getPackageInstaller()
            val isRoot = Shizuku.getUid() == 0

            val installer = if (isRoot) packageName else "com.android.shell"
            val userId = if (isRoot) Process.myUserHandle().hashCode() else 0

            res.append("abandonMySessions: ")
            val sessions = packageInstaller.getMySessions(installer, userId).list
            for (session in sessions) {
                res.append(session.sessionId)
                packageInstaller.abandonSession(session.sessionId)
                res.append(" (abandoned)\n")
            }
        } catch (tr: Throwable) {
            tr.printStackTrace()
            res.append(tr)
        }

        binding.text3.text = res.toString().trim()
    }

    private fun getSystemProperty() {
        val res = StringBuilder()
        try {
            if (Shizuku.getVersion() < 9) {
                res.append("requires Shizuku API 9")
            } else {
                res.append("ro.build.fingerprint=").append(ShizukuSystemProperties.get("ro.build.fingerprint")).append('\n')
                res.append("ro.build.version.sdk=").append(ShizukuSystemProperties.getInt("ro.build.version.sdk", -1)).append('\n')
            }
        } catch (tr: Throwable) {
            tr.printStackTrace()
            res.append(tr.toString())
        }
        binding.text3.text = res.toString().trim()
    }

    private fun setProxy() {
        val res = StringBuilder()
        try {
            val proxy = binding.editProxy.text.toString().trim()
            if (proxy.isEmpty()) {
                res.append("proxy is empty, use \"Clear global http_proxy\" to disable")
            } else if (proxy.indexOf(':') == -1) {
                res.append("invalid proxy, expected format: host:port")
            } else {
                SettingsGlobalUtils.putGlobal("http_proxy", proxy)
                getSharedPreferences(ProxyShortcutActivity.PREFS, MODE_PRIVATE)
                    .edit().putString(ProxyShortcutActivity.KEY_PROXY, proxy).apply()
                res.append("set http_proxy=").append(proxy).append('\n')
                res.append("now http_proxy=").append(SettingsGlobalUtils.getGlobal("http_proxy"))
            }
        } catch (tr: Throwable) {
            tr.printStackTrace()
            res.append(Log.getStackTraceString(tr))
        }
        binding.text3.text = res.toString().trim()
    }

    private fun clearProxy() {
        val res = StringBuilder()
        try {
            SettingsGlobalUtils.putGlobal("http_proxy", ":0")
            res.append("cleared http_proxy (set to :0)\n")
            res.append("now http_proxy=").append(SettingsGlobalUtils.getGlobal("http_proxy"))
        } catch (tr: Throwable) {
            tr.printStackTrace()
            res.append(Log.getStackTraceString(tr))
        }
        binding.text3.text = res.toString().trim()
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            val res = StringBuilder()
            res.append("onServiceConnected: ").append(componentName.className).append('\n')
            if (binder != null && binder.pingBinder()) {
                val service = IUserService.Stub.asInterface(binder)
                try {
                    res.append(service.doSomething())
                } catch (e: RemoteException) {
                    e.printStackTrace()
                    res.append(Log.getStackTraceString(e))
                }
            } else {
                res.append("invalid binder for ").append(componentName).append(" received")
            }
            binding.text3.text = res.toString().trim()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            binding.text3.text = "onServiceDisconnected: " + '\n' + componentName.className
        }
    }

    private val userServiceArgs: Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name))
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private fun bindUserService() {
        val res = StringBuilder()
        try {
            if (Shizuku.getVersion() < 10) {
                res.append("requires Shizuku API 10")
            } else {
                Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            }
        } catch (tr: Throwable) {
            tr.printStackTrace()
            res.append(tr.toString())
        }
        binding.text3.text = res.toString().trim()
    }

    private fun unbindUserService() {
        val res = StringBuilder()
        try {
            if (Shizuku.getVersion() < 10) {
                res.append("requires Shizuku API 10")
            } else {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            }
        } catch (tr: Throwable) {
            tr.printStackTrace()
            res.append(tr.toString())
        }
        binding.text3.text = res.toString().trim()
    }

    private fun peekUserService() {
        val res = StringBuilder()
        try {
            if (Shizuku.getVersion() < 12) {
                res.append("requires Shizuku API 12")
            } else {
                val serviceVersion = Shizuku.peekUserService(userServiceArgs, userServiceConnection)
                if (serviceVersion != -1) {
                    res.append("Service is running, version ").append(serviceVersion)
                } else {
                    res.append("Service is not running")
                }
            }
        } catch (tr: Throwable) {
            tr.printStackTrace()
            res.append(tr.toString())
        }
        binding.text3.text = res.toString().trim()
    }
}
