package app.aaps.implementation.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.aaps.core.interfaces.lifecycle.AppLifecycle
import app.aaps.core.interfaces.protection.ProtectionCheck
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessLifecycleListener @Inject constructor(
    private val protectionCheck: ProtectionCheck
) : DefaultLifecycleObserver, AppLifecycle {

    /**
     * Written on the main thread by the process lifecycle callbacks, read from worker threads that
     * decide whether to prepare graph data — hence @Volatile.
     */
    @Volatile private var visible = false

    override val uiVisible: Boolean get() = visible

    override fun onStart(owner: LifecycleOwner) {
        visible = true
    }

    override fun onStop(owner: LifecycleOwner) {
        visible = false
    }

    override fun onPause(owner: LifecycleOwner) {
        protectionCheck.resetAuthorization()
    }
}
