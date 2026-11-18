package com.srizwan.win11

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.app.AlertDialog
import android.os.Build
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================
// WIN11 PALETTE
// =====================================================================

object W11 {
    val TASKBAR = Color.parseColor("#202020")
    val TASKBAR_BORDER = Color.parseColor("#3A3A3A")
    val ACCENT = Color.parseColor("#4CC2FF")
    val ITEM_ACTIVE_BG = Color.parseColor("#26FFFFFF")
    val DOT = Color.parseColor("#9AFFFFFF")
    val TEXT = Color.WHITE
    val TEXT_DIM = Color.parseColor("#B3FFFFFF")
    val START_BG = Color.parseColor("#F2282828")
    val START_BORDER = Color.parseColor("#404040")
    val START_SEARCH_BG = Color.parseColor("#333333")
    val START_FOOTER_BG = Color.parseColor("#33000000")
    val DIVIDER = Color.parseColor("#26FFFFFF")
}

// =====================================================================
// VECTOR ICON VIEW (Canvas + Path only, no drawable resources)
// =====================================================================

class VectorIconView(context: Context, private val type: String) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var path: Path? = null
    private var color: Int = Color.WHITE

    companion object {
        const val START = "start"
        const val FILES = "files"
        const val BROWSER = "browser"
        const val PHONE = "phone"
        const val MESSAGES = "messages"
        const val PC = "pc"
        const val SEARCH = "search"
        const val WIFI = "wifi"
        const val VOLUME = "volume"
        const val BATTERY = "battery"
        const val SEND = "send"
        const val EDIT = "edit"
        const val BACK = "back"
        const val DELETE = "delete"
        const val BELL = "bell"
        const val DOWNLOAD = "download"

        private fun data(type: String): String? = when (type) {
            FILES -> "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z"
            BROWSER -> "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM11,19.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79L9,15v1c0,1.1 0.9,2 2,2v1.93zM17.9,17.39c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1H8v-2h2c0.55,0 1,-0.45 1,-1V7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41 0,2.08 -0.8,3.97 -2.1,5.39z"
            PHONE -> "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"
            MESSAGES -> "M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2z"
            PC -> "M20,18c1.1,0 1.99,-0.9 1.99,-2L22,6c0,-1.1 -0.9,-2 -2,-2H4c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2H0v2h24v-2h-4zM4,6h16v10H4V6z"
            SEARCH -> "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
            WIFI -> "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z"
            VOLUME -> "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
            BATTERY -> "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4z"
            SEND -> "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z"
            EDIT -> "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z"
            BACK -> "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"
            DOWNLOAD -> "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"
            BELL -> "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
            DELETE -> "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"
            else -> null
        }

        fun colorFor(type: String): Int = when (type) {
            FILES -> Color.parseColor("#FFC83D")
            BROWSER -> Color.parseColor("#4CC2FF")
            PHONE -> Color.parseColor("#6CCB5F")
            MESSAGES -> Color.parseColor("#7AB8FF")
            BELL -> Color.parseColor("#FFB900")
            PC -> Color.parseColor("#4CC2FF")
            START -> Color.parseColor("#4CC2FF")
            else -> Color.WHITE
        }
    }

    init {
        color = colorFor(type)
        data(type)?.let { path = PathParser.createPathFromPathData(it) }
    }

    fun setIconColor(c: Int) {
        color = c
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height) / 24f
        canvas.save()
        canvas.translate((width - 24f * s) / 2f, (height - 24f * s) / 2f)
        canvas.scale(s, s)
        fill.color = color
        if (type == START) {
            canvas.drawRoundRect(RectF(3f, 3f, 11.2f, 11.2f), 1.2f, 1.2f, fill)
            canvas.drawRoundRect(RectF(12.8f, 3f, 21f, 11.2f), 1.2f, 1.2f, fill)
            canvas.drawRoundRect(RectF(3f, 12.8f, 11.2f, 21f), 1.2f, 1.2f, fill)
            canvas.drawRoundRect(RectF(12.8f, 12.8f, 21f, 21f), 1.2f, 1.2f, fill)
        } else {
            path?.let { canvas.drawPath(it, fill) }
        }
        canvas.restore()
    }
}

// =====================================================================
// BLOOM WALLPAPER (drawn programmatically)
// =====================================================================

