package tech.shroyer.q25trackpadcustomizer;

import android.hardware.input.InputManagerGlobal;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.Display;

import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/** Passive UID 0 monitor used only while the app's logical mode is Mouse. */
public final class EdgeScrollMonitorMain {
    // v1 tuning values. Keep these independent for Q25 device adjustment.
    private static final float EDGE_THRESHOLD_PX = 3.0f;
    private static final float REL_DEAD_ZONE = 0.25f;
    private static final float SCROLL_SENSITIVITY = 0.65f;
    private static final int MAX_SCROLL_AMOUNT = 2;
    private static final float ACCUMULATOR_THRESHOLD = 3.0f;
    private static final long MIN_SCROLL_TRIGGER_INTERVAL_MS = 110L;
    // Horizontal v1 starts with the proven vertical values, but remains separable.
    private static final float PAN_EDGE_THRESHOLD_PX = EDGE_THRESHOLD_PX;
    private static final float PAN_REL_DEAD_ZONE = REL_DEAD_ZONE;
    private static final float PAN_SENSITIVITY = SCROLL_SENSITIVITY;
    private static final int MAX_PAN_AMOUNT = MAX_SCROLL_AMOUNT;
    private static final float PAN_ACCUMULATOR_THRESHOLD = 2.7f;
    private static final long MIN_PAN_TRIGGER_INTERVAL_MS = 120L;
    private static final int DEFAULT_SENSITIVITY_LEVEL = 5;
    private static final int CORNER_WEAK_AXIS_EVENTS = 3;
    private static final long CORNER_WEAK_AXIS_MS = 180L;
    private static final float CORNER_MIN_AXIS_FRACTION = 0.12f;
    private static final long CORNER_GESTURE_MS_PER_STEP = 120L;
    private static final long CONTROL_CHECK_INTERVAL_MS = 250L;
    private static final long HEARTBEAT_INTERVAL_MS = 1000L;
    private static final float GUARD_REL_DEAD_ZONE = 0.25f;
    private static final long GUARD_ACTIVITY_INTERVAL_MS = 50L;
    private static final long EDGE_DIAG_WINDOW_MS = 5000L;
    private static final int INJECT_WAIT_FOR_RESULT = 1;
    private static final long ACTION_SCROLL_INTERVAL_MS = 35L;
    private static final long ACTION_SCROLL_STALE_MS = 120L;
    private static final float ACTION_SCROLL_MAX_AXIS = 8.0f;
    private static final float[] DISTANCE_MULTIPLIERS = {
            0.50f, 0.625f, 0.75f, 0.875f, 1.00f,
            1.15f, 1.30f, 1.50f, 1.75f, 2.00f
    };
    private static boolean displayFallbackLogged;

    private EdgeScrollMonitorMain() {
    }

    public static void main(String[] args) {
        Arguments options = Arguments.parse(args);
        if (options == null) {
            Log.e(EdgeScrollContract.ROOT_TAG, "MONITOR_ERROR invalid arguments");
            return;
        }

        Log.i(EdgeScrollContract.ROOT_TAG,
                "MONITOR_START uid=" + Process.myUid()
                        + " displayId=" + options.displayId
                        + " width=" + options.displayWidth
                        + " height=" + options.displayHeight
                        + " verticalLevel=" + options.verticalLevel
                        + " horizontalLevel=" + options.horizontalLevel
                        + " mode=" + (options.actionScroll ? "ACTION_SCROLL" : "BASELINE"));
        if (Process.myUid() != 0) {
            Log.e(EdgeScrollContract.ROOT_TAG,
                    "MONITOR_ERROR root required actualUid=" + Process.myUid());
            deleteIfOwned(options);
            return;
        }

        // An old su request may be approved after its app-side session was stopped.
        if (!controlTokenMatches(options)) {
            Log.i(EdgeScrollContract.ROOT_TAG, "MONITOR_STOP stale control token before connect");
            return;
        }

        Looper.prepare();
        InputMonitor monitor = null;
        MouseReceiver receiver = null;
        EventSink sink = null;
        try {
            sink = EventSink.connect(options.socketName, options.token);
            if (!controlTokenMatches(options)) {
                Log.i(EdgeScrollContract.ROOT_TAG,
                        "MONITOR_STOP stale control token before InputMonitor");
                return;
            }
            monitor = InputManagerGlobal.getInstance().monitorGestureInput(
                    "Q25 Edge Scroll", options.displayId);
            receiver = new MouseReceiver(
                    monitor.getInputChannel(), Looper.myLooper(), options, sink);
            Log.i(EdgeScrollContract.ROOT_TAG,
                    "MONITOR_READY displayId=" + options.displayId
                            + " passive=true pilfer=false");

            Handler handler = new Handler(Looper.myLooper());
            EventSink lifecycleSink = sink;
            MouseReceiver diagnosticsReceiver = receiver;
            long[] lastHeartbeat = {0L};
            Runnable lifecycleCheck = new Runnable() {
                @Override
                public void run() {
                    if (!controlTokenMatches(options)) {
                        Log.i(EdgeScrollContract.ROOT_TAG,
                                "MONITOR_STOP control token removed/changed");
                        Looper.myLooper().quitSafely();
                        return;
                    }
                    long now = SystemClock.elapsedRealtime();
                    diagnosticsReceiver.emitDiagnosticSummaryIfDue(SystemClock.uptimeMillis());
                    if (now - lastHeartbeat[0] >= HEARTBEAT_INTERVAL_MS) {
                        lastHeartbeat[0] = now;
                        if (!lifecycleSink.send("PING")) {
                            Log.e(EdgeScrollContract.ROOT_TAG,
                                    "MONITOR_ERROR app event channel unavailable");
                            Looper.myLooper().quitSafely();
                            return;
                        }
                    }
                    handler.postDelayed(this, CONTROL_CHECK_INTERVAL_MS);
                }
            };
            handler.post(lifecycleCheck);
            Looper.loop();
        } catch (SecurityException e) {
            Log.e(EdgeScrollContract.ROOT_TAG,
                    "MONITOR_ERROR MONITOR_INPUT denied: " + e.getMessage(), e);
        } catch (LinkageError e) {
            Log.e(EdgeScrollContract.ROOT_TAG,
                    "MONITOR_ERROR hidden API linkage: " + e.getMessage(), e);
        } catch (Throwable t) {
            Log.e(EdgeScrollContract.ROOT_TAG,
                    "MONITOR_ERROR " + t.getClass().getSimpleName()
                            + ": " + t.getMessage(), t);
        } finally {
            if (receiver != null) {
                receiver.emitDiagnosticSummary(SystemClock.uptimeMillis(), true);
                receiver.cancelContinuousScroll();
                receiver.cancelPendingGuardActivity();
                receiver.dispose();
            }
            if (monitor != null) monitor.dispose();
            if (sink != null) sink.close();
            deleteIfOwned(options);
            Log.i(EdgeScrollContract.ROOT_TAG, "MONITOR_STOP root child ended");
        }
    }

