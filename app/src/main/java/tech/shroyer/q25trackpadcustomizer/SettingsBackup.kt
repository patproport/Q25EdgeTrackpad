package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal

/**
 * Exports/imports app SharedPreferences as a JSON backup.
 *
 * v1 backups: root["prefs"] = array of entries for q25_prefs only
 * v2 backups: root["prefs_files"] = [{ name, prefs: [...] }, ...]
 */
object SettingsBackup {

    private const val PREFS_NAME_PRIMARY = "q25_prefs"
    private const val BACKUP_VERSION = 2
    private const val TAG = "Q25EdgeScroll"

    private const val LEGACY_APP_ID = "com.shroyer.tech.q25trackpadmodetoggler"
    private const val PRE_RELEASE_APP_ID = "tech.shroyer.q25trackpadcustomizer.edgescroll"

    fun exportToJson(context: Context): String {
        val root = JSONObject()
        root.put("backup_version", BACKUP_VERSION)
        root.put("app_id", context.packageName)

        val prefsFiles = JSONArray()

        // Export ALL shared_prefs/*.xml so globals can’t “hide” in a different file.
        val names = listAllPrefsNames(context)

        for (name in names) {
            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefsFiles.put(
                JSONObject()
                    .put("name", name)
                    .put("prefs", exportPrefsArray(sp))
            )
        }

        root.put("prefs_files", prefsFiles)
        return root.toString()
    }

    /**
     * Restores settings from JSON.
     * Validates before writing and rolls back changed files if a commit or migration fails.
     */
    fun restoreFromJson(context: Context, json: String): Boolean {
        val pendingByFile = try {
            parseBackup(context, json) ?: return false
        } catch (_: Exception) {
            return false
        }

        // Prefs() may migrate the primary file even when it was absent from a v2 backup.
        val names = LinkedHashSet(pendingByFile.keys).apply { add(PREFS_NAME_PRIMARY) }
        val snapshots = try {
            LinkedHashMap<String, List<PendingEntry>>().apply {
                for (name in names) {
                    put(name, snapshotPrefs(context.getSharedPreferences(name, Context.MODE_PRIVATE)))
                }
            }
        } catch (_: Exception) {
            return false
        }

        val changed = LinkedHashSet<String>()
        try {
            for ((name, entries) in pendingByFile) {
                changed.add(name) // commit(false) can still change the in-memory preferences.
                check(replacePrefs(context, name, entries)) { "Preference commit failed" }
            }
            changed.add(PREFS_NAME_PRIMARY)
            Prefs(context) // Preserve the Smooth Wheel distance first-run migration.
        } catch (e: Exception) {
            for (name in changed.toList().asReversed()) {
                try {
                    if (!replacePrefs(context, name, snapshots.getValue(name))) {
                        Log.e(TAG, "SETTINGS_RESTORE_ERROR rollback commit failed for $name")
                    }
                } catch (rollbackError: Exception) {
                    Log.e(TAG, "SETTINGS_RESTORE_ERROR rollback failed for $name", rollbackError)
                }
            }
            Log.e(TAG, "SETTINGS_RESTORE_ERROR restore failed; rollback attempted", e)
            return false
        }

        // A notification failure must not turn a completed preferences restore into a false failure.
        try { NotificationHelper.clearRootError(context) } catch (e: Exception) {
            Log.w(TAG, "SETTINGS_RESTORE_ERROR root notification cleanup failed", e)
        }
        return true
    }

    private fun snapshotPrefs(sp: SharedPreferences): List<PendingEntry> = sp.all.map { (key, value) ->
        when (value) {
            is Boolean -> PendingEntry.Bool(key, value)
            is Int -> PendingEntry.IntVal(key, value)
            is Long -> PendingEntry.LongVal(key, value)
            is Float -> PendingEntry.FloatVal(key, value)
            is String -> PendingEntry.StringVal(key, value)
            is Set<*> -> {
                check(value.all { it is String }) { "Unsupported preference set" }
                PendingEntry.StringSet(key, value.filterIsInstance<String>().toSet())
            }
            else -> error("Unsupported preference type")
        }
    }

    private fun replacePrefs(context: Context, name: String, entries: List<PendingEntry>): Boolean {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        for (entry in entries) {
            when (entry) {
                is PendingEntry.Bool -> editor.putBoolean(entry.key, entry.value)
                is PendingEntry.IntVal -> editor.putInt(entry.key, entry.value)
                is PendingEntry.LongVal -> editor.putLong(entry.key, entry.value)
                is PendingEntry.FloatVal -> editor.putFloat(entry.key, entry.value)
                is PendingEntry.StringVal -> editor.putString(entry.key, entry.value)
                is PendingEntry.StringSet -> editor.putStringSet(entry.key, entry.value)
            }
        }
        return editor.commit()
    }

