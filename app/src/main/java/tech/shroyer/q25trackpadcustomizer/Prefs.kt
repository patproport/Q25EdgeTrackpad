package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * SharedPreferences wrapper for app settings.
 */
class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateSmoothWheelDistanceLevel()
    }

    private fun intPref(key: String, default: Int): Int =
        try { prefs.getInt(key, default) } catch (_: ClassCastException) { default }

    private fun migrateSmoothWheelDistanceLevel() {
        if (prefs.contains(KEY_SMOOTH_WHEEL_DISTANCE_LEVEL)) return
        // Copy the old shared distance once; later Gesture changes must not change Smooth Wheel.
        check(prefs.edit().putInt(KEY_SMOOTH_WHEEL_DISTANCE_LEVEL, getEdgeDistanceLevel()).commit()) {
            "Smooth Wheel distance preference migration failed"
        }
    }

    // ---- System default mode ----

    fun getSystemDefaultMode(): Mode {
        return when (val mode = Mode.fromPrefValue(intPref(KEY_SYSTEM_DEFAULT_MODE, Mode.MOUSE.prefValue))) {
            Mode.FOLLOW_SYSTEM -> Mode.MOUSE
            else -> mode
        }
    }

    fun setSystemDefaultMode(mode: Mode) {
        prefs.edit().putInt(KEY_SYSTEM_DEFAULT_MODE,
            if (mode == Mode.FOLLOW_SYSTEM) Mode.MOUSE.prefValue else mode.prefValue).apply()
    }

    // ---- Last-known logical mode ----
    // Avoids ambiguous /proc reads and improves restore reliability.

    fun getLastKnownMode(): Mode? {
        if (!prefs.contains(KEY_LAST_KNOWN_MODE)) return null
        return when (val mode = Mode.fromPrefValue(intPref(KEY_LAST_KNOWN_MODE, Mode.MOUSE.prefValue))) {
            Mode.FOLLOW_SYSTEM -> Mode.MOUSE
            else -> mode
        }
    }

    fun setLastKnownMode(mode: Mode?) {
        prefs.edit().apply {
            if (mode == null) remove(KEY_LAST_KNOWN_MODE)
            else putInt(KEY_LAST_KNOWN_MODE, mode.prefValue)
        }.apply()
    }

    // ---- Mouse Mode edge sensitivity ----

    fun getVerticalEdgeSensitivityLevel(): Int =
        intPref(KEY_EDGE_VERTICAL_SENSITIVITY_LEVEL, DEFAULT_EDGE_SENSITIVITY_LEVEL)
            .coerceIn(1, 10)

    fun setVerticalEdgeSensitivityLevel(level: Int) {
        val safeLevel = level.coerceIn(1, 10)
        prefs.edit().apply {
            putInt(KEY_EDGE_VERTICAL_SENSITIVITY_LEVEL, safeLevel)
            if (isEdgeSensitivityLinked()) {
                putInt(KEY_EDGE_HORIZONTAL_SENSITIVITY_LEVEL, safeLevel)
            }
        }.apply()
    }

    fun getHorizontalEdgeSensitivityLevel(): Int =
        intPref(KEY_EDGE_HORIZONTAL_SENSITIVITY_LEVEL, DEFAULT_EDGE_SENSITIVITY_LEVEL)
            .coerceIn(1, 10)

    fun setHorizontalEdgeSensitivityLevel(level: Int) {
        val safeLevel = level.coerceIn(1, 10)
        prefs.edit().apply {
            putInt(KEY_EDGE_HORIZONTAL_SENSITIVITY_LEVEL, safeLevel)
            if (isEdgeSensitivityLinked()) {
                putInt(KEY_EDGE_VERTICAL_SENSITIVITY_LEVEL, safeLevel)
            }
        }.apply()
    }

    fun isEdgeSensitivityLinked(): Boolean =
        prefs.getBoolean(KEY_EDGE_SENSITIVITY_LINKED, false)

    fun setEdgeSensitivityLinked(linked: Boolean, verticalLevel: Int) {
        prefs.edit().apply {
            putBoolean(KEY_EDGE_SENSITIVITY_LINKED, linked)
            if (linked) {
                val safeLevel = verticalLevel.coerceIn(1, 10)
                putInt(KEY_EDGE_VERTICAL_SENSITIVITY_LEVEL, safeLevel)
                putInt(KEY_EDGE_HORIZONTAL_SENSITIVITY_LEVEL, safeLevel)
            }
        }.apply()
    }

    fun getEdgeDistanceLevel(): Int =
        intPref(KEY_EDGE_DISTANCE_LEVEL, DEFAULT_EDGE_DISTANCE_LEVEL).coerceIn(1, 10)

    fun setEdgeDistanceLevel(level: Int) {
        prefs.edit().putInt(KEY_EDGE_DISTANCE_LEVEL, level.coerceIn(1, 10)).apply()
    }

    fun getSmoothWheelDistanceLevel(): Int =
        intPref(KEY_SMOOTH_WHEEL_DISTANCE_LEVEL, getEdgeDistanceLevel()).coerceIn(1, 10)

    fun setSmoothWheelDistanceLevel(level: Int) {
        prefs.edit().putInt(KEY_SMOOTH_WHEEL_DISTANCE_LEVEL, level.coerceIn(1, 10)).apply()
    }

    fun getBottomTouchGuardLevel(): Int =
        intPref(KEY_BOTTOM_TOUCH_GUARD_LEVEL, 2).coerceIn(1, 3)

    fun setBottomTouchGuardLevel(level: Int) {
        prefs.edit().putInt(KEY_BOTTOM_TOUCH_GUARD_LEVEL, level.coerceIn(1, 3)).apply()
    }

    // ---- Per-app modes ----

    fun getAppMode(packageName: String): Mode? {
        val key = keyAppMode(packageName)
        if (!prefs.contains(key)) return null
        val value = intPref(key, Mode.FOLLOW_SYSTEM.prefValue)
        return Mode.fromPrefValue(value)
    }

    fun setAppMode(packageName: String, mode: Mode) {
        prefs.edit().putInt(keyAppMode(packageName), mode.prefValue).apply()
    }

    // ---- Toast settings ----

    fun isToastQuickToggleEnabled(): Boolean = prefs.getBoolean(KEY_TOAST_QUICK_TOGGLE, true)

    fun setToastQuickToggleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOAST_QUICK_TOGGLE, enabled).apply()
    }

    // ---- Quick Toggle mode selection (global + per-app overrides) ----
    // Stored as a bitmask: Gesture=1, Keyboard=2, Smooth=8.

    fun getGlobalQuickToggleModes(): Set<Mode> {
        val mask = intPref(KEY_QUICK_TOGGLE_MODES_GLOBAL, QUICK_TOGGLE_DEFAULT_MASK)
        val set = maskToQuickToggleModes(mask)
        return if (set.isEmpty()) maskToQuickToggleModes(QUICK_TOGGLE_DEFAULT_MASK) else set
    }

    fun setGlobalQuickToggleModes(modes: Set<Mode>) {
        val sanitized = sanitizeQuickToggleModes(modes)
        val final = if (sanitized.isEmpty()) maskToQuickToggleModes(QUICK_TOGGLE_DEFAULT_MASK) else sanitized
        prefs.edit().putInt(KEY_QUICK_TOGGLE_MODES_GLOBAL, quickToggleModesToMask(final)).apply()
    }

    /**
     * Per-app override set for quick toggle modes, or null if not set.
     * Empty/invalid masks are treated as "not set".
     */
    fun getAppQuickToggleModesOverride(packageName: String?): Set<Mode>? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyQuickToggleModesPkg(packageName)
        if (!prefs.contains(key)) return null
        val mask = intPref(key, 0)
        val set = maskToQuickToggleModes(mask)
        return if (set.isEmpty()) null else set
    }

    fun setAppQuickToggleModesOverride(packageName: String, overrideModes: Set<Mode>?) {
        val key = keyQuickToggleModesPkg(packageName)
        prefs.edit().apply {
            if (overrideModes == null) {
                remove(key)
            } else {
                val sanitized = sanitizeQuickToggleModes(overrideModes)
                if (sanitized.isEmpty()) remove(key)
                else putInt(key, quickToggleModesToMask(sanitized))
            }
        }.apply()
    }

    /**
     * Effective quick toggle mode set (app override if present, else global),
     * clamped to at least one option.
     */
    fun getEffectiveQuickToggleModes(packageName: String?): Set<Mode> {
        val raw = getAppQuickToggleModesOverride(packageName) ?: getGlobalQuickToggleModes()
        val sanitized = sanitizeQuickToggleModes(raw)
        return if (sanitized.isNotEmpty()) sanitized else getGlobalQuickToggleModes()
    }

    // ---- Auto keyboard on text input (global + per-app) ----

    fun isGlobalAutoKeyboardForTextEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_KEYBOARD_FOR_TEXT_GLOBAL, true)
    }

    fun setGlobalAutoKeyboardForTextEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_KEYBOARD_FOR_TEXT_GLOBAL, enabled).apply()
    }

    /**
     * Per-app override for auto keyboard, or null if not set.
     * - true  => forced ON
     * - false => forced OFF
     * - null  => follow global
     */
    fun getAppAutoKeyboardOverride(packageName: String?): Boolean? {
        if (packageName.isNullOrEmpty()) return null
        val key = keyAutoKeyboardForTextPkg(packageName)
        return if (prefs.contains(key)) prefs.getBoolean(key, true) else null
    }

    fun setAppAutoKeyboardOverride(packageName: String, override: Boolean?) {
        val key = keyAutoKeyboardForTextPkg(packageName)
        prefs.edit().apply {
            if (override == null) remove(key)
            else putBoolean(key, override)
        }.apply()
    }

    fun isEffectiveAutoKeyboardForTextEnabled(packageName: String?): Boolean {
        return getAppAutoKeyboardOverride(packageName) ?: isGlobalAutoKeyboardForTextEnabled()
    }

    // ---- Theme ----

    fun getThemePref(): ThemePref {
        val value = intPref(KEY_THEME_PREF, ThemePref.FOLLOW_SYSTEM.prefValue)
        return ThemePref.fromPrefValue(value)
    }

    fun setThemePref(themePref: ThemePref) {
        prefs.edit().putInt(KEY_THEME_PREF, themePref.prefValue).apply()
    }

    // ---- Excluded packages ----

    fun getExcludedPackages(): Set<String> {
        val stored = prefs.getStringSet(KEY_EXCLUDED_PACKAGES, null)
        return if (stored != null) {
            HashSet(stored)
        } else {
            val defaults = DEFAULT_EXCLUDED_PACKAGES
            prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, HashSet(defaults)).apply()
            defaults
        }
    }

    fun addExcludedPackage(packageName: String) {
        val set = HashSet(getExcludedPackages())
        if (set.add(packageName)) {
            prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, set).apply()
        }
    }

    fun removeExcludedPackage(packageName: String) {
        val set = HashSet(getExcludedPackages())
        if (set.remove(packageName)) {
            prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, set).apply()
        }
    }

    fun resetExcludedPackagesToDefault() {
        prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, HashSet(DEFAULT_EXCLUDED_PACKAGES)).apply()
    }

    // ---- Internal helpers ----

    private fun sanitizeQuickToggleModes(modes: Set<Mode>): Set<Mode> {
        val out = LinkedHashSet<Mode>()
        for (m in modes) {
            when (m) {
                Mode.MOUSE, Mode.MOUSE_SMOOTH, Mode.KEYBOARD -> out.add(m)
                else -> Unit
            }
        }
        return out
    }

    private fun quickToggleModesToMask(modes: Set<Mode>): Int {
        var mask = 0
        for (m in modes) {
            mask = mask or when (m) {
                Mode.MOUSE -> QUICK_TOGGLE_MASK_MOUSE
                Mode.MOUSE_SMOOTH -> QUICK_TOGGLE_MASK_MOUSE_SMOOTH
                Mode.KEYBOARD -> QUICK_TOGGLE_MASK_KEYBOARD
                else -> 0
            }
        }
        return mask
    }

    private fun maskToQuickToggleModes(mask: Int): Set<Mode> {
        val out = LinkedHashSet<Mode>()
        if ((mask and QUICK_TOGGLE_MASK_MOUSE) != 0) out.add(Mode.MOUSE)
        if ((mask and QUICK_TOGGLE_MASK_KEYBOARD) != 0) out.add(Mode.KEYBOARD)
        if ((mask and QUICK_TOGGLE_MASK_MOUSE_SMOOTH) != 0) out.add(Mode.MOUSE_SMOOTH)
        return out
    }

    companion object {
        private const val PREFS_NAME = "q25_prefs"

        private const val KEY_SYSTEM_DEFAULT_MODE = "system_default_mode"
        private const val KEY_LAST_KNOWN_MODE = "last_known_mode"
        private const val KEY_EDGE_VERTICAL_SENSITIVITY_LEVEL = "edge_vertical_sensitivity_level"
        private const val KEY_EDGE_HORIZONTAL_SENSITIVITY_LEVEL = "edge_horizontal_sensitivity_level"
        private const val KEY_EDGE_SENSITIVITY_LINKED = "edge_sensitivity_linked"
        private const val DEFAULT_EDGE_SENSITIVITY_LEVEL = 5
        private const val KEY_EDGE_DISTANCE_LEVEL = "edge_distance_level"
        private const val KEY_SMOOTH_WHEEL_DISTANCE_LEVEL = "smooth_wheel_distance_level"
        private const val DEFAULT_EDGE_DISTANCE_LEVEL = 5
        private const val KEY_BOTTOM_TOUCH_GUARD_LEVEL = "bottom_touch_guard_level"

        private fun keyAppMode(pkg: String) = "app_mode_$pkg"

        private const val KEY_TOAST_QUICK_TOGGLE = "toast_quick_toggle"

        // Quick Toggle selection
        private const val KEY_QUICK_TOGGLE_MODES_GLOBAL = "quick_toggle_modes_global"
        private fun keyQuickToggleModesPkg(pkg: String) = "quick_toggle_modes_pkg_$pkg"

        private const val QUICK_TOGGLE_MASK_MOUSE = 1
        private const val QUICK_TOGGLE_MASK_KEYBOARD = 2
        private const val QUICK_TOGGLE_MASK_MOUSE_SMOOTH = 8
        private const val QUICK_TOGGLE_DEFAULT_MASK =
            QUICK_TOGGLE_MASK_MOUSE or QUICK_TOGGLE_MASK_KEYBOARD

        private const val KEY_AUTO_KEYBOARD_FOR_TEXT_GLOBAL = "auto_keyboard_for_text"
        private fun keyAutoKeyboardForTextPkg(pkg: String) = "auto_keyboard_for_text_pkg_$pkg"

        private const val KEY_THEME_PREF = "theme_pref"
        private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"

        val DEFAULT_EXCLUDED_PACKAGES: Set<String> = setOf(
            // Q25 / custom keyboards
            "it.srik.TypeQ25",
            "com.duc1607.q25keyboard",

            // Popular keyboards
            "com.blackberry.keyboard",
            "com.google.android.inputmethod.latin", // Gboard
            "com.touchtype.swiftkey"             // SwiftKey
        )

        fun applyThemeFromPrefs(context: Context) {
            val prefs = Prefs(context.applicationContext)
            val themePref = prefs.getThemePref()
            val mode = when (themePref) {
                ThemePref.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemePref.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemePref.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