    private enum VerticalEdge { NONE, TOP, BOTTOM }
    private enum HorizontalEdge { NONE, LEFT, RIGHT }
    private enum Corner { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private static final class Trigger {
        final String direction;
        final int output;
        final float input;
        final float accumulator;

        Trigger(String direction, int output, float input, float accumulator) {
            this.direction = direction;
            this.output = output;
            this.input = input;
            this.accumulator = accumulator;
        }
    }

    private static final class AxisTuning {
        final float sensitivity;
        final float threshold;
        final long intervalMs;
        final int maxAmount;

        AxisTuning(float sensitivity, float threshold, long intervalMs, int maxAmount) {
            this.sensitivity = sensitivity;
            this.threshold = threshold;
            this.intervalMs = intervalMs;
            this.maxAmount = maxAmount;
        }

        private static final AxisTuning[] VERTICAL = {
                new AxisTuning(0.35f, 5.50f, 240L, 1),
                new AxisTuning(0.40f, 5.00f, 220L, 1),
                new AxisTuning(0.45f, 4.50f, 200L, 1),
                new AxisTuning(0.55f, 3.70f, 170L, 2),
                new AxisTuning(SCROLL_SENSITIVITY, ACCUMULATOR_THRESHOLD,
                        MIN_SCROLL_TRIGGER_INTERVAL_MS, MAX_SCROLL_AMOUNT),
                new AxisTuning(0.70f, 2.75f, 128L, 2),
                new AxisTuning(0.75f, 2.50f, 116L, 3),
                new AxisTuning(0.80f, 2.25f, 106L, 3),
                new AxisTuning(0.85f, 2.00f, 98L, 4),
                new AxisTuning(0.90f, 1.80f, 90L, 4)
        };
        private static final AxisTuning[] HORIZONTAL = {
                new AxisTuning(0.35f, 5.10f, 255L, 1),
                new AxisTuning(0.40f, 4.60f, 235L, 1),
                new AxisTuning(0.45f, 4.10f, 215L, 1),
                new AxisTuning(0.55f, 3.40f, 182L, 2),
                new AxisTuning(PAN_SENSITIVITY, PAN_ACCUMULATOR_THRESHOLD,
                        MIN_PAN_TRIGGER_INTERVAL_MS, MAX_PAN_AMOUNT),
                new AxisTuning(0.70f, 2.45f, 143L, 2),
                new AxisTuning(0.75f, 2.20f, 132L, 3),
                new AxisTuning(0.80f, 2.00f, 120L, 3),
                new AxisTuning(0.85f, 1.80f, 110L, 4),
                new AxisTuning(0.90f, 1.60f, 100L, 4)
        };

        static AxisTuning vertical(int level) {
            return VERTICAL[level - 1];
        }

        static AxisTuning horizontal(int level) {
            return HORIZONTAL[level - 1];
        }
    }

    private static final class MouseReceiver extends InputEventReceiver {
        private final Arguments options;
        private final EventSink sink;
        private final AxisTuning verticalTuning;
        private final AxisTuning horizontalTuning;
        private VerticalEdge verticalEdge = VerticalEdge.NONE;
        private HorizontalEdge horizontalEdge = HorizontalEdge.NONE;
        private float verticalAccumulator;
        private float horizontalAccumulator;
        private long lastVerticalTriggerAt;
        private long lastHorizontalTriggerAt;
        private boolean relXErrorLogged;
        private Corner corner = Corner.NONE;
        private boolean cornerSuppressed;
        private float cornerAccumulatorX;
        private float cornerAccumulatorY;
        private long lastCornerTriggerAt;
        private long lastCornerGestureDuration;
        private int weakXEvents;
        private int weakYEvents;
        private long weakXSince;
        private long weakYSince;
        private final Handler guardHandler;
        private long lastGuardMovementAt = -1L;
        private long lastGuardSentAt = -GUARD_ACTIVITY_INTERVAL_MS;
        private long lastGuardEventSentAt = -1L;
        private boolean guardTrailingScheduled;
        private long currentEventUptimeMs;
        private long currentReceiveUptimeMs;
        private int currentHistorySize;
        private long nextRequestId;
        private long lastDiagnosticSummaryAt = SystemClock.uptimeMillis();
        private long diagnosticMoves;
        private long diagnosticBatchedEvents;
        private long diagnosticHistoricalSamples;
        private long diagnosticBelowVertical;
        private long diagnosticBelowHorizontal;
        private long diagnosticIntervalVertical;
        private long diagnosticIntervalHorizontal;
        private long diagnosticSentVertical;
        private long diagnosticSentHorizontal;
        private long diagnosticSentCorner;
        private long diagnosticSendDurationTotalMs;
        private long diagnosticSendDurationMaxMs;
        private long diagnosticEdgeEntriesY;
        private long diagnosticEdgeEntriesX;
        private long diagnosticFirstTriggerYCount;
        private long diagnosticFirstTriggerYTotalMs;
        private long diagnosticFirstTriggerYMaxMs;
        private long diagnosticFirstTriggerXCount;
        private long diagnosticFirstTriggerXTotalMs;
        private long diagnosticFirstTriggerXMaxMs;
        private long diagnosticFireYCount;
        private long diagnosticFireYTotalMs;
        private long diagnosticFireYMaxMs;
        private long diagnosticFireXCount;
        private long diagnosticFireXTotalMs;
        private long diagnosticFireXMaxMs;
        private final Handler continuousHandler;
        private boolean continuousFailed;
        private boolean continuousPosted;
        private float pendingVScroll;
        private float pendingHScroll;
        private float continuousRawX;
        private float continuousRawY;
        private int continuousDeviceId;
        private int continuousDisplayId;
        private long lastContinuousInputAt;
        private long lastContinuousOutputAt;
        private Method continuousInjectMethod;
        private final Runnable continuousInjectRunnable = this::injectContinuousScroll;
        private long verticalEdgeEntryUptimeMs = -1L;
        private long horizontalEdgeEntryUptimeMs = -1L;
        private final Runnable guardTrailingRunnable = new Runnable() {
            @Override
            public void run() {
                guardTrailingScheduled = false;
                if (lastGuardMovementAt > lastGuardEventSentAt) {
                    sendGuardActivity(lastGuardMovementAt);
                }
            }
        };

