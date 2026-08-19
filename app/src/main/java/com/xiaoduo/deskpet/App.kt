package com.xiaoduo.deskpet

import android.app.Application
import com.xiaoduo.deskpet.util.CrashReporter
import com.xiaoduo.deskpet.util.PetLogger

/** 进程入口：文件日志 + 崩溃留痕最先立起来（知识包 #44：release 可观测性先行）。 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PetLogger.init(this)
        CrashReporter.install(this)
        PetLogger.i("App", "application onCreate")
    }
}
