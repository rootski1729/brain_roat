package com.brainrot.pet

import android.annotation.SuppressLint
import android.app.Application
import com.brainrot.pet.data.AppCatalog
import com.brainrot.pet.data.BrainStore
import com.brainrot.pet.data.UsageReader
import com.brainrot.pet.runtime.BrainRuntime
import com.brainrot.pet.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BrainRotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        Notifications.createChannels(this)
    }
}

/**
 * Process-wide singletons. The activity, the foreground service, the accessibility service and the
 * widget all run in this one process and share this state.
 */
@SuppressLint("StaticFieldLeak") // Holds only the Application context, which lives as long as the process.
object AppGraph {
    lateinit var store: BrainStore
        private set
    lateinit var usage: UsageReader
        private set
    lateinit var catalog: AppCatalog
        private set
    lateinit var runtime: BrainRuntime
        private set

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(app: Application) {
        if (::runtime.isInitialized) return
        store = BrainStore(app, appScope)
        usage = UsageReader(app)
        catalog = AppCatalog(app)
        runtime = BrainRuntime(app, store, usage, catalog)
    }
}