        MouseReceiver(
                android.view.InputChannel channel,
                Looper looper,
                Arguments options,
                EventSink sink
        ) {
            super(channel, looper);
            this.options = options;
            this.sink = sink;
            this.verticalTuning = AxisTuning.vertical(options.verticalLevel);
            this.horizontalTuning = AxisTuning.horizontal(options.horizontalLevel);
            this.guardHandler = new Handler(looper);
            this.continuousHandler = new Handler(looper);
        }

        private static int readDisplayId(InputEvent event, int fallback) {
            try {
                Method getDisplayId = InputEvent.class.getMethod("getDisplayId");
                return (Integer) getDisplayId.invoke(event);
            } catch (ReflectiveOperationException | RuntimeException error) {
                if (!displayFallbackLogged) {
                    displayFallbackLogged = true;
                    Log.w(EdgeScrollContract.ROOT_TAG,
                            "ACTION_SCROLL_DISPLAY_ID_UNAVAILABLE assumed=" + fallback, error);
                }
                return fallback;
            }
        }

        void cancelContinuousScroll() {
            continuousHandler.removeCallbacks(continuousInjectRunnable);
            continuousPosted = false;
            pendingVScroll = 0f;
            pendingHScroll = 0f;
            lastContinuousInputAt = 0L;
        }

        void cancelPendingGuardActivity() {
            guardHandler.removeCallbacks(guardTrailingRunnable);
            guardTrailingScheduled = false;
        }

        @Override
        public void onInputEvent(InputEvent event) {
            long receivedAtUptimeMs = SystemClock.uptimeMillis();
            try {
                if (!(event instanceof MotionEvent)) return;
                MotionEvent motion = (MotionEvent) event;
                int source = motion.getSource();
                boolean isMouse = (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                        || (source & InputDevice.SOURCE_MOUSE_RELATIVE)
                        == InputDevice.SOURCE_MOUSE_RELATIVE;
                if (!isMouse) return;

                int action = motion.getActionMasked();
                float relX = 0f;
                try {
                    relX = motion.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
                } catch (Throwable t) {
                    if (!relXErrorLogged) {
                        relXErrorLogged = true;
                        Log.e(EdgeScrollContract.ROOT_TAG,
                                "MONITOR_ERROR AXIS_RELATIVE_X unavailable: "
                                        + t.getMessage());
                    }
                }
                float relY = motion.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
                if (action != MotionEvent.ACTION_HOVER_MOVE
                        && action != MotionEvent.ACTION_MOVE) {
                    return;
                }

                currentEventUptimeMs = motion.getEventTime();
                currentReceiveUptimeMs = receivedAtUptimeMs;
                currentHistorySize = motion.getHistorySize();
                diagnosticMoves++;
                if (currentHistorySize > 0) {
                    diagnosticBatchedEvents++;
                    diagnosticHistoricalSamples += currentHistorySize;
                }

                try {
                    noteGuardMovement(relX, relY);
                } catch (Throwable t) {
                    Log.e(EdgeScrollContract.ROOT_TAG,
                            "GUARD_ERROR movement notification failed", t);
                }
                float rawX = motion.getRawX();
                float rawY = motion.getRawY();
                if (options.actionScroll) {
                    continuousDeviceId = motion.getDeviceId();
                    continuousDisplayId = readDisplayId(motion, options.displayId);
                    continuousRawX = rawX;
                    continuousRawY = rawY;
                }
                handleMovement(rawX, rawY, relX, relY);
            } finally {
                // Acknowledge the spy-channel copy without claiming the event.
                finishInputEvent(event, false);
            }
        }

        private void noteGuardMovement(float relX, float relY) {
            if (Float.isNaN(relX) || Float.isInfinite(relX)
                    || Float.isNaN(relY) || Float.isInfinite(relY)
                    || (Math.abs(relX) <= GUARD_REL_DEAD_ZONE
                    && Math.abs(relY) <= GUARD_REL_DEAD_ZONE)) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            lastGuardMovementAt = now;
            long sinceLastSend = now - lastGuardSentAt;
            if (sinceLastSend >= GUARD_ACTIVITY_INTERVAL_MS) {
                cancelPendingGuardActivity();
                sendGuardActivity(now);
            } else if (!guardTrailingScheduled) {
                guardTrailingScheduled = true;
                guardHandler.postDelayed(guardTrailingRunnable,
                        GUARD_ACTIVITY_INTERVAL_MS - sinceLastSend);
            }
        }

        private void sendGuardActivity(long eventTimeMs) {
            if (!sink.send("GUARD_ACTIVITY|" + eventTimeMs)) {
                Log.e(EdgeScrollContract.ROOT_TAG,
                        "MONITOR_ERROR failed to send guard activity");
                Looper.myLooper().quitSafely();
                return;
            }
            lastGuardSentAt = SystemClock.elapsedRealtime();
            lastGuardEventSentAt = eventTimeMs;
        }

        private void handleMovement(float rawX, float rawY, float relX, float relY) {
            if (options.actionScroll && (continuousFailed
                    || continuousDeviceId <= 0
                    || continuousDisplayId != options.displayId
                    || continuousDisplayId != Display.DEFAULT_DISPLAY
                    || Float.isNaN(relX) || Float.isInfinite(relX)
                    || Float.isNaN(relY) || Float.isInfinite(relY)
                    || Float.isNaN(rawX) || Float.isInfinite(rawX)
                    || Float.isNaN(rawY) || Float.isInfinite(rawY)
                    || rawX < 0f || rawY < 0f
                    || rawX >= options.displayWidth || rawY >= options.displayHeight)) {
                exitCorner("invalid action scroll input");
                exitVerticalEdge("invalid action scroll input");
                exitHorizontalEdge("invalid action scroll input");
                cancelContinuousScroll();
                return;
            }
            try {
                if (handleCorner(rawX, rawY, relX, relY)) return;
            } catch (Throwable t) {
                exitCorner("corner processing error");
                cornerSuppressed = true;
                Log.e(EdgeScrollContract.ROOT_TAG,
                        "MONITOR_ERROR corner processing: " + t.getMessage(), t);
            }

            Trigger vertical = processVertical(rawY, relY);
            Trigger horizontal = null;
            try {
                horizontal = processHorizontal(rawX, relX);
            } catch (Throwable t) {
                exitHorizontalEdge("horizontal processing error");
                Log.e(EdgeScrollContract.ROOT_TAG,
                        "MONITOR_ERROR horizontal processing: " + t.getMessage(), t);
            }

            if (vertical == null && horizontal == null) return;

            if (options.actionScroll) {
                float distance = DISTANCE_MULTIPLIERS[options.distanceLevel - 1];
                float v = vertical == null ? 0f : vertical.output * distance
                        * ("UP".equals(vertical.direction) ? 1f : -1f);
                float h = horizontal == null ? 0f : horizontal.output * distance
                        * ("LEFT".equals(horizontal.direction) ? -1f : 1f);
                enqueueContinuousScroll(h, v);
                return;
            }

            String message;
            if (vertical != null && horizontal != null) {
                message = String.format(Locale.US,
                        "DIAGONAL|%s|%d|%.3f|%.3f|%s|%d|%.3f|%.3f",
                        horizontal.direction, horizontal.output, horizontal.input,
                        horizontal.accumulator, vertical.direction, vertical.output,
                        vertical.input, vertical.accumulator);
            } else {
                Trigger trigger = vertical != null ? vertical : horizontal;
                message = String.format(Locale.US, "SCROLL|%s|%d|%.3f|%.3f",
                        trigger.direction, trigger.output, trigger.input,
                        trigger.accumulator);
            }

            if (!sendDiagnosedRequest(message,
                    vertical != null && horizontal != null ? "DIAGONAL"
                            : vertical != null ? "VERTICAL" : "HORIZONTAL")) {
                Log.e(EdgeScrollContract.ROOT_TAG,
                        "MONITOR_ERROR failed to send edge request");
                Looper.myLooper().quitSafely();
                return;
            }

        }

        private void enqueueContinuousScroll(float h, float v) {
            if (h == 0f && v == 0f) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastContinuousInputAt > ACTION_SCROLL_STALE_MS) {
                pendingVScroll = 0f;
                pendingHScroll = 0f;
            }
            lastContinuousInputAt = now;
            if (v != 0f) {
                if (pendingVScroll * v < 0f) pendingVScroll = 0f;
                pendingVScroll = Math.max(-ACTION_SCROLL_MAX_AXIS,
                        Math.min(ACTION_SCROLL_MAX_AXIS, pendingVScroll + v));
            }
            if (h != 0f) {
                if (pendingHScroll * h < 0f) pendingHScroll = 0f;
                pendingHScroll = Math.max(-ACTION_SCROLL_MAX_AXIS,
                        Math.min(ACTION_SCROLL_MAX_AXIS, pendingHScroll + h));
            }
            if (!continuousPosted) {
                continuousPosted = true;
                long delay = Math.max(0L,
                        lastContinuousOutputAt + ACTION_SCROLL_INTERVAL_MS - now);
                continuousHandler.postDelayed(continuousInjectRunnable, delay);
            }
        }

