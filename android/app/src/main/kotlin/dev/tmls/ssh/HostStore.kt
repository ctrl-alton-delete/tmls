package dev.tmls.ssh

import android.system.Os
import java.io.File

/**
 * The two files the host editor writes (spec HOST-1, HOST-2): `~/.ssh/config` ([SshConfig]) and
 * `~/.config/tmls/hosts` ([HostsFile]).
 */
class HostStore(tools: BundledTools) {
    val configFile = File(tools.sshDir, "config")
    val hostsFile = HostsFile(File(tools.configDir, "hosts"))

    fun config(): SshConfig = SshConfig.parse(rawConfig())

    fun rawConfig(): String = if (configFile.exists()) configFile.readText() else ""

    fun saveConfig(config: SshConfig) = saveRaw(config.render())

    /** The raw editor's save (HOST-2); the app's sections are re-read from it next time. */
    fun saveRaw(text: String) {
        val tmp = File(configFile.path + ".tmp")
        tmp.writeText(text)
        Os.chmod(tmp.path, "600".toInt(8))
        tmp.renameTo(configFile)
    }

    /** The hosts the picker offers: the hosts file, then managed hosts it does not list. */
    fun hosts(): List<String> = (hostsFile.read() + config().hosts.map { it.alias }).distinct()

    fun saveHost(entry: HostEntry, replacing: String?) {
        saveConfig(config().withHost(entry, replacing ?: entry.alias))
        if (replacing != null && replacing != entry.alias) hostsFile.rename(replacing, entry.alias) else hostsFile.add(entry.alias)
    }

    fun deleteHost(alias: String) {
        saveConfig(config().withoutHost(alias))
        hostsFile.remove(alias)
    }

    /** Settings' "default user" and "strict host keys" (HOST-2, SEC-5). */
    fun saveDefaults(defaultUser: String, strictHostKeys: Boolean) =
        saveConfig(config().copy(defaultUser = defaultUser.trim(), strictHostKeys = strictHostKeys))

    /** Makes sure the defaults section exists (accept-new, SEC-5) without touching anything else. */
    fun ensureDefaults() {
        val raw = rawConfig()
        if (!raw.contains(SshConfig.DEFAULTS_BEGIN)) saveConfig(SshConfig.parse(raw))
    }
}