    private fun parseBackup(context: Context, json: String): LinkedHashMap<String, List<PendingEntry>>? {
        val root = JSONObject(json)
        val version = exactInt(root.opt("backup_version")) ?: return null
        if (version != 1 && version != 2) return null
        val appId = if (root.has("app_id")) {
            root.opt("app_id") as? String ?: return null
        } else ""
        if (appId.isNotEmpty() && appId != context.packageName &&
            appId != LEGACY_APP_ID && appId != PRE_RELEASE_APP_ID) return null

        val pendingByFile = LinkedHashMap<String, List<PendingEntry>>()
        if (version == 1) {
            if (!root.has("prefs")) return null
            pendingByFile[PREFS_NAME_PRIMARY] =
                parsePrefsArray(PREFS_NAME_PRIMARY, root.getJSONArray("prefs")) ?: return null
        } else {
            if (!root.has("prefs_files")) return null
            val files = root.getJSONArray("prefs_files")
            for (i in 0 until files.length()) {
                val fileObj = files.getJSONObject(i)
                if (!fileObj.has("prefs")) return null
                val sourceName = if (fileObj.has("name")) {
                    fileObj.opt("name") as? String ?: return null
                } else ""
                if ('/' in sourceName || '\\' in sourceName) return null
                val targetName = mapPrefsFileName(sourceName, context.packageName)
                if (targetName.isBlank() || '/' in targetName || '\\' in targetName ||
                    pendingByFile.containsKey(targetName)
                ) return null
                pendingByFile[targetName] = parsePrefsArray(targetName, fileObj.getJSONArray("prefs"))
                    ?: return null
            }
            if (pendingByFile.isEmpty()) return null
        }
        return pendingByFile
    }

    private fun listAllPrefsNames(context: Context): List<String> {
        val names = LinkedHashSet<String>()

        // Always include the known ones.
        names.add(PREFS_NAME_PRIMARY)
        names.add("${context.packageName}_preferences")

        // Include any other prefs files the app has created.
        try {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            val files = dir.listFiles()
            if (files != null) {
                for (f in files) {
                    val n = f.name
                    if (f.isFile && n.endsWith(".xml")) {
                        names.add(n.removeSuffix(".xml"))
                    }
                }
            }
        } catch (_: Exception) {
            // best-effort
        }

        return names.toList().sorted()
    }

    private fun exportPrefsArray(sp: android.content.SharedPreferences): JSONArray {
        val prefsArray = JSONArray()

        for ((key, value) in sp.all) {
            val entry = JSONObject()
            entry.put("key", key)

            when (value) {
                is Boolean -> {
                    entry.put("type", "bool")
                    entry.put("value", value)
                }
                is Int -> {
                    entry.put("type", "int")
                    entry.put("value", value)
                }
                is Long -> {
                    entry.put("type", "long")
                    entry.put("value", value)
                }
                is Float -> {
                    entry.put("type", "float")
                    entry.put("value", value.toDouble())
                }
                is String -> {
                    entry.put("type", "string")
                    entry.put("value", value)
                }
                is Set<*> -> {
                    // Only supports string sets.
                    if (!value.all { it is String }) continue

                    entry.put("type", "string_set")
                    val arr = JSONArray()
                    value.forEach { v -> arr.put(v as String) }
                    entry.put("value", arr)
                }
                else -> {
                    // Unsupported type
                    continue
                }
            }

            prefsArray.put(entry)
        }

        return prefsArray
    }