        private void injectContinuousScroll() {
            continuousPosted = false;
            long now = SystemClock.uptimeMillis();
            if (continuousFailed
                    || now - lastContinuousInputAt > ACTION_SCROLL_STALE_MS
                    || !controlTokenMatches(options)) {
                cancelContinuousScroll();
                return;
            }
            float v = pendingVScroll;
            float h = pendingHScroll;
            if (v == 0f && h == 0f) return;
            pendingVScroll -= v;
            pendingHScroll -= h;
            lastContinuousOutputAt = now;
            // Keep the synthetic scroll inside the app window while the real cursor
            // remains at the edge. Mouse motion and clicks are never reinjected.
            float insetX = Math.min((options.displayWidth - 1f) / 2f,
                    Math.min(40f, Math.max(12f, options.displayWidth * 0.035f)));
            float insetY = Math.min((options.displayHeight - 1f) / 2f,
                    Math.max(24f, options.displayHeight * 0.04f));
            // The existing Bottom Touch Guard can occupy up to 10% of the
            // bottom edge; keep the scroll target beyond that overlay.
            float bottomInset = Math.min((options.displayHeight - 1f) / 2f,
                    Math.max(32f, options.displayHeight * 0.12f));
            // UP scrolls (including top corners) should reach the scrollable body,
            // like the existing Gesture centered on the display.
            boolean topTarget = v > 0f;
            float x = topTarget ? options.displayWidth / 2f : Math.max(insetX,
                    Math.min(options.displayWidth - 1f - insetX, continuousRawX));
            float y = topTarget ? options.displayHeight / 2f : Math.max(insetY,
                    Math.min(options.displayHeight - 1f - bottomInset, continuousRawY));
            MotionEvent scroll = null;
            try {
                MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
                property.id = 0;
                property.toolType = MotionEvent.TOOL_TYPE_MOUSE;
                MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
                coords.x = x;
                coords.y = y;
                coords.setAxisValue(MotionEvent.AXIS_VSCROLL, v);
                coords.setAxisValue(MotionEvent.AXIS_HSCROLL, h);
                MotionEvent.PointerProperties[] properties = {property};
                MotionEvent.PointerCoords[] coordinates = {coords};
                scroll = Build.VERSION.SDK_INT >= 34
                        ? MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                        properties, coordinates, 0, 0, 1f, 1f, continuousDeviceId, 0,
                        InputDevice.SOURCE_MOUSE, continuousDisplayId, 0, 0)
                        : MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                        properties, coordinates, 0, 0, 1f, 1f, continuousDeviceId, 0,
                        InputDevice.SOURCE_MOUSE, 0);
                if (readDisplayId(scroll, Display.DEFAULT_DISPLAY) != continuousDisplayId) {
                    throw new IllegalStateException("ACTION_SCROLL display mismatch");
                }
                if (continuousInjectMethod == null) {
                    continuousInjectMethod = InputManagerGlobal.getInstance().getClass()
                            .getMethod("injectInputEvent", InputEvent.class, int.class);
                }
                Object accepted = continuousInjectMethod.invoke(InputManagerGlobal.getInstance(),
                        scroll, INJECT_WAIT_FOR_RESULT);
                if (!Boolean.TRUE.equals(accepted)) {
                    throw new IllegalStateException("ACTION_SCROLL injection rejected");
                }
            } catch (Throwable error) {
                continuousFailed = true;
                cancelContinuousScroll();
                Log.e(EdgeScrollContract.ROOT_TAG,
                        "ACTION_SCROLL_STOP injection failed; no Gesture fallback", error);
            } finally {
                if (scroll != null) scroll.recycle();
            }
        }

