package com.wludy.rolithax.launcher

import android.app.Application
import com.wludy.rolithax.launcher.data.AppContainer
import com.wludy.rolithax.launcher.data.AppLogger
import com.wludy.rolithax.launcher.data.InstanceIconStore

class RolithaxApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 日志必须在 container 之前初始化：崩溃处理器与首个日志点尽早就位
        AppLogger.init(this)
        InstanceIconStore.init(this)
        container = AppContainer(this)
    }
}
