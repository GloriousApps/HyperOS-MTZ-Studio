package dev.glorioustr.mtzstudio.tester

import java.io.IOException

data class PrivilegedCommandResult(
    val exitCode: Int,
    val output: String,
    val authorizationSource: String,
)

fun interface PrivilegedCommandRunner {
    fun run(command: String, timeoutSeconds: Long): PrivilegedCommandResult
}

class SuPrivilegedCommandRunner : PrivilegedCommandRunner {
    private val useGlobalMountNamespace: Boolean by lazy {
        try {
            val help = BoundedProcessOutput.collect(
                ProcessBuilder("su", "--help").redirectErrorStream(true).start(), 3,
            )
            !help.timedOut && SuCommandPolicy.supportsGlobalMountNamespace(help.output)
        } catch (error: Exception) {
            if (error is InterruptedException || error is java.util.concurrent.CancellationException) throw error
            false
        }
    }

    override fun run(command: String, timeoutSeconds: Long): PrivilegedCommandResult {
        val process = try {
            ProcessBuilder(SuCommandPolicy.arguments(command, useGlobalMountNamespace))
                .redirectErrorStream(true).start()
        } catch (error: IOException) {
            throw ThemeManagerUpdateException("Root command channel could not be started", error)
        }
        val result = BoundedProcessOutput.collect(process, timeoutSeconds)
        if (result.timedOut) throw ThemeManagerUpdateException("Root command timed out: ${result.output}")
        return PrivilegedCommandResult(
            exitCode = result.exitCode,
            output = result.output,
            authorizationSource = if (useGlobalMountNamespace) {
                "Root yöneticisi (su, global mount namespace)"
            } else {
                "Root yöneticisi (su)"
            },
        )
    }
}

class ThemeManagerUpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)
