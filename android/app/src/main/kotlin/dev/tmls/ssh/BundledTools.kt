package dev.tmls.ssh

import android.content.Context
import android.system.Os
import java.io.File

/**
 * The bundled OpenSSH programs and the environment they run in (spec ARCH-3, ARCH-6).
 *
 * Android only lets an app execute files from its `nativeLibraryDir`, and only `lib*.so` files
 * land there, so ssh ships as `libssh.so`. At start this makes `$APP/bin/ssh → libssh.so`
 * (and scp, ssh-keygen) and the directories ssh needs; [env] is what every process the app
 * starts gets: `$APP/bin` first on `PATH`, `HOME=$APP/home`.
 */
class BundledTools(context: Context) {
    val appDir: File = context.filesDir
    val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
    val binDir = File(appDir, "bin")
    val home = File(appDir, "home")
    val sshDir = File(home, ".ssh")
    val configDir = File(home, ".config/tmls")
    /** ControlMaster sockets (HOST-6); short enough for a Unix socket path (ARCH-6). */
    val controlDir = File(appDir, "run")
    val tmpDir: File = context.cacheDir

    fun program(name: String) = File(binDir, name)

    val env: Map<String, String>
        get() = mapOf(
            "PATH" to "${binDir.absolutePath}:/system/bin",
            "HOME" to home.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
        )

    /** Creates the directories and (re)links the programs; idempotent, cheap: call at every start. */
    fun install(): BundledTools {
        listOf(binDir, home, configDir, tmpDir).forEach { it.mkdirs() }
        for (dir in listOf(sshDir, controlDir)) {
            dir.mkdirs()
            Os.chmod(dir.absolutePath, "700".toInt(8))
        }
        for (name in PROGRAMS) {
            val target = File(nativeLibDir, "lib$name.so")
            val link = program(name)
            // nativeLibraryDir changes with every app update, so links are always remade
            if (link.exists() || isSymlink(link)) link.delete()
            if (target.exists()) Os.symlink(target.absolutePath, link.absolutePath)
        }
        return this
    }

    /** Programs that are linked and point at an existing file. */
    fun missing(): List<String> = PROGRAMS.filter { !program(it).canExecute() }

    private fun isSymlink(file: File) = runCatching { Os.readlink(file.absolutePath) }.isSuccess

    companion object {
        val PROGRAMS = listOf("ssh", "scp", "ssh-keygen")
    }
}
