package dev.tmls.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConfigTest {
    private val desktop = HostEntry("desktop", hostName = "desktop.tail.ts.net", user = "alton")

    @Test
    fun aFileWithoutMarkersIsAllUserText() {
        val config = SshConfig.parse("Host old\n  HostName 1.2.3.4\n")
        assertTrue(config.hosts.isEmpty())
        assertEquals("Host old\n  HostName 1.2.3.4", config.userText)
    }

    @Test
    fun renderThenParseGivesTheSameConfig() {
        val config = SshConfig(
            hosts = listOf(desktop, HostEntry("box", port = 2222, identityFile = "~/.ssh/id_box", proxyJump = "desktop")),
            userText = "Host legacy\n  HostName 10.0.0.1",
            defaultUser = "alton",
            strictHostKeys = true,
        )
        assertEquals(config, SshConfig.parse(config.render()))
    }

    @Test
    fun hostsComeFirstAndDefaultsLast() {
        val text = SshConfig(hosts = listOf(desktop), userText = "Host other\n  Port 1", defaultUser = "alton").render()
        val hostAt = text.indexOf("Host desktop")
        val userAt = text.indexOf("Host other")
        val defaultsAt = text.indexOf("Host *")
        assertTrue(hostAt in 0 until userAt && userAt < defaultsAt)
        assertTrue(text.contains("  User alton\n  StrictHostKeyChecking accept-new"))
    }

    @Test
    fun theUsersGlobalOptionsStayBeforeAnyHostBlock() {
        val text = SshConfig(hosts = listOf(desktop), userText = "ServerAliveInterval 30\n\nHost other\n  Port 1").render()
        assertTrue(text.indexOf("ServerAliveInterval 30") < text.indexOf("Host desktop"))
        assertEquals("ServerAliveInterval 30\n\nHost other\n  Port 1", SshConfig.parse(text).userText)
    }

    @Test
    fun withHostReplacesInPlaceAndRenames() {
        val config = SshConfig(hosts = listOf(desktop, HostEntry("b"), HostEntry("c")))
        val renamed = config.withHost(HostEntry("b2", hostName = "x"), replacing = "b")
        assertEquals(listOf("desktop", "b2", "c"), renamed.hosts.map { it.alias })
        assertEquals(listOf("desktop", "b", "c", "d"), config.withHost(HostEntry("d")).hosts.map { it.alias })
    }

    @Test
    fun unknownOptionsInAManagedBlockAreKept() {
        val text = SshConfig.HOSTS_BEGIN + "\nHost a\n  HostName h\n  ForwardAgent yes\n" + SshConfig.HOSTS_END + "\n"
        val config = SshConfig.parse(text)
        assertEquals(mapOf("forwardagent" to "yes"), config.host("a")!!.extra)
        assertTrue(config.render().contains("  forwardagent yes"))
    }

    @Test
    fun reservedAndUnsafeNamesAreRefused() {
        listOf("", "localhost", "tmls-app", "-x", "a b", "a*", "a/b").forEach {
            assertTrue(it, HostEntry(it).problems().containsKey("alias"))
        }
        assertTrue(HostEntry("ok", hostName = "a b").problems().containsKey("hostName"))
        assertTrue(HostEntry("ok", port = 70000).problems().containsKey("port"))
        assertFalse(desktop.problems().isNotEmpty())
    }
}
