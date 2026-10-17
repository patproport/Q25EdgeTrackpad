package tech.shroyer.q25trackpadcustomizer

import android.app.Activity
import android.os.Bundle

/**
 * Quick toggle entry point for shortcuts/keymappers. No UI is shown.
 */
class QuickToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContext = applicationContext
        val prefs = Prefs(appContext)

        val targetPackage = AppState.currentForegroundPackage

        val quickToggleModes = prefs.getEffectiveQuickToggleModes(targetPackage)

        TrackpadController.setModeValueAsync({
            val currentLogical = AppState.currentMode
                ?: prefs.getLastKnownMode()
                ?: getEffectiveModeForPackage(prefs, targetPackage)
            computeNextQuickToggleMode(currentLogical, quickToggleModes)
        }) { nextMode, success ->
            if (!success || nextMode == null) {
                NotificationHelper.showRootError(
                    appContext,
                    "Trackpad toggle failed (root error). Please grant root access."
                )
            } else {
                if (!targetPackage.isNullOrEmpty()) AppState.manualOverrideForPackage = targetPackage
                NotificationHelper.clearRootError(appContext)

                AppState.currentMode = nextMode
                prefs.setLastKnownMode(nextMode)

                if (prefs.isToastQuickToggleEnabled()) {
                    ToastHelper.show(appContext, "Toggled to ${modeLabel(nextMode)} Mode!")
                }
            }
        }
        // Theme.NoDisplay must finish before onResume completes; root work continues in the queue.
        finish()
    }

    private fun computeNextQuickToggleMode(
        currentMode: Mode,
        selectedModes: Set<Mode>
    ): Mode {
        val order = listOf(Mode.MOUSE_SMOOTH, Mode.MOUSE, Mode.KEYBOARD)
        // Prefs supplies at least one valid mode; keep a safe default for corrupt settings.
        val cycle = order.filter { it in selectedModes }.ifEmpty { listOf(Mode.MOUSE, Mode.KEYBOARD) }
        val index = cycle.indexOf(currentMode)
        return if (index < 0) cycle.first() else cycle[(index + 1) % cycle.size]
    }

    private fun getEffectiveModeForPackage(prefs: Prefs, packageName: String?): Mode {
        if (packageName.isNullOrEmpty()) return prefs.getSystemDefaultMode()
        return when (val appMode = prefs.getAppMode(packageName)) {
            Mode.MOUSE, Mode.MOUSE_SMOOTH, Mode.KEYBOARD -> appMode
            Mode.FOLLOW_SYSTEM, null -> prefs.getSystemDefaultMode()
        }
    }

    private fun modeLabel(mode: Mode): String {
        return when (mode) {
            Mode.MOUSE -> "Mouse (Gesture)"
            Mode.MOUSE_SMOOTH -> "Mouse (Smooth Wheel)"
            Mode.KEYBOARD -> "Keyboard"
            Mode.FOLLOW_SYSTEM -> "System"
        }
    }
}
