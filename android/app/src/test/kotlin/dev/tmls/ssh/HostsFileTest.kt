package dev.tmls.ssh

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HostsFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun readsLikeClientCore() {
        assertEquals(listOf("a", "b"), HostsFile.parse("# hosts\na  # the desktop\n\n b\na\n"))
    }

    @Test
    fun addRemoveRenameKeepComments() {
        val file = File(tmp.root, "hosts")
        file.writeText("# mine\na\n")
        val hosts = HostsFile(file)
        hosts.add("b")
        hosts.add("a")
        assertEquals(listOf("a", "b"), hosts.read())
        hosts.rename("a", "z")
        hosts.remove("b")
        assertEquals(listOf("z"), hosts.read())
        assertEquals("# mine", file.readText().lines().first())
    }

    @Test
    fun addCreatesTheFile() {
        val hosts = HostsFile(File(tmp.root, "dir/hosts"))
        hosts.add("phone")
        assertEquals(listOf("phone"), hosts.read())
    }
}
