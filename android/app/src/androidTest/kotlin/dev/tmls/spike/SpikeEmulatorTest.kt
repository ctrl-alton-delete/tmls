package dev.tmls.spike

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tmls.TmlsApp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Plan M1 on the emulator (CI): every spike check against the test host the workflow runs on
 * the CI machine (tooling/android/testhost: sshd + tmux), reached at 10.0.2.2:2222 with a key the
 * workflow passes in as the instrumentation argument `sshKey` (base64 of the private key).
 * Without that argument only the offline checks run.
 */
@RunWith(AndroidJUnit4::class)
class SpikeEmulatorTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TmlsApp
    private val checks = SpikeChecks(app.tools)
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun offlineChecksPass() {
        checks.writeDefaultConfig(SpikeChecks.defaultConfig("", ""))
        val results = checks.runAll("")
        println(SpikeChecks.report(results))
        val failed = results.filter { it.outcome == SpikeChecks.Outcome.FAIL }
        assertTrue(SpikeChecks.report(failed), failed.isEmpty())
    }

    @Test
    fun hostChecksPassAgainstTestHost() {
        val key = args.getString("sshKey")
        assumeTrue("no sshKey argument: no test host", !key.isNullOrBlank())
        val identity = File(app.tools.sshDir, "id_testhost")
        identity.writeBytes(Base64.decode(key, Base64.DEFAULT))
        android.system.Os.chmod(identity.absolutePath, "600".toInt(8))
        checks.writeDefaultConfig(
            SpikeChecks.defaultConfig("", "") +
                "\nHost testhost\n  HostName 10.0.2.2\n  Port 2222\n  User tmls\n  IdentityFile ~/.ssh/id_testhost\n",
        )
        val results = checks.runAll("testhost")
        println(SpikeChecks.report(results))
        val failed = results.filter { it.outcome != SpikeChecks.Outcome.PASS }
        assertTrue(SpikeChecks.report(failed), failed.isEmpty())
    }
}
