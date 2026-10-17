package tech.shroyer.q25trackpadcustomizer

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class AppListAdapter(
    private val context: Context,
    items: List<AppItem>,
    private val prefs: Prefs
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val allItems: MutableList<AppItem> = items.toMutableList()
    private val filteredItems: MutableList<AppItem> = items.toMutableList()

    private val modeOptions = listOf(
        Mode.FOLLOW_SYSTEM,
        Mode.MOUSE_SMOOTH,
        Mode.MOUSE,
        Mode.KEYBOARD
    )

    private val modeLabels = listOf(
        "Follow system",
        "Mouse (Smooth Wheel)",
        "Mouse (Gesture)",
        "Keyboard mode"
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_mode, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = filteredItems.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filteredItems[position]

        holder.tvAppName.text = app.label
        holder.tvPackageName.text = app.packageName
        holder.imgAppIcon.setImageDrawable(app.icon)

        val savedMode = prefs.getAppMode(app.packageName) ?: Mode.FOLLOW_SYSTEM
        val spinnerAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            modeLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        holder.spinnerAppMode.adapter = spinnerAdapter

        val index = modeOptions.indexOf(savedMode).coerceAtLeast(0)
        holder.spinnerAppMode.setSelection(index, false)

        holder.btnAppSettings.visibility = View.VISIBLE
        holder.btnAppSettings.setOnClickListener { showAppSettingsDialog(app) }

        holder.spinnerAppMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val mode = modeOptions[pos]
                if (mode != prefs.getAppMode(app.packageName)) prefs.setAppMode(app.packageName, mode)
            }
        }
    }

    private fun showAppSettingsDialog(app: AppItem) {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_app_settings, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvAppSettingsTitle)

        // Quick Toggle (per-app)
        val checkQtUseGlobal = dialogView.findViewById<CheckBox>(R.id.checkAppQuickToggleUseGlobal)
        val checkQtMouse = dialogView.findViewById<CheckBox>(R.id.checkAppQuickToggleMouse)
        val checkQtMouseSmooth = dialogView.findViewById<CheckBox>(R.id.checkAppQuickToggleMouseSmooth)
        val checkQtKeyboard = dialogView.findViewById<CheckBox>(R.id.checkAppQuickToggleKeyboard)

        // Auto keyboard
        val checkAutoKeyboard = dialogView.findViewById<CheckBox>(R.id.checkAppAutoKeyboard)

        tvTitle.text = "Settings for ${app.label}"

        // ----- Quick Toggle (per-app) -----
        val globalQt = prefs.getGlobalQuickToggleModes()
        val qtOverride = prefs.getAppQuickToggleModesOverride(app.packageName) // null => follow global

        var suppressQt = false
        var pendingQtUseGlobal = (qtOverride == null)
        val pendingQt = LinkedHashSet(qtOverride ?: globalQt)

        fun applyQtSetToUi(set: Set<Mode>) {
            suppressQt = true
            try {
                checkQtMouse.isChecked = set.contains(Mode.MOUSE)
                checkQtMouseSmooth.isChecked = set.contains(Mode.MOUSE_SMOOTH)
                checkQtKeyboard.isChecked = set.contains(Mode.KEYBOARD)
            } finally {
                suppressQt = false
            }
        }

        fun setQtEnabledState() {
            val enabled = !pendingQtUseGlobal
            val alpha = if (enabled) 1f else 0.5f
            checkQtMouse.isEnabled = enabled
            checkQtMouseSmooth.isEnabled = enabled
            checkQtKeyboard.isEnabled = enabled
            checkQtMouse.alpha = alpha
            checkQtMouseSmooth.alpha = alpha
            checkQtKeyboard.alpha = alpha
        }

        fun selectedQtFromUi(): LinkedHashSet<Mode> {
            val out = LinkedHashSet<Mode>()
            if (checkQtMouse.isChecked) out.add(Mode.MOUSE)
            if (checkQtMouseSmooth.isChecked) out.add(Mode.MOUSE_SMOOTH)
            if (checkQtKeyboard.isChecked) out.add(Mode.KEYBOARD)
            return out
        }

        fun syncPendingQtOrRevert(revert: () -> Unit) {
            val sel = selectedQtFromUi()
            if (sel.isEmpty()) {
                revert()
                Toast.makeText(context, "Select at least one Quick Toggle mode.", Toast.LENGTH_SHORT).show()
                return
            }
            pendingQt.clear()
            pendingQt.addAll(sel)
        }

        // Init QT UI
        checkQtUseGlobal.isChecked = pendingQtUseGlobal
        applyQtSetToUi(if (pendingQtUseGlobal) globalQt else pendingQt)
        setQtEnabledState()

        checkQtUseGlobal.setOnCheckedChangeListener { _, isChecked ->
            if (suppressQt) return@setOnCheckedChangeListener
            pendingQtUseGlobal = isChecked
            if (pendingQtUseGlobal) {
                // Show global selection while disabled
                applyQtSetToUi(globalQt)
            } else {
                // Start from current override (or global if none existed)
                if (pendingQt.isEmpty()) pendingQt.addAll(globalQt)
                applyQtSetToUi(pendingQt)
            }
            setQtEnabledState()
        }

        checkQtMouse.setOnCheckedChangeListener { _, _ ->
            if (suppressQt || pendingQtUseGlobal) return@setOnCheckedChangeListener
            syncPendingQtOrRevert {
                suppressQt = true
                try { checkQtMouse.isChecked = true } finally { suppressQt = false }
            }
        }
        checkQtMouseSmooth.setOnCheckedChangeListener { _, _ ->
            if (suppressQt || pendingQtUseGlobal) return@setOnCheckedChangeListener
            syncPendingQtOrRevert {
                suppressQt = true
                try { checkQtMouseSmooth.isChecked = true } finally { suppressQt = false }
            }
        }
        checkQtKeyboard.setOnCheckedChangeListener { _, _ ->
            if (suppressQt || pendingQtUseGlobal) return@setOnCheckedChangeListener
            syncPendingQtOrRevert {
                suppressQt = true
                try { checkQtKeyboard.isChecked = true } finally { suppressQt = false }
            }
        }
        // ----- Auto keyboard -----
        checkAutoKeyboard.isChecked = prefs.isEffectiveAutoKeyboardForTextEnabled(app.packageName)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setPositiveButton("Save") { d, _ ->

                // Save Quick Toggle override
                val finalQtOverride: Set<Mode>? = if (pendingQtUseGlobal) {
                    null
                } else {
                    // If same as global, don't store override (acts like "follow global")
                    val sel = LinkedHashSet(pendingQt)
                    if (sel.isEmpty()) null else sel
                }?.let { sel ->
                    if (sel == prefs.getGlobalQuickToggleModes()) null else sel
                }
                prefs.setAppQuickToggleModesOverride(app.packageName, finalQtOverride)

                val desiredAuto = checkAutoKeyboard.isChecked
                val globalAuto = prefs.isGlobalAutoKeyboardForTextEnabled()
                prefs.setAppAutoKeyboardOverride(app.packageName, if (desiredAuto == globalAuto) null else desiredAuto)

                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()
    }

    fun filter(query: String) {
        val lower = query.lowercase()
        filteredItems.clear()
        if (lower.isEmpty()) {
            filteredItems.addAll(allItems)
        } else {
            filteredItems.addAll(
                allItems.filter {
                    it.label.lowercase().contains(lower) ||
                            it.packageName.lowercase().contains(lower)
                }
            )
        }
        notifyDataSetChanged()
    }

    fun updateApps(newItems: List<AppItem>) {
        allItems.clear()
        allItems.addAll(newItems)
        filteredItems.clear()
        filteredItems.addAll(newItems)
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgAppIcon: ImageView = view.findViewById(R.id.imgAppIcon)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackageName: TextView = view.findViewById(R.id.tvPackageName)
        val spinnerAppMode: Spinner = view.findViewById(R.id.spinnerAppMode)
        val btnAppSettings: ImageButton = view.findViewById(R.id.btnAppSettings)
    }
}
