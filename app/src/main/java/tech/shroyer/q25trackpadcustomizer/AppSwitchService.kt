package tech.shroyer.q25trackpadcustomizer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/**
 * Tracks foreground app changes and key events, applying trackpad mode and scroll behavior.
 * Supports temporary Keyboard override while editing text.
 */
class AppSwitchService : AccessibilityService() {

    private lateinit var prefs: Prefs

    private var lastAppliedPackage: String? = null
    private var lastAppliedMode: Mode? = null

    private var imePackages: Set<String> = emptySet()

    private var lastTextInteractionUptime: Long = 0L
    private var textModeRequestPending = false
    private var textRestoreRequestPending = false
    private var pendingAutoModeRequest: Pair<String, Mode>? = null

    private val contentCheckHandler = Handler(Looper.getMainLooper())
    private var contentCheckScheduled = false
    private val contentCheckRunnable = Runnable {
        contentCheckScheduled = false
        handleContentChangedCheck()
    }

    private val textOverrideHandler = Handler(Looper.getMainLooper())
    private var textOverrideMisses = 0
    private val textOverrideCheckRunnable = object : Runnable {
        override fun run() {
            if (!AppState.textInputOverrideActive) {
                textOverrideMisses = 0
                return
            }

            val fg = AppState.currentForegroundPackage
            if (!isAutoKeyboardEnabledForPackage(fg)) {
                restoreModeAfterTextInput()
                stopTextOverrideMonitor()
                return
            }

            val recent = (SystemClock.uptimeMillis() - lastTextInteractionUptime) < 1200L
            val stillEditing = hasFocusedTextInput() || recent

            if (stillEditing) {
                textOverrideMisses = 0
            } else {
                textOverrideMisses++
                if (textOverrideMisses >= 2) {
                    restoreModeAfterTextInput()
                    stopTextOverrideMonitor()
                    return
                }
            }

            textOverrideHandler.postDelayed(this, TEXT_OVERRIDE_CHECK_INTERVAL_MS)
        }
    }

