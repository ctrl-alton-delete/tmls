package dev.tmls.ssh

/**
 * The app's `~/.ssh/config` (spec HOST-1, HOST-2, SEC-5): the hosts the host editor manages, the
 * user's own text, and the app's defaults, in that order, because ssh takes the first value it
 * finds for each option:
 *
 * ```
 * # >>> tmls hosts …
 * Host desktop
 *   HostName desktop.tailnet.ts.net
 *   User alton
 * # <<< tmls hosts
 * (anything the user wrote, kept as it is)
 * # >>> tmls defaults …
 * Host *
 *   User alton
 *   StrictHostKeyChecking accept-new
 * # <<< tmls defaults
 * ```
 *
 * A file without the markers (written by hand, or by the M1 spike) is all user text.
 */
data class SshConfig(
    val hosts: List<HostEntry> = emptyList(),
    val userText: String = "",
    /** `User` for every host that sets none (HOST-2); empty: ssh's default, the app's uid name. */
    val defaultUser: String = "",
    /** `StrictHostKeyChecking yes` instead of `accept-new` (SEC-5). */
    val strictHostKeys: Boolean = false,
) {
    fun host(alias: String): HostEntry? = hosts.firstOrNull { it.alias == alias }

    /** This config with [entry] added, or replacing the entry called [replacing] (default: its own alias). */
    fun withHost(entry: HostEntry, replacing: String = entry.alias): SshConfig {
        val index = hosts.indexOfFirst { it.alias == replacing }
        val rest = hosts.filter { it.alias != replacing && it.alias != entry.alias }
        val list = if (index < 0) rest + entry else rest.toMutableList().apply { add(minOf(index, size), entry) }
        return copy(hosts = list)
    }

    fun withoutHost(alias: String): SshConfig = copy(hosts = hosts.filter { it.alias != alias })

    fun render(): String = buildString {
        // the user's options before their first Host/Match apply to every host, so they stay
        // first: after a managed `Host` line they would belong to that host
        val lines = userText.trim('\n').lines()
        val firstBlock = lines.indexOfFirst { BLOCK.containsMatchIn(it) }.let { if (it < 0) lines.size else it }
        val global = lines.take(firstBlock).joinToString("\n").trim('\n')
        val text = lines.drop(firstBlock).joinToString("\n").trim('\n')
        if (global.isNotEmpty()) append(global).append("\n\n")
        if (hosts.isNotEmpty()) {
            append(HOSTS_BEGIN).append('\n')
            hosts.forEach { append(it.render()).append('\n') }
            append(HOSTS_END).append('\n')
        }
        if (text.isNotEmpty()) append(text).append("\n\n")
        append(DEFAULTS_BEGIN).append('\n')
        append("Host *\n")
        if (defaultUser.isNotEmpty()) append("  User ").append(defaultUser).append('\n')
        append("  StrictHostKeyChecking ").append(if (strictHostKeys) "yes" else "accept-new").append('\n')
        append(DEFAULTS_END).append('\n')
    }

    companion object {
        const val HOSTS_BEGIN = "# >>> tmls hosts (written by the app: edit them in the app) >>>"
        const val HOSTS_END = "# <<< tmls hosts <<<"
        const val DEFAULTS_BEGIN = "# >>> tmls defaults (written by the app: edit them in Settings) >>>"
        const val DEFAULTS_END = "# <<< tmls defaults <<<"
        private val BLOCK = Regex("^\\s*(Host|Match)(\\s|=)", RegexOption.IGNORE_CASE)

        fun parse(text: String): SshConfig {
            val lines = text.lines()
            val hosts = section(lines, HOSTS_BEGIN, HOSTS_END)
            val defaults = section(lines, DEFAULTS_BEGIN, DEFAULTS_END)
            val managed = (hosts?.let { it.first..it.last } ?: IntRange.EMPTY).toSet() +
                (defaults?.let { it.first..it.last } ?: IntRange.EMPTY).toSet()
            val user = lines.filterIndexed { i, _ -> i !in managed }.joinToString("\n").trim('\n')
            val defaultBlock = defaults?.let { blocks(lines.subList(it.first + 1, it.last)) }?.firstOrNull()
            return SshConfig(
                hosts = hosts?.let { blocks(lines.subList(it.first + 1, it.last)) }.orEmpty(),
                userText = user,
                defaultUser = defaultBlock?.user.orEmpty(),
                strictHostKeys = defaultBlock?.extra?.get("stricthostkeychecking") == "yes",
            )
        }

        /** The line numbers of a marked section, markers included, or null without both markers. */
        private fun section(lines: List<String>, begin: String, end: String): IntRange? {
            val from = lines.indexOfFirst { it.trim() == begin }
            if (from < 0) return null
            val to = (from + 1 until lines.size).firstOrNull { lines[it].trim() == end } ?: return null
            return from..to
        }

        private fun blocks(lines: List<String>): List<HostEntry> {
            val out = mutableListOf<HostEntry>()
            var current: HostEntry? = null
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val (key, value) = line.split(Regex("\\s+|\\s*=\\s*"), limit = 2).let { it[0].lowercase() to it.getOrElse(1) { "" }.trim() }
                if (key == "host") {
                    current?.let(out::add)
                    current = HostEntry(alias = value)
                    continue
                }
                val c = current ?: continue
                current = when (key) {
                    "hostname" -> c.copy(hostName = value)
                    "user" -> c.copy(user = value)
                    "port" -> c.copy(port = value.toIntOrNull())
                    "identityfile" -> c.copy(identityFile = value)
                    "proxyjump" -> c.copy(proxyJump = value)
                    else -> c.copy(extra = c.extra + (key to value))
                }
            }
            current?.let(out::add)
            return out
        }
    }
}