        private Corner cornerAt(float rawX, float rawY) {
            boolean left = rawX <= PAN_EDGE_THRESHOLD_PX;
            boolean right = rawX >= options.displayWidth - 1f - PAN_EDGE_THRESHOLD_PX;
            boolean top = rawY <= EDGE_THRESHOLD_PX;
            boolean bottom = rawY >= options.displayHeight - 1f - EDGE_THRESHOLD_PX;
            if (top && left) return Corner.TOP_LEFT;
            if (top && right) return Corner.TOP_RIGHT;
            if (bottom && left) return Corner.BOTTOM_LEFT;
            if (bottom && right) return Corner.BOTTOM_RIGHT;
            return Corner.NONE;
        }

        private boolean handleCorner(float rawX, float rawY, float relX, float relY) {
            Corner candidate = cornerAt(rawX, rawY);
            if (candidate == Corner.NONE) {
                exitCorner("cursor left corner zone");
                cornerSuppressed = false;
                return false;
            }

            boolean left = candidate == Corner.TOP_LEFT || candidate == Corner.BOTTOM_LEFT;
            boolean top = candidate == Corner.TOP_LEFT || candidate == Corner.TOP_RIGHT;
            boolean outwardX = left ? relX < 0f : relX > 0f;
            boolean outwardY = top ? relY < 0f : relY > 0f;
            boolean inwardX = left ? relX > 0f : relX < 0f;
            boolean inwardY = top ? relY > 0f : relY < 0f;

            if (corner != Corner.NONE && (corner != candidate || inwardX || inwardY)) {
                exitCorner(corner != candidate ? "corner changed" : "inward input");
                cornerSuppressed = true;
                return false;
            }
            if (cornerSuppressed) {
                if (!outwardX || !outwardY
                        || Math.abs(relX) <= PAN_REL_DEAD_ZONE
                        || Math.abs(relY) <= REL_DEAD_ZONE) return false;
                cornerSuppressed = false;
            }
            if (corner == Corner.NONE) {
                if (inwardX || inwardY) {
                    cornerSuppressed = true;
                    return false;
                }
                enterCorner(candidate);
                return true;
            }

            long now = SystemClock.elapsedRealtime();
            boolean strongX = outwardX && Math.abs(relX) > PAN_REL_DEAD_ZONE;
            boolean strongY = outwardY && Math.abs(relY) > REL_DEAD_ZONE;
            if (strongX && !strongY) {
                if (weakYEvents++ == 0) weakYSince = now;
                weakXEvents = 0;
            } else if (strongY && !strongX) {
                if (weakXEvents++ == 0) weakXSince = now;
                weakYEvents = 0;
            } else {
                weakXEvents = 0;
                weakYEvents = 0;
            }
            if (weakXEvents >= CORNER_WEAK_AXIS_EVENTS
                    || weakYEvents >= CORNER_WEAK_AXIS_EVENTS
                    || (weakXEvents > 0 && now - weakXSince >= CORNER_WEAK_AXIS_MS)
                    || (weakYEvents > 0 && now - weakYSince >= CORNER_WEAK_AXIS_MS)) {
                exitCorner("one axis stayed weak");
                cornerSuppressed = true;
                return false;
            }

            if (strongX) cornerAccumulatorX += Math.abs(relX) * horizontalTuning.sensitivity;
            if (strongY) cornerAccumulatorY += Math.abs(relY) * verticalTuning.sensitivity;
            float normalizedX = cornerAccumulatorX / horizontalTuning.threshold;
            float normalizedY = cornerAccumulatorY / verticalTuning.threshold;
            float combined = normalizedX + normalizedY;
            float cap = horizontalTuning.maxAmount + verticalTuning.maxAmount;
            if (combined > cap) {
                float scale = cap / combined;
                cornerAccumulatorX *= scale;
                cornerAccumulatorY *= scale;
                normalizedX *= scale;
                normalizedY *= scale;
                combined = cap;
            }

            if ((!strongX && !strongY)
                    || normalizedX < CORNER_MIN_AXIS_FRACTION
                    || normalizedY < CORNER_MIN_AXIS_FRACTION
                    || combined < 1f
                    || now - lastCornerTriggerAt < Math.max(
                            Math.max(verticalTuning.intervalMs,
                                    horizontalTuning.intervalMs),
                            lastCornerGestureDuration)) {
                return true;
            }

            int output = Math.max(1, Math.min(
                    Math.max(horizontalTuning.maxAmount, verticalTuning.maxAmount),
                    (int) Math.floor(combined)));
            int outputX = Math.min(output, horizontalTuning.maxAmount);
            int outputY = Math.min(output, verticalTuning.maxAmount);
            float inputX = cornerAccumulatorX / horizontalTuning.sensitivity;
            float inputY = cornerAccumulatorY / verticalTuning.sensitivity;
            float remaining = Math.max(0f, (combined - output) / combined);
            cornerAccumulatorX *= remaining;
            cornerAccumulatorY *= remaining;
            String horizontal = left ? "LEFT" : "RIGHT";
            String vertical = top ? "UP" : "DOWN";
            // Gesture IPC keeps the accumulated input used for its X/Y ratio.
            String message = String.format(Locale.US,
                    "DIAGONAL|%s|%d|%.3f|%.3f|%s|%d|%.3f|%.3f",
                    horizontal, outputX, inputX, cornerAccumulatorX,
                    vertical, outputY, inputY, cornerAccumulatorY);
            if (options.actionScroll) {
                float maxInput = Math.max(inputX, inputY);
                float distance = DISTANCE_MULTIPLIERS[options.distanceLevel - 1];
                float h = (left ? -1f : 1f) * outputX * inputX / maxInput * distance;
                float v = (top ? 1f : -1f) * outputY * inputY / maxInput * distance;
                enqueueContinuousScroll(h, v);
            } else if (!sendDiagnosedRequest(message, "CORNER")) {
                Log.e(EdgeScrollContract.ROOT_TAG,
                        "MONITOR_ERROR failed to send corner request");
                Looper.myLooper().quitSafely();
                return true;
            }
            lastCornerTriggerAt = now;
            lastCornerGestureDuration = CORNER_GESTURE_MS_PER_STEP * Math.max(outputX, outputY);
            return true;
        }

