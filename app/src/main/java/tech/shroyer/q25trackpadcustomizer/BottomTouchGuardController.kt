package tech.shroyer.q25trackpadcustomizer

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/** A bottom-only accessibility window; idle windows pass touches through. */
internal class BottomTouchGuardController(
    private val service: AccessibilityService,
    private val diagnostics: EdgeScrollDiagnostics
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private var guardView: View? = null
    private var guardParams: WindowManager.LayoutParams? = null
    private var guardActive = false
    private var level = 2
    private var deadlineMs = 0L
    private var destroyed = false
    private val timeout = Runnable { expireOrReschedule() }

    fun setLevel(newLevel: Int) {
        val safeLevel = newLevel.coerceIn(1, 3)
        if (level == safeLevel) return
        level = safeLevel
        if (!guardActive) return // Apply the new height on the next activation.
        val view = guardView ?: return
        val params = guardParams ?: return
        try {
            params.height = guardHeightPx()
            val startedAt = SystemClock.elapsedRealtimeNanos()
            try {
                windowManager.updateViewLayout(view, params)
            } finally {
                diagnostics.recordWindowUpdate(SystemClock.elapsedRealtimeNanos() - startedAt)
            }
        } catch (error: Exception) {
            Log.e(EdgeScrollContract.APP_TAG, "GUARD_ERROR height update failed", error)
            hideImmediately("height update failed")
        }
    }

    fun onTrackpadActivity(eventElapsedRealtimeMs: Long) {
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        val age = now - eventElapsedRealtimeMs
        if (age < 0L || age >= RELEASE_DELAY_MS) return
        deadlineMs = maxOf(deadlineMs, eventElapsedRealtimeMs + RELEASE_DELAY_MS)
        if (guardView == null) show()
        else if (!guardActive) setTouchBlocking(true, "trackpad activity")
        if (guardActive) {
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, (deadlineMs - now).coerceAtLeast(1L))
        }
    }

    fun hideImmediately(reason: String) {
        handler.removeCallbacks(timeout)
        deadlineMs = 0L
        releaseWindow(reason)
    }

    private fun releaseWindow(reason: String) {
        val view = guardView ?: return
        guardView = null
        guardParams = null
        if (guardActive) {
            guardActive = false
            diagnostics.recordGuardHide()
            Log.i(EdgeScrollContract.APP_TAG, "GUARD_HIDE reason=$reason windowRetained=false")
        }
        removeWindow(view, reason)
    }

    private fun removeWindow(view: View, reason: String) {
        try {
            val startedAt = SystemClock.elapsedRealtimeNanos()
            try {
                windowManager.removeViewImmediate(view)
            } finally {
                diagnostics.recordWindowRemoveImmediate(
                    SystemClock.elapsedRealtimeNanos() - startedAt
                )
            }
        } catch (error: Exception) {
            Log.e(EdgeScrollContract.APP_TAG, "GUARD_ERROR remove failed reason=$reason", error)
            // If removal also fails, an invisible window must not keep consuming touch.
            view.visibility = View.INVISIBLE
            try {
                windowManager.removeView(view)
            } catch (fallbackError: Exception) {
                Log.e(EdgeScrollContract.APP_TAG,
                    "GUARD_ERROR fallback remove failed reason=$reason", fallbackError)
            }
        }
    }

    fun destroy() {
        destroyed = true
        hideImmediately("service destroyed")
    }

    private fun expireOrReschedule() {
        val remaining = deadlineMs - SystemClock.elapsedRealtime()
        if (remaining > 0L) {
            handler.postDelayed(timeout, remaining)
        } else {
            Log.i(EdgeScrollContract.APP_TAG, "GUARD_TIMEOUT")
            deadlineMs = 0L
            setTouchBlocking(false, "250ms timeout")
        }
    }

    private fun setTouchBlocking(blocking: Boolean, reason: String) {
        val view = guardView ?: return
        val params = guardParams ?: return
        if (guardActive == blocking) return
        try {
            params.flags = if (blocking) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            if (blocking) params.height = guardHeightPx()
            val startedAt = SystemClock.elapsedRealtimeNanos()
            try {
                windowManager.updateViewLayout(view, params)
            } finally {
                diagnostics.recordWindowUpdate(SystemClock.elapsedRealtimeNanos() - startedAt)
            }
            guardActive = blocking
            if (blocking) {
                diagnostics.recordGuardShow()
                Log.i(EdgeScrollContract.APP_TAG, "GUARD_SHOW level=$level heightPx=${params.height} reused=true")
            } else {
                diagnostics.recordGuardHide()
                Log.i(EdgeScrollContract.APP_TAG, "GUARD_HIDE reason=$reason windowRetained=true")
            }
        } catch (error: Exception) {
            Log.e(EdgeScrollContract.APP_TAG, "GUARD_ERROR touch update failed reason=$reason", error)
            releaseWindow("touch update failed")
        }
    }

    private fun show() {
        val view = object : View(service) {
            override fun onTouchEvent(event: MotionEvent): Boolean = true
            override fun onHoverEvent(event: MotionEvent): Boolean = true
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            guardHeightPx(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 0
            alpha = 1f
            setTitle("Q25 Bottom Touch Guard")
        }
        val startedAt = SystemClock.elapsedRealtimeNanos()
        try {
            windowManager.addView(view, params)
            guardView = view
            guardParams = params
            guardActive = true
            diagnostics.recordGuardShow()
            Log.i(EdgeScrollContract.APP_TAG, "GUARD_SHOW level=$level heightPx=${params.height} reused=false")
        } catch (error: Exception) {
            Log.e(EdgeScrollContract.APP_TAG, "GUARD_ERROR addView failed", error)
            // addView may have attached the window before throwing.
            view.visibility = View.INVISIBLE
            removeWindow(view, "addView failed")
        } finally {
            diagnostics.recordWindowAdd(SystemClock.elapsedRealtimeNanos() - startedAt)
        }
    }

    private fun guardHeightPx(): Int {
        val height = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.maximumWindowMetrics.bounds.height()
            } else {
                service.resources.displayMetrics.heightPixels
            }
        } catch (_: Exception) {
            service.resources.displayMetrics.heightPixels
        }.coerceAtLeast(1)
        val fraction = when (level) { 1 -> 0.03f; 3 -> 0.10f; else -> 0.05f }
        return (height * fraction).roundToInt().coerceAtLeast(1)
    }

    companion object {
        private const val RELEASE_DELAY_MS = 250L
    }
}
