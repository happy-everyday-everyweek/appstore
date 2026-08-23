package me.spica27.spicamusic

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.spica27.spicamusic.crash.CrashHandler
import me.spica27.spicamusic.di.AppModule
import me.spica27.spicamusic.dsh.DshManager
import me.spica27.spicamusic.feature.settings.domain.settingsDomainModule
import me.spica27.spicamusic.service.TermHostService
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext.startKoin
import timber.log.Timber

/**
 * 应用市场客户端 Application
 * 负责初始化 Koin 依赖注入与其他全局配置
 */
class App : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化日志
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 初始化 Koin 依赖注入
        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(
                settingsDomainModule,
                AppModule.appModule,
            )
        }

        // DeepSeek Harness：环境未部署则自动部署，服务未启动则自动启动（终端内执行，无需用户确认）
        appScope.launch {
            DshManager.ensureReady(this@App)
        }
        // 终端宿主前台服务：保活终端环境与 dsh 进程（退后台不被回收）
        runCatching { TermHostService.start(this) }
        CrashHandler.init(this)
    }

    companion object {
        private lateinit var instance: App

        fun getInstance(): App = instance
    }
}
