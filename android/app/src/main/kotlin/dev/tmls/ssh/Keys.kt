package dev.tmls.ssh

import android.system.Os
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** One private key in `~/.ssh` and its public half, if known. */
data class SshKey(val file: File, val publicKey: String?) {
    val name: String get() = file.name
    /** How the host editor's IdentityFile names it. */
    val configPath: String get() = "~/.ssh/${file.name}"
}

/**
 * The app's ssh keys (spec HOST-3, SEC-2): private keys live in `$HOME/.ssh` (app-private), made
 * by the bundled `ssh-keygen` or imported from a file. `id_ed25519` is one ssh tries by itself;
 * others are chosen per host (IdentityFile).
 */
class Keys(private val tools: BundledTools) {
    fun list(): List<SshKey> =
        tools.sshDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("id_") && !it.name.endsWith(".pub") }
            .sortedBy { it.name }
            .map { SshKey(it, File(it.path + ".pub").takeIf(File::isFile)?.readText()?.trim()) }

    /** Makes `~/.ssh/<name>` (ed25519); [passphrase] may be empty. Fails if the file exists. */
    fun generate(name: String = "id_ed25519", passphrase: String = ""): SshKey {
        val file = File(tools.sshDir, checkName(name))
        require(!file.exists()) { "${file.name} already exists" }
        run(listOf("ssh-keygen", "-q", "-t", "ed25519", "-N", passphrase, "-C", "tmls@android", "-f", file.path))
        return list().first { it.file == file }
    }

    /**
     * Copies a private key in as `~/.ssh/<name>` (mode 600) and derives its public key when it has
     * no passphrase, or with [passphrase]. Returns the key; its public half is null when it could not
     * be derived (encrypted and no passphrase given): ssh still uses it and asks in the terminal.
     */
    fun import(name: String, input: InputStream, passphrase: String = ""): SshKey {
        val file = File(tools.sshDir, checkName(name))
        require(!file.exists()) { "${file.name} already exists" }
        val bytes = input.readBytes()
        require(String(bytes).contains("PRIVATE KEY")) { "that file is not a private key" }
        file.writeBytes(bytes)
        Os.chmod(file.path, "600".toInt(8))
        runCatching { run(listOf("ssh-keygen", "-y", "-P", passphrase, "-f", file.path)) }
            .onSuccess { File(file.path + ".pub").writeText(it.trim() + "\n") }
        return list().first { it.file == file }
    }

    fun delete(key: SshKey) {
        key.file.delete()
        File(key.file.path + ".pub").delete()
    }

    /** Forgets [host]'s key in known_hosts (SEC-5: after a host key changed on purpose). */
    fun forgetHostKey(host: String): String =
        run(listOf("ssh-keygen", "-R", host, "-f", File(tools.sshDir, "known_hosts").path))

    private fun checkName(name: String): String {
        require(Regex("id_[A-Za-z0-9_.-]+").matches(name) && !name.endsWith(".pub")) {
            "a key name starts with id_ and has only letters, digits, _ . -"
        }
        return name
    }

    private fun run(argv: List<String>): String {
        val process = ProcessBuilder(listOf(tools.program(argv[0]).path) + argv.drop(1))
            .redirectErrorStream(true)
            .apply { environment().putAll(tools.env) }
            .start()
        process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "${argv[0]} timed out" }
        check(process.exitValue() == 0) { out.trim().ifEmpty { "${argv[0]} failed (${process.exitValue()})" } }
        return out
    }
}
