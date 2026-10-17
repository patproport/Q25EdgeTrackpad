package tech.shroyer.q25trackpadcustomizer

/**
 * Shared in-memory state for foreground tracking and temporary overrides.
 */
object AppState {

    @Volatile
    var currentForegroundPackage: String? = null

    @Volatile
    var manualOverrideForPackage: String? = null

    @Volatile
    var currentMode: Mode? = null
        set(value) {
            val previous = field
            field = value
            if (previous != value) modeObserver?.invoke(value)
        }

    @Volatile
    var modeObserver: ((Mode?) -> Unit)? = null

    @Volatile
    var rootErrorShown: Boolean = false

    // ---- Text-input override ----

    @Volatile
    var textInputOverrideActive: Boolean = false

    @Volatile
    var modeBeforeTextInput: Mode? = null
}
