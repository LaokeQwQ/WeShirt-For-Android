package com.laoke.weshirt

import android.app.Application
import com.laoke.weshirt.platform.PlatformCoordinator

class WeShirtApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PlatformCoordinator.bootstrap(this)
        com.laoke.weshirt.platform.widget.LaundryWidgetTheme.observe(this)
    }
}
