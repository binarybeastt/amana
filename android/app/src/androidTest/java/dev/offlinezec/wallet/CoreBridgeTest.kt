package dev.offlinezec.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinezec.wallet.core.Core
import dev.offlinezec.wallet.core.CoreException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** P2.G1.T2 and T3: the app reaches offline-core, and Rust failures come back as exceptions. */
@RunWith(AndroidJUnit4::class)
class CoreBridgeTest {
    private val core = Core(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun t2_version_string() = runBlocking {
        val v = core.version()
        assertTrue("got: $v", v.startsWith("offline-core "))
    }

    private fun expectCoreException(what: String, block: suspend () -> Unit): String = runBlocking {
        try {
            block()
            fail("$what: no exception")
            ""
        } catch (e: CoreException) {
            e.message ?: ""
        }
    }

    @Test
    fun t3_rust_error_becomes_exception() {
        val m = expectCoreException("error") { core.testFailure(0) }
        assertTrue("got: $m", "forced Rust error" in m)
        runBlocking { assertTrue(core.version().startsWith("offline-core ")) }
    }

    @Test
    fun t3_rust_panic_becomes_exception() {
        val m = expectCoreException("panic") { core.testFailure(1) }
        assertTrue("got: $m", "panic" in m && "forced Rust panic" in m)
        // The core process survived the panic: it still answers.
        runBlocking { assertTrue(core.version().startsWith("offline-core ")) }
    }

    @Test
    fun t3_core_process_death_becomes_exception() {
        val m = expectCoreException("kill") { core.testFailure(2) }
        assertTrue("got: $m", m.isNotEmpty())
        // Android restarts the provider's process on the next call.
        runBlocking { assertTrue(core.version().startsWith("offline-core ")) }
    }

    @Test
    fun t3_bad_input_is_an_exception_not_a_crash() {
        val m = expectCoreException("missing db") { core.notes("/nonexistent/data.sqlite3") }
        assertTrue("got: $m", "no wallet database" in m)
    }
}
