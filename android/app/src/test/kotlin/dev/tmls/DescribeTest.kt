package dev.tmls

import org.junit.Assert.assertEquals
import org.junit.Test

class DescribeTest {
    @Test
    fun showsVersionAndRuleCount() {
        assertEquals(
            listOf("core 0.1.0", "27 agent detection rules"),
            describe(Result.success(CoreInfo("core 0.1.0", 27))),
        )
    }

    @Test
    fun showsWhyTheCoreFailed() {
        val lines = describe(Result.failure(UnsatisfiedLinkError("no core_ffi")))
        assertEquals("core failed to load", lines[0])
    }
}
