package com.genius.hyperlyrics.root.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

object ShellUtils {

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = ProcessBuilder("su", "-c", "id -u")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@withContext false
            }
            process.exitValue() == 0 &&
                process.inputStream.bufferedReader().use { reader ->
                    reader.readText().lineSequence().any { it.trim() == "0" }
                }
        } catch (_: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    suspend fun killAppProcesses(packageNames: Collection<String>): Boolean {
        // libxposed service 102 can inspect or hot-reload targets, but it does not expose a
        // process-termination operation. Keep this destructive refresh behind an explicit root shell.
        val normalizedPackages = packageNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (normalizedPackages.isEmpty()) return true
        if (normalizedPackages.any { !PACKAGE_NAME_PATTERN.matches(it) }) return false

        val musicPackages = normalizedPackages.filterNot { it == SYSTEM_UI_PACKAGE }
        val shouldRestartSystemUi = SYSTEM_UI_PACKAGE in normalizedPackages
        val script = buildString {
            appendLine("user_id=\$(am get-current-user 2>/dev/null)")
            appendLine("case \"\$user_id\" in ''|*[!0-9]*) user_id=0 ;; esac")
            musicPackages.forEach { packageName ->
                appendLine("am force-stop --user \"\$user_id\" '$packageName' >/dev/null 2>&1 || exit 1")
            }
            if (shouldRestartSystemUi) {
                appendLine("systemui_pids=\$(pidof '$SYSTEM_UI_PACKAGE' 2>/dev/null)")
                appendLine("[ -n \"\$systemui_pids\" ] || exit 1")
                appendLine("for pid in \$systemui_pids; do kill -15 \"\$pid\" >/dev/null 2>&1 || true; done")
                appendLine("for pid in \$systemui_pids; do kill -9 \"\$pid\" >/dev/null 2>&1 || true; done")
            }
        }
        return execRootScriptSilent("nsenter --mount=/proc/1/ns/mnt -- sh", script)
    }

    suspend fun execRootScriptSilent(cmd: String, inputScript: String? = null): Boolean {
        return withContext(Dispatchers.IO) {
            var process: Process? = null
            var os: DataOutputStream? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                if (inputScript != null) {
                    os = DataOutputStream(process.outputStream)
                    os.write(inputScript.toByteArray(Charsets.UTF_8))
                    os.writeBytes("\nexit\n")
                    os.flush()
                }
                val exitCode = process.waitFor()
                return@withContext exitCode == 0
            } catch (_: Exception) {
                return@withContext false
            } finally {
                try { os?.close() } catch (_: Exception) {}
                process?.destroy()
            }
        }
    }

}
