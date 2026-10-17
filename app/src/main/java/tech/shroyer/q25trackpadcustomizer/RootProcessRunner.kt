package tech.shroyer.q25trackpadcustomizer

import android.os.Build
import android.os.SystemClock
import java.io.IOException

/** Bounded process cleanup shared by short su commands and the monitor child. */
internal object RootProcessRunner {
    data class Result(
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean = false,
        val error: Exception? = null
    ) {
        val successful: Boolean get() = exitCode == 0 && !timedOut && error == null
    }

    fun runSu(command: String, timeoutMs: Long): Result {
        var process: Process? = null
        val output = StringBuilder()
        return try {
            val child = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = child
            val outputPump = startOutputPump(child) { line ->
                synchronized(output) {
                    if (output.length < MAX_CAPTURE_CHARS) {
                        output.append(line.take(MAX_CAPTURE_CHARS - output.length)).append('\n')
                    }
                }
            }
            val exit = waitForExit(child, timeoutMs)
            if (exit == null) {
                val stopped = terminate(child)
                Result(null, "", timedOut = true,
                    error = if (stopped) null else IOException("su process did not exit after destroy"))
            } else {
                outputPump.join(PUMP_JOIN_MS)
                if (outputPump.isAlive) closeStreams(child)
                Result(exit, synchronized(output) { output.toString() })
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process?.let { terminate(it) }
            Result(null, "", error = e)
        } catch (e: Exception) {
            process?.let { terminate(it) }
            Result(null, "", error = e)
        } finally {
            process?.let { closeStreams(it) }
        }
    }

    fun startOutputPump(process: Process, onLine: (String) -> Unit): Thread =
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach(onLine)
                }
            } catch (_: IOException) {
                // Closing the process streams during shutdown also ends this reader.
            }
        }, "Q25 su output").apply {
            isDaemon = true
            start()
        }

    /** Returns null when the process has not exited before the deadline. */
    @Throws(InterruptedException::class)
    fun waitForExit(process: Process, timeoutMs: Long): Int? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            exitCodeOrNull(process)?.let { return it }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return null
            Thread.sleep(minOf(POLL_MS, remaining))
        }
    }

    fun exitCodeOrNull(process: Process): Int? = try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

    /** Best effort: stop the su wrapper, then force it if the platform supports it. */
    fun terminate(process: Process): Boolean {
        if (exitCodeOrNull(process) != null) return true
        var interrupted = Thread.interrupted()
        try {
            try { process.destroy() } catch (_: Exception) {}
            try {
                if (waitForExit(process, DESTROY_GRACE_MS) != null) return true
            } catch (_: InterruptedException) {
                interrupted = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { process.destroyForcibly() } catch (_: Exception) {}
            }
            return try {
                waitForExit(process, FORCE_GRACE_MS) != null
            } catch (_: InterruptedException) {
                interrupted = true
                exitCodeOrNull(process) != null
            }
        } finally {
            closeStreams(process)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun closeStreams(process: Process) {
        try { process.outputStream.close() } catch (_: Exception) {}
        try { process.inputStream.close() } catch (_: Exception) {}
        try { process.errorStream.close() } catch (_: Exception) {}
    }

    private const val MAX_CAPTURE_CHARS = 4_096
    private const val POLL_MS = 50L
    private const val DESTROY_GRACE_MS = 300L
    private const val FORCE_GRACE_MS = 500L
    private const val PUMP_JOIN_MS = 200L
}
