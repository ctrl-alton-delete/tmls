package dev.tmls.ssh

import java.io.File

/**
 * `~/.config/tmls/hosts` (spec HOST-1): the hosts tmls lists, one ssh name per line, `#` starts
 * a comment; the same format the desktop's `client-core` reads. Edits keep comments and order.
 */
class HostsFile(private val file: File) {
    fun read(): List<String> = parse(if (file.exists()) file.readText() else "")

    fun add(host: String) {
        if (host in read()) return
        val text = if (file.exists()) file.readText() else ""
        write(text.trimEnd('\n').let { if (it.isEmpty()) "$host\n" else "$it\n$host\n" })
    }

    fun remove(host: String) {
        if (!file.exists()) return
        write(file.readText().lines().filter { strip(it) != host }.joinToString("\n"))
    }

    fun rename(from: String, to: String) {
        if (!file.exists() || from !in read()) return add(to)
        write(file.readText().lines().map { if (strip(it) == from) it.replaceFirst(from, to) else it }.joinToString("\n"))
    }

    private fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    companion object {
        fun parse(text: String): List<String> = text.lines().map(::strip).filter { it.isNotEmpty() }.distinct()

        private fun strip(line: String) = line.substringBefore('#').trim()
    }
}
