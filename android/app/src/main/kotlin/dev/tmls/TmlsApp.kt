package dev.tmls

import android.app.Application
import dev.tmls.core.setEnv
import dev.tmls.ssh.BundledTools

/** Sets up the bundled programs and the core's environment once per process (ARCH-3). */
class TmlsApp : Application() {
    lateinit var tools: BundledTools
        private set

    /** Why the Rust core could not be reached, if it could not. */
    var coreError: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()
        tools = BundledTools(this).install()
        coreError = runCatching { setEnv(tools.env) }.exceptionOrNull()
    }
}