class BloomView(context: Context) : View(context) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint1 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val big = maxOf(w, h).toFloat()
        basePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.parseColor("#03112E"), Color.parseColor("#0A3A8A"), Color.parseColor("#1C7BE0")),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint1.shader = RadialGradient(
            w * 0.72f, h * 0.38f, big * 0.55f,
            intArrayOf(Color.parseColor("#CC6FD3FF"), Color.parseColor("#663E8EF0"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint2.shader = RadialGradient(
            w * 0.25f, h * 0.75f, big * 0.5f,
            intArrayOf(Color.parseColor("#884B4BE0"), Color.parseColor("#332A2AA0"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        petalPaint.shader = LinearGradient(
            0f, -big * 0.2f, 0f, big * 0.2f,
            Color.parseColor("#AA9BE3FF"), Color.parseColor("#223E8EF0"), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, basePaint)
        canvas.drawRect(0f, 0f, w, h, glowPaint1)
        canvas.drawRect(0f, 0f, w, h, glowPaint2)
        val big = maxOf(w, h)
        val angles = floatArrayOf(-30f, 30f, 90f, 150f)
        for (a in angles) {
            canvas.save()
            canvas.translate(w * 0.62f, h * 0.45f)
            canvas.rotate(a)
            canvas.drawOval(RectF(-big * 0.09f, -big * 0.2f, big * 0.09f, big * 0.2f), petalPaint)
            canvas.restore()
        }
    }
}

// =====================================================================
// SHELL: taskbar + desktop
// =====================================================================

private const val PIN_COLUMNS = 6

class Win11Shell(
    private val ctx: Context,
    private val onNavigate: (String) -> Unit
) {

    private class TaskItem(val frame: FrameLayout, val indicator: View)

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val items = LinkedHashMap<String, TaskItem>()
    private val running = mutableSetOf<String>()
    private var active = "desktop"

    // ── Start menu state (Step 4.1) ──────────────────────────────
    private var startOverlay: FrameLayout? = null
    private var startPanel: LinearLayout? = null
    private var startOpen = false

    // ── Pinned apps (Step 4.2) ───────────────────────────────────
    val appRepo = AppRepository(ctx)
    private var pinnedGrid: GridLayout? = null
    private var pinnedEmpty: TextView? = null
    private var cellW = 0

    // ── All apps (Step 4.3) ──────────────────────────────────────
    private var pinnedPage: LinearLayout? = null
    private var allPage: LinearLayout? = null
    private var allScroll: ScrollView? = null
    private var allList: LinearLayout? = null
    private var letterOverlay: FrameLayout? = null
    private var showHiddenApps = false

    // ── Search (Step 4.4) ────────────────────────────────────────
    private var searchBox: EditText? = null
    private var searchPage: ScrollView? = null
    private var searchList: LinearLayout? = null
    private var searchClear: TextView? = null
    private val letterAnchors = LinkedHashMap<String, View>()

    /** Slots that Steps 4.2–4.5 fill in. */
    var startSearchSlot: FrameLayout? = null
        private set
    var startBodySlot: LinearLayout? = null
        private set
    var startFooterSlot: FrameLayout? = null
        private set

    private var clockView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 15000)
        }
    }

    fun startClock() {
        handler.removeCallbacks(tick)
        tick.run()
    }

    fun stopClock() {
        handler.removeCallbacks(tick)
    }

    private fun updateClock() {
        val now = Date()
        val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        val d = SimpleDateFormat("M/d/yyyy", Locale.getDefault()).format(now)
        clockView?.text = "$t\n$d"
    }

    // ── Taskbar ──────────────────────────────────────────────────

    fun buildTaskbar(): LinearLayout {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(W11.TASKBAR)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        outer.addView(View(ctx).apply {
            setBackgroundColor(W11.TASKBAR_BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            )
        }

        // left spacer keeps the icon group truly centered
        row.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.MATCH_PARENT)
        })

        val center = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        center.addView(makeItem("start", VectorIconView.START))
        center.addView(makeItem("files", VectorIconView.FILES))
        center.addView(makeItem("browser", VectorIconView.BROWSER))
        center.addView(makeItem("dialer", VectorIconView.PHONE))
        center.addView(makeItem("sms", VectorIconView.MESSAGES))
        row.addView(center)

        // system tray clock (right)
        clockView = TextView(ctx).apply {
            setTextColor(W11.TEXT)
            textSize = 10.5f
            typeface = Typeface.DEFAULT
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        row.addView(clockView)
        outer.addView(row)

        updateClock()
        setActive(active)
        return outer
    }

    private fun makeItem(key: String, iconType: String): FrameLayout {
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                setMargins(dp(1), 0, dp(1), 0)
            }
        }
        val icon = VectorIconView(ctx, iconType).apply {
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        }
        val indicator = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(6), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(2)
            }
            visibility = View.GONE
        }
        frame.addView(icon)
        frame.addView(indicator)

        frame.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        frame.setOnClickListener { onNavigate(key) }
        items[key] = TaskItem(frame, indicator)
        return frame
    }

    /** Called by MainActivity whenever the visible section changes. */
    fun setActive(section: String) {
        active = section
        if (section != "desktop" && section != "start") running.add(section)
        for ((key, item) in items) {
            val isActive = key == active || (key == "start" && startOpen)
            val isRunning = key in running
            item.frame.background = if (isActive) GradientDrawable().apply {
                cornerRadius = dpf(6f)
                setColor(W11.ITEM_ACTIVE_BG)
            } else null
            val lp = item.indicator.layoutParams as FrameLayout.LayoutParams
            when {
                isActive && key != "start" -> {
                    lp.width = dp(16)
                    item.indicator.background = pill(W11.ACCENT)
                    item.indicator.visibility = View.VISIBLE
                }
                isRunning && key != "start" -> {
                    lp.width = dp(6)
                    item.indicator.background = pill(W11.DOT)
                    item.indicator.visibility = View.VISIBLE
                }
                else -> item.indicator.visibility = View.GONE
            }
            item.indicator.layoutParams = lp
        }
    }

    private fun dpf(v: Float): Float = v * density

    private fun pill(c: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpf(2f)
        setColor(c)
    }

    // ── Start menu (Step 4.1: panel skeleton + open/close) ───────

    fun isStartOpen(): Boolean = startOpen

    fun toggleStart() {
        if (startOpen) closeStart() else openStart()
    }

    fun openStart() {
        val overlay = startOverlay ?: return
        val panel = startPanel ?: return
        if (startOpen) return
        startOpen = true
        overlay.visibility = View.VISIBLE
        panel.animate().cancel()
        panel.alpha = 0f
        panel.translationY = dpf(48f)
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
        setActive(active)
        showPinnedPage()
        appRepo.load { changed -> if (changed) refreshAll() }
    }

    fun closeStart(animated: Boolean = true) {
        val overlay = startOverlay ?: return
        val panel = startPanel ?: return
        if (!startOpen) return
        startOpen = false
        hideKeyboard()
        panel.animate().cancel()
        if (animated) {
            panel.animate()
                .alpha(0f)
                .translationY(dpf(32f))
                .setDuration(150)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { if (!startOpen) overlay.visibility = View.GONE }
                .start()
        } else {
            panel.alpha = 0f
            overlay.visibility = View.GONE
        }
        setActive(active)
    }

    /**
     * Full-size overlay: transparent scrim (tap outside = close) + the Start panel,
     * centered above the taskbar. Add it as the LAST child of the content frame.
     */
    fun buildStartPanel(): FrameLayout {
        val dm = ctx.resources.displayMetrics
        val panelW = minOf(dm.widthPixels - dp(16), dp(560))
        val panelH = minOf(dp(600), (dm.heightPixels * 0.72f).toInt())

        val overlay = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        // scrim: tap outside the panel closes Start
        overlay.addView(View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { closeStart() }
        })

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                panelW, panelH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(8) }
            background = GradientDrawable().apply {
                cornerRadius = dpf(8f)
                setColor(W11.START_BG)
                setStroke(1, W11.START_BORDER)
            }
            elevation = dpf(16f)
            isClickable = true // swallow taps so they don't reach the scrim
        }

        // ── search slot (real search wired in Step 4.4) ──
        val searchSlot = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
            ).apply { setMargins(dp(20), dp(20), dp(20), dp(8)) }
        }
        searchSlot.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                cornerRadius = dpf(20f)
                setColor(W11.START_SEARCH_BG)
                setStroke(1, W11.START_BORDER)
            }
            addView(VectorIconView(ctx, VectorIconView.SEARCH).apply {
                setIconColor(W11.TEXT_DIM)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            })
            val box = EditText(ctx).apply {
                hint = "Type here to search"
                setHintTextColor(W11.TEXT_DIM)
                setTextColor(W11.TEXT)
                textSize = 13f
                setSingleLine(true)
                background = null
                setPadding(dp(10), 0, dp(6), 0)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { onSearchChanged(s?.toString() ?: "") }
                })
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                        runSearchAction(); true
                    } else false
                }
            }
            searchBox = box
            addView(box)
            val clear = TextView(ctx).apply {
                text = "✕"
                setTextColor(W11.TEXT_DIM)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(4), 0)
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnClickListener { box.setText("") }
            }
            searchClear = clear
            addView(clear)
        })
        panel.addView(searchSlot)

        // ── body slot: "Pinned" header now; apps grid (4.2) / All apps (4.3) go below ──
        val bodySlot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val pPage = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        pPage.addView(sectionHeader("Pinned", "All apps  ›") { showAllAppsPage() })
        pPage.addView(buildPinnedGrid(panelW))
        val aPage = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE
        }
        aPage.addView(sectionHeader("All apps", "‹  Back") { showPinnedPage() })
        aPage.addView(buildAllAppsList())
        bodySlot.addView(pPage)
        bodySlot.addView(aPage)
        bodySlot.addView(buildSearchPage())
        pinnedPage = pPage
        allPage = aPage
        panel.addView(bodySlot)

        // ── footer slot (user + power wired in Step 4.5) ──
        panel.addView(View(ctx).apply {
            setBackgroundColor(W11.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        val footerSlot = FrameLayout(ctx).apply {
            setBackgroundColor(W11.START_FOOTER_BG)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)
            )
        }
        buildFooter(footerSlot)
        panel.addView(footerSlot)

        overlay.addView(panel)

        startOverlay = overlay
        startPanel = panel
        startSearchSlot = searchSlot
        startBodySlot = bodySlot
        startFooterSlot = footerSlot
        return overlay
    }

    // ── Footer: user profile + power (Step 4.5) ──────────────────

    private val shellPrefs by lazy { ctx.getSharedPreferences("start_menu", Context.MODE_PRIVATE) }
    private var userNameView: TextView? = null
    private var avatarView: TextView? = null

    private fun userName(): String {
        shellPrefs.getString("user_name", null)?.takeIf { it.isNotBlank() }?.let { return it }
        val dev = try { Settings.Global.getString(ctx.contentResolver, "device_name") } catch (e: Exception) { null }
        return if (!dev.isNullOrBlank()) dev else "User"
    }

    private fun applyUserName() {
        val n = userName()
        userNameView?.text = n
        avatarView?.text = n.trim().take(1).uppercase(Locale.ROOT)
    }

    private fun buildFooter(footer: FrameLayout) {
        val user = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(14), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), Gravity.START or Gravity.CENTER_VERTICAL
            ).apply { leftMargin = dp(16) }
            background = GradientDrawable().apply { cornerRadius = dpf(6f); setColor(Color.TRANSPARENT) }
        }
        val avatar = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(W11.ACCENT) }
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        }
        val name = TextView(ctx).apply {
            setTextColor(W11.TEXT)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(220)
            setPadding(dp(12), 0, 0, 0)
        }
        user.addView(avatar); user.addView(name)
        avatarView = avatar; userNameView = name
        applyUserName()
        user.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> v.setBackgroundColor(W11.ITEM_ACTIVE_BG)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.TRANSPARENT)
            }
            false
        }
        user.setOnClickListener { showUserMenu(it) }
        footer.addView(user)

        val power = object : View(ctx) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = W11.TEXT
                strokeCap = Paint.Cap.ROUND
            }
            override fun onDraw(c: Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                val r = minOf(w, h) * 0.26f
                p.strokeWidth = dpf(1.8f)
                val box = RectF(w / 2 - r, h / 2 - r + dpf(1f), w / 2 + r, h / 2 + r + dpf(1f))
                c.drawArc(box, -60f, 300f, false, p)
                c.drawLine(w / 2, h / 2 - r - dpf(3f), w / 2, h / 2 - dpf(1f), p)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = dp(16) }
            background = GradientDrawable().apply { cornerRadius = dpf(6f); setColor(Color.TRANSPARENT) }
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> v.setBackgroundColor(W11.ITEM_ACTIVE_BG)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.TRANSPARENT)
                }
                false
            }
            setOnClickListener { showPowerMenu(it) }
        }
        footer.addView(power)
    }

    private fun showUserMenu(anchor: View) {
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, "Change name")
        menu.menu.add(0, 2, 1, "Settings")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> askUserName()
                2 -> try {
                    ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    closeStart(animated = false)
                } catch (e: Exception) { }
            }
            true
        }
        menu.show()
    }

    private fun askUserName() {
        val input = EditText(ctx).apply {
            setText(userName()); setSelection(text.length); setSingleLine(true)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Your name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                shellPrefs.edit().putString("user_name", input.text.toString().trim()).apply()
                applyUserName()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Android only lets a launcher lock / show the power dialog through an
    // accessibility service, so the user must enable "Win11 Power and Lock" once.
    private fun showPowerMenu(anchor: View) {
        val menu = PopupMenu(ctx, anchor, Gravity.END)
        menu.menu.add(0, 1, 0, "Lock")
        menu.menu.add(0, 2, 1, "Restart")
        menu.menu.add(0, 3, 2, "Shut down")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> doPower(lock = true)
                2, 3 -> doPower(lock = false)
            }
            true
        }
        menu.show()
    }

    private fun doPower(lock: Boolean) {
        if (!PowerService.isEnabled) { askEnablePowerService(); return }
        closeStart(animated = false)
        val ok = if (lock) PowerService.lock() else PowerService.powerDialog()
        if (!ok) {
            val msg = when {
                lock && Build.VERSION.SDK_INT < 28 -> "Lock needs Android 9 or newer"
                !lock && Build.VERSION.SDK_INT < 31 -> "Hold the physical power button to restart or shut down"
                else -> "Action failed"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun askEnablePowerService() {
        AlertDialog.Builder(ctx)
            .setTitle("Enable power controls")
            .setMessage("Android only allows Lock / Restart / Shut down through an accessibility service. " +
                "Turn on \"Win11 Power and Lock\" in Accessibility settings. It does not read your screen.")
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    closeStart(animated = false)
                } catch (e: Exception) { }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Search (Step 4.4): live filter + web fallback ────────────

    private fun buildSearchPage(): View {
        val sv = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
        }
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        sv.addView(list)
        searchPage = sv
        searchList = list
        return sv
    }

    private var currentQuery = ""
    private var searchResults: List<AppEntry> = emptyList()

    private fun clearSearch() {
        searchBox?.let { if (it.text.isNotEmpty()) it.setText("") }
    }

    private fun hideKeyboard() {
        val b = searchBox ?: return
        try {
            b.clearFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(b.windowToken, 0)
        } catch (e: Exception) { }
    }

    private fun onSearchChanged(q: String) {
        val query = q.trim()
        currentQuery = query
        searchClear?.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        val sp = searchPage ?: return
        if (query.isEmpty()) {
            sp.visibility = View.GONE
            if (allPage?.visibility != View.VISIBLE) pinnedPage?.visibility = View.VISIBLE
            else allPage?.visibility = View.VISIBLE
            return
        }
        pinnedPage?.visibility = View.GONE
        allPage?.visibility = View.GONE
        letterOverlay?.visibility = View.GONE
        sp.visibility = View.VISIBLE
        renderSearch(query)
        sp.scrollTo(0, 0)
    }

    private fun renderSearch(query: String) {
        val list = searchList ?: return
        list.removeAllViews()
        val ql = query.lowercase(Locale.ROOT)
        // hidden apps are never shown in search
        val hits = appRepo.visibleApps().filter { it.label.lowercase(Locale.ROOT).contains(ql) }
            .sortedWith(compareBy<AppEntry>(
                { val l = it.label.lowercase(Locale.ROOT); if (l.startsWith(ql)) 0 else if (l.split(" ").any { w -> w.startsWith(ql) }) 1 else 2 },
                { it.label.lowercase(Locale.ROOT) }
            ))
        searchResults = hits

        list.addView(TextView(ctx).apply {
            text = if (hits.isEmpty()) "No apps found" else "Apps"
            setTextColor(if (hits.isEmpty()) W11.TEXT_DIM else W11.TEXT)
            textSize = 14f
            typeface = if (hits.isEmpty()) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32))
        })
        for (e in hits) list.addView(appRow(e, hidden = false).apply {
            setOnClickListener { launchApp(e) }
        })

        // Web search fallback row (always last; the only row when nothing matches)
        list.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(8) }
            background = GradientDrawable().apply {
                cornerRadius = dpf(4f)
                setColor(W11.START_SEARCH_BG)
            }
            addView(VectorIconView(ctx, VectorIconView.SEARCH).apply {
                setIconColor(W11.ACCENT)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
            addView(TextView(ctx).apply {
                text = "Search the web for \"$query\""
                setTextColor(W11.TEXT)
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), 0, 0, 0)
            })
            setOnClickListener { webSearch(query) }
        })
    }

    /** IME "search" key: open the top app match, otherwise search the web. */
    private fun runSearchAction() {
        val q = currentQuery
        if (q.isEmpty()) return
        val top = searchResults.firstOrNull()
        if (top != null) launchApp(top) else webSearch(q)
    }

    private fun webSearch(q: String) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                Toast.makeText(ctx, "No app can open the web search", Toast.LENGTH_SHORT).show()
                return
            }
        }
        closeStart(animated = false)
    }

    // ── Pages: Pinned <-> All apps (Step 4.3) ────────────────────

    private fun sectionHeader(title: String, action: String, onAction: () -> Unit): FrameLayout =
        FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32))
            addView(TextView(ctx).apply {
                text = title
                setTextColor(W11.TEXT)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.START
                )
            })
            addView(TextView(ctx).apply {
                text = action
                setTextColor(W11.TEXT)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dpf(4f)
                    setColor(W11.START_SEARCH_BG)
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(28),
                    Gravity.END or Gravity.CENTER_VERTICAL
                )
                setOnClickListener { onAction() }
            })
        }

    fun showAllAppsPage() {
        clearSearch(); searchPage?.visibility = View.GONE
        letterOverlay?.visibility = View.GONE
        pinnedPage?.visibility = View.GONE
        allPage?.visibility = View.VISIBLE
        refreshAllApps()
        allScroll?.scrollTo(0, 0)
    }

    fun showPinnedPage() {
        clearSearch(); searchPage?.visibility = View.GONE
        letterOverlay?.visibility = View.GONE
        allPage?.visibility = View.GONE
        pinnedPage?.visibility = View.VISIBLE
        refreshPinnedGrid()
    }

    /** Hardware/gesture Back: returns true if it was consumed inside the Start menu. */
    fun handleBack(): Boolean {
        if (!startOpen) return false
        if (searchPage?.visibility == View.VISIBLE) {
            searchBox?.setText("")
            return true
        }
        if (letterOverlay?.visibility == View.VISIBLE) {
            letterOverlay?.visibility = View.GONE
            return true
        }
        if (allPage?.visibility == View.VISIBLE) {
            showPinnedPage()
            return true
        }
        return false
    }

    private fun refreshAll() {
        refreshPinnedGrid()
        if (searchPage?.visibility == View.VISIBLE) renderSearch(currentQuery)
        if (allPage?.visibility == View.VISIBLE) refreshAllApps()
    }

    // ── All apps list (Step 4.3): A–Z with letter headers ────────

    private fun letterOf(e: AppEntry): String {
        val c = e.label.trim().codePointAtOrNull() ?: return "#"
        return if (Character.isLetter(c)) String(Character.toChars(c)).uppercase(Locale.ROOT) else "#"
    }

    private fun String.codePointAtOrNull(): Int? = if (isEmpty()) null else codePointAt(0)

    private fun buildAllAppsList(): View {
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val scroll = ScrollView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(list)
        frame.addView(scroll)

        val overlay = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(W11.START_BG)
            isClickable = true
            visibility = View.GONE
        }
        frame.addView(overlay)

        allScroll = scroll
        allList = list
        letterOverlay = overlay
        return frame
    }

    fun refreshAllApps() {
        val list = allList ?: return
        val keepY = allScroll?.scrollY ?: 0
        list.removeAllViews()
        letterAnchors.clear()

        val visible = appRepo.visibleApps()
        if (visible.isEmpty() && appRepo.hiddenApps().isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = if (appRepo.apps.isEmpty()) "Loading apps…" else "No apps found."
                setTextColor(W11.TEXT_DIM)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }

        val sorted = visible.sortedWith(
            compareBy<AppEntry>({ if (letterOf(it) == "#") "" else letterOf(it) },
                { it.label.lowercase(Locale.ROOT) })
        )
        var current = ""
        for (e in sorted) {
            val l = letterOf(e)
            if (l != current) {
                current = l
                val h = letterHeader(l)
                letterAnchors[l] = h
                list.addView(h)
            }
            list.addView(appRow(e, hidden = false))
        }

        // Hidden apps section (collapsed by default)
        val hidden = appRepo.hiddenApps()
        if (hidden.isNotEmpty()) {
            list.addView(TextView(ctx).apply {
                text = (if (showHiddenApps) "▾  " else "▸  ") + "Hidden apps (${hidden.size})"
                setTextColor(W11.TEXT_DIM)
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
                ).apply { topMargin = dp(8) }
                setOnClickListener { showHiddenApps = !showHiddenApps; refreshAllApps() }
            })
            if (showHiddenApps) for (e in hidden) list.addView(appRow(e, hidden = true))
        }
        list.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(12))
        })
        allScroll?.post { allScroll?.scrollTo(0, keepY) }
    }

    private fun letterHeader(letter: String): TextView = TextView(ctx).apply {
        text = letter
        setTextColor(W11.ACCENT)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
        setOnClickListener { showLetterGrid() }
    }

    private fun showLetterGrid() {
        val overlay = letterOverlay ?: return
        overlay.removeAllViews()
        val present = letterAnchors.keys.toList()
        val all = LinkedHashSet<String>().apply {
            add("#"); for (c in 'A'..'Z') add(c.toString()); addAll(present)
        }
        val cols = 6
        val cell = (cellW).coerceAtLeast(dp(48))
        val grid = GridLayout(ctx).apply {
            columnCount = cols
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        for (l in all) {
            val on = l in present
            grid.addView(TextView(ctx).apply {
                text = l
                gravity = Gravity.CENTER
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (on) W11.ACCENT else Color.parseColor("#55FFFFFF"))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cell; height = dp(48)
                }
                if (on) setOnClickListener {
                    overlay.visibility = View.GONE
                    val target = letterAnchors[l]
                    if (target != null) allScroll?.post { allScroll?.smoothScrollTo(0, target.top) }
                }
            })
        }
        val scroll = ScrollView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(ctx).apply {
                setPadding(0, dp(12), 0, dp(12))
                addView(grid.apply {
                    (layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
                })
            })
        }
        overlay.addView(scroll)
        overlay.visibility = View.VISIBLE
    }

    private fun appRow(e: AppEntry, hidden: Boolean): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        alpha = if (hidden) 0.55f else 1f
        addView(ImageView(ctx).apply {
            setImageDrawable(e.icon)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        addView(TextView(ctx).apply {
            text = e.label
            setTextColor(W11.TEXT)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (!hidden && appRepo.isPinned(e)) {
            addView(TextView(ctx).apply {
                text = "pinned"
                setTextColor(W11.TEXT_DIM)
                textSize = 10f
            })
        }
        setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> v.setBackgroundColor(W11.ITEM_ACTIVE_BG)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.TRANSPARENT)
            }
            false
        }
        setOnClickListener { launchApp(e) }
        setOnLongClickListener { showAppMenu(this, e); true }
    }

    /** Long-press menu in All apps: pin/unpin, hide/unhide, app info. */
    private fun showAppMenu(anchor: View, e: AppEntry) {
        val menu = PopupMenu(ctx, anchor)
        val hidden = appRepo.isHidden(e)
        if (hidden) {
            menu.menu.add(0, 1, 0, "Unhide app")
        } else {
            menu.menu.add(0, 2, 0, if (appRepo.isPinned(e)) "Unpin from Start" else "Pin to Start")
            menu.menu.add(0, 3, 1, "Hide app")
        }
        if (!e.isBuiltIn) menu.menu.add(0, 4, 2, "App info")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { appRepo.unhide(e); refreshAllApps() }
                2 -> { appRepo.togglePin(e); refreshAllApps() }
                3 -> {
                    appRepo.hide(e); refreshAllApps()
                    Toast.makeText(ctx, "${e.label} hidden. Find it under Hidden apps.", Toast.LENGTH_SHORT).show()
                }
                4 -> openAppInfo(e)
            }
            true
        }
        menu.show()
    }

    private fun openAppInfo(e: AppEntry) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            closeStart(animated = false)
        } catch (ex: Exception) { }
    }

    // ── Pinned apps grid (Step 4.2) ──────────────────────────────

    private fun buildPinnedGrid(panelW: Int): View {
        cellW = (panelW - dp(40)) / PIN_COLUMNS
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val grid = GridLayout(ctx).apply { columnCount = PIN_COLUMNS }
        val empty = TextView(ctx).apply {
            text = "No pinned apps.\nUse All apps to pin them here."
            setTextColor(W11.TEXT_DIM)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, 0)
            visibility = View.GONE
        }
        holder.addView(grid)
        holder.addView(empty)
        scroll.addView(holder)
        pinnedGrid = grid
        pinnedEmpty = empty

        refreshPinnedGrid()
        appRepo.load { changed -> if (changed) refreshAll() }
        return scroll
    }

    fun refreshPinnedGrid() {
        val grid = pinnedGrid ?: return
        grid.removeAllViews()
        val pins = appRepo.pinned()
        pinnedEmpty?.visibility =
            if (pins.isEmpty() && appRepo.apps.isNotEmpty()) View.VISIBLE else View.GONE
        for (e in pins) grid.addView(pinnedCell(e))
    }

    private fun pinnedCell(e: AppEntry): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), dp(4))
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellW
                height = dp(80)
            }
            addView(ImageView(ctx).apply {
                setImageDrawable(e.icon)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            })
            addView(TextView(ctx).apply {
                text = e.label
                setTextColor(W11.TEXT)
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> v.setBackgroundColor(W11.ITEM_ACTIVE_BG)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.setBackgroundColor(Color.TRANSPARENT)
                }
                false
            }
            setOnClickListener { launchApp(e) }
            setOnLongClickListener { showPinMenu(this, e); true }
        }
    }

    fun launchApp(e: AppEntry) {
        // Built-in apps (e.g. Browser) open inside Win11 itself
        e.section?.let { sec ->
            closeStart(animated = false)
            onNavigate(sec)
            return
        }
        try {
            ctx.startActivity(e.launchIntent())
            closeStart(animated = false)
        } catch (ex: Exception) {
            Toast.makeText(ctx, "Can't open ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPinMenu(anchor: View, e: AppEntry) {
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, "Unpin from Start")
        menu.menu.add(0, 2, 1, "Move to front")
        menu.menu.add(0, 5, 2, "Hide app")
        if (!e.isBuiltIn) menu.menu.add(0, 3, 3, "App info")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { appRepo.unpin(e); refreshPinnedGrid() }
                2 -> { appRepo.moveToFront(e); refreshPinnedGrid() }
                3 -> openAppInfo(e)
                5 -> {
                    appRepo.hide(e); refreshPinnedGrid()
                    Toast.makeText(ctx, "${e.label} hidden. Find it in All apps > Hidden apps.", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        menu.show()
    }

    // ── Desktop ──────────────────────────────────────────────────

    fun buildDesktop(): FrameLayout {
        val desktop = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        desktop.addView(BloomView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        column.addView(desktopIcon(VectorIconView.PC, "This PC", "files"))
        column.addView(desktopIcon(VectorIconView.BROWSER, "Browser", "browser"))
        column.addView(desktopIcon(VectorIconView.PHONE, "Phone", "dialer"))
        column.addView(desktopIcon(VectorIconView.MESSAGES, "Messages", "sms"))
        desktop.addView(column)
        return desktop
    }

    private fun desktopIcon(iconType: String, label: String, section: String): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(82))
            addView(VectorIconView(ctx, iconType).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            })
            addView(TextView(ctx).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 2
                setShadowLayer(4f, 0f, 1f, Color.parseColor("#99000000"))
                setPadding(0, dp(4), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> v.setBackgroundColor(Color.parseColor("#33FFFFFF"))
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.TRANSPARENT)
                }
                false
            }
            setOnClickListener { onNavigate(section) }
        }
    }
}