    private val edgeDiagnostics = EdgeScrollDiagnostics()
    private lateinit var edgeScrollController: EdgeScrollController
    private lateinit var bottomTouchGuard: BottomTouchGuardController
    private val guardModeObserver: (Mode?) -> Unit = { mode ->
        if (mode?.isMouseMode() != true) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                bottomTouchGuard.hideImmediately("mode=$mode")
                if (edgeMonitorActionScroll && edgeMouseSessionActive) {
                    stopEdgeMonitor("scroll mode=$mode")
                }
            } else {
                edgeModeHandler.post {
                    bottomTouchGuard.hideImmediately("mode=$mode")
                    if (edgeMonitorActionScroll && edgeMouseSessionActive) {
                        stopEdgeMonitor("scroll mode=$mode")
                    }
                }
            }
        }
        // Both Mouse modes share proc=0, but require different root monitor sessions.
        if (Looper.myLooper() == Looper.getMainLooper()) syncEdgeScrollMonitor()
        else edgeModeHandler.post { syncEdgeScrollMonitor() }
    }
    private val edgeModeHandler = Handler(Looper.getMainLooper())
    private var edgeMonitorSessionToken: String? = null
    private val edgeMouseSessionActive: Boolean get() = edgeMonitorSessionToken != null
    private var edgeMonitorConnectedAtMs = 0L
    private var edgeMonitorRetryAtMs = 0L
    private var edgeMonitorFailureCount = 0
    private var edgeServiceConnected = false
    private var edgeVerticalLevel = 5
    private var edgeHorizontalLevel = 5
    private var edgeDistanceLevel = 5
    private var edgeMonitorActionScroll = false
    private var lastLoggedScrollMode: String? = null
    private var lastGestureClampLogAt = 0L
    private var lastGestureDistanceCapLogAt = -GESTURE_DISTANCE_CAP_LOG_INTERVAL_MS
    private data class PendingEdgeGesture(
        val request: EdgeScrollRequest,
        val receivedAtMs: Long
    )
    private var pendingEdgeGesture: PendingEdgeGesture? = null
    private var edgeGestureInFlight = false
    private var edgeGestureDispatchPosted = false
    private var edgeGestureId = 0L
    private var edgeInFlightTrace: EdgeScrollTrace? = null
    private var edgeInFlightDurationMs = EDGE_GESTURE_DURATION_MS
    private var edgeInFlightMode = "BASELINE"
    private var edgeCurrentDispatchCallUptimeMs = 0L
    private var edgeLastDispatchCallUptimeMs = 0L
    private var edgeDiagnosticWindowAt = SystemClock.uptimeMillis()
    private var edgeDiagnosticReceived = 0L
    private var edgeDiagnosticReceivedY = 0L
    private var edgeDiagnosticReceivedX = 0L
    private var edgeDiagnosticReceivedDiagonal = 0L
    private var edgeDiagnosticReplaced = 0L
    private var edgeDiagnosticStale = 0L
    private var edgeDiagnosticCleared = 0L
    private var edgeDiagnosticDispatchCalls = 0L
    private var edgeDiagnosticNotDispatched = 0L
    private var edgeDiagnosticCompleted = 0L
    private var edgeDiagnosticCancelled = 0L
    private class EdgeLatency {
        var count = 0L
        var total = 0L
        var max = 0L
        fun add(deltaMs: Long) {
            if (deltaMs < 0L) return
            count++
            total += deltaMs
            max = maxOf(max, deltaMs)
        }
        fun summary(): String = "${if (count == 0L) 0L else total / count}/$max"
        fun reset() { count = 0L; total = 0L; max = 0L }
    }
    private val edgeEventToDecision = EdgeLatency()
    private val edgeRootToSocket = EdgeLatency()
    private val edgeSocketToMain = EdgeLatency()
    private val edgeMainToDispatch = EdgeLatency()
    private val edgeDispatchInterval = EdgeLatency()
    private val edgeGestureDuration = EdgeLatency()
    private val edgeGestureCallback = EdgeLatency()
    private var lastEdgeDispatchLogAt = -EDGE_GESTURE_LOG_INTERVAL_MS
    private var lastEdgeCancelLogAt = -EDGE_GESTURE_LOG_INTERVAL_MS
    private var lastEdgeStaleLogAt = -EDGE_GESTURE_LOG_INTERVAL_MS
    private var lastEdgeFallbackAt = -EDGE_FALLBACK_MIN_INTERVAL_MS
    private var lastEdgeFallbackLogAt = -EDGE_GESTURE_LOG_INTERVAL_MS
    private val edgeGestureDispatchRunnable = Runnable {
        edgeGestureDispatchPosted = false
        dispatchLatestEdgeGesture()
    }
    private val edgePendingExpiryRunnable = Runnable {
        pendingEdgeGesture?.let {
            pendingEdgeGesture = null
            edgeDiagnosticStale++
            logStaleEdgeGesture(SystemClock.elapsedRealtime() - it.receivedAtMs)
        }
    }
    private val edgeModeCheckRunnable = object : Runnable {
        override fun run() {
            syncEdgeScrollMonitor()
            emitEdgeDiagnosticSummary()
            edgeDiagnostics.emitSummary()
            edgeModeHandler.postDelayed(this, EDGE_MODE_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        bottomTouchGuard = BottomTouchGuardController(this, edgeDiagnostics)
        bottomTouchGuard.setLevel(prefs.getBottomTouchGuardLevel())
        AppState.modeObserver = guardModeObserver
        edgeScrollController = EdgeScrollController(
            this,
            diagnostics = edgeDiagnostics,
            onScroll = { request -> handleEdgeScrollRequest(request) },
            onGuardActivity = { eventTimeMs ->
                if (edgeServiceConnected && edgeMouseSessionActive &&
                    AppState.currentMode?.isMouseMode() == true
                ) {
                    bottomTouchGuard.onTrackpadActivity(eventTimeMs)
                }
            },
            onSessionConnected = { token ->
                if (edgeMonitorSessionToken == token) {
                    edgeMonitorConnectedAtMs = SystemClock.elapsedRealtime()
                }
            },
            onSessionStopped = { token, reason ->
                if (edgeMonitorSessionToken == token) {
                    edgeMonitorSessionToken = null
                    bottomTouchGuard.hideImmediately("monitor: $reason")
                    clearPendingEdgeGesture()
                    if (edgeServiceConnected && AppState.currentMode?.isMouseMode() == true) {
                        if (edgeMonitorConnectedAtMs > 0L &&
                            SystemClock.elapsedRealtime() - edgeMonitorConnectedAtMs >=
                            EDGE_MONITOR_STABLE_MS
                        ) {
                            edgeMonitorFailureCount = 0
                        }
                        scheduleEdgeMonitorRetry(reason)
                    } else {
                        resetEdgeMonitorRetry()
                    }
                }
            }
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        edgeServiceConnected = true
        imePackages = loadImePackages()
        if (AppState.currentMode == null) {
            AppState.currentMode = prefs.getLastKnownMode() ?: prefs.getSystemDefaultMode()
        }
        edgeModeHandler.removeCallbacks(edgeModeCheckRunnable)
        edgeModeHandler.post(edgeModeCheckRunnable)
    }

    override fun onDestroy() {
        edgeServiceConnected = false
        emitEdgeDiagnosticSummary(true)
        if (AppState.modeObserver === guardModeObserver) AppState.modeObserver = null
        bottomTouchGuard.destroy()
        edgeDiagnostics.emitSummary(force = true)
        stopTextOverrideMonitor()
        contentCheckHandler.removeCallbacks(contentCheckRunnable)
        edgeModeHandler.removeCallbacks(edgeModeCheckRunnable)
        clearPendingEdgeGesture()
        edgeMonitorSessionToken = null
        resetEdgeMonitorRetry()
        edgeScrollController.shutdown()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        edgeServiceConnected = false
        emitEdgeDiagnosticSummary(true)
        bottomTouchGuard.hideImmediately("service unbound")
        edgeDiagnostics.emitSummary(force = true)
        stopEdgeMonitor("service unbound")
        edgeModeHandler.removeCallbacks(edgeModeCheckRunnable)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return

                if (isImePackage(pkg)) {
                    lastTextInteractionUptime = SystemClock.uptimeMillis()
                    val fg = AppState.currentForegroundPackage
                    if (isAutoKeyboardEnabledForPackage(fg)) {
                        enterTextInputOverrideIfNeeded()
                    }
                    return
                }

                handleForegroundAppChanged(pkg)
                scheduleContentCheck()
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleViewFocusedForAutoKeyboard(event)
                scheduleContentCheck()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                handleTextInteraction(event)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleContentCheck()
            }
        }
    }

    override fun onInterrupt() {
        emitEdgeDiagnosticSummary(true)
        bottomTouchGuard.hideImmediately("service interrupted")
        edgeDiagnostics.emitSummary(force = true)
        stopEdgeMonitor("service interrupted")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_BACK ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            handleEndOfTextInputCheck()
            return false
        }

        return false
    }

    // ---------- IME / AUTO KEYBOARD FOR TEXT INPUT ----------

    private fun loadImePackages(): Set<String> {
        return try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val enabled = imm.enabledInputMethodList
            HashSet<String>().apply {
                for (ime in enabled) add(ime.packageName)
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun isImePackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        if (imePackages.contains(pkg)) return true

        if (pkg.contains("inputmethod", ignoreCase = true)) return true
        if (pkg.contains("keyboard", ignoreCase = true)) return true
        if (pkg.contains("ime", ignoreCase = true)) return true

        return false
    }

    private fun isAutoKeyboardEnabledForPackage(packageName: String?): Boolean {
        return prefs.isEffectiveAutoKeyboardForTextEnabled(packageName)
    }

    private fun handleTextInteraction(event: AccessibilityEvent) {
        val fg = AppState.currentForegroundPackage
        if (!isAutoKeyboardEnabledForPackage(fg)) return

        if (fg != null && prefs.getExcludedPackages().contains(fg)) return

        lastTextInteractionUptime = SystemClock.uptimeMillis()

        val src = event.source
        val isText = if (src != null) {
            val res = isTextInputNode(src)
            src.recycle()
            res
        } else false

        if (isText) {
            enterTextInputOverrideIfNeeded()
        } else {
            scheduleContentCheck()
        }
    }

    private fun scheduleContentCheck() {
        if (contentCheckScheduled) return
        contentCheckScheduled = true
        contentCheckHandler.removeCallbacks(contentCheckRunnable)
        contentCheckHandler.postDelayed(contentCheckRunnable, 120L)
    }

    private fun handleContentChangedCheck() {
        val fg = AppState.currentForegroundPackage

        if (!isAutoKeyboardEnabledForPackage(fg) || (fg != null && prefs.getExcludedPackages().contains(fg))) {
            if (AppState.textInputOverrideActive) {
                restoreModeAfterTextInput()
                stopTextOverrideMonitor()
            }
            return
        }

        if (hasFocusedTextInput()) {
            enterTextInputOverrideIfNeeded()
        }
    }

    private fun handleViewFocusedForAutoKeyboard(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()

        if (pkg == "android" || pkg == "com.android.systemui") return

        val excluded = prefs.getExcludedPackages()
        if (pkg.isNotEmpty() && excluded.contains(pkg)) {
            if (AppState.textInputOverrideActive) {
                restoreModeAfterTextInput()
                stopTextOverrideMonitor()
            }
            return
        }

        if (!isAutoKeyboardEnabledForPackage(pkg)) return

        val src = event.source ?: return
        val isText = isTextInputNode(src)
        src.recycle()

        if (isText) {
            lastTextInteractionUptime = SystemClock.uptimeMillis()
            enterTextInputOverrideIfNeeded()
        }
    }

    private fun isTextInputNode(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText", ignoreCase = true)) return true
        if (cls.contains("TextInput", ignoreCase = true)) return true
        if (cls.contains("AutoCompleteTextView", ignoreCase = true)) return true
        if (node.isEditable) return true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return true
            }
        } catch (_: Exception) {
        }

        return false
    }

    private fun enterTextInputOverrideIfNeeded() {
        if (AppState.textInputOverrideActive || textModeRequestPending || textRestoreRequestPending) return

        val fg = AppState.currentForegroundPackage
        if (fg != null && prefs.getExcludedPackages().contains(fg)) return

        val prevMode = AppState.currentMode
            ?: prefs.getLastKnownMode()
            ?: getEffectiveModeForPackage(fg)

        AppState.modeBeforeTextInput = prevMode
        textModeRequestPending = true
        val shouldRun = {
            edgeServiceConnected && AppState.currentForegroundPackage == fg &&
                !AppState.textInputOverrideActive
        }
        val onComplete: (Boolean?) -> Unit = { success ->
            textModeRequestPending = false
            if (success == null || !edgeServiceConnected) {
                AppState.modeBeforeTextInput = null
            } else if (!success) {
                showRootError()
                AppState.modeBeforeTextInput = null
                AppState.textInputOverrideActive = false
                stopTextOverrideMonitor()
            } else {
                NotificationHelper.clearRootError(this)
                AppState.currentMode = Mode.KEYBOARD
                AppState.textInputOverrideActive = true
                val stillEditing = AppState.currentForegroundPackage == fg &&
                    isAutoKeyboardEnabledForPackage(fg) &&
                    (hasFocusedTextInput() ||
                        SystemClock.uptimeMillis() - lastTextInteractionUptime < 1200L)
                if (stillEditing) {
                    startTextOverrideMonitor()
                } else {
                    if (AppState.currentForegroundPackage != fg) AppState.modeBeforeTextInput = null
                    restoreModeAfterTextInput()
                }
            }
        }
        if (prevMode == Mode.KEYBOARD) {
            TrackpadController.ensureKeyboardAsync(shouldRun, onComplete)
        } else {
            TrackpadController.setModeValueAsync({
                if (shouldRun()) Mode.KEYBOARD else null
            }) { attempted, success -> onComplete(if (attempted == null) null else success) }
        }
    }

    private fun restoreModeAfterTextInput() {
        if (textRestoreRequestPending) return
        val fg = AppState.currentForegroundPackage
        val targetMode = AppState.modeBeforeTextInput ?: getEffectiveModeForPackage(fg)
        val currentLogicalMode = AppState.currentMode
        val manualOverrideAtRequest = AppState.manualOverrideForPackage
        AppState.modeBeforeTextInput = null
        AppState.textInputOverrideActive = false
        stopTextOverrideMonitor()
        textRestoreRequestPending = true
        TrackpadController.restoreModeAsync(
            targetMode,
            currentLogicalMode,
            shouldRun = { edgeServiceConnected && AppState.currentForegroundPackage == fg &&
                !AppState.textInputOverrideActive &&
                AppState.currentMode == currentLogicalMode &&
                AppState.manualOverrideForPackage == manualOverrideAtRequest }
        ) { success ->
            textRestoreRequestPending = false
            if (success == false && edgeServiceConnected) {
                showRootError()
            } else if (success == true && edgeServiceConnected &&
                AppState.currentForegroundPackage == fg && !AppState.textInputOverrideActive
            ) {
                NotificationHelper.clearRootError(this)
                AppState.currentMode = targetMode
            }
            if (success == true && edgeServiceConnected &&
                isAutoKeyboardEnabledForPackage(AppState.currentForegroundPackage) &&
                hasFocusedTextInput()
            ) {
                enterTextInputOverrideIfNeeded()
            }
        }
    }

    private fun handleEndOfTextInputCheck() {
        val fg = AppState.currentForegroundPackage
        if (!isAutoKeyboardEnabledForPackage(fg)) return
        if (!AppState.textInputOverrideActive) return

        val recent = (SystemClock.uptimeMillis() - lastTextInteractionUptime) < 1200L
        if (!hasFocusedTextInput() && !recent) {
            restoreModeAfterTextInput()
            stopTextOverrideMonitor()
        }
    }

    private fun startTextOverrideMonitor() {
        textOverrideMisses = 0
        textOverrideHandler.removeCallbacks(textOverrideCheckRunnable)
        textOverrideHandler.postDelayed(textOverrideCheckRunnable, TEXT_OVERRIDE_CHECK_INTERVAL_MS)
    }

    private fun stopTextOverrideMonitor() {
        textOverrideHandler.removeCallbacks(textOverrideCheckRunnable)
        textOverrideMisses = 0
    }

    private fun hasFocusedTextInput(): Boolean {
        val root = rootInActiveWindow ?: return false

        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }

        if (focused != null) {
            val res = isTextInputNode(focused)
            focused.recycle()
            root.recycle()
            return res
        }

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val focusedNow = node.isFocused || node.isAccessibilityFocused
            if (focusedNow && isTextInputNode(node)) {
                node.recycle()
                recycleQueue(queue)
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }

            node.recycle()
        }

        return false
    }

    private fun recycleQueue(queue: ArrayDeque<AccessibilityNodeInfo>) {
        while (queue.isNotEmpty()) {
            queue.removeFirst().recycle()
        }
    }

    // ---------- SCROLL HELPERS ----------

    private fun resetEdgeMonitorRetry() {
        edgeMonitorConnectedAtMs = 0L
        edgeMonitorRetryAtMs = 0L
        edgeMonitorFailureCount = 0
    }

    private fun stopEdgeMonitor(reason: String) {
        edgeMonitorSessionToken = null
        resetEdgeMonitorRetry()
        edgeScrollController.stop(reason)
    }

    private fun scheduleEdgeMonitorRetry(reason: String) {
        edgeMonitorConnectedAtMs = 0L
        edgeMonitorFailureCount++
        if (edgeMonitorFailureCount >= EDGE_MONITOR_MAX_CONSECUTIVE_FAILURES) {
            edgeMonitorRetryAtMs = Long.MAX_VALUE
            Log.e(EdgeScrollContract.APP_TAG,
                "MONITOR_ERROR retry suspended after $edgeMonitorFailureCount failures: $reason")
            showRootError()
            return
        }
        val delayMs = minOf(
            EDGE_MONITOR_MAX_RETRY_MS,
            EDGE_MONITOR_FIRST_RETRY_MS shl (edgeMonitorFailureCount - 1)
        )
        edgeMonitorRetryAtMs = SystemClock.elapsedRealtime() + delayMs
        Log.w(EdgeScrollContract.APP_TAG,
            "MONITOR_RETRY reason=$reason delayMs=$delayMs attempt=$edgeMonitorFailureCount")
    }

    private fun syncEdgeScrollMonitor() {
        val actionScrollEnabled = AppState.currentMode == Mode.MOUSE_SMOOTH
        val scrollMode = edgeScrollModeName()
        if (lastLoggedScrollMode != scrollMode) {
            lastLoggedScrollMode = scrollMode
            if (actionScrollEnabled) {
                Log.i(EdgeScrollContract.APP_TAG,
                    "EDGE_OUTPUT_MODE mode=SMOOTH_WHEEL output=ACTION_SCROLL gestureFallback=false")
            } else {
                Log.i(EdgeScrollContract.APP_TAG,
                    "GESTURE_AB_MODE mode=$scrollMode " +
                        "durationYMs=$EDGE_GESTURE_DURATION_MS " +
                        "durationXMs=$EDGE_GESTURE_DURATION_MS " +
                        "durationCornerMs=$EDGE_GESTURE_DURATION_MS")
            }
        }
        if (!edgeServiceConnected) {
            bottomTouchGuard.hideImmediately("service disconnected")
            return
        }
        bottomTouchGuard.setLevel(prefs.getBottomTouchGuardLevel())
        if (!EDGE_SCROLL_ENABLED) {
            bottomTouchGuard.hideImmediately("feature disabled")
            clearPendingEdgeGesture()
            if (edgeMouseSessionActive) {
                stopEdgeMonitor("feature disabled")
            }
            return
        }

        val mouseMode = AppState.currentMode?.isMouseMode() == true
        if (mouseMode) {
            if (actionScrollEnabled && edgeGestureInFlight) {
                clearPendingEdgeGesture()
                if (edgeMouseSessionActive) {
                    stopEdgeMonitor("waiting for Gesture before Smooth Wheel")
                }
                return
            }
            val verticalLevel = prefs.getVerticalEdgeSensitivityLevel()
            val horizontalLevel = prefs.getHorizontalEdgeSensitivityLevel()
            val distanceLevel = if (actionScrollEnabled) {
                prefs.getSmoothWheelDistanceLevel()
            } else {
                prefs.getEdgeDistanceLevel()
            }
            if (edgeMouseSessionActive &&
                (verticalLevel != edgeVerticalLevel || horizontalLevel != edgeHorizontalLevel ||
                    distanceLevel != edgeDistanceLevel ||
                    actionScrollEnabled != edgeMonitorActionScroll)
            ) {
                bottomTouchGuard.hideImmediately("monitor restart")
                clearPendingEdgeGesture()
                stopEdgeMonitor("edge sensitivity changed")
            }
            if (edgeMouseSessionActive) return
            if (SystemClock.elapsedRealtime() < edgeMonitorRetryAtMs) return
            edgeVerticalLevel = verticalLevel
            edgeHorizontalLevel = horizontalLevel
            edgeDistanceLevel = distanceLevel
            edgeMonitorActionScroll = actionScrollEnabled
            val (displayWidthPx, displayHeightPx) = edgeScrollDisplaySizePx()
            when (val result = edgeScrollController.start(
                displayId = 0,
                displayWidthPx = displayWidthPx,
                displayHeightPx = displayHeightPx,
                verticalLevel = verticalLevel,
                horizontalLevel = horizontalLevel,
                distanceLevel = distanceLevel,
                actionScroll = actionScrollEnabled
            )) {
                is EdgeScrollStartResult.Started -> {
                    edgeMonitorSessionToken = result.token
                    edgeMonitorConnectedAtMs = 0L
                }
                EdgeScrollStartResult.WaitingForPrevious -> Unit
                EdgeScrollStartResult.Failed -> {
                    bottomTouchGuard.hideImmediately("monitor start failed")
                    Log.e(EdgeScrollContract.APP_TAG,
                        "MONITOR_ERROR start failed; Stock Mouse remains active")
                    scheduleEdgeMonitorRetry("start failed")
                }
            }
        } else if (edgeMouseSessionActive) {
            bottomTouchGuard.hideImmediately("mode=${AppState.currentMode}")
            clearPendingEdgeGesture()
            stopEdgeMonitor("mode=${AppState.currentMode}")
        } else {
            bottomTouchGuard.hideImmediately("mode=${AppState.currentMode}")
            clearPendingEdgeGesture()
            resetEdgeMonitorRetry()
        }
    }

    private fun edgeScrollDisplaySizePx(): Pair<Int, Int> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val bounds = wm.maximumWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val metrics = resources.displayMetrics
                metrics.widthPixels to metrics.heightPixels
            }
        } catch (_: Exception) {
            val metrics = resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }.let { (width, height) ->
            width.coerceAtLeast(1) to height.coerceAtLeast(1)
        }
    }

    private fun handleEdgeScrollRequest(request: EdgeScrollRequest) {
        if (AppState.currentMode != Mode.MOUSE) return
        if (request.horizontalDirection == null && request.verticalDirection == null) return
        edgeDiagnosticReceived++
        when {
            request.verticalDirection != null && request.horizontalDirection != null ->
                edgeDiagnosticReceivedDiagonal++
            request.verticalDirection != null -> edgeDiagnosticReceivedY++
            else -> edgeDiagnosticReceivedX++
        }
        request.trace?.let { trace ->
            edgeEventToDecision.add(trace.rootDecisionUptimeMs - trace.eventUptimeMs)
            edgeRootToSocket.add(trace.appReceiveUptimeMs - trace.rootSendStartUptimeMs)
            edgeSocketToMain.add(trace.mainUptimeMs - trace.appReceiveUptimeMs)
        }
        val replaced = pendingEdgeGesture != null
        if (replaced) {
            edgeDiagnosticReplaced++
        }
        pendingEdgeGesture = PendingEdgeGesture(request, SystemClock.elapsedRealtime())
        edgeModeHandler.removeCallbacks(edgePendingExpiryRunnable)
        edgeModeHandler.postDelayed(edgePendingExpiryRunnable, EDGE_PENDING_MAX_AGE_MS)
        scheduleLatestEdgeGesture()
    }

    private fun edgeGestureDirection(request: EdgeScrollRequest): String =
        listOfNotNull(request.verticalDirection, request.horizontalDirection).joinToString("+")

    private fun edgeScrollModeName(): String =
        if (AppState.currentMode == Mode.MOUSE_SMOOTH) "SMOOTH_WHEEL" else "BASELINE"

    private fun edgeGestureAmount(request: EdgeScrollRequest): Int =
        maxOf(request.verticalAmount, request.horizontalAmount)

    private fun scheduleLatestEdgeGesture() {
        if (edgeGestureInFlight || edgeGestureDispatchPosted || pendingEdgeGesture == null) return
        edgeGestureDispatchPosted = true
        edgeModeHandler.post(edgeGestureDispatchRunnable)
    }

    private fun clearPendingEdgeGesture() {
        if (pendingEdgeGesture != null) {
            edgeDiagnosticCleared++
        }
        pendingEdgeGesture = null
        edgeModeHandler.removeCallbacks(edgePendingExpiryRunnable)
        edgeModeHandler.removeCallbacks(edgeGestureDispatchRunnable)
        edgeGestureDispatchPosted = false
    }

    private fun logStaleEdgeGesture(ageMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEdgeStaleLogAt >= EDGE_GESTURE_LOG_INTERVAL_MS) {
            lastEdgeStaleLogAt = now
            Log.i(EdgeScrollContract.APP_TAG, "GESTURE_DROP_STALE ageMs=$ageMs")
        }
    }

    private fun allowEdgeFallback(axis: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEdgeFallbackAt < EDGE_FALLBACK_MIN_INTERVAL_MS) return false
        lastEdgeFallbackAt = now
        if (now - lastEdgeFallbackLogAt >= EDGE_GESTURE_LOG_INTERVAL_MS) {
            lastEdgeFallbackLogAt = now
            Log.i(EdgeScrollContract.APP_TAG, "GESTURE_FALLBACK axis=$axis")
        }
        return true
    }

    private fun dispatchLatestEdgeGesture() {
        if (AppState.currentMode != Mode.MOUSE) {
            clearPendingEdgeGesture()
            return
        }
        if (edgeGestureInFlight) return
        val pending = pendingEdgeGesture ?: return
        pendingEdgeGesture = null
        edgeModeHandler.removeCallbacks(edgePendingExpiryRunnable)
        if (AppState.currentMode != Mode.MOUSE) {
            edgeDiagnosticCleared++
            return
        }
        val ageMs = SystemClock.elapsedRealtime() - pending.receivedAtMs
        if (ageMs >= EDGE_PENDING_MAX_AGE_MS) {
            edgeDiagnosticStale++
            logStaleEdgeGesture(ageMs)
            return
        }
        edgeGestureInFlight = true
        edgeInFlightTrace = pending.request.trace
        edgeInFlightMode = edgeScrollModeName()
        edgeInFlightDurationMs = EDGE_GESTURE_DURATION_MS
        edgeCurrentDispatchCallUptimeMs = 0L
        val id = ++edgeGestureId
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                finishEdgeGesture(id, cancelled = false)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                finishEdgeGesture(id, cancelled = true)
            }
        }
        val dispatched = try {
            dispatchEdgeGesture(pending.request, callback)
        } catch (e: Exception) {
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR edge gesture dispatch failed", e)
            false
        }
        if (dispatched) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEdgeDispatchLogAt >= EDGE_GESTURE_LOG_INTERVAL_MS) {
                lastEdgeDispatchLogAt = now
                Log.i(EdgeScrollContract.APP_TAG,
                    "GESTURE_DISPATCH direction=${edgeGestureDirection(pending.request)} " +
                        "amount=${edgeGestureAmount(pending.request)} " +
                        "mode=$edgeInFlightMode " +
                        "duration=$edgeInFlightDurationMs " +
                        "distanceLevel=${prefs.getEdgeDistanceLevel()}")
            }
        } else {
            edgeDiagnosticNotDispatched++
            edgeInFlightTrace = null
            edgeGestureInFlight = false
            scheduleLatestEdgeGesture()
        }
    }

    private fun finishEdgeGesture(id: Long, cancelled: Boolean) {
        if (!edgeGestureInFlight || id != edgeGestureId) return
        edgeGestureInFlight = false
        val callbackAt = SystemClock.uptimeMillis()
        if (cancelled) edgeDiagnosticCancelled++ else edgeDiagnosticCompleted++
        if (edgeCurrentDispatchCallUptimeMs > 0L) {
            val callbackDurationMs = callbackAt - edgeCurrentDispatchCallUptimeMs
            edgeGestureCallback.add(callbackDurationMs)
            edgeDiagnostics.recordGestureCallback(
                callbackDurationMs,
                edgeInFlightDurationMs
            )
        }
        edgeInFlightTrace = null
        val now = SystemClock.elapsedRealtime()
        if (cancelled) {
            if (now - lastEdgeCancelLogAt >= EDGE_GESTURE_LOG_INTERVAL_MS) {
                lastEdgeCancelLogAt = now
                Log.w(EdgeScrollContract.APP_TAG, "GESTURE_CANCELLED")
            }
        }
        scheduleLatestEdgeGesture()
    }

    private fun dispatchMeasuredEdgeGesture(
        gesture: GestureDescription,
        callback: GestureResultCallback
    ): Boolean {
        val callAt = SystemClock.uptimeMillis()
        edgeCurrentDispatchCallUptimeMs = callAt
        edgeDiagnosticDispatchCalls++
        edgeInFlightTrace?.let { edgeMainToDispatch.add(callAt - it.mainUptimeMs) }
        if (edgeLastDispatchCallUptimeMs > 0L) {
            edgeDispatchInterval.add(callAt - edgeLastDispatchCallUptimeMs)
        }
        edgeLastDispatchCallUptimeMs = callAt
        val dispatched = dispatchGesture(gesture, callback, edgeModeHandler)
        if (dispatched) edgeGestureDuration.add(edgeInFlightDurationMs)
        return dispatched
    }

    private fun emitEdgeDiagnosticSummary(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - edgeDiagnosticWindowAt < EDGE_DIAG_WINDOW_MS) return
        val windowMs = now - edgeDiagnosticWindowAt
        edgeDiagnosticWindowAt = now
        if (edgeDiagnosticReceived > 0L || edgeDiagnosticDispatchCalls > 0L ||
            edgeDiagnosticCompleted > 0L || edgeDiagnosticCancelled > 0L ||
            edgeDiagnosticReplaced > 0L || edgeDiagnosticStale > 0L ||
            edgeDiagnosticCleared > 0L || edgeDiagnosticNotDispatched > 0L
        ) {
            Log.i(EdgeScrollContract.APP_TAG,
                "EDGE_DIAG_APP windowMs=$windowMs " +
                    "mode=${edgeScrollModeName()} " +
                    "received=$edgeDiagnosticReceived " +
                    "receivedY=$edgeDiagnosticReceivedY receivedX=$edgeDiagnosticReceivedX " +
                    "receivedDiagonal=$edgeDiagnosticReceivedDiagonal " +
                    "replaced=$edgeDiagnosticReplaced stale=$edgeDiagnosticStale " +
                    "cleared=$edgeDiagnosticCleared dispatchCalls=$edgeDiagnosticDispatchCalls " +
                    "notDispatched=$edgeDiagnosticNotDispatched " +
                    "completed=$edgeDiagnosticCompleted cancelled=$edgeDiagnosticCancelled " +
                    "eventToDecisionAvgMaxMs=${edgeEventToDecision.summary()} " +
                    "sendStartToSocketAvgMaxMs=${edgeRootToSocket.summary()} " +
                    "socketToMainAvgMaxMs=${edgeSocketToMain.summary()} " +
                    "mainToDispatchAvgMaxMs=${edgeMainToDispatch.summary()} " +
                    "dispatchIntervalAvgMaxMs=${edgeDispatchInterval.summary()} " +
                    "gestureDurationAvgMaxMs=${edgeGestureDuration.summary()} " +
                    "dispatchToCallbackAvgMaxMs=${edgeGestureCallback.summary()}")
        }
        edgeDiagnosticReceived = 0L
        edgeDiagnosticReceivedY = 0L
        edgeDiagnosticReceivedX = 0L
        edgeDiagnosticReceivedDiagonal = 0L
        edgeDiagnosticReplaced = 0L
        edgeDiagnosticStale = 0L
        edgeDiagnosticCleared = 0L
        edgeDiagnosticDispatchCalls = 0L
        edgeDiagnosticNotDispatched = 0L
        edgeDiagnosticCompleted = 0L
        edgeDiagnosticCancelled = 0L
        edgeEventToDecision.reset()
        edgeRootToSocket.reset()
        edgeSocketToMain.reset()
        edgeMainToDispatch.reset()
        edgeDispatchInterval.reset()
        edgeGestureDuration.reset()
        edgeGestureCallback.reset()
    }

    private fun dispatchEdgeGesture(
        request: EdgeScrollRequest,
        callback: GestureResultCallback
    ): Boolean {
        val horizontal = request.horizontalDirection
        val vertical = request.verticalDirection
        if (horizontal != null && vertical != null) {
            return handleEdgeDiagonalTrigger(request, callback)
        } else if (vertical != null) {
            return handleEdgeScrollTrigger(
                vertical,
                request.verticalAmount,
                request.verticalInput,
                request.verticalAccumulator,
                callback
            )
        } else if (horizontal != null) {
            return handleEdgePanTrigger(
                horizontal,
                request.horizontalAmount,
                request.horizontalInput,
                request.horizontalAccumulator,
                callback
            )
        }
        return false
    }

    private fun edgeMaxAmount(level: Int): Int = when (level) {
        1, 2 -> 1
        7, 8 -> 3
        9, 10 -> 4
        else -> 2
    }

    private fun handleEdgeScrollTrigger(
        direction: EdgeScrollDirection,
        amount: Int,
        input: Float,
        accumulator: Float,
        callback: GestureResultCallback
    ): Boolean {
        if (AppState.currentMode != Mode.MOUSE) return false
        val requestedUp = direction == EdgeScrollDirection.UP
        val scrollUp = if (EDGE_SCROLL_REVERSE_DIRECTION) !requestedUp else requestedUp
        val output = amount.coerceIn(1, edgeMaxAmount(edgeVerticalLevel))
        val dispatched = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && performVerticalGesture(
                scrollUp, output, edgeDistanceMultiplier(),
                protectSystemUi = true, callback = callback
            )
        } catch (e: Exception) {
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR vertical gesture failed", e)
            false
        }
        if (!dispatched && allowEdgeFallback("VERTICAL")) {
            scrollVerticalNodeBased(scrollUp, output)
        }
        return dispatched
    }

    private fun handleEdgePanTrigger(
        direction: EdgeScrollDirection,
        amount: Int,
        input: Float,
        accumulator: Float,
        callback: GestureResultCallback
    ): Boolean {
        if (AppState.currentMode != Mode.MOUSE) return false
        val requestedLeft = direction == EdgeScrollDirection.LEFT
        val panLeft = if (EDGE_PAN_REVERSE_DIRECTION) !requestedLeft else requestedLeft
        val output = amount.coerceIn(1, edgeMaxAmount(edgeHorizontalLevel))
        return scrollEdgeHorizontal(panLeft, output, edgeDistanceMultiplier(), callback)
    }

    private fun handleEdgeDiagonalTrigger(
        request: EdgeScrollRequest,
        callback: GestureResultCallback
    ): Boolean {
        if (AppState.currentMode != Mode.MOUSE) return false
        val horizontal = request.horizontalDirection ?: return false
        val vertical = request.verticalDirection ?: return false
        val requestedLeft = horizontal == EdgeScrollDirection.LEFT
        val requestedUp = vertical == EdgeScrollDirection.UP
        val panLeft = if (EDGE_PAN_REVERSE_DIRECTION) !requestedLeft else requestedLeft
        val scrollUp = if (EDGE_SCROLL_REVERSE_DIRECTION) !requestedUp else requestedUp
        val horizontalOutput = request.horizontalAmount.coerceIn(1, edgeMaxAmount(edgeHorizontalLevel))
        val verticalOutput = request.verticalAmount.coerceIn(1, edgeMaxAmount(edgeVerticalLevel))
        val maxInput = maxOf(abs(request.horizontalInput), abs(request.verticalInput))
        if (!maxInput.isFinite() || maxInput <= 0f) return false

        val dispatched = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && performDiagonalGesture(
                left = panLeft,
                horizontalSteps = horizontalOutput,
                horizontalInput = request.horizontalInput,
                up = scrollUp,
                verticalSteps = verticalOutput,
                verticalInput = request.verticalInput,
                distanceMultiplier = edgeDistanceMultiplier(),
                protectSystemUi = true,
                callback = callback
            )
        } catch (e: Exception) {
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR diagonal gesture failed", e)
            false
        }
        if (!dispatched && allowEdgeFallback("CORNER")) {
            // These are node actions, not two separately dispatched gestures.
            scrollVerticalNodeBased(scrollUp, verticalOutput)
            scrollHorizontalNodeBasedDirectional(panLeft, horizontalOutput)
        }
        return dispatched
    }

    private fun edgeDistanceMultiplier(): Float =
        EDGE_DISTANCE_MULTIPLIERS[prefs.getEdgeDistanceLevel() - 1]

    private fun scrollEdgeHorizontal(
        left: Boolean,
        steps: Int,
        distanceMultiplier: Float,
        callback: GestureResultCallback
    ): Boolean {
        val dispatched = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                performHorizontalGesture(
                    left, steps, distanceMultiplier,
                    protectSystemUi = true, callback = callback
                )
        } catch (e: Exception) {
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR horizontal gesture failed", e)
            false
        }
        if (!dispatched && allowEdgeFallback("HORIZONTAL")) {
            scrollHorizontalNodeBasedDirectional(left, steps)
        }
        return dispatched
    }

    private fun buildGestureStroke(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        displayWidthPx: Int,
        displayHeightPx: Int,
        protectSystemUi: Boolean
    ): GestureDescription? {
        if (displayWidthPx <= 0 || displayHeightPx <= 0 ||
            !startX.isFinite() || !startY.isFinite() ||
            !endX.isFinite() || !endY.isFinite()
        ) return null
        val maxX = (displayWidthPx - 1).toFloat()
        val maxY = (displayHeightPx - 1).toFloat()
        val density = resources.displayMetrics.density
            .takeIf { it.isFinite() && it > 0f } ?: 1f
        fun insetPx(dp: Float): Float = ceil((dp * density).toDouble()).toFloat()
        val requestedSideInset = if (protectSystemUi) insetPx(OTHER_SAFE_INSET_DP) else 0f
        val requestedTopInset = if (protectSystemUi) insetPx(TOP_SAFE_INSET_DP) else 0f
        val requestedBottomInset = if (protectSystemUi) insetPx(OTHER_SAFE_INSET_DP) else 0f
        val minX = min(requestedSideInset, maxX / 2f)
        val maxSafeX = maxX - min(requestedSideInset, maxX - minX)
        val minY = min(requestedTopInset, maxY / 2f)
        val maxSafeY = maxY - min(requestedBottomInset, maxY - minY)
        val safeStartX = startX.coerceIn(minX, maxSafeX)
        val safeStartY = startY.coerceIn(minY, maxSafeY)
        val safeEndX = endX.coerceIn(minX, maxSafeX)
        val safeEndY = endY.coerceIn(minY, maxSafeY)
        if (safeStartX != startX || safeStartY != startY ||
            safeEndX != endX || safeEndY != endY
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastGestureClampLogAt >= GESTURE_CLAMP_LOG_INTERVAL_MS) {
                lastGestureClampLogAt = now
                Log.d(EdgeScrollContract.APP_TAG,
                    "GESTURE_CLAMP original=($startX,$startY)->($endX,$endY) " +
                        "clamped=($safeStartX,$safeStartY)->($safeEndX,$safeEndY) " +
                        "safeArea=$protectSystemUi")
            }
        }
        if (safeStartX == safeEndX && safeStartY == safeEndY) return null
        val path = Path().apply {
            moveTo(safeStartX, safeStartY)
            lineTo(safeEndX, safeEndY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun logGestureDistanceCap(
        axis: String,
        requestedDistance: Float,
        actualDistance: Float,
        maxDistance: Float,
        scale: Float = 1f
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGestureDistanceCapLogAt < GESTURE_DISTANCE_CAP_LOG_INTERVAL_MS) return
        lastGestureDistanceCapLogAt = now
        Log.i(EdgeScrollContract.APP_TAG,
            "GESTURE_DISTANCE_CAP axis=$axis requestedDistance=$requestedDistance " +
                "actualDistance=$actualDistance maxDistance=$maxDistance scale=$scale")
    }

    private fun performVerticalGesture(
        up: Boolean,
        steps: Int,
        distanceMultiplier: Float = 1f,
        protectSystemUi: Boolean = false,
        callback: GestureResultCallback? = null
    ): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()

        val baseDistance = height * 0.15f
        val distance = baseDistance * steps * distanceMultiplier
        val maxDistance = height * MAX_GESTURE_SPAN_FRACTION
        val actualDistance = if (protectSystemUi) min(distance, maxDistance) else distance
        if (actualDistance < distance) {
            logGestureDistanceCap("VERTICAL", distance, actualDistance, maxDistance)
        }
        val duration = if (protectSystemUi) {
            if (callback != null) edgeInFlightDurationMs else EDGE_GESTURE_DURATION_MS
        } else 120L * steps

        val centerX = width / 2f
        val centerY = height / 2f

        val (startY, endY) = if (up) {
            (centerY + actualDistance / 2f) to (centerY - actualDistance / 2f)
        } else {
            (centerY - actualDistance / 2f) to (centerY + actualDistance / 2f)
        }

        val gesture = buildGestureStroke(
            centerX, startY, centerX, endY, duration,
            dm.widthPixels, dm.heightPixels, protectSystemUi
        ) ?: return false
        return if (callback != null) {
            dispatchMeasuredEdgeGesture(gesture, callback)
        } else {
            dispatchGesture(gesture, null, null)
        }
    }

    private fun performHorizontalGesture(
        left: Boolean,
        steps: Int,
        distanceMultiplier: Float = 1f,
        protectSystemUi: Boolean = false,
        callback: GestureResultCallback? = null
    ): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()

        val baseDistance = width * 0.15f
        val distance = baseDistance * steps * distanceMultiplier
        val maxDistance = width * MAX_GESTURE_SPAN_FRACTION
        val actualDistance = if (protectSystemUi) min(distance, maxDistance) else distance
        if (actualDistance < distance) {
            logGestureDistanceCap("HORIZONTAL", distance, actualDistance, maxDistance)
        }
        val duration = if (protectSystemUi) {
            if (callback != null) edgeInFlightDurationMs else EDGE_GESTURE_DURATION_MS
        } else 120L * steps

        val centerX = width / 2f
        val centerY = height / 2f

        val (startX, endX) = if (left) {
            (centerX + actualDistance / 2f) to (centerX - actualDistance / 2f)
        } else {
            (centerX - actualDistance / 2f) to (centerX + actualDistance / 2f)
        }

        val gesture = buildGestureStroke(
            startX, centerY, endX, centerY, duration,
            dm.widthPixels, dm.heightPixels, protectSystemUi
        ) ?: return false
        return if (callback != null) {
            dispatchMeasuredEdgeGesture(gesture, callback)
        } else {
            dispatchGesture(gesture, null, null)
        }
    }

    private fun performDiagonalGesture(
        left: Boolean,
        horizontalSteps: Int,
        horizontalInput: Float,
        up: Boolean,
        verticalSteps: Int,
        verticalInput: Float,
        distanceMultiplier: Float,
        protectSystemUi: Boolean,
        callback: GestureResultCallback
    ): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()
        val maxInput = maxOf(abs(horizontalInput), abs(verticalInput))
        if (!maxInput.isFinite() || maxInput <= 0f) return false
        val distancePerStep = minOf(width, height) * 0.15f
        val requestedHorizontalDistance = distancePerStep * horizontalSteps *
            abs(horizontalInput) / maxInput * distanceMultiplier
        val requestedVerticalDistance = distancePerStep * verticalSteps *
            abs(verticalInput) / maxInput * distanceMultiplier
        val maxHorizontalDistance = width * MAX_GESTURE_SPAN_FRACTION
        val maxVerticalDistance = height * MAX_GESTURE_SPAN_FRACTION
        var scale = 1f
        if (protectSystemUi) {
            if (requestedHorizontalDistance > maxHorizontalDistance) {
                scale = min(scale, maxHorizontalDistance / requestedHorizontalDistance)
            }
            if (requestedVerticalDistance > maxVerticalDistance) {
                scale = min(scale, maxVerticalDistance / requestedVerticalDistance)
            }
        }
        val horizontalDistance = requestedHorizontalDistance * scale
        val verticalDistance = requestedVerticalDistance * scale
        if (scale < 1f) {
            val requestedLength = hypot(
                requestedHorizontalDistance.toDouble(), requestedVerticalDistance.toDouble()
            ).toFloat()
            logGestureDistanceCap(
                "CORNER", requestedLength, requestedLength * scale,
                requestedLength * scale, scale
            )
        }
        val duration = EDGE_GESTURE_DURATION_MS
        val centerX = width / 2f
        val centerY = height / 2f
        val (startX, endX) = if (left) {
            (centerX + horizontalDistance / 2f) to (centerX - horizontalDistance / 2f)
        } else {
            (centerX - horizontalDistance / 2f) to (centerX + horizontalDistance / 2f)
        }
        val (startY, endY) = if (up) {
            (centerY + verticalDistance / 2f) to (centerY - verticalDistance / 2f)
        } else {
            (centerY - verticalDistance / 2f) to (centerY + verticalDistance / 2f)
        }
        val gesture = buildGestureStroke(
            startX, startY, endX, endY, duration,
            dm.widthPixels, dm.heightPixels, protectSystemUi
        ) ?: return false
        return dispatchMeasuredEdgeGesture(gesture, callback)
    }

    private fun scrollVerticalNodeBased(up: Boolean, steps: Int) {
        val node = findScrollableNode() ?: return
        val action = if (up) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        repeat(steps) { node.performAction(action) }
        node.recycle()
    }

    private fun scrollHorizontalNodeBasedDirectional(left: Boolean, steps: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val action = if (left) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            } else {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            }
            val node = findScrollableNodeWithAction(action.id) ?: return
            repeat(steps) { node.performAction(action.id) }
            node.recycle()
        } catch (e: Exception) {
            Log.e(EdgeScrollContract.APP_TAG, "MONITOR_ERROR horizontal node action failed", e)
        }
    }

    private fun findScrollableNodeWithAction(actionId: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable && node.actionList.any { it.id == actionId }) {
                recycleQueue(queue)
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            node.recycle()
        }
        return null
    }

    private fun findScrollableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (node.isScrollable) {
                recycleQueue(queue)
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }

            node.recycle()
        }

        return null
    }

    // ---------- APP SWITCH / MODE LOGIC ----------

    private fun handleForegroundAppChanged(packageName: String) {
        if (packageName == this.packageName) return
        if (packageName == "android" || packageName == "com.android.systemui") return
        if (isImePackage(packageName)) return

        val excluded = prefs.getExcludedPackages()
        if (excluded.contains(packageName)) return

        val prevFg = AppState.currentForegroundPackage
        AppState.currentForegroundPackage = packageName

        val overridePkg = AppState.manualOverrideForPackage
        if (overridePkg != null && overridePkg != packageName) {
            AppState.manualOverrideForPackage = null
        }

        if (AppState.textInputOverrideActive && prevFg != null && prevFg != packageName) {
            AppState.textInputOverrideActive = false
            AppState.modeBeforeTextInput = null
            stopTextOverrideMonitor()
        }

        if (AppState.manualOverrideForPackage == packageName) return
        if (AppState.textInputOverrideActive) return

        val (effectiveMode, isDefault) = when (val appMode = prefs.getAppMode(packageName)) {
            Mode.MOUSE, Mode.MOUSE_SMOOTH, Mode.KEYBOARD -> appMode to false
            Mode.FOLLOW_SYSTEM, null -> prefs.getSystemDefaultMode() to true
        }

        val currentMode = AppState.currentMode
        // A stored obsolete/invalid mode can decode to Mouse while hardware is still in Keyboard.
        // Apply the first foreground mode after service start before taking this fast path.
        if (isDefault && currentMode != null && currentMode == effectiveMode &&
            lastAppliedMode != null && pendingAutoModeRequest == null &&
            !textModeRequestPending && !textRestoreRequestPending
        ) {
            lastAppliedPackage = packageName
            lastAppliedMode = effectiveMode
            return
        }

        if (lastAppliedPackage == packageName && lastAppliedMode == effectiveMode &&
            pendingAutoModeRequest == null && !textModeRequestPending && !textRestoreRequestPending
        ) return
        val request = packageName to effectiveMode
        if (pendingAutoModeRequest == request) return
        pendingAutoModeRequest = request
        TrackpadController.setModeValueAsync({
            if (edgeServiceConnected && AppState.currentForegroundPackage == packageName &&
                AppState.manualOverrideForPackage != packageName &&
                !AppState.textInputOverrideActive && !textModeRequestPending &&
                !textRestoreRequestPending
            ) effectiveMode else null
        }) completion@{ attemptedMode, success ->
            if (pendingAutoModeRequest == request) pendingAutoModeRequest = null
            if (attemptedMode == null || !edgeServiceConnected) return@completion
            if (!success) {
                if (AppState.currentForegroundPackage == packageName) showRootError()
                return@completion
            }
            if (AppState.currentForegroundPackage != packageName ||
                AppState.manualOverrideForPackage == packageName ||
                AppState.textInputOverrideActive
            ) return@completion

            NotificationHelper.clearRootError(this)
            lastAppliedPackage = packageName
            lastAppliedMode = effectiveMode
            AppState.currentMode = effectiveMode
            prefs.setLastKnownMode(effectiveMode)
        }
    }

    private fun getEffectiveModeForPackage(packageName: String?): Mode {
        if (packageName.isNullOrEmpty()) return prefs.getSystemDefaultMode()
        return when (val appMode = prefs.getAppMode(packageName)) {
            Mode.MOUSE, Mode.MOUSE_SMOOTH, Mode.KEYBOARD -> appMode
            Mode.FOLLOW_SYSTEM, null -> prefs.getSystemDefaultMode()
        }
    }

    private fun showRootError() {
        NotificationHelper.showRootError(
            this,
            "Root access failed. Please grant root to Q25 Trackpad Customizer."
        )
    }

    companion object {
        private const val EDGE_MONITOR_FIRST_RETRY_MS = 1000L
        private const val EDGE_MONITOR_MAX_RETRY_MS = 30_000L
        private const val EDGE_MONITOR_STABLE_MS = 10_000L
        private const val EDGE_DIAG_WINDOW_MS = 5000L
        private const val EDGE_GESTURE_DURATION_MS = 70L
        private const val EDGE_PENDING_MAX_AGE_MS = 120L
        private const val EDGE_GESTURE_LOG_INTERVAL_MS = 1000L
        private const val EDGE_FALLBACK_MIN_INTERVAL_MS = 100L
        private const val MAX_GESTURE_SPAN_FRACTION = 0.70f
        private const val GESTURE_DISTANCE_CAP_LOG_INTERVAL_MS = 1000L
        private const val TOP_SAFE_INSET_DP = 20f
        private const val OTHER_SAFE_INSET_DP = 8f
        private const val GESTURE_CLAMP_LOG_INTERVAL_MS = 1000L
        private val EDGE_DISTANCE_MULTIPLIERS = floatArrayOf(
            0.50f, 0.625f, 0.75f, 0.875f, 1.00f,
            1.15f, 1.30f, 1.50f, 1.75f, 2.00f
        )
        private const val EDGE_SCROLL_ENABLED = true
        private const val EDGE_SCROLL_REVERSE_DIRECTION = true
        private const val EDGE_PAN_REVERSE_DIRECTION = true
        private const val EDGE_MODE_CHECK_INTERVAL_MS = 200L
        private const val EDGE_MONITOR_MAX_CONSECUTIVE_FAILURES = 6
        private const val TEXT_OVERRIDE_CHECK_INTERVAL_MS = 350L
    }
}
