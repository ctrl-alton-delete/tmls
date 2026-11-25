package dev.tmls.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    @Test
    fun prefixKeysBecomeBytes() {
        assertEquals("\u0002", SessionManager.prefixBytes("C-b"))
        assertEquals("\u0001", SessionManager.prefixBytes("C-a"))
        assertEquals("\u0000", SessionManager.prefixBytes("C-Space"))
        assertEquals("`", SessionManager.prefixBytes("`"))
        assertEquals("\u001ba", SessionManager.prefixBytes("M-a"))
        assertEquals("\u001d", SessionManager.prefixBytes("C-]"))
        assertNull(SessionManager.prefixBytes("F12"))
    }

    @Test
    fun endsAreDescribed() {
        assertTrue(SessionManager.describeEnd(255, "@@@ WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED! @@@").hostKeyChanged)
        assertTrue(SessionManager.describeEnd(255, "").message.contains("Connection"))
        assertTrue(SessionManager.describeEnd(0, "[detached]").message.startsWith("Session ended"))
    }
}
