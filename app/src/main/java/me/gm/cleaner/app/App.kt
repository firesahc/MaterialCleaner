package me.gm.cleaner.app

import android.app.Application
import android.content.Context
import android.os.Build
import com.topjohnwu.superuser.Shell
import me.gm.cleaner.client.ServerStateMachine
import me.gm.cleaner.dao.AppLabelCache
import me.gm.cleaner.dao.RootPreferences
import me.gm.cleaner.dao.ServiceMoreOptionsPreferences
import me.gm.cleaner.core.config.ServicePreferences
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        System.loadLibrary(String(charArrayOf('c', 'l', 'e', 'a', 'n', 'e', 'r')))
    }

    override fun onCreate() {
        super.onCreate()
        AppLabelCache.init(this)
        ServerStateMachine.init(this)
        val dpsContext = createDeviceProtectedStorageContext()
        RootPreferences.init(dpsContext)
        ServicePreferences.init(dpsContext)
        ServiceMoreOptionsPreferences.init(dpsContext)
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
    }
}
