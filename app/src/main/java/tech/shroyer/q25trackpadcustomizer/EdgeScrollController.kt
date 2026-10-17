package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

internal enum class EdgeScrollDirection { UP, DOWN, LEFT, RIGHT }

internal data class EdgeScrollTrace(
    val id: String,
    val eventUptimeMs: Long,
    val rootReceiveUptimeMs: Long,
    val rootDecisionUptimeMs: Long,
    val rootSendStartUptimeMs: Long,
    val appReceiveUptimeMs: Long = 0L,
    val mainUptimeMs: Long = 0L
)

internal data class EdgeScrollRequest(
    val trace: EdgeScrollTrace? = null,
    val horizontalDirection: EdgeScrollDirection? = null,
    val horizontalAmount: Int = 0,
    val horizontalInput: Float = 0f,
    val horizontalAccumulator: Float = 0f,
    val verticalDirection: EdgeScrollDirection? = null,
    val verticalAmount: Int = 0,
    val verticalInput: Float = 0f,
    val verticalAccumulator: Float = 0f
)

internal sealed class EdgeScrollStartResult {
    data class Started(val token: String) : EdgeScrollStartResult()
    object WaitingForPrevious : EdgeScrollStartResult()
    object Failed : EdgeScrollStartResult()
}

/** Owns one passive root monitor session while the logical mode is Mouse. */
internal class EdgeScrollController(
    context: Context,
    private val diagnostics: EdgeScrollDiagnostics,
    private val onScroll: (EdgeScrollRequest) -> Unit,
    private val onGuardActivity: (Long) -> Unit,
    private val onSessionConnected: (String) -> Unit,
    private val onSessionStopped: (String, String) -> Unit
) {
    private val appContext = context.applicationContext
    private val controlFile = File(appContext.filesDir, EdgeScrollContract.CONTROL_FILE)
    private val worker = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var active = false
    private var activeToken: String? = null
    private var connected = false
    private var startupTimeout: Runnable? = null
    private var heartbeatTimeout: Runnable? = null
    private var lastPingAtMs = 0L
    // A stopped root child must exit before another InputMonitor is launched.
    private val rootChildrenPending = HashSet<String>()
    private var serverSocket: LocalServerSocket? = null
    private var eventSocket: LocalSocket? = null

    @Synchronized
    fun start(
        displayId: Int,
        displayWidthPx: Int,
        displayHeightPx: Int,
        verticalLevel: Int,
        horizontalLevel: Int,
        distanceLevel: Int,
        actionScroll: Boolean
    ): EdgeScrollStartResult {
        if (active) return activeToken?.let { EdgeScrollStartResult.Started(it) }
            ?: EdgeScrollStartResult.Failed
        if (rootChildrenPending.isNotEmpty()) return EdgeScrollStartResult.WaitingForPrevious

        val token = UUID.randomUUID().toString().replace("-", "")
        val socketName = "q25_edge_$token"
        val server = try {
            LocalServerSocket(socketName)
        } catch (e: Exception) {
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR local socket create failed", e)
            return EdgeScrollStartResult.Failed
        }

        try {
            FileOutputStream(controlFile, false).use { output ->
                output.write(token.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
        } catch (e: Exception) {
            try { server.close() } catch (_: Exception) {}
            if (controlFile.exists() && !controlFile.delete()) {
                Log.w(EdgeScrollContract.APP_TAG, "MONITOR_ERROR control token delete failed")
            }
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR control token create failed", e)
            return EdgeScrollStartResult.Failed
        }

        active = true
        activeToken = token
        connected = false
        serverSocket = server
        eventSocket = null
        Log.i(
            EdgeScrollContract.APP_TAG,
            "MONITOR_START displayId=$displayId width=$displayWidthPx " +
            "height=$displayHeightPx verticalLevel=$verticalLevel " +
                "horizontalLevel=$horizontalLevel distanceLevel=$distanceLevel " +
                "actionScroll=$actionScroll"
        )

        val timeout = Runnable { failSession(token, "startup timeout waiting for monitor PING", null, true) }
        startupTimeout = timeout
        mainHandler.postDelayed(timeout, STARTUP_TIMEOUT_MS)
        rootChildrenPending.add(token)
        try {
            worker.execute { acceptEvents(token, server) }
            worker.execute {
                try {
                    runRootChild(
                        token = token,
                        socketName = socketName,
                        displayId = displayId,
                        displayWidthPx = displayWidthPx,
                        displayHeightPx = displayHeightPx,
                        verticalLevel = verticalLevel,
                        horizontalLevel = horizontalLevel,
                        distanceLevel = distanceLevel,
                        actionScroll = actionScroll
                    )
                } finally {
                    synchronized(this) { rootChildrenPending.remove(token) }
                }
            }
        } catch (e: RuntimeException) {
            rootChildrenPending.remove(token)
            stop("worker launch failed")
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR worker launch failed", e)
            return EdgeScrollStartResult.Failed
        }
        return EdgeScrollStartResult.Started(token)
    }

    @Synchronized
    fun stop(reason: String) {
        if (!active && activeToken == null) return
        active = false
        activeToken = null
        connected = false
        cancelStartupTimeoutLocked()
        cancelHeartbeatTimeoutLocked()
        if (controlFile.exists() && !controlFile.delete()) {
            Log.w(EdgeScrollContract.APP_TAG, "MONITOR_ERROR control token delete failed")
        }
        closeSocketsLocked()
        Log.i(EdgeScrollContract.APP_TAG, "MONITOR_STOP reason=$reason")
    }

    fun shutdown() {
        stop("service destroyed")
        worker.shutdownNow()
    }

    @Synchronized
    fun isConnected(token: String): Boolean = isActiveSessionLocked(token) && connected

    private fun acceptEvents(token: String, server: LocalServerSocket) {
        var client: LocalSocket? = null
        try {
            val accepted = server.accept()
            client = accepted
            synchronized(this) {
                if (!isActiveSessionLocked(token)) {
                    accepted.close()
                    return
                }
                eventSocket = accepted
            }

            BufferedReader(InputStreamReader(accepted.inputStream, StandardCharsets.UTF_8)).use { reader ->
                if (reader.readLine() != "HELLO|$token") {
                    failSession(token, "invalid event channel handshake", null)
                    return
                }
                while (isActiveSession(token)) {
                    val line = reader.readLine() ?: break
                    val receivedAt = SystemClock.uptimeMillis()
                    if (line == "PING") {
                        markConnected(token)
                        continue
                    }
                    handleProtocolLine(token, line, receivedAt)
                }
            }
            if (isActiveSession(token)) {
                failSession(token, "event channel closed", null)
            }
        } catch (e: Exception) {
            if (isActiveSession(token)) {
                failSession(token, "event channel failure", e)
            }
        } finally {
            try { client?.close() } catch (_: Exception) {}
        }
    }

    @Synchronized
    private fun markConnected(token: String) {
        if (!isActiveSessionLocked(token)) return
        val firstPing = !connected
        connected = true
        lastPingAtMs = SystemClock.elapsedRealtime()
        cancelStartupTimeoutLocked()
        scheduleHeartbeatTimeoutLocked(token)
        if (firstPing) {
            Log.i(EdgeScrollContract.APP_TAG, "MONITOR_CONNECTED root monitor ready")
            mainHandler.post {
                if (isConnected(token)) onSessionConnected(token)
            }
        }
    }

    private fun scheduleHeartbeatTimeoutLocked(token: String, delayMs: Long = HEARTBEAT_TIMEOUT_MS) {
        cancelHeartbeatTimeoutLocked()
        val timeout = Runnable { checkHeartbeat(token) }
        heartbeatTimeout = timeout
        mainHandler.postDelayed(timeout, delayMs)
    }

    @Synchronized
    private fun checkHeartbeat(token: String) {
        if (!isActiveSessionLocked(token) || !connected) return
        val remaining = HEARTBEAT_TIMEOUT_MS - (SystemClock.elapsedRealtime() - lastPingAtMs)
        if (remaining > 0L) {
            scheduleHeartbeatTimeoutLocked(token, remaining)
        } else {
            failSession(token, "event heartbeat timeout", null)
        }
    }

    private fun handleProtocolLine(token: String, line: String, receivedAt: Long) {
        val parts = line.split('|')
        if (parts.firstOrNull() == "GUARD_ACTIVITY") {
            if (parts.size != 2) return
            val eventTimeMs = parts[1].toLongOrNull() ?: return
            if (!isActiveSession(token)) return
            val postedAt = SystemClock.uptimeMillis()
            mainHandler.post {
                diagnostics.recordGuardQueue(SystemClock.uptimeMillis() - postedAt)
                if (isActiveSession(token)) onGuardActivity(eventTimeMs)
            }
            return
        }
        val request = when (parts.firstOrNull()) {
            "SCROLL" -> parseSingleAxis(parts, receivedAt)
            "DIAGONAL" -> parseDiagonal(parts, receivedAt)
            else -> null
        } ?: return
        if (!isActiveSession(token)) return
        val postedAt = SystemClock.uptimeMillis()
        mainHandler.post {
            diagnostics.recordScrollQueue(SystemClock.uptimeMillis() - postedAt)
            if (isActiveSession(token)) {
                onScroll(request.copy(trace = request.trace?.copy(
                    mainUptimeMs = SystemClock.uptimeMillis()
                )))
            }
        }
    }

    private fun parseDirection(value: String): EdgeScrollDirection? {
        return when (value) {
            "UP" -> EdgeScrollDirection.UP
            "DOWN" -> EdgeScrollDirection.DOWN
            "LEFT" -> EdgeScrollDirection.LEFT
            "RIGHT" -> EdgeScrollDirection.RIGHT
            else -> null
        }
    }

    private fun parseTrace(parts: List<String>, offset: Int, receivedAt: Long): EdgeScrollTrace? {
        if (parts.size != offset + 5) return null
        return EdgeScrollTrace(
            id = parts[offset].takeIf { it.isNotBlank() } ?: return null,
            eventUptimeMs = parts[offset + 1].toLongOrNull() ?: return null,
            rootReceiveUptimeMs = parts[offset + 2].toLongOrNull() ?: return null,
            rootDecisionUptimeMs = parts[offset + 3].toLongOrNull() ?: return null,
            rootSendStartUptimeMs = parts[offset + 4].toLongOrNull() ?: return null,
            appReceiveUptimeMs = receivedAt
        )
    }

    private fun parseSingleAxis(parts: List<String>, receivedAt: Long): EdgeScrollRequest? {
        if (parts.size != 5 && parts.size != 10) return null
        val trace = if (parts.size == 10) parseTrace(parts, 5, receivedAt) else null
        val direction = parseDirection(parts[1]) ?: return null
        val amount = parts[2].toIntOrNull() ?: return null
        val input = parts[3].toFloatOrNull() ?: return null
        val accumulator = parts[4].toFloatOrNull() ?: return null
        return if (direction == EdgeScrollDirection.LEFT ||
            direction == EdgeScrollDirection.RIGHT
        ) {
            EdgeScrollRequest(
                trace = trace,
                horizontalDirection = direction,
                horizontalAmount = amount,
                horizontalInput = input,
                horizontalAccumulator = accumulator
            )
        } else {
            EdgeScrollRequest(
                trace = trace,
                verticalDirection = direction,
                verticalAmount = amount,
                verticalInput = input,
                verticalAccumulator = accumulator
            )
        }
    }

    private fun parseDiagonal(parts: List<String>, receivedAt: Long): EdgeScrollRequest? {
        if (parts.size != 9 && parts.size != 14) return null
        val trace = if (parts.size == 14) parseTrace(parts, 9, receivedAt) else null
        val horizontalDirection = parseDirection(parts[1]) ?: return null
        val verticalDirection = parseDirection(parts[5]) ?: return null
        if (horizontalDirection != EdgeScrollDirection.LEFT &&
            horizontalDirection != EdgeScrollDirection.RIGHT
        ) return null
        if (verticalDirection != EdgeScrollDirection.UP &&
            verticalDirection != EdgeScrollDirection.DOWN
        ) return null
        return EdgeScrollRequest(
            trace = trace,
            horizontalDirection = horizontalDirection,
            horizontalAmount = parts[2].toIntOrNull() ?: return null,
            horizontalInput = parts[3].toFloatOrNull() ?: return null,
            horizontalAccumulator = parts[4].toFloatOrNull() ?: return null,
            verticalDirection = verticalDirection,
            verticalAmount = parts[6].toIntOrNull() ?: return null,
            verticalInput = parts[7].toFloatOrNull() ?: return null,
            verticalAccumulator = parts[8].toFloatOrNull() ?: return null
        )
    }

    private fun runRootChild(
        token: String,
        socketName: String,
        displayId: Int,
        displayWidthPx: Int,
        displayHeightPx: Int,
        verticalLevel: Int,
        horizontalLevel: Int,
        distanceLevel: Int,
        actionScroll: Boolean
    ) {
        if (!isActiveSession(token)) return
        val apkPath = appContext.applicationInfo.sourceDir
        val command = "export CLASSPATH=${shellQuote(apkPath)}" +
            "; exec /system/bin/app_process" +
            " -Xhidden-api-policy:disabled" +
            " -Xtarget-sdk-version:34" +
            " /system/bin ${EdgeScrollContract.ROOT_MAIN_CLASS}" +
            " --control ${shellQuote(controlFile.absolutePath)}" +
            " --token ${shellQuote(token)}" +
            " --socket ${shellQuote(socketName)}" +
            " --display-id $displayId" +
            " --display-width $displayWidthPx" +
            " --display-height $displayHeightPx" +
            " --vertical-level $verticalLevel" +
            " --horizontal-level $horizontalLevel" +
            " --distance-level $distanceLevel" +
            " --action-scroll ${if (actionScroll) 1 else 0}"

        var child: Process? = null
        var outputPump: Thread? = null
        var interrupted = false
        try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            child = process
            outputPump = RootProcessRunner.startOutputPump(process) { line ->
                if (line.isNotBlank()) {
                    Log.w(EdgeScrollContract.APP_TAG, "MONITOR_ERROR su: $line")
                }
            }
            var stopRequestedAtMs = 0L
            while (true) {
                val result = RootProcessRunner.waitForExit(process, ROOT_CHILD_POLL_MS)
                if (result != null) {
                    if (isActiveSession(token)) {
                        failSession(token, "root child exited code=$result", null)
                    }
                    break
                }
                if (!isActiveSession(token)) {
                    if (stopRequestedAtMs == 0L) stopRequestedAtMs = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - stopRequestedAtMs >= ROOT_CHILD_STOP_GRACE_MS) {
                        if (!RootProcessRunner.terminate(process)) {
                            Log.e(EdgeScrollContract.APP_TAG,
                                "MONITOR_ERROR root child did not exit after destroy")
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (isActiveSession(token)) {
                failSession(token, "root child start/wait failed", e)
            }
            if (e is InterruptedException) interrupted = true
        } finally {
            child?.let {
                if (RootProcessRunner.exitCodeOrNull(it) == null &&
                    !RootProcessRunner.terminate(it)
                ) {
                    Log.e(EdgeScrollContract.APP_TAG,
                        "MONITOR_ERROR root child still alive after cleanup")
                }
                RootProcessRunner.closeStreams(it)
            }
            try { outputPump?.join(ROOT_OUTPUT_JOIN_MS) } catch (_: InterruptedException) {
                interrupted = true
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @Synchronized
    private fun failSession(
        token: String,
        message: String,
        error: Throwable?,
        onlyIfUnconnected: Boolean = false
    ) {
        if (!isActiveSessionLocked(token)) return
        if (onlyIfUnconnected && connected) return
        active = false
        activeToken = null
        connected = false
        cancelStartupTimeoutLocked()
        cancelHeartbeatTimeoutLocked()
        if (controlFile.exists() && !controlFile.delete()) {
            Log.w(EdgeScrollContract.APP_TAG, "MONITOR_ERROR control token delete failed")
        }
        closeSocketsLocked()
        if (error == null) {
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR $message")
        } else {
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR $message", error)
        }
        mainHandler.post {
            onSessionStopped(token, message)
        }
    }

    private fun cancelStartupTimeoutLocked() {
        startupTimeout?.let { mainHandler.removeCallbacks(it) }
        startupTimeout = null
    }

    private fun cancelHeartbeatTimeoutLocked() {
        heartbeatTimeout?.let { mainHandler.removeCallbacks(it) }
        heartbeatTimeout = null
    }

    private fun isActiveSession(token: String): Boolean = synchronized(this) {
        isActiveSessionLocked(token)
    }

    private fun isActiveSessionLocked(token: String): Boolean = active && activeToken == token

    private fun closeSocketsLocked() {
        try { eventSocket?.close() } catch (_: Exception) {}
        eventSocket = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val STARTUP_TIMEOUT_MS = 30_000L
        const val HEARTBEAT_TIMEOUT_MS = 10_000L
        const val ROOT_CHILD_POLL_MS = 100L
        const val ROOT_CHILD_STOP_GRACE_MS = 1_000L
        const val ROOT_OUTPUT_JOIN_MS = 200L
    }
}
