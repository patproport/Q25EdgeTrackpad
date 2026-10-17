package tech.shroyer.q25trackpadcustomizer

import android.R as AndroidR
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    // Header
    private lateinit var tvSettingsVersion: TextView

    // Initial Setup
    private lateinit var btnTestRootAccess: Button
    private lateinit var btnAccessibilitySetup: Button

    // Mouse Mode edge sensitivity
    private lateinit var seekVerticalEdgeSensitivity: SeekBar
    private lateinit var tvVerticalEdgeSensitivityCurrent: TextView
    private lateinit var seekHorizontalEdgeSensitivity: SeekBar
    private lateinit var tvHorizontalEdgeSensitivityCurrent: TextView
    private lateinit var seekEdgeDistance: SeekBar
    private lateinit var tvEdgeDistanceCurrent: TextView
    private lateinit var seekSmoothWheelDistance: SeekBar
    private lateinit var tvSmoothWheelDistanceCurrent: TextView
    private lateinit var checkEdgeSensitivityLinked: CheckBox
    private lateinit var spinnerBottomTouchGuard: Spinner

    // Toast/Theme UI
    private lateinit var checkToastQuick: CheckBox
    private lateinit var spinnerTheme: Spinner

    // Quick Toggle global modes
    private lateinit var checkQuickToggleMouse: CheckBox
    private lateinit var checkQuickToggleMouseSmooth: CheckBox
    private lateinit var checkQuickToggleKeyboard: CheckBox

    // Auto keyboard for text input
    private lateinit var checkAutoKeyboardText: CheckBox

    // Backup/restore
    private lateinit var btnBackupSettings: Button
    private lateinit var btnRestoreSettings: Button

    // GitHub link
    private lateinit var tvViewOnGitHub: TextView

    // Exclusions
    private lateinit var btnAddExcludedApps: Button
    private lateinit var recyclerExcludedApps: RecyclerView
    private lateinit var editSearchExcluded: EditText
    private lateinit var btnResetExcluded: ImageButton

    private lateinit var excludedAdapter: ExcludedAppsAdapter
    private var allExcludedItems: List<ExcludedAppItem> = emptyList()

    // Setup prefs (root granted flag)
    private val setupPrefs by lazy { getSharedPreferences(SETUP_PREFS, MODE_PRIVATE) }

    // Wizard dialog guard
    private var wizardDialogShowing = false

    private val themeOptions = listOf(
        ThemePref.FOLLOW_SYSTEM,
        ThemePref.LIGHT,
        ThemePref.DARK
    )

    private val themeLabels = listOf(
        "Follow system",
        "Light",
        "Dark"
    )

    private var suppressUiListeners = false

    private val backupCreateLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) handleBackupCreate(uri)
        }

    private val restoreOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handleRestoreOpen(uri)
        }

    private fun getAppVersionName(): String {
        return try {
            val pm = packageManager
            val pkg = packageName
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            info.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyThemeFromPrefs(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = Prefs(this)

        bindSettingsViews()
        setupViewOnGitHubLink()

        // Exclusions
        btnAddExcludedApps = findViewById(R.id.btnAddExcludedApps)
        recyclerExcludedApps = findViewById(R.id.recyclerExcludedApps)
        editSearchExcluded = findViewById(R.id.editSearchExcluded)
        btnResetExcluded = findViewById(R.id.btnResetExcluded)

        setupInitialSetupSection()

        setupEdgeSensitivity()
        setupEdgeDistance()
        setupSmoothWheelDistance()
        setupBottomTouchGuard()
        setupToasts()
        setupQuickToggleSettings()
        setupThemeSpinner()
        setupBackupRestore()
        setupAutoKeyboardForText()
        setupExcludedAppsSection()
    }

    private fun bindSettingsViews() {
        // Header
        tvSettingsVersion = findViewById(R.id.tvSettingsVersion)
        tvSettingsVersion.text = "v${getAppVersionName()}"

        // Initial setup buttons
        btnTestRootAccess = findViewById(R.id.btnTestRootAccess)
        btnAccessibilitySetup = findViewById(R.id.btnAccessibilitySetup)

        seekVerticalEdgeSensitivity = findViewById(R.id.seekVerticalEdgeSensitivity)
        tvVerticalEdgeSensitivityCurrent = findViewById(R.id.tvVerticalEdgeSensitivityCurrent)
        seekHorizontalEdgeSensitivity = findViewById(R.id.seekHorizontalEdgeSensitivity)
        tvHorizontalEdgeSensitivityCurrent = findViewById(R.id.tvHorizontalEdgeSensitivityCurrent)
        seekEdgeDistance = findViewById(R.id.seekEdgeDistance)
        tvEdgeDistanceCurrent = findViewById(R.id.tvEdgeDistanceCurrent)
        seekSmoothWheelDistance = findViewById(R.id.seekSmoothWheelDistance)
        tvSmoothWheelDistanceCurrent = findViewById(R.id.tvSmoothWheelDistanceCurrent)
        checkEdgeSensitivityLinked = findViewById(R.id.checkEdgeSensitivityLinked)
        spinnerBottomTouchGuard = findViewById(R.id.spinnerBottomTouchGuard)

        // Toast + theme
        checkToastQuick = findViewById(R.id.checkToastQuick)
        spinnerTheme = findViewById(R.id.spinnerTheme)

        // Quick Toggle settings
        checkQuickToggleMouse = findViewById(R.id.checkQuickToggleMouse)
        checkQuickToggleMouseSmooth = findViewById(R.id.checkQuickToggleMouseSmooth)
        checkQuickToggleKeyboard = findViewById(R.id.checkQuickToggleKeyboard)

        // Auto keyboard
        checkAutoKeyboardText = findViewById(R.id.checkAutoKeyboardText)

        // Backup/restore
        btnBackupSettings = findViewById(R.id.btnBackupSettings)
        btnRestoreSettings = findViewById(R.id.btnRestoreSettings)

        // GitHub link
        tvViewOnGitHub = findViewById(R.id.tvViewOnGitHub)
    }

    override fun onResume() {
        super.onResume()
        refreshUiFromPrefs()
        updateAccessibilitySetupButton()

        // If user is in the middle of the step-by-step wizard, continue it here too.
        if (maybeContinueAccessibilityWizard()) {
            updateAccessibilitySetupButton()
            return
        }
        updateAccessibilitySetupButton()
    }

    private fun refreshUiFromPrefs() {
        if (!::prefs.isInitialized) return

        suppressUiListeners = true
        try {
            refreshEdgeControlsFromPrefs()

            // Toasts
            checkToastQuick.isChecked = prefs.isToastQuickToggleEnabled()

            // Quick Toggle global modes
            val qt = prefs.getGlobalQuickToggleModes()
            checkQuickToggleMouse.isChecked = qt.contains(Mode.MOUSE)
            checkQuickToggleMouseSmooth.isChecked = qt.contains(Mode.MOUSE_SMOOTH)
            checkQuickToggleKeyboard.isChecked = qt.contains(Mode.KEYBOARD)

            // Theme
            val currentTheme = prefs.getThemePref()
            val themeIdx = themeOptions.indexOf(currentTheme).coerceAtLeast(0)
            if (spinnerTheme.selectedItemPosition != themeIdx) {
                spinnerTheme.setSelection(themeIdx, false)
            }

            // Auto keyboard
            checkAutoKeyboardText.isChecked = prefs.isGlobalAutoKeyboardForTextEnabled()

            // Exclusions
            if (::excludedAdapter.isInitialized) {
                refreshExcludedList(editSearchExcluded.text?.toString().orEmpty())
            }
        } finally {
            suppressUiListeners = false
        }
    }

    private fun refreshEdgeControlsFromPrefs() {
        val verticalLevel = prefs.getVerticalEdgeSensitivityLevel()
        seekVerticalEdgeSensitivity.progress = verticalLevel - 1
        tvVerticalEdgeSensitivityCurrent.text = "Current: $verticalLevel"
        val horizontalLevel = prefs.getHorizontalEdgeSensitivityLevel()
        seekHorizontalEdgeSensitivity.progress = horizontalLevel - 1
        tvHorizontalEdgeSensitivityCurrent.text = "Current: $horizontalLevel"
        val distanceLevel = prefs.getEdgeDistanceLevel()
        seekEdgeDistance.progress = distanceLevel - 1
        tvEdgeDistanceCurrent.text = "Current: $distanceLevel"
        val smoothWheelDistanceLevel = prefs.getSmoothWheelDistanceLevel()
        seekSmoothWheelDistance.progress = smoothWheelDistanceLevel - 1
        tvSmoothWheelDistanceCurrent.text = "Current: $smoothWheelDistanceLevel"
        spinnerBottomTouchGuard.setSelection(prefs.getBottomTouchGuardLevel() - 1, false)
        checkEdgeSensitivityLinked.isChecked = prefs.isEdgeSensitivityLinked()
    }

    // ---------- Initial Setup section ----------

    private fun setupInitialSetupSection() {
        btnTestRootAccess.setOnClickListener { runRootTestInteractive() }

        btnAccessibilitySetup.setOnClickListener {
            // If not enabled, start the 3-step wizard.
            if (!isAccessibilityServiceEnabled()) {
                beginAccessibilityWizard()
            }
        }

        updateAccessibilitySetupButton()
    }

    private fun updateAccessibilitySetupButton() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            btnAccessibilitySetup.isEnabled = false
            btnAccessibilitySetup.text = "Accessibility Service enabled ✅"
            btnAccessibilitySetup.alpha = 0.7f
        } else {
            btnAccessibilitySetup.isEnabled = true
            btnAccessibilitySetup.text = "Enable Accessibility Service"
            btnAccessibilitySetup.alpha = 1.0f
        }
    }

    // ---------- Accessibility step-by-step wizard (shared with MainActivity) ----------

    private fun getA11yWizardStep(): Int {
        val sp = getSharedPreferences(SHARED_PREFS_NAME, MODE_PRIVATE)
        return sp.getInt(KEY_A11Y_WIZARD_STEP, 0)
    }

    private fun setA11yWizardStep(step: Int) {
        val sp = getSharedPreferences(SHARED_PREFS_NAME, MODE_PRIVATE)
        sp.edit().putInt(KEY_A11Y_WIZARD_STEP, step).apply()
    }

    private fun cancelAccessibilityWizard() {
        setA11yWizardStep(0)
    }

    private fun beginAccessibilityWizard() {
        setA11yWizardStep(1)
        showAccessibilityWizardStep1()
    }

    private fun maybeContinueAccessibilityWizard(): Boolean {
        if (wizardDialogShowing) return true

        val step = getA11yWizardStep()
        if (step == 0) return false

        if (isAccessibilityServiceEnabled()) {
            cancelAccessibilityWizard()
            return false
        }

        when (step) {
            1 -> showAccessibilityWizardStep1()
            2 -> showAccessibilityWizardStep2()
            3 -> showAccessibilityWizardStep3()
            4 -> showAccessibilityWizardStillNotEnabled()
            else -> cancelAccessibilityWizard()
        }
        return true
    }

    private fun showAccessibilityWizardStep1() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "Step 1 of 3\n\n" +
                    "• Open Accessibility settings\n" +
                    "• Tap “Q25 Trackpad Customizer” (will be grayed out)\n" +
                    "• You’ll see a “Restricted setting” message\n" +
                    "• Come back here (just hit Back)\n\n" +
                    "Already saw the restricted message? You can jump to App info."

        showScrollableDialog(
            title = "Enable Accessibility",
            message = msg,
            positiveText = "Open Accessibility Settings",
            onPositive = {
                setA11yWizardStep(2)
                openAccessibilitySettings()
            },
            neutralText = "Open App Info",
            onNeutral = {
                setA11yWizardStep(3)
                openAppDetailsSettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showAccessibilityWizardStep2() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "Step 2 of 3\n\n" +
                    "Now allow restricted settings for this app:\n" +
                    "• In App info (Next screen), tap the ⋮ menu (top-right)\n" +
                    "• Tap “Allow restricted settings”\n" +
                    "• Enter your PIN/passcode, then return here (Press Back button)\n\n" +
                    "Don’t see the ⋮ menu? You probably missed step 1 - go back and tap the app in Accessibility first."

        showScrollableDialog(
            title = "Allow restricted settings",
            message = msg,
            positiveText = "Open App Info",
            onPositive = {
                setA11yWizardStep(3)
                openAppDetailsSettings()
            },
            neutralText = "Back to Accessibility",
            onNeutral = {
                setA11yWizardStep(2)
                openAccessibilitySettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showAccessibilityWizardStep3() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "Step 3 of 3\n\n" +
                    "Go back to Accessibility and enable the service:\n" +
                    "Settings > Accessibility > Q25 Trackpad Customizer > Enable\n\n" +
                    "Still grayed out? Go back to App info and double-check “Allow restricted settings” is enabled."

        showScrollableDialog(
            title = "Enable the service",
            message = msg,
            positiveText = "Open Accessibility Settings",
            onPositive = {
                setA11yWizardStep(4)
                openAccessibilitySettings()
            },
            neutralText = "Open App Info",
            onNeutral = {
                setA11yWizardStep(3)
                openAppDetailsSettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showAccessibilityWizardStillNotEnabled() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "It looks like the Accessibility service is still OFF.\n\n" +
                    "Go back and make sure “Q25 Trackpad Customizer” is enabled in Accessibility.\n\n" +
                    "If it’s still grayed out, re-check App info > ⋮ > Allow restricted settings."

        showScrollableDialog(
            title = "Almost there",
            message = msg,
            positiveText = "Open Accessibility Settings",
            onPositive = {
                setA11yWizardStep(4)
                openAccessibilitySettings()
            },
            neutralText = "Open App Info",
            onNeutral = {
                setA11yWizardStep(3)
                openAppDetailsSettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showScrollableDialog(
        title: String,
        message: String,
        positiveText: String,
        onPositive: (() -> Unit)?,
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
        negativeText: String = "Close",
        onNegative: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(6))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tv = TextView(this).apply {
            text = message
            textSize = 14f
        }

        container.addView(tv)
        scroll.addView(container)

        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(positiveText) { d, _ ->
                d.dismiss()
                onPositive?.invoke()
            }
            .setNegativeButton(negativeText) { d, _ ->
                d.dismiss()
                onNegative?.invoke()
            }
            .setOnDismissListener { onDismiss?.invoke() }

        if (!neutralText.isNullOrBlank()) {
            b.setNeutralButton(neutralText) { d, _ ->
                d.dismiss()
                onNeutral?.invoke()
            }
        }

        b.show()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open Accessibility settings.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val uri = Uri.parse("package:$packageName")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open app settings.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Root test (su -c id) ----------

    private fun runRootTestInteractive() {
        val progress = showSimpleProgressDialog(
            title = "Checking root…",
            message = "Requesting root access (su -c id). If Magisk/KernelSU prompts you, tap Allow/Grant."
        )

        Thread {
            val result = testRootAccessDetailed()
            Handler(Looper.getMainLooper()).post {
                try { progress.dismiss() } catch (_: Exception) {}
                if (isFinishing || isDestroyed) return@post

                setupPrefs.edit().putBoolean(KEY_ROOT_GRANTED, result.granted).apply()

                val mgr = detectRootManager()
                val mgrLine = when (mgr) {
                    RootManager.MAGISK -> "Magisk detected."
                    RootManager.KERNELSU -> "KernelSU detected."
                    RootManager.UNKNOWN -> "Root manager not detected, or hidden (such as KernelSU)."
                }

                val msg = buildString {
                    append(if (result.granted) "✅ Root access granted.\n\n" else "❌ Root access NOT granted.\n\n")
                    append(mgrLine)
                    append("\n\n")
                    append(
                        when {
                            result.granted -> "You’re good to go."
                            else -> rootTroubleshootingText(mgr)
                        }
                    )
                }

                val otherHelpLabel = when (mgr) {
                    RootManager.MAGISK -> "Using KernelSU instead?"
                    RootManager.KERNELSU -> "Using Magisk instead?"
                    RootManager.UNKNOWN -> "More help"
                }

                showScrollableDialog(
                    title = "Root access",
                    message = msg,
                    positiveText = "OK",
                    onPositive = null,
                    neutralText = otherHelpLabel,
                    onNeutral = { showRootHelpDialog() },
                    negativeText = "Close",
                    onNegative = null
                )
            }
        }.start()
    }

    private data class RootTestResult(
        val granted: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val error: String? = null
    )

    private fun testRootAccessDetailed(): RootTestResult {
        val result = RootProcessRunner.runSu("id", ROOT_TEST_TIMEOUT_MS)
        return RootTestResult(
            granted = result.successful && result.output.contains("uid=0"),
            exitCode = result.exitCode ?: -1,
            stdout = result.output.trim(),
            stderr = "", // stderr is merged into output so it is always drained.
            error = if (result.timedOut) "Root request timed out" else result.error?.message
        )
    }

    private enum class RootManager { MAGISK, KERNELSU, UNKNOWN }

    private fun detectRootManager(): RootManager {
        val pm = packageManager

        val hasMagisk =
            isPackageInstalled(pm, "com.topjohnwu.magisk") ||
                    isPackageInstalled(pm, "io.github.vvb2060.magisk")

        val hasKernelSu =
            isPackageInstalled(pm, "me.weishu.kernelsu")

        return when {
            hasKernelSu -> RootManager.KERNELSU
            hasMagisk -> RootManager.MAGISK
            else -> RootManager.UNKNOWN
        }
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun rootTroubleshootingText(mgr: RootManager): String {
        val managerHint = when (mgr) {
            RootManager.MAGISK -> "Open Magisk > Superuser, find this app, and allow it."
            RootManager.KERNELSU -> "Open KernelSU > Superuser, find this app, and allow it."
            RootManager.UNKNOWN -> "Open your root manager’s Superuser list and allow this app."
        }

        return """
If you didn’t see a prompt, or you previously denied it:

• $managerHint
• Then tap “Test / Grant Root Access” again.

(If root is disabled/unavailable, mode switching won’t work.)
""".trim()
    }

    private fun showRootHelpDialog() {
        showScrollableDialog(
            title = "Root help",
            message = """
This app requests root by running:
su -c id

• Magisk: you should get a prompt the first time. If you denied it before, enable it in Magisk > Superuser.
• KernelSU: enable it in KernelSU > Superuser.

If you’re using something else, look for a “Superuser / SU” permission list and allow this app there.
""".trim(),
            positiveText = "OK",
            onPositive = null,
            negativeText = "Close",
            onNegative = null
        )
    }

    private fun showSimpleProgressDialog(title: String, message: String): AlertDialog {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val bar = ProgressBar(this).apply { isIndeterminate = true }
        val tv = TextView(this).apply {
            text = message
            setPadding(32, 0, 0, 0)
        }

        container.addView(bar)
        container.addView(tv)

        return AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
        if (!enabled) return false

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        // Prefer exact service component match, but also allow "packageName/" style entries as fallback.
        val component = ComponentName(this, AppSwitchService::class.java).flattenToString()
        return enabledServices.split(':').any { entry ->
            entry.equals(component, ignoreCase = true) ||
                    entry.startsWith("$packageName/") ||
                    entry.contains(packageName)
        }
    }

    // ---------- Mouse Mode edge sensitivity ----------

    private fun setupEdgeSensitivity() {
        bindEdgeSensitivity(
            seekVerticalEdgeSensitivity,
            tvVerticalEdgeSensitivityCurrent,
            prefs.getVerticalEdgeSensitivityLevel(),
            prefs::setVerticalEdgeSensitivityLevel,
            seekHorizontalEdgeSensitivity,
            tvHorizontalEdgeSensitivityCurrent
        )
        bindEdgeSensitivity(
            seekHorizontalEdgeSensitivity,
            tvHorizontalEdgeSensitivityCurrent,
            prefs.getHorizontalEdgeSensitivityLevel(),
            prefs::setHorizontalEdgeSensitivityLevel,
            seekVerticalEdgeSensitivity,
            tvVerticalEdgeSensitivityCurrent
        )
        checkEdgeSensitivityLinked.isChecked = prefs.isEdgeSensitivityLinked()
        checkEdgeSensitivityLinked.setOnCheckedChangeListener { _, linked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            val verticalLevel = seekVerticalEdgeSensitivity.progress + 1
            prefs.setEdgeSensitivityLinked(linked, verticalLevel)
            if (linked) {
                seekHorizontalEdgeSensitivity.progress = verticalLevel - 1
                tvHorizontalEdgeSensitivityCurrent.text = "Current: $verticalLevel"
            }
        }
    }

    private fun bindEdgeSensitivity(
        seekBar: SeekBar,
        currentLabel: TextView,
        savedLevel: Int,
        saveLevel: (Int) -> Unit,
        linkedSeekBar: SeekBar,
        linkedCurrentLabel: TextView
    ) {
        seekBar.max = 9
        seekBar.progress = savedLevel - 1
        currentLabel.text = "Current: $savedLevel"
        var trackingTouch = false
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val level = progress + 1
                currentLabel.text = "Current: $level"
                if (fromUser && checkEdgeSensitivityLinked.isChecked) {
                    linkedSeekBar.progress = progress
                    linkedCurrentLabel.text = "Current: $level"
                }
                if (fromUser && !trackingTouch && !suppressUiListeners) saveLevel(level)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                trackingTouch = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                trackingTouch = false
                if (!suppressUiListeners) saveLevel(bar.progress + 1)
            }
        })
    }

    private fun setupEdgeDistance() {
        setupDistanceSeekBar(
            seekEdgeDistance,
            tvEdgeDistanceCurrent,
            prefs.getEdgeDistanceLevel(),
            prefs::setEdgeDistanceLevel
        )
    }

    private fun setupSmoothWheelDistance() {
        setupDistanceSeekBar(
            seekSmoothWheelDistance,
            tvSmoothWheelDistanceCurrent,
            prefs.getSmoothWheelDistanceLevel(),
            prefs::setSmoothWheelDistanceLevel
        )
    }

    private fun setupDistanceSeekBar(
        seekBar: SeekBar,
        currentLabel: TextView,
        savedLevel: Int,
        saveLevel: (Int) -> Unit
    ) {
        seekBar.max = 9
        seekBar.progress = savedLevel - 1
        currentLabel.text = "Current: $savedLevel"
        var trackingTouch = false
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val level = progress + 1
                currentLabel.text = "Current: $level"
                if (fromUser && !trackingTouch && !suppressUiListeners) {
                    saveLevel(level)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                trackingTouch = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                trackingTouch = false
                if (!suppressUiListeners) saveLevel(bar.progress + 1)
            }
        })
    }

    private fun setupBottomTouchGuard() {
        val labels = listOf("Low (3%)", "Medium (5%)", "High (10%)")
        val adapter = ArrayAdapter(this, AndroidR.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerBottomTouchGuard.adapter = adapter
        spinnerBottomTouchGuard.setSelection(prefs.getBottomTouchGuardLevel() - 1, false)
        spinnerBottomTouchGuard.setOnItemSelectedListenerSimple { position ->
            if (!suppressUiListeners) prefs.setBottomTouchGuardLevel(position + 1)
        }
    }

    // ---------- Toast ----------

    private fun setupToasts() {
        checkToastQuick.isChecked = prefs.isToastQuickToggleEnabled()

        checkToastQuick.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setToastQuickToggleEnabled(isChecked)
        }
    }

    // ---------- Quick Toggle ----------

    private fun setupQuickToggleSettings() {
        fun selectedQuickToggleModesFromUi(): Set<Mode> {
            val out = LinkedHashSet<Mode>()
            if (checkQuickToggleMouse.isChecked) out.add(Mode.MOUSE)
            if (checkQuickToggleMouseSmooth.isChecked) out.add(Mode.MOUSE_SMOOTH)
            if (checkQuickToggleKeyboard.isChecked) out.add(Mode.KEYBOARD)
            return out
        }

        fun applyQuickToggleSelection(maybeRevert: (() -> Unit)? = null) {
            val selected = selectedQuickToggleModesFromUi()
            if (selected.isEmpty()) {
                maybeRevert?.invoke()
                Toast.makeText(this, "Select at least one mode for Quick Toggle.", Toast.LENGTH_SHORT).show()
                return
            }
            prefs.setGlobalQuickToggleModes(selected)
        }

        val qt = prefs.getGlobalQuickToggleModes()
        suppressUiListeners = true
        try {
            checkQuickToggleMouse.isChecked = qt.contains(Mode.MOUSE)
            checkQuickToggleMouseSmooth.isChecked = qt.contains(Mode.MOUSE_SMOOTH)
            checkQuickToggleKeyboard.isChecked = qt.contains(Mode.KEYBOARD)

        } finally {
            suppressUiListeners = false
        }

        checkQuickToggleMouse.setOnCheckedChangeListener { _, _ ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            applyQuickToggleSelection(maybeRevert = {
                suppressUiListeners = true
                try { checkQuickToggleMouse.isChecked = true } finally { suppressUiListeners = false }
            })
        }
        checkQuickToggleMouseSmooth.setOnCheckedChangeListener { _, _ ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            applyQuickToggleSelection(maybeRevert = {
                suppressUiListeners = true
                try { checkQuickToggleMouseSmooth.isChecked = true } finally { suppressUiListeners = false }
            })
        }
        checkQuickToggleKeyboard.setOnCheckedChangeListener { _, _ ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            applyQuickToggleSelection(maybeRevert = {
                suppressUiListeners = true
                try { checkQuickToggleKeyboard.isChecked = true } finally { suppressUiListeners = false }
            })
        }
    }

    // ---------- Theme ----------

    private fun setupThemeSpinner() {
        val adapter = ArrayAdapter(this, AndroidR.layout.simple_spinner_item, themeLabels)
        adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerTheme.adapter = adapter

        val currentTheme = prefs.getThemePref()
        val index = themeOptions.indexOf(currentTheme).coerceAtLeast(0)
        spinnerTheme.setSelection(index, false)

        spinnerTheme.setOnItemSelectedListenerSimple { position ->
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
            val selectedTheme = themeOptions[position]
            if (selectedTheme != prefs.getThemePref()) {
                prefs.setThemePref(selectedTheme)
                Prefs.applyThemeFromPrefs(this)
                recreate()
            }
        }
    }

    // ---------- Auto keyboard ----------

    private fun setupAutoKeyboardForText() {
        checkAutoKeyboardText.isChecked = prefs.isGlobalAutoKeyboardForTextEnabled()
        checkAutoKeyboardText.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setGlobalAutoKeyboardForTextEnabled(isChecked)
        }
    }

    // ---------- Backup & Restore ----------

    private fun setupBackupRestore() {
        btnBackupSettings.setOnClickListener {
            val name = "q25_settings_backup_${System.currentTimeMillis()}.json"
            backupCreateLauncher.launch(name)
        }

        btnRestoreSettings.setOnClickListener {
            restoreOpenLauncher.launch(arrayOf("application/json"))
        }
    }

    private fun handleBackupCreate(uri: Uri) {
        try {
            val out = contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Backup output stream unavailable")
            out.use {
                val json = SettingsBackup.exportToJson(this)
                it.write(json.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "Settings backup saved.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to save backup.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleRestoreOpen(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() }
            if (json.isNullOrBlank()) {
                Toast.makeText(this, "Invalid or empty backup file.", Toast.LENGTH_LONG).show()
                return
            }

            completeSettingsRestore(json)
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to restore backup.", Toast.LENGTH_LONG).show()
        }
    }

    private fun completeSettingsRestore(json: String) {
        try {
            val success = SettingsBackup.restoreFromJson(this, json)
            if (success) {
                Toast.makeText(this, "Backup restored. Restarting settings...", Toast.LENGTH_SHORT).show()
                Prefs.applyThemeFromPrefs(this)
                recreate()
            } else {
                Toast.makeText(this, "Backup is invalid or could not be restored.", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to restore backup.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- GitHub link ----------

    private fun setupViewOnGitHubLink() {
        tvViewOnGitHub.paintFlags = tvViewOnGitHub.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tvViewOnGitHub.setOnClickListener { openGitHubReleasePage() }
    }

    private fun openGitHubReleasePage() {
        openInBrowser(GITHUB_RELEASES_URL)
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser found to open the link.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to open link.", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    // ---------- Exclusions ----------

    private fun setupExcludedAppsSection() {
        recyclerExcludedApps.layoutManager = LinearLayoutManager(this)
        excludedAdapter = ExcludedAppsAdapter(mutableListOf()) { item ->
            if (Prefs.DEFAULT_EXCLUDED_PACKAGES.contains(item.packageName)) {
                Toast.makeText(
                    this,
                    "Warning: removing some keyboard apps from exclusions may cause frequent mode switching.",
                    Toast.LENGTH_LONG
                ).show()
            }
            prefs.removeExcludedPackage(item.packageName)
            refreshExcludedList(editSearchExcluded.text?.toString().orEmpty())
        }
        recyclerExcludedApps.adapter = excludedAdapter

        btnAddExcludedApps.setOnClickListener { showExcludeAppPickerDialog() }

        btnResetExcluded.setOnClickListener {
            prefs.resetExcludedPackagesToDefault()
            Toast.makeText(this, "Exclusion list reset to defaults.", Toast.LENGTH_SHORT).show()
            refreshExcludedList(editSearchExcluded.text?.toString().orEmpty())
        }

        editSearchExcluded.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshExcludedList(s?.toString().orEmpty())
            }
        })

        refreshExcludedList("")
    }

    private fun refreshExcludedList(query: String) {
        val pkgs = prefs.getExcludedPackages()
        val pm = packageManager

        allExcludedItems = pkgs.map { pkg -> buildExcludedAppItem(pm, pkg) }

        val filtered = if (query.isBlank()) {
            allExcludedItems
        } else {
            val lower = query.lowercase()
            allExcludedItems.filter {
                it.label.lowercase().contains(lower) ||
                        it.packageName.lowercase().contains(lower)
            }
        }

        excludedAdapter.updateItems(filtered)
    }

    private fun buildExcludedAppItem(pm: PackageManager, packageName: String): ExcludedAppItem {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon = pm.getApplicationIcon(appInfo)
            ExcludedAppItem(packageName, label, icon)
        } catch (_: Exception) {
            ExcludedAppItem(packageName, packageName, null)
        }
    }

    private fun showExcludeAppPickerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_exclude_app_picker, null)
        val editSearch = dialogView.findViewById<EditText>(R.id.editSearchExcludePicker)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerExcludePickerApps)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select apps to exclude")
            .setView(dialogView)
            .setNegativeButton("Close") { d, _ -> d.dismiss() }
            .create()

        val allCandidates = loadCandidateAppsForExclusion()

        lateinit var pickerAdapter: ExclusionPickerAdapter
        pickerAdapter = ExclusionPickerAdapter(this, allCandidates.toMutableList()) { app ->
            prefs.addExcludedPackage(app.packageName)
            refreshExcludedList(editSearchExcluded.text?.toString().orEmpty())
            pickerAdapter.removeApp(app.packageName)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = pickerAdapter

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pickerAdapter.filter(s?.toString().orEmpty())
            }
        })

        dialog.show()
    }

    private fun loadCandidateAppsForExclusion(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }

        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val excluded = prefs.getExcludedPackages()
        val myPackage = packageName

        val map = LinkedHashMap<String, AppItem>()

        for (ri in resolveInfos) {
            val pkg = ri.activityInfo.packageName
            if (map.containsKey(pkg)) continue
            if (pkg == myPackage) continue
            if (excluded.contains(pkg)) continue

            val label = ri.loadLabel(pm).toString()
            val icon = ri.loadIcon(pm)
            map[pkg] = AppItem(pkg, label, icon)
        }

        return map.values.sortedBy { it.label.lowercase() }
    }

    companion object {
        private const val ROOT_TEST_TIMEOUT_MS = 30_000L
        private const val GITHUB_RELEASES_URL = "https://github.com/patproport/Q25EdgeTrackpad/releases/latest"
        private const val SETUP_PREFS = "setup_state"
        const val KEY_ROOT_GRANTED = "root_granted"

        // Must match MainActivity wizard prefs/key
        private const val SHARED_PREFS_NAME = "q25_prefs"
        private const val KEY_A11Y_WIZARD_STEP = "a11y_wizard_step_v1"
    }
}