        private void enterCorner(Corner candidate) {
            corner = candidate;
            cornerAccumulatorX = 0f;
            cornerAccumulatorY = 0f;
            weakXEvents = 0;
            weakYEvents = 0;
            verticalEdge = VerticalEdge.NONE;
            horizontalEdge = HorizontalEdge.NONE;
            verticalAccumulator = 0f;
            horizontalAccumulator = 0f;
            if (options.actionScroll) cancelContinuousScroll();
        }

        private void exitCorner(String reason) {
            if (corner == Corner.NONE) return;
            corner = Corner.NONE;
            cornerAccumulatorX = 0f;
            cornerAccumulatorY = 0f;
            weakXEvents = 0;
            weakYEvents = 0;
            verticalEdge = VerticalEdge.NONE;
            horizontalEdge = HorizontalEdge.NONE;
            verticalAccumulator = 0f;
            horizontalAccumulator = 0f;
            if (options.actionScroll) cancelContinuousScroll();
        }

        private Trigger processVertical(float rawY, float relY) {
            float bottomClamp = Math.max(0f, options.displayHeight - 1f);
            VerticalEdge candidate = VerticalEdge.NONE;
            if (rawY <= EDGE_THRESHOLD_PX) {
                candidate = VerticalEdge.TOP;
            } else if (rawY >= bottomClamp - EDGE_THRESHOLD_PX) {
                candidate = VerticalEdge.BOTTOM;
            }

            if (candidate == VerticalEdge.NONE) {
                exitVerticalEdge("cursor left edge");
                return null;
            }

            boolean outward = (candidate == VerticalEdge.TOP && relY < 0f)
                    || (candidate == VerticalEdge.BOTTOM && relY > 0f);
            boolean inward = (candidate == VerticalEdge.TOP && relY > 0f)
                    || (candidate == VerticalEdge.BOTTOM && relY < 0f);

            if (inward) {
                exitVerticalEdge("inward input");
                return null;
            }

            if (verticalEdge != candidate) {
                verticalEdge = candidate;
                verticalAccumulator = 0f;
                verticalEdgeEntryUptimeMs = SystemClock.uptimeMillis();
                diagnosticEdgeEntriesY++;
                // Reaching the edge is not itself a scroll request. A later outward
                // relative event is required.
                return null;
            }

            if (!outward || relY == 0f) return null;
            float magnitude = Math.abs(relY);
            if (magnitude <= REL_DEAD_ZONE) return null;

            verticalAccumulator = Math.min(
                    verticalAccumulator + magnitude * verticalTuning.sensitivity,
                    verticalTuning.threshold * verticalTuning.maxAmount);
            long now = SystemClock.elapsedRealtime();
            boolean belowThreshold = verticalAccumulator < verticalTuning.threshold;
            boolean withinInterval = now - lastVerticalTriggerAt < verticalTuning.intervalMs;
            if (belowThreshold) diagnosticBelowVertical++;
            if (withinInterval) diagnosticIntervalVertical++;
            if (belowThreshold || withinInterval) return null;

            int available = (int) Math.floor(
                    verticalAccumulator / verticalTuning.threshold);
            int output = Math.max(1, Math.min(verticalTuning.maxAmount, available));
            verticalAccumulator -= output * verticalTuning.threshold;
            if (lastVerticalTriggerAt > 0L && verticalEdgeEntryUptimeMs < 0L) {
                long interval = now - lastVerticalTriggerAt;
                diagnosticFireYCount++;
                diagnosticFireYTotalMs += interval;
                diagnosticFireYMaxMs = Math.max(diagnosticFireYMaxMs, interval);
            }
            lastVerticalTriggerAt = now;
            if (verticalEdgeEntryUptimeMs >= 0L) {
                long delay = SystemClock.uptimeMillis() - verticalEdgeEntryUptimeMs;
                diagnosticFirstTriggerYCount++;
                diagnosticFirstTriggerYTotalMs += delay;
                diagnosticFirstTriggerYMaxMs = Math.max(diagnosticFirstTriggerYMaxMs, delay);
                verticalEdgeEntryUptimeMs = -1L;
            }
            String direction = candidate == VerticalEdge.TOP ? "UP" : "DOWN";
            return new Trigger(direction, output, relY, verticalAccumulator);
        }

