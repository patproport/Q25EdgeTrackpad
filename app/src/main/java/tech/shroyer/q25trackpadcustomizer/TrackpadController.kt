package tech.shroyer.q25trackpadcustomizer

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.Executors

// Handle reading and writing /proc/q20_switch_key_mouse using root
object TrackpadController {

    private const val PROC_PATH = "/proc/q20_switch_key_mouse"
    private const val ROOT_COMMAND_TIMEOUT_MS = 10_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = ArrayDeque<() -> Unit>()
    private var operationRunning = false

    /** Root operations and their main-thread completions run in request order. */
    private fun enqueue(operation: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { enqueue(operation) }
            return
        }
        pending.addLast(operation)
        runNext()
    }

    private fun runNext() {
        if (operationRunning || pending.isEmpty()) return
        operationRunning = true
        pending.removeFirst().invoke()
    }

    private fun finishOperation() {
        operationRunning = false
        runNext()
    }

    fun setModeValueAsync(mode: Mode, onComplete: (Boolean) -> Unit) {
        setModeValueAsync({ mode }) { _, success -> onComplete(success) }
    }

    /** The provider runs when this request reaches the head of the queue. */
    fun setModeValueAsync(modeProvider: () -> Mode?, onComplete: (Mode?, Boolean) -> Unit) {
        enqueue {
            val mode = modeProvider()
            val value = mode?.let { procValueForMode(it) }
            if (value == null) {
                try { onComplete(null, false) } finally { finishOperation() }
            } else {
                worker.execute {
                    val success = writeValue(value)
                    mainHandler.post {
                        try { onComplete(mode, success) } finally { finishOperation() }
                    }
                }
            }
        }
    }

    /** Mirrors the old Keyboard-already-selected read/repair path as one queue item. */
    fun ensureKeyboardAsync(shouldRun: () -> Boolean, onComplete: (Boolean?) -> Unit) {
        enqueue {
            if (!shouldRun()) {
                try { onComplete(null) } finally { finishOperation() }
            } else {
                worker.execute {
                    val current = readValue()
                    val success = current == null || current == "1" || writeValue("1")
                    mainHandler.post {
                        try { onComplete(success) } finally { finishOperation() }
                    }
                }
            }
        }
    }

    /** Mirrors the old restore read/conditional-write path as one queue item. */
    fun restoreModeAsync(
        mode: Mode,
        currentLogicalMode: Mode?,
        shouldRun: () -> Boolean,
        onComplete: (Boolean?) -> Unit
    ) {
        enqueue {
            val value = procValueForMode(mode)
            if (!shouldRun()) {
                try { onComplete(null) } finally { finishOperation() }
            } else if (value == null) {
                try { onComplete(false) } finally { finishOperation() }
            } else {
                worker.execute {
                    val current = readValue()
                    val alreadyCorrect = currentLogicalMode == mode &&
                        (current == null || current == value)
                    val success = alreadyCorrect || writeValue(value)
                    mainHandler.post {
                        try { onComplete(success) } finally { finishOperation() }
                    }
                }
            }
        }
    }

    private fun procValueForMode(mode: Mode): String? = when (mode) {
        Mode.MOUSE, Mode.MOUSE_SMOOTH -> "0"
        Mode.KEYBOARD -> "1"
        Mode.FOLLOW_SYSTEM -> null
    }

    /**
     * Toggle between 0 and 1 and return the new Mode if successful.
     * 0 = Mouse, 1 = Keyboard (per your mapping).
     */
    fun toggleValue(): Mode? {
        val current = readValue()

        // If current is 0 (Mouse), Switch to Keyboard; if 1 (Keyboard), Switch to Mouse.
        val newMode = when (current) {
            "0" -> Mode.KEYBOARD
            "1" -> Mode.MOUSE
            else -> Mode.MOUSE // fallback if it can't be read for any reason
        }

        val procValue = newMode.procValue ?: return null
        val success = writeValue(procValue)
        if (!success) {
            return null
        }

        AppState.currentMode = newMode
        return newMode
    }

    /**
     * Set the logical mode explicitly.
     * FOLLOW_SYSTEM is resolved before calling this.
     *
     * Both Mouse outputs use hardware mode 0; KEYBOARD uses 1.
     */
    fun setModeValue(mode: Mode): Boolean {
        val procValue = procValueForMode(mode) ?: return false

        val success = writeValue(procValue)
        if (success) {
            AppState.currentMode = mode
        }
        return success
    }

    /**
     * Read the current value ("0" or "1") from /proc using su
     */
    fun readValue(): String? {
        val (ok, output) = execSu("cat $PROC_PATH")
        if (!ok || output == null) return null
        val trimmed = output.trim()
        return if (trimmed == "0" || trimmed == "1") trimmed else null
    }

    /**
     * Write a value ("0" or "1") to /proc using su
     */
    private fun writeValue(value: String): Boolean {
        val (ok, _) = execSu("echo $value > $PROC_PATH")
        return ok
    }

    /**
     * Run a single su -c command and return (success, stdout)
     */
    private fun execSu(cmd: String): Pair<Boolean, String?> {
        val result = RootProcessRunner.runSu(cmd, ROOT_COMMAND_TIMEOUT_MS)
        if (result.timedOut) {
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR su command timed out")
            if (result.error != null) {
                Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR su process cleanup failed", result.error)
            }
        } else if (result.error != null) {
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR su command failed", result.error)
        }
        return result.successful to result.output.takeIf { result.successful }
    }
}
