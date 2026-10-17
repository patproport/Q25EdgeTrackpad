package android.hardware.input;

import android.view.InputMonitor;

/** Compile-only declaration of an Android framework hidden API. */
public final class InputManagerGlobal {
    public static InputManagerGlobal getInstance() {
        throw new UnsupportedOperationException("compile-only stub");
    }

    public InputMonitor monitorGestureInput(String name, int displayId) {
        throw new UnsupportedOperationException("compile-only stub");
    }
}
