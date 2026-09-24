package app.raum

import android.app.Application
import app.raum.di.appModule
import app.raum.platform.service.CrashRecorder
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.android.ext.android.get

class RaumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Der Bridge-Prozess („:bridge“) braucht nur seinen Dienst – kein Core, keine Datenbank, kein Controller
        if (getProcessName().endsWith(":bridge")) return
        startKoin {
            androidContext(this@RaumApplication)
            modules(appModule)
        }
        get<CrashRecorder>().install()
        // Core startet mit dem Prozess – egal ob über Launcher, Boot oder Dienst-Neustart.
        get<CoreStartup>().start()
    }
}
