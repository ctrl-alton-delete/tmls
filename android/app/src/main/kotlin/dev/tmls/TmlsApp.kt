package dev.tmls

import android.app.Application
import dev.tmls.core.setEnv
import dev.tmls.core.useControlMaster
import dev.tmls.settings.Settings
import dev.tmls.ssh.BundledTools
import dev.tmls.ssh.HostStore
import dev.tmls.ssh.Keys
import dev.tmls.term.SessionManager

/** Sets up the bundled programs and the core's environment once per process (ARCH-3). */
class TmlsApp : Application() {
    lateinit var tools: BundledTools
        private set
    lateinit var settings: Settings
        private set
    lateinit var hosts: HostStore
        private set
    lateinit var keys: Keys
        private set
    /** The open tabs (ARCH-5): as long-lived as the process. */
    lateinit var sessions: SessionManager
        private set

    /** Why the Rust core could not be reached, if it could not. */
    var coreError: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()
        tools = BundledTools(this).install()
        settings = Settings(this)
        hosts = HostStore(tools)
        keys = Keys(tools)
        coreError = runCatching {
            setEnv(tools.env)
            // HOST-6: every ssh the app starts shares one connection per host
            useControlMaster(tools.controlDir.absolutePath)
        }.exceptionOrNull()
        runCatching { hosts.ensureDefaults() }
        sessions = SessionManager(this, tools, settings)
    }
}