        private Trigger processHorizontal(float rawX, float relX) {
            float rightClamp = Math.max(0f, options.displayWidth - 1f);
            HorizontalEdge candidate = HorizontalEdge.NONE;
            if (rawX <= PAN_EDGE_THRESHOLD_PX) {
                candidate = HorizontalEdge.LEFT;
            } else if (rawX >= rightClamp - PAN_EDGE_THRESHOLD_PX) {
                candidate = HorizontalEdge.RIGHT;
            }

            if (candidate == HorizontalEdge.NONE) {
                exitHorizontalEdge("cursor left edge");
                return null;
            }

            boolean outward = (candidate == HorizontalEdge.LEFT && relX < 0f)
                    || (candidate == HorizontalEdge.RIGHT && relX > 0f);
            boolean inward = (candidate == HorizontalEdge.LEFT && relX > 0f)
                    || (candidate == HorizontalEdge.RIGHT && relX < 0f);

            if (inward) {
                exitHorizontalEdge("inward input");
                return null;
            }

            if (horizontalEdge != candidate) {
                horizontalEdge = candidate;
                horizontalAccumulator = 0f;
                horizontalEdgeEntryUptimeMs = SystemClock.uptimeMillis();
                diagnosticEdgeEntriesX++;
                return null;
            }

            if (!outward || relX == 0f) return null;
            float magnitude = Math.abs(relX);
            if (magnitude <= PAN_REL_DEAD_ZONE) return null;

            horizontalAccumulator = Math.min(
                    horizontalAccumulator + magnitude * horizontalTuning.sensitivity,
                    horizontalTuning.threshold * horizontalTuning.maxAmount);
            long now = SystemClock.elapsedRealtime();
            boolean belowThreshold = horizontalAccumulator < horizontalTuning.threshold;
            boolean withinInterval = now - lastHorizontalTriggerAt < horizontalTuning.intervalMs;
            if (belowThreshold) diagnosticBelowHorizontal++;
            if (withinInterval) diagnosticIntervalHorizontal++;
            if (belowThreshold || withinInterval) return null;

            int available = (int) Math.floor(
                    horizontalAccumulator / horizontalTuning.threshold);
            int output = Math.max(1, Math.min(horizontalTuning.maxAmount, available));
            horizontalAccumulator -= output * horizontalTuning.threshold;
            if (lastHorizontalTriggerAt > 0L && horizontalEdgeEntryUptimeMs < 0L) {
                long interval = now - lastHorizontalTriggerAt;
                diagnosticFireXCount++;
                diagnosticFireXTotalMs += interval;
                diagnosticFireXMaxMs = Math.max(diagnosticFireXMaxMs, interval);
            }
            lastHorizontalTriggerAt = now;
            if (horizontalEdgeEntryUptimeMs >= 0L) {
                long delay = SystemClock.uptimeMillis() - horizontalEdgeEntryUptimeMs;
                diagnosticFirstTriggerXCount++;
                diagnosticFirstTriggerXTotalMs += delay;
                diagnosticFirstTriggerXMaxMs = Math.max(diagnosticFirstTriggerXMaxMs, delay);
                horizontalEdgeEntryUptimeMs = -1L;
            }
            String direction = candidate == HorizontalEdge.LEFT ? "LEFT" : "RIGHT";
            return new Trigger(direction, output, relX, horizontalAccumulator);
        }

        private void exitVerticalEdge(String reason) {
            if (verticalEdge == VerticalEdge.NONE) return;
            if (options.actionScroll) {
                pendingVScroll = 0f;
            }
            verticalEdge = VerticalEdge.NONE;
            verticalAccumulator = 0f;
            verticalEdgeEntryUptimeMs = -1L;
        }

        private void exitHorizontalEdge(String reason) {
            if (horizontalEdge == HorizontalEdge.NONE) return;
            if (options.actionScroll) pendingHScroll = 0f;
            horizontalEdge = HorizontalEdge.NONE;
            horizontalAccumulator = 0f;
            horizontalEdgeEntryUptimeMs = -1L;
        }

        private boolean sendDiagnosedRequest(String message, String axis) {
            long sequence = ++nextRequestId;
            String id = options.token + "-" + sequence;
            long decisionAt = SystemClock.uptimeMillis();
            long sendStartAt = SystemClock.uptimeMillis();
            String diagnosedMessage = message + "|" + id + "|"
                    + currentEventUptimeMs + "|" + currentReceiveUptimeMs + "|"
                    + decisionAt + "|" + sendStartAt;
            boolean sent = sink.send(diagnosedMessage);
            long sendEndAt = SystemClock.uptimeMillis();
            if (sent) {
                long duration = Math.max(0L, sendEndAt - sendStartAt);
                diagnosticSendDurationTotalMs += duration;
                diagnosticSendDurationMaxMs = Math.max(diagnosticSendDurationMaxMs, duration);
                if ("VERTICAL".equals(axis)) diagnosticSentVertical++;
                else if ("HORIZONTAL".equals(axis)) diagnosticSentHorizontal++;
                else diagnosticSentCorner++;
            }
            return sent;
        }

        void emitDiagnosticSummaryIfDue(long nowUptimeMs) {
            emitDiagnosticSummary(nowUptimeMs, false);
        }

