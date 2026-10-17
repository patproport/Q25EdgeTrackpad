package tech.shroyer.q25trackpadcustomizer

/**
 * Logical trackpad mode.
 * Both Mouse modes use hardware mode 0; Keyboard uses hardware mode 1.
 */
enum class Mode(val prefValue: Int, val procValue: String?) {
    MOUSE(0, "0"),
    KEYBOARD(1, "1"),
    FOLLOW_SYSTEM(2, null),
    MOUSE_SMOOTH(4, "0");

    fun isMouseMode(): Boolean = this == MOUSE || this == MOUSE_SMOOTH

    companion object {

        fun fromPrefValue(value: Int): Mode {
            return when (value) {
                0 -> MOUSE
                1 -> KEYBOARD
                2 -> FOLLOW_SYSTEM
                4 -> MOUSE_SMOOTH
                // Includes the retired value 3 and any invalid value.
                else -> MOUSE
            }
        }

        /**
         * /proc reports the Q25 hardware mode, so both Mouse outputs read as Mouse.
         */
        fun fromProcValue(value: String?): Mode? {
            return when (value) {
                "0" -> MOUSE
                "1" -> KEYBOARD
                else -> null
            }
        }
    }
}
