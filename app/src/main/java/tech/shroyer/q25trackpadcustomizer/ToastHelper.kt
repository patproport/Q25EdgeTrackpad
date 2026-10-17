package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

// Show notifications without creating an Activity or a window transition.
object ToastHelper {
    private const val TAG = "Q25EdgeScroll"

    fun show(context: Context, text: String) {
        if (text.isEmpty()) return
        val appContext = context.applicationContext ?: context
        val displayToast = Runnable {
            try {
                Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Toast display failed", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            displayToast.run()
        } else {
            try {
                if (!Handler(Looper.getMainLooper()).post(displayToast)) {
                    Log.w(TAG, "Toast display skipped: main looper unavailable")
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Toast dispatch failed", e)
            }
        }
    }
}