        void emitDiagnosticSummary(long nowUptimeMs, boolean force) {
            if (!force && nowUptimeMs - lastDiagnosticSummaryAt < EDGE_DIAG_WINDOW_MS) return;
            long windowMs = nowUptimeMs - lastDiagnosticSummaryAt;
            lastDiagnosticSummaryAt = nowUptimeMs;
            if (diagnosticMoves == 0L && diagnosticSentVertical == 0L
                    && diagnosticSentHorizontal == 0L && diagnosticSentCorner == 0L) return;
            long sent = diagnosticSentVertical + diagnosticSentHorizontal + diagnosticSentCorner;
            Log.i(EdgeScrollContract.ROOT_TAG,
                    "EDGE_DIAG_ROOT windowMs=" + windowMs
                             + " mode=" + (options.actionScroll ? "ACTION_SCROLL" : "BASELINE")
                            + " moves=" + diagnosticMoves
                            + " batchedEvents=" + diagnosticBatchedEvents
                            + " historySamples=" + diagnosticHistoricalSamples
                            + " belowY=" + diagnosticBelowVertical
                            + " belowX=" + diagnosticBelowHorizontal
                            + " intervalY=" + diagnosticIntervalVertical
                            + " intervalX=" + diagnosticIntervalHorizontal
                            + " entriesY=" + diagnosticEdgeEntriesY
                            + " entriesX=" + diagnosticEdgeEntriesX
                            + " firstYAvgMaxMs=" + (diagnosticFirstTriggerYCount == 0L
                            ? 0L : diagnosticFirstTriggerYTotalMs / diagnosticFirstTriggerYCount)
                            + "/" + diagnosticFirstTriggerYMaxMs
                            + " firstXAvgMaxMs=" + (diagnosticFirstTriggerXCount == 0L
                            ? 0L : diagnosticFirstTriggerXTotalMs / diagnosticFirstTriggerXCount)
                            + "/" + diagnosticFirstTriggerXMaxMs
                            + " sentY=" + diagnosticSentVertical
                            + " sentX=" + diagnosticSentHorizontal
                            + " sentDiagonal=" + diagnosticSentCorner
                            + " fireYAvgMaxMs=" + (diagnosticFireYCount == 0L
                            ? 0L : diagnosticFireYTotalMs / diagnosticFireYCount)
                            + "/" + diagnosticFireYMaxMs
                            + " fireXAvgMaxMs=" + (diagnosticFireXCount == 0L
                            ? 0L : diagnosticFireXTotalMs / diagnosticFireXCount)
                            + "/" + diagnosticFireXMaxMs
                            + " sendAvgMs=" + (sent == 0L ? 0L : diagnosticSendDurationTotalMs / sent)
                            + " sendMaxMs=" + diagnosticSendDurationMaxMs);
            diagnosticMoves = diagnosticBatchedEvents = diagnosticHistoricalSamples = 0L;
            diagnosticBelowVertical = diagnosticBelowHorizontal = 0L;
            diagnosticIntervalVertical = diagnosticIntervalHorizontal = 0L;
            diagnosticSentVertical = diagnosticSentHorizontal = diagnosticSentCorner = 0L;
            diagnosticSendDurationTotalMs = diagnosticSendDurationMaxMs = 0L;
            diagnosticEdgeEntriesY = diagnosticEdgeEntriesX = 0L;
            diagnosticFirstTriggerYCount = diagnosticFirstTriggerYTotalMs = 0L;
            diagnosticFirstTriggerYMaxMs = 0L;
            diagnosticFirstTriggerXCount = diagnosticFirstTriggerXTotalMs = 0L;
            diagnosticFirstTriggerXMaxMs = 0L;
            diagnosticFireYCount = diagnosticFireYTotalMs = diagnosticFireYMaxMs = 0L;
            diagnosticFireXCount = diagnosticFireXTotalMs = diagnosticFireXMaxMs = 0L;
        }
    }

    private static final class EventSink {
        private final LocalSocket socket;
        private final BufferedWriter writer;

        private EventSink(LocalSocket socket, BufferedWriter writer) {
            this.socket = socket;
            this.writer = writer;
        }

        static EventSink connect(String socketName, String token) throws Exception {
            Exception lastError = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                LocalSocket socket = new LocalSocket();
                try {
                    socket.connect(new LocalSocketAddress(
                            socketName, LocalSocketAddress.Namespace.ABSTRACT));
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                            socket.getOutputStream(), StandardCharsets.UTF_8));
                    EventSink sink = new EventSink(socket, writer);
                    if (!sink.send("HELLO|" + token)) {
                        sink.close();
                        throw new IllegalStateException("handshake write failed");
                    }
                    return sink;
                } catch (Exception e) {
                    lastError = e;
                    try { socket.close(); } catch (Exception ignored) {}
                    SystemClock.sleep(50L);
                }
            }
            throw new IllegalStateException("cannot connect app event channel", lastError);
        }

        synchronized boolean send(String line) {
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        void close() {
            try { writer.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean controlTokenMatches(Arguments options) {
        try {
            if (!options.controlFile.isFile()) return false;
            String current = new String(
                    Files.readAllBytes(options.controlFile.toPath()),
                    StandardCharsets.UTF_8).trim();
            return options.token.equals(current);
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteIfOwned(Arguments options) {
        if (controlTokenMatches(options) && !options.controlFile.delete()) {
            Log.w(EdgeScrollContract.ROOT_TAG,
                    "MONITOR_ERROR control token delete failed");
        }
    }

    private static final class Arguments {
        final File controlFile;
        final String token;
        final String socketName;
        final int displayId;
        final int displayWidth;
        final int displayHeight;
        final int verticalLevel;
        final int horizontalLevel;
        final int distanceLevel;
        final boolean actionScroll;

        Arguments(
                File controlFile,
                String token,
                String socketName,
                int displayId,
                int displayWidth,
                int displayHeight,
                int verticalLevel,
                int horizontalLevel,
                int distanceLevel,
                boolean actionScroll
        ) {
            this.controlFile = controlFile;
            this.token = token;
            this.socketName = socketName;
            this.displayId = displayId;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.verticalLevel = verticalLevel;
            this.horizontalLevel = horizontalLevel;
            this.distanceLevel = distanceLevel;
            this.actionScroll = actionScroll;
        }

        static Arguments parse(String[] args) {
            String control = null;
            String token = null;
            String socket = null;
            int displayId = 0;
            int displayWidth = 0;
            int displayHeight = 0;
            int verticalLevel = DEFAULT_SENSITIVITY_LEVEL;
            int horizontalLevel = DEFAULT_SENSITIVITY_LEVEL;
            int distanceLevel = 5;
            boolean actionScroll = false;
            try {
                for (int i = 0; i + 1 < args.length; i += 2) {
                    switch (args[i]) {
                        case "--control": control = args[i + 1]; break;
                        case "--token": token = args[i + 1]; break;
                        case "--socket": socket = args[i + 1]; break;
                        case "--display-id": displayId = Integer.parseInt(args[i + 1]); break;
                        case "--display-width":
                            displayWidth = Integer.parseInt(args[i + 1]);
                            break;
                        case "--display-height":
                            displayHeight = Integer.parseInt(args[i + 1]);
                            break;
                        case "--vertical-level":
                            verticalLevel = Integer.parseInt(args[i + 1]);
                            break;
                        case "--horizontal-level":
                            horizontalLevel = Integer.parseInt(args[i + 1]);
                            break;
                        case "--distance-level":
                            distanceLevel = Integer.parseInt(args[i + 1]);
                            break;
                        case "--action-scroll": actionScroll = "1".equals(args[i + 1]); break;
                        default: return null;
                    }
                }
            } catch (NumberFormatException e) {
                return null;
            }
            if (control == null || token == null || token.isEmpty()
                    || socket == null || socket.isEmpty()
                    || displayWidth <= 0 || displayHeight <= 0
                    || verticalLevel < 1 || verticalLevel > 10
                    || horizontalLevel < 1 || horizontalLevel > 10
                    || distanceLevel < 1 || distanceLevel > 10) {
                return null;
            }
            return new Arguments(
                    new File(control), token, socket, displayId,
                    displayWidth, displayHeight, verticalLevel, horizontalLevel,
                    distanceLevel, actionScroll);
        }
    }
}
