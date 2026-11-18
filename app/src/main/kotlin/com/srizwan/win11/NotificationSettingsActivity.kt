package com.srizwan.win11

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Notification settings (Win11 style, programmatic UI, vector icons only):
 * choose which chat apps may show alerts, plus a master switch and a sound switch.
 * Alerts are shown by WhatsAppNotificationListener even when the launcher is in the background.
 */
class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var statusBtn: TextView
    private val appRows = mutableListOf<Pair<View, SwitchCompat>>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { supportActionBar?.hide() } catch (e: Exception) { }
        try {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.parseColor("#202020")
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.parseColor("#202020")
        } catch (e: Exception) { }
        setContentView(buildScreen())
        try {
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // =====================================================================
    // UI
    // =====================================================================

    private fun buildScreen(): View {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#202020"))
        root.layoutParams = ViewGroup.LayoutParams(mp, mp)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        // ── Title bar: back + bell + title ──
        val titleBar = LinearLayout(this)
        titleBar.orientation = LinearLayout.HORIZONTAL
        titleBar.gravity = Gravity.CENTER_VERTICAL
        titleBar.setPadding(dp(6), 0, dp(14), 0)
        titleBar.layoutParams = LinearLayout.LayoutParams(mp, dp(52))
        titleBar.addView(iconButton(VectorIconView.BACK, 40, Color.WHITE) { finish() })
        val bell = VectorIconView(this, VectorIconView.BELL)
        bell.layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(6) }
        titleBar.addView(bell)
        val title = TextView(this)
        title.text = "Notifications"
        title.setTextColor(W11.TEXT)
        title.textSize = 17f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setPadding(dp(10), 0, 0, 0)
        titleBar.addView(title)
        root.addView(titleBar)

        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(mp, 0, 1f)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(4), dp(16), dp(24))

        // ── Access status card ──
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = round(Color.parseColor("#2B2B2B"), 8)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { bottomMargin = dp(16) }
        val cardTitle = TextView(this)
        cardTitle.text = "Notification access"
        cardTitle.setTextColor(W11.TEXT)
        cardTitle.textSize = 14f
        cardTitle.typeface = Typeface.DEFAULT_BOLD
        card.addView(cardTitle)
        statusText = TextView(this)
        statusText.setTextColor(W11.TEXT_DIM)
        statusText.textSize = 12f
        statusText.setPadding(0, dp(4), 0, 0)
        card.addView(statusText)
        statusBtn = TextView(this)
        statusBtn.text = "Open settings"
        statusBtn.setTextColor(Color.BLACK)
        statusBtn.textSize = 13f
        statusBtn.typeface = Typeface.DEFAULT_BOLD
        statusBtn.gravity = Gravity.CENTER
        statusBtn.background = round(W11.ACCENT, 6)
        statusBtn.setPadding(dp(16), dp(8), dp(16), dp(8))
        statusBtn.layoutParams = LinearLayout.LayoutParams(wc, wc).apply { topMargin = dp(10) }
        statusBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Can't open settings", Toast.LENGTH_SHORT).show()
            }
        }
        card.addView(statusBtn)
        col.addView(card)

        // ── Master switch ──
        col.addView(sectionLabel("General"))
        col.addView(
            toggleRow(
                VectorIconView.BELL, W11.ACCENT, "Show message notifications",
                "Turn everything on or off at once",
                NotifPrefs.isMasterOn(this)
            ) { on ->
                NotifPrefs.setMaster(this, on)
                applyMasterState(on)
            }.first
        )

        // ── Per-app switches ──
        col.addView(sectionLabel("Apps"))
        val master = NotifPrefs.isMasterOn(this)
        for (app in NotifPrefs.apps) {
            val row = toggleRow(
                VectorIconView.MESSAGES, app.color, app.label,
                app.packages.first(),
                NotifPrefs.isAppOn(this, app.id)
            ) { on -> NotifPrefs.setAppOn(this, app.id, on) }
            appRows.add(row)
            col.addView(row.first)
        }
        applyMasterState(master)

        // ── Sound ──
        col.addView(sectionLabel("Sound"))
        col.addView(
            toggleRow(
                VectorIconView.BELL, Color.parseColor("#FFB900"), "Play notification sound",
                "System notification sound with each alert",
                NotifPrefs.isSoundOn(this)
            ) { on -> NotifPrefs.setSound(this, on) }.first
        )

        // ── Test ──
        val test = TextView(this)
        test.text = "Send test notification"
        test.setTextColor(Color.BLACK)
        test.textSize = 14f
        test.typeface = Typeface.DEFAULT_BOLD
        test.gravity = Gravity.CENTER
        test.background = round(W11.ACCENT, 8)
        test.setPadding(dp(16), dp(12), dp(16), dp(12))
        test.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(20) }
        test.setOnClickListener {
            NotifPrefs.alert(this, "Test", "This is a test message", "Messages")
        }
        col.addView(test)

        val note = TextView(this)
        note.text = "Alerts also appear while the launcher is paused, stopped or in the background."
        note.setTextColor(W11.TEXT_DIM)
        note.textSize = 11f
        note.setPadding(dp(4), dp(10), dp(4), 0)
        col.addView(note)

        scroll.addView(col)
        root.addView(scroll)
        return root
    }

    private fun sectionLabel(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(W11.TEXT_DIM)
        tv.textSize = 12f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setPadding(dp(4), dp(10), 0, dp(8))
        return tv
    }

    private fun iconButton(type: String, sizeDp: Int, color: Int, onClick: () -> Unit): FrameLayout {
        val box = FrameLayout(this)
        box.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        val ic = VectorIconView(this, type)
        ic.setIconColor(color)
        val isz = (sizeDp * 0.5f).toInt()
        ic.layoutParams = FrameLayout.LayoutParams(dp(isz), dp(isz), Gravity.CENTER)
        box.addView(ic)
        box.isClickable = true
        box.setOnClickListener { onClick() }
        return box
    }

    /** One card row: vector icon, title, subtitle, Win11-style switch. */
    private fun toggleRow(
        iconType: String, iconColor: Int, title: String, subtitle: String,
        checked: Boolean, onChange: (Boolean) -> Unit
    ): Pair<View, SwitchCompat> {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = round(Color.parseColor("#2B2B2B"), 8)
        row.setPadding(dp(14), dp(12), dp(10), dp(12))
        row.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { bottomMargin = dp(6) }

        val ic = VectorIconView(this, iconType)
        ic.setIconColor(iconColor)
        ic.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(14) }
        row.addView(ic)

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
        val t = TextView(this)
        t.text = title
        t.setTextColor(W11.TEXT)
        t.textSize = 14f
        texts.addView(t)
        val st = TextView(this)
        st.text = subtitle
        st.setTextColor(W11.TEXT_DIM)
        st.textSize = 11f
        texts.addView(st)
        row.addView(texts)

        val sw = SwitchCompat(this)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Color.BLACK, Color.parseColor("#CCCCCC")))
        sw.trackTintList = ColorStateList(states, intArrayOf(W11.ACCENT, Color.parseColor("#555555")))
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        row.addView(sw)
        return Pair(row, sw)
    }

    /** Dims and disables the per-app rows while the master switch is off. */
    private fun applyMasterState(masterOn: Boolean) {
        for ((row, sw) in appRows) {
            row.alpha = if (masterOn) 1f else 0.45f
            sw.isEnabled = masterOn
        }
    }

    private fun refreshStatus() {
        val on = WhatsAppNotificationListener.isEnabled(this)
        statusText.text =
            if (on) "Access is on. Alerts work even when the launcher is in the background."
            else "Access is off. Turn it on to receive alerts from your chat apps."
        statusBtn.visibility = if (on) View.GONE else View.VISIBLE
    }
}