    private fun parsePrefsArray(name: String, prefsArray: JSONArray): List<PendingEntry>? {
        val pending = ArrayList<PendingEntry>(prefsArray.length())
        val seenKeys = HashSet<String>()

        for (i in 0 until prefsArray.length()) {
            val entry = prefsArray.getJSONObject(i)
            val key = entry.opt("key") as? String ?: return null
            val type = entry.opt("type") as? String ?: return null
            if (key.isEmpty() || !seenKeys.add(key)) return null
            val value = entry.opt("value")

            val parsed = when (type) {
                "bool" -> PendingEntry.Bool(key, value as? Boolean ?: return null)
                "int" -> PendingEntry.IntVal(key, exactInt(value) ?: return null)
                "long" -> PendingEntry.LongVal(key, exactLong(value) ?: return null)
                "float" -> {
                    val number = value as? Number ?: return null
                    val float = number.toFloat()
                    if (!float.isFinite()) return null
                    PendingEntry.FloatVal(key, float)
                }
                "string" -> PendingEntry.StringVal(key, value as? String ?: return null)
                "string_set" -> {
                    val arr = value as? JSONArray ?: return null
                    val set = mutableSetOf<String>()
                    for (j in 0 until arr.length()) {
                        set.add(arr.opt(j) as? String ?: return null)
                    }
                    PendingEntry.StringSet(key, set)
                }
                else -> return null
            }
            if (!isValidPreference(name, parsed)) return null
            pending.add(parsed)
        }

        return pending
    }

    private fun exactInt(value: Any?): Int? = try {
        (value as? Number)?.let { BigDecimal(it.toString()).intValueExact() }
    } catch (_: ArithmeticException) { null }

    private fun exactLong(value: Any?): Long? = try {
        (value as? Number)?.let { BigDecimal(it.toString()).longValueExact() }
    } catch (_: ArithmeticException) { null }

    private fun isValidPreference(name: String, entry: PendingEntry): Boolean {
        if (name == "setup_state" && entry.key == "root_granted") return entry is PendingEntry.Bool
        if (name != PREFS_NAME_PRIMARY) return true
        val key = entry.key
        return when {
            key == "system_default_mode" || key == "last_known_mode" ||
                key.startsWith("app_mode_") -> entry is PendingEntry.IntVal && entry.value in 0..4
            key == "edge_vertical_sensitivity_level" || key == "edge_horizontal_sensitivity_level" ||
                key == "edge_distance_level" || key == "smooth_wheel_distance_level" ->
                entry is PendingEntry.IntVal && entry.value in 1..10
            key == "bottom_touch_guard_level" ->
                entry is PendingEntry.IntVal && entry.value in 1..3
            key == "quick_toggle_modes_global" || key.startsWith("quick_toggle_modes_pkg_") ->
                entry is PendingEntry.IntVal && entry.value >= 0 && (entry.value and 11) == entry.value
            key == "theme_pref" -> entry is PendingEntry.IntVal && entry.value in 0..2
            key == "edge_sensitivity_linked" || key == "toast_quick_toggle" ||
                key == "auto_keyboard_for_text" || key.startsWith("auto_keyboard_for_text_pkg_") ||
                key == "setup_wizard_shown_v1" -> entry is PendingEntry.Bool
            key == "a11y_wizard_step_v1" -> entry is PendingEntry.IntVal && entry.value in 0..4
            key == "excluded_packages" -> entry is PendingEntry.StringSet
            else -> true // Preserve other current and legacy preferences.
        }
    }

    /**
     * Map a prefs file name from a backup (possibly from an older package) into the current app.
     */
    private fun mapPrefsFileName(nameInBackup: String, currentPackageName: String): String {
        if (nameInBackup.isBlank()) return PREFS_NAME_PRIMARY
        if (nameInBackup == PREFS_NAME_PRIMARY) return PREFS_NAME_PRIMARY

        // Default prefs from any package end in "_preferences" - map to current package's default prefs.
        if (nameInBackup.endsWith("_preferences")) {
            return "${currentPackageName}_preferences"
        }

        // If a prefs name somehow includes the legacy package, remap it.
        if (nameInBackup.startsWith(PRE_RELEASE_APP_ID)) {
            return nameInBackup.replaceFirst(PRE_RELEASE_APP_ID, currentPackageName)
        }
        if (nameInBackup.startsWith(LEGACY_APP_ID)) {
            return nameInBackup.replaceFirst(LEGACY_APP_ID, currentPackageName)
        }

        // Unknown/other: restore into the same-named file (best-effort).
        return nameInBackup
    }

    private sealed class PendingEntry(open val key: String) {
        data class Bool(override val key: String, val value: Boolean) : PendingEntry(key)
        data class IntVal(override val key: String, val value: Int) : PendingEntry(key)
        data class LongVal(override val key: String, val value: Long) : PendingEntry(key)
        data class FloatVal(override val key: String, val value: Float) : PendingEntry(key)
        data class StringVal(override val key: String, val value: String) : PendingEntry(key)
        data class StringSet(override val key: String, val value: Set<String>) : PendingEntry(key)
    }
}