/** One host the editor manages (HOST-2). Empty fields are left out of the config. */
data class HostEntry(
    val alias: String,
    val hostName: String = "",
    val user: String = "",
    val port: Int? = null,
    val identityFile: String = "",
    val proxyJump: String = "",
    /** Other options found in the managed block, kept as they were (lower-case keys). */
    val extra: Map<String, String> = emptyMap(),
) {
    fun render(): String = buildString {
        append("Host ").append(alias).append('\n')
        if (hostName.isNotEmpty()) append("  HostName ").append(hostName).append('\n')
        if (user.isNotEmpty()) append("  User ").append(user).append('\n')
        port?.let { append("  Port ").append(it).append('\n') }
        if (identityFile.isNotEmpty()) append("  IdentityFile ").append(identityFile).append('\n')
        if (proxyJump.isNotEmpty()) append("  ProxyJump ").append(proxyJump).append('\n')
        extra.forEach { (k, v) -> append("  ").append(k).append(' ').append(v).append('\n') }
    }

    /** Why this entry can't be saved, field by field; empty when it can. */
    fun problems(): Map<String, String> = buildMap {
        when {
            alias.isEmpty() -> put("alias", "needs a name")
            alias.any { it.isWhitespace() || it in "*?!,#\"'" } -> put("alias", "no spaces, quotes or * ? ! , #")
            alias.startsWith("-") -> put("alias", "can't start with -")
            alias == "localhost" -> put("alias", "localhost is reserved; call the phone e.g. phone (HOST-7)")
            alias == "tmls-app" -> put("alias", "tmls-app is reserved")
            alias.contains("/") -> put("alias", "no / (tmls uses it for a tmux server: host/server)")
        }
        fun plain(field: String, value: String) {
            if (value.any { it.isWhitespace() || it == '"' || it == '#' }) put(field, "no spaces, quotes or #")
        }
        plain("hostName", hostName)
        plain("user", user)
        plain("identityFile", identityFile)
        plain("proxyJump", proxyJump)
        if (port != null && port !in 1..65535) put("port", "1–65535")
    }
}
