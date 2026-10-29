package cn.coldlake.stellar.opensource

import android.app.Application
import cn.coldlake.stellar.opensource.cache.AppCaches

class StellarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCaches.install(this)
    }
}
