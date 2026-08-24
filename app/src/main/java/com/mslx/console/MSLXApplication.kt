package com.mslx.console

import android.app.Application
import com.mslx.console.data.AppContainer
import com.mslx.console.data.AppLogger

class MSLXApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 日志必须在 container 之前初始化：崩溃处理器与首个日志点尽早就位
        AppLogger.init(this)
        container = AppContainer(this)
    }
}
