package android.view;

import android.os.Looper;

/** Compile-only declaration of an Android framework hidden API. */
public abstract class InputEventReceiver {
    public InputEventReceiver(InputChannel inputChannel, Looper looper) {
        throw new UnsupportedOperationException("compile-only stub");
    }

    public void onInputEvent(InputEvent event) {
    }

    public final void finishInputEvent(InputEvent event, boolean handled) {
        throw new UnsupportedOperationException("compile-only stub");
    }

    public void dispose() {
        throw new UnsupportedOperationException("compile-only stub");
    }
}
