package tech.shroyer.q25trackpadcustomizer

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Low-volume, process-local timing aggregation for Edge Scroll diagnostics. */
internal class EdgeScrollDiagnostics {
    private data class MetricSnapshot(val count: Long, val total: Long, val max: Long) {
        fun avgMax(): String = "${if (count == 0L) 0L else total / count}/$max"
    }

    private class Metric {
        private val count = AtomicLong()
        private val total = AtomicLong()
        private val max = AtomicLong()

        fun add(value: Long) {
            if (value < 0L) return
            count.incrementAndGet()
            total.addAndGet(value)
            var current = max.get()
            while (value > current && !max.compareAndSet(current, value)) {
                current = max.get()
            }
        }

        fun snapshotAndReset(): MetricSnapshot = MetricSnapshot(
            count.getAndSet(0L),
            total.getAndSet(0L),
            max.getAndSet(0L)
        )
    }

    private val scrollQueueMs = Metric()
    private val guardQueueMs = Metric()
    private val windowAddUs = Metric()
    private val windowRemoveImmediateUs = Metric()
    private val windowUpdateUs = Metric()
    private val gestureCallbackOverrunMs = Metric()
    private val guardShowCount = AtomicLong()
    private val guardHideCount = AtomicLong()
    private var windowStartedAtMs = SystemClock.uptimeMillis()

    fun recordScrollQueue(delayMs: Long) = scrollQueueMs.add(delayMs)

    fun recordGuardQueue(delayMs: Long) = guardQueueMs.add(delayMs)

    fun recordWindowAdd(durationNanos: Long) = windowAddUs.add(durationNanos / 1_000L)

    fun recordWindowRemoveImmediate(durationNanos: Long) =
        windowRemoveImmediateUs.add(durationNanos / 1_000L)

    fun recordWindowUpdate(durationNanos: Long) = windowUpdateUs.add(durationNanos / 1_000L)

    fun recordGestureCallback(totalDurationMs: Long, expectedDurationMs: Long) {
        gestureCallbackOverrunMs.add((totalDurationMs - expectedDurationMs).coerceAtLeast(0L))
    }

    fun recordGuardShow() {
        guardShowCount.incrementAndGet()
    }

    fun recordGuardHide() {
        guardHideCount.incrementAndGet()
    }

    fun emitSummary(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val windowMs = now - windowStartedAtMs
        if (!force && windowMs < DIAGNOSTIC_WINDOW_MS) return
        windowStartedAtMs = now

        val scrollQueue = scrollQueueMs.snapshotAndReset()
        val guardQueue = guardQueueMs.snapshotAndReset()
        val windowAdd = windowAddUs.snapshotAndReset()
        val windowRemove = windowRemoveImmediateUs.snapshotAndReset()
        val windowUpdate = windowUpdateUs.snapshotAndReset()
        val callbackOverrun = gestureCallbackOverrunMs.snapshotAndReset()

        Log.i(
            EdgeScrollContract.APP_TAG,
            "EDGE_DIAG_MAIN windowMs=$windowMs " +
                "scrollQueueCount=${scrollQueue.count} scrollQueueAvgMaxMs=${scrollQueue.avgMax()} " +
                "guardQueueCount=${guardQueue.count} guardQueueAvgMaxMs=${guardQueue.avgMax()} " +
                "wmAddCount=${windowAdd.count} wmAddAvgMaxUs=${windowAdd.avgMax()} " +
                "wmRemoveCount=${windowRemove.count} wmRemoveAvgMaxUs=${windowRemove.avgMax()} " +
                "wmUpdateCount=${windowUpdate.count} wmUpdateAvgMaxUs=${windowUpdate.avgMax()} " +
                "callbackOverrunCount=${callbackOverrun.count} " +
                "callbackOverrunAvgMaxMs=${callbackOverrun.avgMax()} " +
                "guardShows=${guardShowCount.getAndSet(0L)} " +
                "guardHides=${guardHideCount.getAndSet(0L)}"
        )
    }

    companion object {
        private const val DIAGNOSTIC_WINDOW_MS = 5000L
    }
}
