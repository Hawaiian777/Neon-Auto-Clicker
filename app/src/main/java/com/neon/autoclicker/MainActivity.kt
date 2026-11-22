package com.neon.autoclicker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val EXTRA_SECTION = "section"
        const val SECTION_TOP = "top"
        const val SECTION_APPEARANCE = "appearance"
        private val QUICK_INTERVALS = Config.QUICK_INTERVALS
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var scrollRoot: ScrollView
    private lateinit var permCard: LinearLayout
    private lateinit var permStatus: TextView
    private lateinit var permList: LinearLayout
    private lateinit var permTip: TextView
    private lateinit var btnPermission: Button
    private lateinit var permBg: PermBackground
    private lateinit var intervalValue: TextView
    private lateinit var intervalHint: TextView
    private lateinit var seek: SeekBar
    private lateinit var switchTimer: Switch
    private lateinit var btnTimer: Button
    private lateinit var switchVolume: Switch
    private lateinit var targetsInfo: TextView
    private lateinit var profileList: LinearLayout
    private lateinit var appearanceCard: View
    private lateinit var themeName: TextView
    private lateinit var switchLock: Switch
    private lateinit var targetSeek: SeekBar
    private lateinit var targetSizeValue: TextView
    private lateinit var preview: TargetView
    private lateinit var btnLaunch: Button
    private lateinit var btnCloseOverlay: Button
    private lateinit var btnReturnTop: Button

    private val swatches = mutableListOf<View>()
    private val intervalChips = mutableListOf<Pair<Button, Int>>() // quarters, or Prefs.CUSTOM
    private val warnChips = mutableListOf<Pair<Button, Int>>()     // seconds, or Prefs.CUSTOM
    private val modeChips = mutableListOf<Pair<Button, Int>>()
    private val appModeChips = mutableListOf<Pair<Button, AppMode>>()
    private val menuChips = mutableListOf<Pair<Button, Int>>()
    private val bgChips = mutableListOf<Pair<Button, Boolean>>()   // true = Clear

    /** Color + appearance the screen was last styled with; restyle only when it changes. */
    private var styledWith: String? = null

    private val ui = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density

    /** The chosen neon color in the chosen app appearance (Night / Day / Sunset / System). */
    private fun neon() = Themes.app(this)

    /** Watches the real Android accessibility setting, so the Permission card always matches it. */
    private val a11yObserver = object : ContentObserver(ui) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // the service connects a moment after the switch flips
            ui.postDelayed(refreshServiceTask, 300)
            ui.postDelayed(refreshServiceTask, 1500)
        }
    }
    private val refreshServiceTask = Runnable { refreshService() }

    /**
     * Every setting shown on this screen refreshes from here, whether it was changed on this
     * screen or on the floating menu. (Listeners only fire when a value really changes.)
     */
    private val prefSync = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        ui.post {
            when (key) {
                Prefs.KEY_THEME, Prefs.KEY_APP_MODE -> {
                    ui.removeCallbacks(restyle)
                    ui.postDelayed(restyle, 50)
                }
                Prefs.KEY_MENU_SIZE, Prefs.KEY_PANEL_CLEAR, Prefs.KEY_PANEL_LOCKED -> refreshAppearance()
                Prefs.KEY_TARGET_DP -> refreshTargetSize()
                Prefs.KEY_INTERVAL_Q -> refreshInterval()
                Prefs.KEY_TIMER_ON, Prefs.KEY_TIMER_DUR -> refreshTimer()
                Prefs.KEY_WARN_SEC -> refreshWarn()
                Prefs.KEY_MODE, Prefs.KEY_TARGETS -> refreshTargets()
                Prefs.KEY_PROFILES -> refreshProfiles()
            }
        }
    }
    private val restyle = Runnable { applyTheme() }

    // ---------------- Lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)

        scrollRoot = findViewById(R.id.scrollRoot)
        permCard = findViewById(R.id.permCard)
        permStatus = findViewById(R.id.permStatus)
        permList = findViewById(R.id.permList)
        permTip = findViewById(R.id.permTip)
        btnPermission = findViewById(R.id.btnPermission)
        intervalValue = findViewById(R.id.intervalValue)
        intervalHint = findViewById(R.id.intervalHint)
        seek = findViewById(R.id.intervalSeek)
        switchTimer = findViewById(R.id.switchTimer)
        btnTimer = findViewById(R.id.btnTimer)
        switchVolume = findViewById(R.id.switchVolume)
        targetsInfo = findViewById(R.id.targetsInfo)
        profileList = findViewById(R.id.profileList)
        appearanceCard = findViewById(R.id.appearanceCard)
        themeName = findViewById(R.id.themeName)
        switchLock = findViewById(R.id.switchLock)
        targetSeek = findViewById(R.id.targetSeek)
        targetSizeValue = findViewById(R.id.targetSizeValue)
        btnLaunch = findViewById(R.id.btnLaunch)
        btnCloseOverlay = findViewById(R.id.btnCloseOverlay)
        btnReturnTop = findViewById(R.id.btnReturnTop)

        // 1. Permission (the card's background only animates while it's on screen)
        permBg = PermBackground(this)
        btnPermission.setOnClickListener { onPermissionButton() }
        scrollRoot.setOnScrollChangeListener { _, _, _, _, _ -> updatePermOnScreen() }

        // 2. Interval
        seek.max = Prefs.SLIDER_MAX_Q - Prefs.MIN_Q
        seek.setOnSeekBarChangeListener(onUserSeek { setQuarters(it + Prefs.MIN_Q) })
        findViewById<Button>(R.id.btnMinus).setOnClickListener {
            val q = Prefs.quarters(this)
            setQuarters(q - Prefs.stepBelow(q))
        }
        findViewById<Button>(R.id.btnPlus).setOnClickListener {
            val q = Prefs.quarters(this)
            setQuarters(q + Prefs.stepAbove(q))
        }
        buildChipGrid(R.id.quickGrid, 5, QUICK_INTERVALS + ("Custom" to Prefs.CUSTOM), intervalChips) { q ->
            if (q == Prefs.CUSTOM) showCustomIntervalDialog() else setQuarters(q)
        }

        // 3. Timer and warning lights
        switchTimer.setOnCheckedChangeListener { _, checked -> Prefs.setTimerOn(this, checked) }
        btnTimer.setOnClickListener { showTimerDialog() }
        buildChipGrid(R.id.warnGrid, 4, Prefs.WARN_PRESETS.map { Prefs.durLabel(it) to it } + ("Custom" to Prefs.CUSTOM), warnChips) { sec ->
            if (sec == Prefs.CUSTOM) showCustomWarnDialog() else prefs.edit().putInt(Prefs.KEY_WARN_SEC, sec).apply()
        }
        val beacon = BeaconView(this).apply { alignTo = findViewById(R.id.warnLabel) }
        findViewById<FrameLayout>(R.id.warnBeaconHolder).addView(
            beacon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // 4. Start / stop
        switchVolume.isChecked = prefs.getBoolean(Prefs.KEY_VOLUME_TOGGLE, false)
        switchVolume.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_VOLUME_TOGGLE, checked).apply()
        }

        // 5. Targets, 6. Profiles
        buildChipRow(R.id.modeRow, listOf("In turn: 1, 2, 3…" to Prefs.MODE_SEQUENCE, "All, in order" to Prefs.MODE_ALL), modeChips, 44) {
            prefs.edit().putInt(Prefs.KEY_MODE, it).apply()
        }
        findViewById<Button>(R.id.btnSaveProfile).setOnClickListener { showSaveProfileDialog() }

        // 7. Appearance
        buildChipRow(R.id.appModeRow, AppMode.values().map { it.label to it }, appModeChips) {
            prefs.edit().putInt(Prefs.KEY_APP_MODE, it.ordinal).apply()
        }
        buildSwatches()
        val sizes = Prefs.MENU_SIZES.mapIndexed { i, (label, _) -> label to i }
        buildChipRow(R.id.menuRow1, sizes.take(3), menuChips) { prefs.edit().putInt(Prefs.KEY_MENU_SIZE, it).apply() }
        buildChipRow(R.id.menuRow2, sizes.drop(3), menuChips) { prefs.edit().putInt(Prefs.KEY_MENU_SIZE, it).apply() }
        buildChipRow(R.id.bgRow, listOf("Black" to false, "Clear" to true), bgChips) {
            prefs.edit().putBoolean(Prefs.KEY_PANEL_CLEAR, it).apply()
        }
        switchLock.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_PANEL_LOCKED, checked).apply()
        }
        targetSeek.max = (Prefs.TARGET_MAX_DP - Prefs.TARGET_MIN_DP) / Prefs.TARGET_STEP_DP
        targetSeek.setOnSeekBarChangeListener(onUserSeek { progress ->
            prefs.edit().putInt(Prefs.KEY_TARGET_DP, Prefs.TARGET_MIN_DP + progress * Prefs.TARGET_STEP_DP).apply()
        })
        preview = TargetView(this)
        findViewById<FrameLayout>(R.id.targetPreview).addView(preview)
        findViewById<Button>(R.id.btnResetLook).setOnClickListener {
            prefs.edit()
                .remove(Prefs.KEY_THEME)
                .remove(Prefs.KEY_MENU_SIZE)
                .remove(Prefs.KEY_TARGET_DP)
                .remove(Prefs.KEY_APP_MODE)
                .remove(Prefs.KEY_PANEL_CLEAR)
                .apply()
        }

        // Floating controls
        btnLaunch.setOnClickListener { onLaunchButton() }
        btnReturnTop.setOnClickListener { onLaunchButton() }
        btnCloseOverlay.setOnClickListener {
            ClickerService.instance?.hideOverlay()
            refreshService()
        }

        showVersion()

        // Opened from the app icon with no floating session going on: the auto-stop timer
        // starts OFF at 1:00 (deliberately not remembered). Coming back from the floating
        // menu (Home / Settings) never resets it.
        if (savedInstanceState == null && isLauncherLaunch(intent) && ClickerService.instance?.isOverlayShown != true) {
            Prefs.resetTimer(this)
        }

        prefs.registerOnSharedPreferenceChangeListener(prefSync)
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, a11yObserver
        )
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, a11yObserver
        )
        handleSection(intent)
        // everything is filled in by onResume, which runs next
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSection(intent)
    }

    override fun onResume() {
        super.onResume()
        ClickerService.appInForeground = true
        // After the one-time PC setup, quietly switch the service back on if the phone turned it off.
        if (ClickerService.instance == null && !isServiceEnabledInSettings()) enableServiceDirectly()

        // Settings may have changed on the floating menu while this screen was hidden.
        if (styledWith != styleKey()) applyTheme() else refreshAll()
        if (ClickerService.instance == null) ui.postDelayed(refreshServiceTask, 800) // may still be connecting
        scrollRoot.post { updatePermOnScreen() }
    }

    override fun onPause() {
        super.onPause()
        ClickerService.appInForeground = false
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefSync)
        contentResolver.unregisterContentObserver(a11yObserver)
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun isLauncherLaunch(i: Intent?): Boolean =
        i?.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_LAUNCHER)

    private fun handleSection(i: Intent?) {
        val section = i?.getStringExtra(EXTRA_SECTION) ?: return
        i.removeExtra(EXTRA_SECTION)
        when (section) {
            SECTION_APPEARANCE -> scrollRoot.post { scrollRoot.smoothScrollTo(0, appearanceCard.top) }
            SECTION_TOP -> scrollRoot.post { scrollRoot.smoothScrollTo(0, 0) }
        }
    }

    /** Version and build number come from the build settings (build.gradle.kts). */
    @Suppress("DEPRECATION")
    private fun showVersion() {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        val code = when {
            info == null -> 0L
            Build.VERSION.SDK_INT >= 28 -> info.longVersionCode
            else -> info.versionCode.toLong()
        }
        findViewById<TextView>(R.id.versionText).text = "Version ${info?.versionName ?: "?"}"
        findViewById<TextView>(R.id.buildText).text = "Build $code"
    }

    private fun updatePermOnScreen() {
        permBg.onScreen = permCard.getLocalVisibleRect(Rect())
    }

    // ---------------- Theme ----------------

    private fun styleKey() = "${Prefs.themeIndex(this)}|${Themes.resolveLook(this)}"

    /** Re-colors the whole screen with the chosen color and appearance, then refreshes it. */
    private fun applyTheme() {
        val t = neon()
        applyWindowLook(t)
        styleTree(scrollRoot, t)
        // The Permission card sits on its own dark background (red grid or electric green),
        // so its contents use the dark look in every appearance, with a soft shadow for contrast.
        val dark = Themes.current(this)
        for (i in 0 until permCard.childCount) styleTree(permCard.getChildAt(i), dark)
        shadowTexts(permCard)
        styledWith = styleKey()
        refreshAll()
    }

    private fun refreshAll() {
        refreshService()
        refreshInterval()
        refreshTimer()
        refreshWarn()
        refreshTargets()
        refreshProfiles()
        refreshAppearance()
    }

    /** Background (gradient for Sunset) and status/navigation bar colors and icons. */
    @Suppress("DEPRECATION")
    private fun applyWindowLook(t: NeonTheme) {
        window.setBackgroundDrawable(t.background())
        scrollRoot.setBackgroundColor(Color.TRANSPARENT)
        window.statusBarColor = if (t.look == Look.SUNSET) Color.parseColor("#2A0D2A") else t.bg
        window.navigationBarColor = if (t.look == Look.SUNSET) Color.parseColor("#2E1206") else t.bg
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (t.isLight) mask else 0, mask)
        } else {
            var flags = window.decorView.systemUiVisibility
            flags = if (t.isLight) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= 26) {
                flags = if (t.isLight) flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    /** Colors views by their tag (set in the layout). */
    private fun styleTree(v: View, t: NeonTheme) {
        when (v.tag) {
            "title" -> (v as TextView).apply {
                setTextColor(t.main)
                setShadowLayer(if (t.isLight) 0f else 18f, 0f, 0f, t.main)
            }
            "glow" -> (v as TextView).apply {
                setTextColor(t.main)
                setShadowLayer(if (t.isLight) 0f else 14f, 0f, 0f, t.main)
            }
            "neon" -> (v as TextView).setTextColor(t.main)
            "body" -> (v as TextView).setTextColor(t.body)
            "card" -> v.background = NeonUi.card(t, density)
            "permcard" -> v.background = permBg
            "outline" -> {
                v.background = NeonUi.outline(t, density)
                (v as TextView).setTextColor(t.main)
            }
            "filled" -> {
                v.background = NeonUi.filled(t, density)
                (v as TextView).setTextColor(t.onMain)
            }
            "seek" -> (v as SeekBar).apply {
                progressTintList = ColorStateList.valueOf(t.main)
                thumbTintList = ColorStateList.valueOf(t.main)
                progressBackgroundTintList = ColorStateList.valueOf(t.faint)
            }
            "switch" -> (v as Switch).apply {
                setTextColor(t.main)
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                val offThumb = Color.parseColor(if (t.isLight) "#B8BEC4" else "#555555")
                val offTrack = Color.parseColor(if (t.isLight) "#DDE1E5" else "#222222")
                thumbTintList = ColorStateList(states, intArrayOf(t.main, offThumb))
                trackTintList = ColorStateList(states, intArrayOf(t.dim, offTrack))
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) styleTree(v.getChildAt(i), t)
    }

    private fun shadowTexts(v: View) {
        if (v is TextView && v !is Button) v.setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
        if (v is ViewGroup) for (i in 0 until v.childCount) shadowTexts(v.getChildAt(i))
    }

    // ---------------- Chips (shared) ----------------

    private fun chipLp(heightDp: Int = 40) =
        LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }

    private fun chip(label: String, onClick: () -> Unit) = Button(this, null, 0, R.style.NeonChip).apply {
        text = label
        textSize = 12f
        setOnClickListener { onClick() }
    }

    /** One row of equal-width chips. */
    private fun <T> buildChipRow(
        rowId: Int, items: List<Pair<String, T>>, into: MutableList<Pair<Button, T>>,
        heightDp: Int = 40, onPick: (T) -> Unit,
    ) {
        val row = findViewById<LinearLayout>(rowId)
        items.forEach { (label, value) ->
            val b = chip(label) { onPick(value) }
            row.addView(b, chipLp(heightDp))
            into += b to value
        }
    }

    /** Chips wrapped into rows of [perRow]; the last row keeps the same chip width. */
    private fun buildChipGrid(
        gridId: Int, perRow: Int, items: List<Pair<String, Int>>,
        into: MutableList<Pair<Button, Int>>, onPick: (Int) -> Unit,
    ) {
        val grid = findViewById<LinearLayout>(gridId)
        var row: LinearLayout? = null
        items.forEachIndexed { i, (label, value) ->
            if (i % perRow == 0) row = LinearLayout(this).also { grid.addView(it) }
            val b = chip(label) { onPick(value) }
            row?.addView(b, chipLp())
            into += b to value
        }
        repeat((perRow - items.size % perRow) % perRow) { row?.addView(View(this), chipLp()) }
    }

    /** Filled = selected, outlined = not. */
    private fun select(b: Button, on: Boolean, t: NeonTheme) {
        if (on) {
            b.background = NeonUi.filled(t, density, 12f)
            b.setTextColor(t.onMain)
        } else {
            b.background = NeonUi.outline(t, density)
            b.setTextColor(t.main)
        }
    }

    // ---------------- 1. Permission ----------------

    private val serviceComponent get() = ComponentName(this, ClickerService::class.java)

    /** Is Neon Auto Clicker switched on in Android's accessibility settings? (the real setting) */
    private fun isServiceEnabledInSettings(): Boolean = try {
        Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').any { it.isNotBlank() && ComponentName.unflattenFromString(it) == serviceComponent }
    } catch (e: Exception) {
        ClickerService.instance != null
    }

    private fun isBatteryUnrestricted(): Boolean = try {
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (e: Exception) {
        false
    }

    private val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase(Locale.US).let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    /** True only after the one-time grant: adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS */
    private fun canWriteSecure() =
        checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Adds our service to the enabled list. Only works with the one-time grant. */
    private fun enableServiceDirectly(): Boolean {
        if (!canWriteSecure()) return false
        return try {
            val cur = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val parts = cur.split(':').filter { it.isNotBlank() }.toMutableList()
            if (parts.none { ComponentName.unflattenFromString(it) == serviceComponent }) parts += serviceComponent.flattenToString()
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, parts.joinToString(":"))
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Permission card and floating-controls buttons, from the real Android state. */
    private fun refreshService() {
        val svc = ClickerService.instance
        val auto = canWriteSecure()
        val enabled = isServiceEnabledInSettings()
        val allOn = enabled && svc != null // every required permission is really on
        val onColor = StatusColors.ON
        val offColor = StatusColors.OFF
        val warnColor = StatusColors.WARN

        permBg.on = allOn
        permStatus.text = if (allOn) "✓ ALL SET · PERMISSION ON" else "✗ PERMISSION NEEDED"
        permStatus.setTextColor(if (allOn) onColor else offColor)

        permList.removeAllViews()
        when {
            allOn -> permRow("✓", onColor, "Accessibility service · required", "ON and running")
            enabled -> permRow("!", warnColor, "Accessibility service · required",
                "Switched on, but not running. If this stays, turn it off and on again.")
            else -> permRow("✗", offColor, "Accessibility service · required", "OFF · lets the app tap the screen")
        }
        if (isBatteryUnrestricted()) {
            permRow("✓", onColor, "Battery · recommended", "Unrestricted")
        } else {
            permRow("•", warnColor, "Battery · recommended",
                "Set to “No restrictions” so the phone doesn't switch the clicker off. Tap to open ›") {
                openAppBatterySettings()
            }
        }

        btnPermission.text = when {
            allOn -> "Accessibility settings"
            auto -> "Turn on permission"
            else -> "Open accessibility settings"
        }
        permTip.text = when {
            allOn -> if (auto) "Auto-enable is set up: the app turns this back on if the phone switches it off." else ""
            auto -> "Auto-enable is set up: one tap turns it on."
            else -> buildString {
                append("Android doesn't let apps turn this on by themselves. After tapping the button:\n")
                append("1. Find Neon Auto Clicker. It's often under “Downloaded apps” or “Installed apps”.\n")
                append("2. Open it and turn the switch on, then tap Allow.")
                if (isXiaomi) append("\nXiaomi: wait for the 10-second countdown before Allow.")
            }
        }
        permTip.visibility = if (permTip.text.isEmpty()) View.GONE else View.VISIBLE
        shadowTexts(permCard)

        val shown = svc?.isOverlayShown == true
        btnLaunch.text = if (shown) "RETURN TO FLOATING CONTROLS" else "SHOW FLOATING CONTROLS"
        btnCloseOverlay.visibility = if (shown) View.VISIBLE else View.GONE
        btnReturnTop.visibility = if (shown) View.VISIBLE else View.GONE
    }

    private fun permRow(mark: String, markColor: Int, title: String, detail: String, onClick: (() -> Unit)? = null) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            if (onClick != null) setOnClickListener { onClick() }
        }
        r.addView(TextView(this).apply {
            text = mark
            textSize = 15f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(markColor)
            minWidth = dp(22)
        })
        r.addView(TextView(this).apply {
            text = "$title\n$detail"
            textSize = 13f
            setTextColor(StatusColors.TEXT)
            setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        permList.addView(r)
    }

    private fun onPermissionButton() {
        if (ClickerService.instance == null && !isServiceEnabledInSettings() && enableServiceDirectly()) return // the observer updates the card
        openAccessibilitySettings()
    }

    /** This app's own Android settings page (Battery is on it on most phones, including Xiaomi). */
    private fun openAppBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            toast("Tap Battery, then choose “No restrictions” or “Unrestricted”", long = true)
        } catch (e: Exception) {
            toast("Couldn't open app settings")
        }
    }

    /**
     * Gets as close to Neon Auto Clicker's own accessibility switch as Android allows, using only
     * standard Android intents (nothing specific to one phone maker):
     *  1. Android 11+: the app's own switch page. Android 12+ usually reserves it for system
     *     apps, so this often fails, which is expected.
     *  2. The Accessibility list, asking Settings to highlight our entry (Pixel-style Settings
     *     scroll to it; other phones just open the list).
     *  3. Main Settings.
     * A short message then says what to tap next.
     */
    private fun openAccessibilitySettings() {
        val flat = serviceComponent.flattenToString()
        val countdown = if (isXiaomi) " Wait for the countdown, then Allow." else ""
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        .putExtra(Intent.EXTRA_COMPONENT_NAME, flat)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                toast("Turn on the Neon Auto Clicker switch.$countdown", long = true)
                return
            } catch (e: Exception) {
                // not allowed on this phone: fall through
            }
        }
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(":settings:fragment_args_key", flat)
                    .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", flat) })
            )
            toast("Tap “Downloaded apps” or “Installed apps” if you see it, then Neon Auto Clicker, and turn it on.$countdown", long = true)
            return
        } catch (e: Exception) {
            // fall through
        }
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            toast("Open Accessibility, then find Neon Auto Clicker and turn it on.", long = true)
        } catch (e: Exception) {
            toast("Couldn't open settings")
        }
    }

    // ---------------- Floating controls ----------------

    /**
     * Controls already showing: step back to them exactly as they are (nothing is hidden,
     * reset or rebuilt). Not showing: show them, restored from the saved settings.
     */
    private fun onLaunchButton() {
        val svc = ClickerService.instance
        if (svc == null) {
            toast("Turn on the permission first", long = true)
            onPermissionButton()
            return
        }
        if (!svc.isOverlayShown) svc.showOverlay()
        moveTaskToBack(true)
    }

    // ---------------- 2. Interval ----------------

    private fun setQuarters(q: Int) {
        prefs.edit().putInt(Prefs.KEY_INTERVAL_Q, q.coerceIn(Prefs.MIN_Q, Prefs.MAX_Q)).apply()
    }

    private fun refreshInterval() {
        val t = neon()
        val q = Prefs.quarters(this)
        val label = Prefs.formatInterval(q)
        intervalValue.text = label
        intervalValue.textSize = if (label.length > 9) 22f else 34f
        seek.progress = minOf(q, Prefs.SLIDER_MAX_Q) - Prefs.MIN_Q
        val isPreset = QUICK_INTERVALS.any { it.second == q }
        intervalChips.forEach { (b, cq) -> select(b, if (cq == Prefs.CUSTOM) !isPreset else cq == q, t) }
        intervalHint.text = Config.rateHint(q)
    }

    private fun showCustomIntervalDialog() {
        val q = Prefs.quarters(this)
        val m = picker(59, q / 240)
        val s = picker(59, (q % 240) / 4)
        val f = picker(3, q % 4).apply { displayedValues = arrayOf(".00", ".25", ".50", ".75") }
        neonDialog("Custom interval", pickerRow(m to "min", s to "", f to "s"), "Set") {
            val total = m.value * 240 + s.value * 4 + f.value
            if (total <= 0) toast("Pick an interval longer than 0") else setQuarters(total)
        }
    }

    // ---------------- 3. Timer and warning lights ----------------

    private fun refreshTimer() {
        val on = Prefs.timerOn(this)
        if (switchTimer.isChecked != on) switchTimer.isChecked = on
        switchTimer.text = if (on) "Auto-stop timer · ON" else "Auto-stop timer · OFF"
        btnTimer.text = "Length: ${Prefs.durLabel(Prefs.timerDuration(this))}"
        btnTimer.alpha = if (on) 1f else 0.6f // can be set before switching on
    }

    private fun showTimerDialog() {
        val t = neon()
        val dur = Prefs.timerDuration(this)
        val isPreset = Prefs.TIMER_PRESETS.any { it.second == dur }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        lateinit var dialog: AlertDialog
        Prefs.TIMER_PRESETS.forEach { (label, value) ->
            val selected = if (value == Prefs.CUSTOM) !isPreset else value == dur
            list.addView(TextView(this).apply {
                text = (if (selected) "●  " else "○  ") + label
                textSize = 16f
                setTextColor(if (selected) t.main else t.body)
                if (selected) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = RippleDrawable(ColorStateList.valueOf(t.alpha(0x44)), null, ColorDrawable(Color.WHITE))
                setOnClickListener {
                    dialog.dismiss()
                    if (value == Prefs.CUSTOM) showCustomTimerDialog() else Prefs.setTimerDuration(this@MainActivity, value)
                }
            })
        }
        dialog = neonDialog("Timer length", ScrollView(this).apply { addView(list) }, null, null)
    }

    private fun showCustomTimerDialog() {
        val cur = Prefs.timerDuration(this)
        val h = picker(23, cur / 3600)
        val m = picker(59, (cur % 3600) / 60)
        val s = picker(59, cur % 60)
        neonDialog("Custom timer", pickerRow(h to "h", m to "m", s to "s"), "Set") {
            val total = h.value * 3600 + m.value * 60 + s.value
            if (total <= 0) toast("Pick a time longer than 0") else Prefs.setTimerDuration(this, total)
        }
    }

    private fun refreshWarn() {
        val t = neon()
        val cur = Prefs.warnSeconds(this)
        val isPreset = cur in Prefs.WARN_PRESETS
        warnChips.forEach { (b, sec) ->
            select(b, if (sec == Prefs.CUSTOM) !isPreset else sec == cur, t)
            if (sec == Prefs.CUSTOM) b.text = if (isPreset) "Custom" else Prefs.durLabel(cur)
        }
    }

    private fun showCustomWarnDialog() {
        val cur = Prefs.warnSeconds(this)
        val m = picker(59, cur / 60)
        val s = picker(59, cur % 60)
        neonDialog("Warning lights from", pickerRow(m to "min", s to "s"), "Set") {
            val total = m.value * 60 + s.value
            if (total <= 0) toast("Pick a time longer than 0") else prefs.edit().putInt(Prefs.KEY_WARN_SEC, total).apply()
        }
    }

    // ---------------- 5. Targets ----------------

    private fun refreshTargets() {
        val t = neon()
        val n = Prefs.targetCount(this)
        targetsInfo.text = if (n == 1) "1 target placed." else "$n targets placed, numbered on screen."
        val mode = Prefs.mode(this)
        modeChips.forEach { (b, m) -> select(b, m == mode, t) }
    }

    // ---------------- 6. Profiles ----------------

    private fun refreshProfiles() {
        val t = neon()
        profileList.removeAllViews()
        val list = Profiles.load(this)
        if (list.isEmpty()) {
            profileList.addView(TextView(this).apply {
                text = "No profiles yet."
                textSize = 13f
                setTextColor(t.dim)
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        list.forEach { p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = NeonUi.card(t, density)
                setPadding(dp(12), dp(10), dp(8), dp(10))
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = p.name
                textSize = 15f
                setTextColor(t.main)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            })
            texts.addView(TextView(this).apply {
                text = p.summary()
                textSize = 12f
                setTextColor(t.body)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(chip("Load") { loadProfile(p) }.apply {
                background = NeonUi.filled(t, density, 10f)
                setTextColor(t.onMain)
            }, LinearLayout.LayoutParams(dp(64), dp(38)).apply { marginStart = dp(6) })
            row.addView(chip("✕") { confirmDeleteProfile(p) }.apply {
                background = NeonUi.outline(t, density, 10f)
                setTextColor(t.main)
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(6) })
            profileList.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
    }

    /** Loads the setup; the floating controls start a fresh session (never clicking). */
    private fun loadProfile(p: Profile) {
        Profiles.apply(this, p)
        toast("Loaded “${p.name}”")
    }

    private fun confirmDeleteProfile(p: Profile) {
        val msg = TextView(this).apply {
            text = "Delete “${p.name}”?"
            textSize = 15f
            setTextColor(neon().body)
            setPadding(dp(22), dp(8), dp(22), dp(8))
        }
        neonDialog("Delete profile", msg, "Delete") { Profiles.delete(this, p.name) }
    }

    private fun showSaveProfileDialog() {
        val t = neon()
        val existing = Profiles.load(this)
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "Profile name"
            setText("Profile ${existing.size + 1}")
            selectAll()
            setTextColor(t.main)
            setHintTextColor(t.dim)
            backgroundTintList = ColorStateList.valueOf(t.main)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), 0)
            addView(input)
        }
        neonDialog("Save profile", box, "Save") {
            val name = input.text.toString().trim().ifEmpty { "Profile ${existing.size + 1}" }
            val replacing = existing.any { it.name.equals(name, ignoreCase = true) }
            Profiles.saveOrReplace(this, Profiles.current(this, name))
            if (replacing) toast("Updated “$name”") // a new one simply appears in the list
        }
    }

    // ---------------- 7. Appearance ----------------

    private fun buildSwatches() {
        val row1 = findViewById<LinearLayout>(R.id.swatchRow1)
        val row2 = findViewById<LinearLayout>(R.id.swatchRow2)
        Themes.ALL.forEachIndexed { i, th ->
            val v = View(this)
            v.contentDescription = th.name
            v.setOnClickListener { prefs.edit().putInt(Prefs.KEY_THEME, i).apply() }
            val lp = LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
            (if (i < 5) row1 else row2).addView(v, lp)
            swatches += v
        }
    }

    private fun refreshAppearance() {
        val t = neon()
        val cur = Prefs.themeIndex(this)
        val ring = if (t.isLight) Color.parseColor("#22272C") else Color.WHITE
        swatches.forEachIndexed { i, v ->
            val selected = i == cur
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Themes.ALL[i].neon)
                setStroke(if (selected) hairline(1.5f, density) else 0, ring)
            }
            v.scaleX = if (selected) 1.15f else 1f
            v.scaleY = v.scaleX
        }
        themeName.text = t.name

        val mode = Prefs.appMode(this)
        appModeChips.forEach { (b, m) -> select(b, m == mode, t) }
        val size = Prefs.menuSizeIndex(this)
        menuChips.forEach { (b, i) -> select(b, i == size, t) }
        val clear = Prefs.panelClear(this)
        bgChips.forEach { (b, c) -> select(b, c == clear, t) }
        switchLock.isChecked = Prefs.panelLocked(this)
        refreshTargetSize()
    }

    private fun refreshTargetSize() {
        val d = Prefs.targetDp(this)
        targetSeek.progress = (d - Prefs.TARGET_MIN_DP) / Prefs.TARGET_STEP_DP
        targetSizeValue.text = if (d == Prefs.TARGET_DEFAULT_DP) "$d dp · default" else "$d dp"
        preview.color = Themes.current(this).neon
        preview.ringPx = dp(d)
        val win = TargetView.windowSizeFor(preview.ringPx, density)
        preview.layoutParams = FrameLayout.LayoutParams(win, win, Gravity.CENTER)
    }

    // ---------------- Dialogs and helpers ----------------

    /** A dialog in the current colors: themed box, thin neon outline, themed buttons. */
    private fun neonDialog(title: String, content: View, positive: String?, onPositive: (() -> Unit)?): AlertDialog {
        val t = neon()
        val titleView = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(t.main)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(22), dp(20), dp(22), dp(8))
        }
        val b = AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setView(content)
            .setNegativeButton("Cancel", null)
        if (positive != null) b.setPositiveButton(positive) { _, _ -> onPositive?.invoke() }
        val d = b.create()
        d.setOnShowListener {
            d.window?.setBackgroundDrawable(InsetDrawable(GradientDrawable().apply {
                setColor(t.surface)
                setStroke(hairline(1f, density), t.main)
                cornerRadius = dp(18).toFloat()
            }, dp(16)))
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(t.main)
            d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(t.body)
        }
        d.show()
        return d
    }

    private fun picker(max: Int, start: Int): NumberPicker = NumberPicker(this).apply {
        minValue = 0
        maxValue = max
        value = start.coerceIn(0, max)
        if (Build.VERSION.SDK_INT >= 29) setTextColor(neon().main)
    }

    /** Number pickers side by side, each followed by its unit. */
    private fun pickerRow(vararg parts: Pair<NumberPicker, String>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), 0)
        val c = neon().main
        parts.forEach { (p, unit) ->
            addView(p)
            if (unit.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                text = unit
                textSize = 18f
                setTextColor(c)
                setPadding(dp(4), 0, dp(10), 0)
            })
        }
    }

    private fun onUserSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * density).toInt()
}
