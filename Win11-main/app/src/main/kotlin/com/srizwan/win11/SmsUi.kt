package com.srizwan.win11

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Messages app (Windows 11 / Fluent look).
 *
 * Performance notes (this replaces the old main-thread implementation):
 *  - every SMS / contact query runs on a single background thread, results are posted back
 *  - contact names are cached (one lookup per number, not one per redraw)
 *  - lists are RecyclerViews, so only the visible rows exist as views
 *  - search is filtered in memory instantly; the "inside message text" search runs in background
 *  - a ContentObserver refreshes the list while the screen is open
 */
class SmsUi(
    private val act: Activity,
    private val onCall: (String) -> Unit,
    private val onNeedPermission: () -> Unit
) {

    // ── Palette ──────────────────────────────────────────────────
    private companion object {
        val BG_TITLE = Color.parseColor("#202020")
        val BG_LIST = Color.parseColor("#272727")
        val BG_THREAD = Color.parseColor("#1C1C1C")
        val BG_BAR = Color.parseColor("#242424")
        val FIELD_BG = Color.parseColor("#2D2D2D")
        val FIELD_STROKE = Color.parseColor("#3A3A3A")
        val BUBBLE_IN = Color.parseColor("#2E2E2E")
        val BUBBLE_IN_STROKE = Color.parseColor("#3C3C3C")
        val BUBBLE_OUT = Color.parseColor("#0A64B4")
        val DAY_CHIP = Color.parseColor("#2B2B2B")
        val DANGER = Color.parseColor("#FF99A4")
        val GREEN = Color.parseColor("#6CCB5F")
        val SMS_URI: Uri = Uri.parse("content://sms/")

        const val MAX_CONVS = 250
        const val MAX_MSGS = 400
        const val TYPE_SENT = 2
        const val TYPE_FAILED = 5
        const val TYPE_QUEUED = 4
        const val ROW_DAY = 0
        const val ROW_MSG = 1
    }

    // ── Data ─────────────────────────────────────────────────────
    private data class Conv(
        val threadId: Long,
        val address: String,
        val name: String,
        val snippet: String,
        val date: Long,
        val type: Int,
        val unread: Int
    ) {
        val key: String get() = if (threadId > 0) "t$threadId" else "a${normAddr(address)}"
    }

    private class Msg(val body: String, val date: Long, val type: Int)

    private class Row(val kind: Int, val label: String = "", val msg: Msg? = null)

    private class ListResult(val convs: List<Conv>, val error: String?)

    // ── Threading ────────────────────────────────────────────────
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var listGen = 0
    private var threadGen = 0
    private var bodyGen = 0
    private var destroyed = false

    // ── State ────────────────────────────────────────────────────
    private val wide: Boolean = act.resources.configuration.screenWidthDp >= 560
    private val density = act.resources.displayMetrics.density
    private var allConvs: List<Conv> = emptyList()
    private var bodyHits: Set<String> = emptySet()
    private var query = ""
    private var current: Conv? = null
    private var msgs: List<Msg> = emptyList()
    private val localSent = HashMap<String, MutableList<Msg>>() // sent from here, may not be in provider
    private val nameCache = HashMap<String, String>() // io thread only
    private var nameCacheStamp = 0L
    private var observerRegistered = false
    private var shown = false

    // ── Views ────────────────────────────────────────────────────
    val root: LinearLayout
    private val listPane: LinearLayout
    private val threadPane: LinearLayout
    private val placeholder: LinearLayout
    private val threadContent: LinearLayout
    private val convRecycler: RecyclerView
    private val stateView: LinearLayout
    private val stateIcon: VectorIconView
    private val stateTitle: TextView
    private val stateSub: TextView
    private val stateButton: TextView
    private val searchEdit: EditText
    private val convAdapter = ConvAdapter()

    private val threadRecycler: RecyclerView
    private val threadAdapter = ThreadAdapter()
    private val headerAvatar: Avatar
    private val headerName: TextView
    private val headerSub: TextView
    private val replyEdit: EditText
    private val sendBtn: FrameLayout

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            ui.removeCallbacks(refreshRunnable)
            ui.postDelayed(refreshRunnable, 500)
        }
    }
    private val refreshRunnable = Runnable { if (shown && !destroyed) refresh() }
    private val searchRunnable = Runnable { applyQuery() }

    // =================================================================
    // Small helpers
    // =================================================================

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    private fun dpf(v: Float): Float = v * density

    private fun round(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radiusDp.toFloat())
            if (stroke != null) setStroke(dp(1).coerceAtLeast(1), stroke)
        }

    private fun ripple(content: android.graphics.drawable.Drawable?, radiusDp: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpf(radiusDp.toFloat())
        }
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#26FFFFFF")), content, mask)
    }

    private fun toast(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(act, p) == PackageManager.PERMISSION_GRANTED

    private fun hasRead() = hasPerm(Manifest.permission.READ_SMS)

    private fun iconButton(
        type: String, sizeDp: Int, iconColor: Int, bg: Int?, radiusDp: Int = 6,
        onClick: () -> Unit
    ): FrameLayout {
        val box = FrameLayout(act)
        box.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        box.background = ripple(if (bg != null) round(bg, radiusDp) else null, radiusDp)
        val ic = VectorIconView(act, type)
        ic.setIconColor(iconColor)
        val isz = (sizeDp * 0.5f).toInt()
        ic.layoutParams = FrameLayout.LayoutParams(dp(isz), dp(isz), Gravity.CENTER)
        box.addView(ic)
        box.isClickable = true
        box.setOnClickListener { onClick() }
        return box
    }

    /** Round avatar: first letter of the name, or a person glyph for plain numbers. */
    private inner class Avatar(sizeDp: Int) {
        private val palette = intArrayOf(
            0xFF0078D4.toInt(), 0xFF107C10.toInt(), 0xFF8764B8.toInt(),
            0xFFCA5010.toInt(), 0xFF00B7C3.toInt(), 0xFFC239B3.toInt()
        )
        val view = FrameLayout(act)
        private val letter = TextView(act)
        private val icon = VectorIconView(act, VectorIconView.PERSON)

        init {
            view.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            letter.setTextColor(Color.WHITE)
            letter.textSize = sizeDp * 0.4f
            letter.typeface = Typeface.DEFAULT_BOLD
            letter.gravity = Gravity.CENTER
            letter.includeFontPadding = false
            letter.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            val isz = (sizeDp * 0.55f).toInt()
            icon.setIconColor(Color.WHITE)
            icon.layoutParams = FrameLayout.LayoutParams(dp(isz), dp(isz), Gravity.CENTER)
            view.addView(letter)
            view.addView(icon)
        }

        fun bind(name: String) {
            val color = palette[(name.hashCode() and 0x7fffffff) % palette.size]
            view.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            val ch = name.trim().firstOrNull()
            if (ch != null && ch.isLetter()) {
                letter.text = ch.toString().uppercase()
                letter.visibility = View.VISIBLE
                icon.visibility = View.GONE
            } else {
                letter.visibility = View.GONE
                icon.visibility = View.VISIBLE
            }
        }
    }

    /** Linear layout whose width is capped to a fraction of what the parent offers (chat bubble). */
    private class MaxWidthLayout(ctx: Context, private val fraction: Float) : LinearLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(widthMeasureSpec)
            val size = MeasureSpec.getSize(widthMeasureSpec)
            val cap = (size * fraction).toInt()
            val spec = if (mode != MeasureSpec.UNSPECIFIED && cap > 0)
                MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
            else widthMeasureSpec
            super.onMeasure(spec, heightMeasureSpec)
        }
    }

    // =================================================================
    // Build UI
    // =================================================================

    init {
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_TITLE)
            layoutParams = ViewGroup.LayoutParams(mp, mp)
        }

        // ── Window title bar ──
        val titleBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(mp, dp(40))
        }
        titleBar.addView(VectorIconView(act, VectorIconView.MESSAGES).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        titleBar.addView(TextView(act).apply {
            text = "Messages"
            setTextColor(W11.TEXT)
            textSize = 12f
            setPadding(dp(10), 0, 0, 0)
        })
        root.addView(titleBar)

        val body = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(mp, 0, 1f)
        }

        // ── LEFT: search + compose + conversation list ──
        listPane = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_LIST)
            layoutParams = if (wide) LinearLayout.LayoutParams(dp(330), mp)
            else LinearLayout.LayoutParams(0, mp, 1f)
        }

        val toolbar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(mp, wc)
        }
        val searchBox = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(FIELD_BG, 6, FIELD_STROKE)
            setPadding(dp(10), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        }
        searchBox.addView(VectorIconView(act, VectorIconView.SEARCH).apply {
            setIconColor(W11.TEXT_DIM)
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
        })
        searchEdit = EditText(act).apply {
            hint = "Search messages"
            textSize = 13f
            maxLines = 1
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    ui.removeCallbacks(searchRunnable)
                    ui.postDelayed(searchRunnable, 200)
                }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        searchBox.addView(searchEdit)
        searchBox.setOnClickListener { searchEdit.requestFocus() }
        searchEdit.setOnFocusChangeListener { _, focus ->
            searchBox.background = round(FIELD_BG, 6, if (focus) W11.ACCENT else FIELD_STROKE)
        }
        toolbar.addView(searchBox)
        val composeBtn = iconButton(VectorIconView.EDIT, 38, Color.BLACK, W11.ACCENT) {
            showComposeDialog(null)
        }
        (composeBtn.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        toolbar.addView(composeBtn)
        listPane.addView(toolbar)

        val listFrame = FrameLayout(act).apply {
            layoutParams = LinearLayout.LayoutParams(mp, 0, 1f)
        }
        convRecycler = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act)
            adapter = convAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 0, 0, dp(6))
            layoutParams = FrameLayout.LayoutParams(mp, mp)
        }
        listFrame.addView(convRecycler)

        stateView = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(mp, mp)
        }
        stateIcon = VectorIconView(act, VectorIconView.MESSAGES).apply {
            setIconColor(Color.parseColor("#554CC2FF"))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        stateView.addView(stateIcon)
        stateTitle = TextView(act).apply {
            setTextColor(W11.TEXT)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        stateView.addView(stateTitle)
        stateSub = TextView(act).apply {
            setTextColor(W11.TEXT_DIM)
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        stateView.addView(stateSub)
        stateButton = TextView(act).apply {
            setTextColor(Color.BLACK)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(9), dp(20), dp(9))
            background = ripple(round(W11.ACCENT, 6), 6)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(wc, wc).apply { topMargin = dp(16) }
        }
        stateView.addView(stateButton)
        listFrame.addView(stateView)
        listPane.addView(listFrame)
        body.addView(listPane)

        if (wide) {
            body.addView(View(act).apply {
                setBackgroundColor(W11.DIVIDER)
                layoutParams = LinearLayout.LayoutParams(dp(1), mp)
            })
        }

        // ── RIGHT: placeholder (wide) or the open conversation ──
        threadPane = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_THREAD)
            layoutParams = LinearLayout.LayoutParams(0, mp, 1f)
            visibility = if (wide) View.VISIBLE else View.GONE
        }
        placeholder = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(mp, 0, 1f)
            visibility = if (wide) View.VISIBLE else View.GONE
        }
        placeholder.addView(VectorIconView(act, VectorIconView.MESSAGES).apply {
            setIconColor(Color.parseColor("#554CC2FF"))
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        })
        placeholder.addView(TextView(act).apply {
            text = "Select a conversation"
            setTextColor(W11.TEXT_DIM)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        threadPane.addView(placeholder)

        threadContent = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(mp, 0, 1f)
            visibility = View.GONE
        }

        // header
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG_BAR)
            setPadding(if (wide) dp(16) else dp(6), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(mp, wc)
        }
        if (!wide) {
            header.addView(iconButton(VectorIconView.BACK, 40, Color.WHITE, null) { closeThread() })
        }
        headerAvatar = Avatar(38)
        (headerAvatar.view.layoutParams as LinearLayout.LayoutParams).setMargins(dp(4), 0, dp(12), 0)
        header.addView(headerAvatar.view)
        val nameBlock = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
        }
        headerName = TextView(act).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerSub = TextView(act).apply {
            textSize = 11.5f
            setTextColor(W11.TEXT_DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nameBlock.addView(headerName)
        nameBlock.addView(headerSub)
        header.addView(nameBlock)
        header.addView(iconButton(VectorIconView.PHONE, 40, GREEN, null) {
            current?.let { onCall(it.address) }
        })
        header.addView(iconButton(VectorIconView.DELETE, 40, DANGER, null) {
            current?.let { confirmDelete(it) }
        })
        threadContent.addView(header)
        threadContent.addView(View(act).apply {
            setBackgroundColor(W11.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(mp, dp(1))
        })

        // messages
        threadRecycler = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act).apply { stackFromEnd = true }
            adapter = threadAdapter
            setItemViewCacheSize(10)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(mp, 0, 1f)
        }
        threadContent.addView(threadRecycler)

        // composer
        val replyBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(BG_BAR)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(mp, wc)
        }
        val inputBox = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(FIELD_BG, 8, FIELD_STROKE)
            setPadding(dp(12), dp(2), dp(12), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
        }
        replyEdit = EditText(act).apply {
            hint = "Type a message"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            setBackgroundColor(Color.TRANSPARENT)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            minHeight = dp(40)
            setPadding(0, dp(6), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(mp, wc)
        }
        inputBox.addView(replyEdit)
        replyEdit.setOnFocusChangeListener { _, focus ->
            inputBox.background = round(FIELD_BG, 8, if (focus) W11.ACCENT else FIELD_STROKE)
        }
        replyBar.addView(inputBox)
        sendBtn = iconButton(VectorIconView.SEND, 44, Color.BLACK, W11.ACCENT, 8) { sendFromComposer() }
        (sendBtn.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        sendBtn.alpha = 0.4f
        replyBar.addView(sendBtn)
        replyEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                sendBtn.alpha = if (s.isNullOrBlank()) 0.4f else 1f
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        threadContent.addView(replyBar)

        threadPane.addView(threadContent)
        body.addView(threadPane)
        root.addView(body)
    }

    // =================================================================
    // Public API (called from MainActivity)
    // =================================================================

    private var askedPermission = false

    fun onShown() {
        shown = true
        if (!hasRead() && !askedPermission) {
            askedPermission = true
            onNeedPermission()
        }
        if (!observerRegistered && hasRead()) {
            try {
                act.contentResolver.registerContentObserver(SMS_URI, true, observer)
                observerRegistered = true
            } catch (e: Throwable) { }
        }
        refresh()
    }

    fun onHidden() {
        shown = false
        ui.removeCallbacks(refreshRunnable)
        if (observerRegistered) {
            try { act.contentResolver.unregisterContentObserver(observer) } catch (e: Throwable) { }
            observerRegistered = false
        }
    }

    fun destroy() {
        destroyed = true
        onHidden()
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    /** Reload the conversation list (and the open thread). Safe to call any time. */
    fun refresh() {
        if (destroyed) return
        if (!hasRead()) {
            showState(
                VectorIconView.MESSAGES, "Allow access to your messages",
                "Messages needs SMS permission to show and send texts.", "Allow access"
            ) { onNeedPermission() }
            return
        }
        if (shown && !observerRegistered) {
            try {
                act.contentResolver.registerContentObserver(SMS_URI, true, observer)
                observerRegistered = true
            } catch (e: Throwable) { }
        }
        loadConversations()
        current?.let { loadThread(it, stickBottom = false) }
    }

    /** Back press: returns true if it closed the open conversation (narrow layout only). */
    fun handleBack(): Boolean {
        if (!wide && current != null) {
            closeThread()
            return true
        }
        return false
    }

    // =================================================================
    // Conversation list
    // =================================================================

    private fun showState(
        icon: String, title: String, sub: String, button: String? = null,
        onClick: (() -> Unit)? = null
    ) {
        stateIcon.visibility = View.VISIBLE
        stateTitle.text = title
        stateSub.text = sub
        stateSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        if (button != null) {
            stateButton.text = button
            stateButton.visibility = View.VISIBLE
            stateButton.setOnClickListener { onClick?.invoke() }
        } else stateButton.visibility = View.GONE
        stateView.visibility = View.VISIBLE
        convRecycler.visibility = View.GONE
    }

    private fun hideState() {
        stateView.visibility = View.GONE
        convRecycler.visibility = View.VISIBLE
    }

    private fun loadConversations() {
        val gen = ++listGen
        io.execute {
            if (System.currentTimeMillis() - nameCacheStamp > 5 * 60 * 1000L) {
                nameCache.clear()
                nameCacheStamp = System.currentTimeMillis()
            }
            val first = try {
                queryConversations(resolveNames = false)
            } catch (e: Throwable) {
                ListResult(emptyList(), e.message ?: e.javaClass.simpleName)
            }
            ui.post { if (gen == listGen && !destroyed) onConversations(first) }
            if (first.error != null) return@execute

            // second pass: resolve contact names (cached) and post again only if something changed
            val named = first.convs.map { c ->
                val n = resolveName(c.address)
                if (n == c.name) c else c.copy(name = n)
            }
            if (named.zip(first.convs).any { (a, b) -> a.name != b.name }) {
                ui.post { if (gen == listGen && !destroyed) onConversations(ListResult(named, null)) }
            }
        }
    }

    private fun queryConversations(resolveNames: Boolean): ListResult {
        val cr = act.contentResolver
        val unread = HashMap<String, Int>()
        cr.query(SMS_URI, arrayOf("thread_id", "address"), "read = 0", null, null)?.use { c ->
            val iT = c.getColumnIndex("thread_id")
            val iA = c.getColumnIndex("address")
            while (c.moveToNext()) {
                val tid = if (iT >= 0) c.getLong(iT) else 0L
                val addr = if (iA >= 0) c.getString(iA) ?: "" else ""
                val k = if (tid > 0) "t$tid" else "a${normAddr(addr)}"
                unread[k] = (unread[k] ?: 0) + 1
            }
        }
        val map = LinkedHashMap<String, Conv>()
        cr.query(
            SMS_URI, arrayOf("thread_id", "address", "body", "date", "type"),
            null, null, "date DESC"
        )?.use { c ->
            val iT = c.getColumnIndex("thread_id")
            val iA = c.getColumnIndex("address")
            val iB = c.getColumnIndex("body")
            val iD = c.getColumnIndex("date")
            val iY = c.getColumnIndex("type")
            while (c.moveToNext()) {
                val tid = if (iT >= 0) c.getLong(iT) else 0L
                val addr = (if (iA >= 0) c.getString(iA) else null) ?: "Unknown"
                val key = if (tid > 0) "t$tid" else "a${normAddr(addr)}"
                if (map.containsKey(key)) continue
                val cached = nameCache[addr]
                map[key] = Conv(
                    tid, addr,
                    if (resolveNames) resolveName(addr) else (cached ?: addr),
                    ((if (iB >= 0) c.getString(iB) else null) ?: "").take(160),
                    if (iD >= 0) c.getLong(iD) else 0L,
                    if (iY >= 0) c.getInt(iY) else 1,
                    unread[key] ?: 0
                )
                if (map.size >= MAX_CONVS) break
            }
        }
        return ListResult(map.values.toList(), null)
    }

    private fun onConversations(result: ListResult) {
        if (result.error != null) {
            showState(VectorIconView.MESSAGES, "Couldn't load messages", result.error)
            return
        }
        allConvs = mergeLocal(result.convs)
        applyFilter()
    }

    /** Keep conversations that only exist locally (just sent from here to a brand-new number). */
    private fun mergeLocal(loaded: List<Conv>): List<Conv> {
        if (localSent.isEmpty()) return loaded
        val keys = loaded.map { normAddr(it.address) }.toSet()
        val extra = allConvs.filter { it.threadId <= 0 && normAddr(it.address) !in keys && localSent.containsKey(it.key) }
        return if (extra.isEmpty()) loaded else (extra + loaded).sortedByDescending { it.date }
    }

    private fun applyQuery() {
        val q = searchEdit.text?.toString()?.trim()?.lowercase() ?: ""
        if (q == query) return
        query = q
        bodyHits = emptySet()
        applyFilter()
        if (q.length >= 2) {
            val gen = ++bodyGen
            io.execute {
                val hits = HashSet<String>()
                try {
                    act.contentResolver.query(
                        SMS_URI, arrayOf("thread_id", "address"),
                        "body LIKE ?", arrayOf("%$q%"), "date DESC"
                    )?.use { c ->
                        val iT = c.getColumnIndex("thread_id")
                        val iA = c.getColumnIndex("address")
                        while (c.moveToNext() && hits.size < 500) {
                            val tid = if (iT >= 0) c.getLong(iT) else 0L
                            val addr = if (iA >= 0) c.getString(iA) ?: "" else ""
                            hits.add(if (tid > 0) "t$tid" else "a${normAddr(addr)}")
                        }
                    }
                } catch (e: Throwable) { }
                ui.post {
                    if (gen == bodyGen && !destroyed && q == query) {
                        bodyHits = hits
                        applyFilter()
                    }
                }
            }
        }
    }

    private fun applyFilter() {
        val q = query
        val list = if (q.isEmpty()) allConvs else allConvs.filter {
            it.name.lowercase().contains(q) || it.address.lowercase().contains(q) ||
                it.snippet.lowercase().contains(q) || it.key in bodyHits
        }
        convAdapter.items = list
        convAdapter.notifyDataSetChanged()
        when {
            list.isEmpty() && q.isEmpty() ->
                showState(VectorIconView.MESSAGES, "No messages yet", "Tap the pencil to start a conversation.")
            list.isEmpty() -> showState(VectorIconView.SEARCH, "No results", "Nothing found for \"$q\".")
            else -> hideState()
        }
    }

    // ── Conversation list adapter ────────────────────────────────

    private inner class ConvVH(
        val row: LinearLayout,
        val indicator: View,
        val avatar: Avatar,
        val name: TextView,
        val snippet: TextView,
        val time: TextView,
        val badge: TextView
    ) : RecyclerView.ViewHolder(row)

    private inner class ConvAdapter : RecyclerView.Adapter<ConvVH>() {
        var items: List<Conv> = emptyList()

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConvVH {
            val wc = ViewGroup.LayoutParams.WRAP_CONTENT
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(12), dp(10))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, wc
                ).apply { setMargins(dp(6), dp(1), dp(6), dp(1)) }
                foreground = ripple(null, 6)
                isClickable = true
            }
            val indicator = View(act).apply {
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(18)).apply { setMargins(0, 0, dp(7), 0) }
            }
            row.addView(indicator)
            val avatar = Avatar(42)
            (avatar.view.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
            row.addView(avatar.view)

            val text = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
            }
            val name = TextView(act).apply {
                setTextColor(W11.TEXT)
                textSize = 14.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val snippet = TextView(act).apply {
                textSize = 12.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, 0)
            }
            text.addView(name)
            text.addView(snippet)
            row.addView(text)

            val right = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(wc, wc)
            }
            val time = TextView(act).apply {
                setTextColor(W11.TEXT_DIM)
                textSize = 11f
            }
            val badge = TextView(act).apply {
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                background = round(W11.ACCENT, 9)
                minWidth = dp(18)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                layoutParams = LinearLayout.LayoutParams(wc, wc).apply { topMargin = dp(4) }
            }
            right.addView(time)
            right.addView(badge)
            row.addView(right)
            return ConvVH(row, indicator, avatar, name, snippet, time, badge)
        }

        override fun onBindViewHolder(h: ConvVH, position: Int) {
            val conv = items[position]
            val selected = wide && conv.key == current?.key
            val unread = conv.unread > 0
            h.row.background = round(if (selected) W11.ITEM_ACTIVE_BG else Color.TRANSPARENT, 6)
            h.indicator.background = round(if (selected) W11.ACCENT else Color.TRANSPARENT, 2)
            h.avatar.bind(conv.name)
            h.name.text = conv.name
            h.name.typeface = if (unread) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val sent = conv.type == TYPE_SENT || conv.type == TYPE_QUEUED
            h.snippet.text = (if (sent) "You: " else "") + conv.snippet.replace('\n', ' ')
            h.snippet.setTextColor(if (unread) W11.TEXT else W11.TEXT_DIM)
            h.time.text = listTime(conv.date)
            h.time.setTextColor(if (unread) W11.ACCENT else W11.TEXT_DIM)
            if (unread) {
                h.badge.text = if (conv.unread > 99) "99+" else conv.unread.toString()
                h.badge.visibility = View.VISIBLE
            } else h.badge.visibility = View.GONE
            h.row.setOnClickListener { openThread(conv) }
            h.row.setOnLongClickListener {
                val menu = PopupMenu(act, h.row)
                menu.menu.add(0, 1, 0, "Call")
                menu.menu.add(0, 2, 1, "Delete conversation")
                menu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> onCall(conv.address)
                        2 -> confirmDelete(conv)
                    }
                    true
                }
                menu.show()
                true
            }
        }
    }

    // =================================================================
    // Contacts
    // =================================================================

    /** io thread only */
    private fun resolveName(address: String): String {
        nameCache[address]?.let { return it }
        var name = address
        // alphanumeric sender ids (e.g. CIRKLE_DATA) are never in contacts
        if (hasPerm(Manifest.permission.READ_CONTACTS) && address.count { it.isDigit() } >= 3) {
            try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)
                )
                act.contentResolver.query(
                    uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
                )?.use { if (it.moveToFirst()) name = it.getString(0) ?: address }
            } catch (e: Throwable) { }
        }
        nameCache[address] = name
        return name
    }

    // =================================================================
    // Open conversation
    // =================================================================

    private fun showThreadPane() {
        placeholder.visibility = View.GONE
        threadContent.visibility = View.VISIBLE
        threadPane.visibility = View.VISIBLE
        if (!wide) listPane.visibility = View.GONE
    }

    private fun closeThread() {
        current = null
        msgs = emptyList()
        threadContent.visibility = View.GONE
        hideKeyboard()
        if (wide) placeholder.visibility = View.VISIBLE
        else {
            threadPane.visibility = View.GONE
            listPane.visibility = View.VISIBLE
        }
        convAdapter.notifyDataSetChanged()
    }

    private fun hideKeyboard() {
        try {
            val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(root.windowToken, 0)
        } catch (e: Throwable) { }
    }

    private fun openThread(conv: Conv) {
        val changed = current?.key != conv.key
        current = conv
        if (changed) {
            msgs = emptyList()
            threadAdapter.rows = emptyList()
            threadAdapter.notifyDataSetChanged()
            replyEdit.setText("")
        }
        headerAvatar.bind(conv.name)
        headerName.text = conv.name
        headerSub.text = if (conv.name != conv.address) conv.address else ""
        showThreadPane()
        convAdapter.notifyDataSetChanged() // selection highlight
        loadThread(conv, stickBottom = true)
    }

    private fun loadThread(conv: Conv, stickBottom: Boolean) {
        val gen = ++threadGen
        io.execute {
            val loaded = ArrayList<Msg>()
            try {
                val byThread = conv.threadId > 0
                act.contentResolver.query(
                    SMS_URI, arrayOf("body", "date", "type"),
                    if (byThread) "thread_id = ?" else "address = ?",
                    arrayOf(if (byThread) conv.threadId.toString() else conv.address),
                    "date DESC"
                )?.use { c ->
                    val iB = c.getColumnIndex("body")
                    val iD = c.getColumnIndex("date")
                    val iY = c.getColumnIndex("type")
                    while (c.moveToNext() && loaded.size < MAX_MSGS) {
                        loaded.add(
                            Msg(
                                (if (iB >= 0) c.getString(iB) else null) ?: "",
                                if (iD >= 0) c.getLong(iD) else 0L,
                                if (iY >= 0) c.getInt(iY) else 1
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                ui.post { if (!destroyed) toast("Couldn't load messages: ${e.message}") }
                return@execute
            }
            loaded.reverse() // oldest first
            if (conv.unread > 0) markRead(conv)
            ui.post {
                if (gen == threadGen && !destroyed && current?.key == conv.key) {
                    if (conv.unread > 0) current = conv.copy(unread = 0)
                    showMessages(conv, loaded, stickBottom)
                }
            }
        }
    }

    /** Works only when this app is the default SMS app; otherwise it silently does nothing. */
    private fun markRead(conv: Conv) {
        try {
            val v = ContentValues().apply { put("read", 1); put("seen", 1) }
            val byThread = conv.threadId > 0
            act.contentResolver.update(
                SMS_URI, v,
                if (byThread) "thread_id = ? AND read = 0" else "address = ? AND read = 0",
                arrayOf(if (byThread) conv.threadId.toString() else conv.address)
            )
        } catch (e: Throwable) { }
    }

    private fun showMessages(conv: Conv, loaded: List<Msg>, stickBottom: Boolean) {
        val merged = ArrayList<Msg>(loaded)
        // keep messages we just sent that the provider doesn't hold (we are not the default SMS app)
        localSent[conv.key]?.let { pending ->
            val iter = pending.iterator()
            while (iter.hasNext()) {
                val m = iter.next()
                val present = loaded.any { p ->
                    p.type != 1 && p.body == m.body && Math.abs(p.date - m.date) < 120_000L
                }
                if (present) iter.remove() else merged.add(m)
            }
        }
        merged.sortBy { it.date }
        msgs = merged

        val lm = threadRecycler.layoutManager as LinearLayoutManager
        val wasAtBottom = threadAdapter.itemCount == 0 ||
            lm.findLastVisibleItemPosition() >= threadAdapter.itemCount - 2
        threadAdapter.rows = buildRows(merged)
        threadAdapter.notifyDataSetChanged()
        headerSub.text = (if (conv.name != conv.address) "${conv.address}  -  " else "") +
            (if (merged.size >= MAX_MSGS) "$MAX_MSGS+" else merged.size.toString()) + " messages"
        if ((stickBottom || wasAtBottom) && threadAdapter.itemCount > 0) {
            threadRecycler.scrollToPosition(threadAdapter.itemCount - 1)
        }
    }

    private fun buildRows(list: List<Msg>): List<Row> {
        val out = ArrayList<Row>(list.size + 16)
        val cal = Calendar.getInstance()
        val today = Calendar.getInstance()
        val fmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
        var lastKey = -1
        for (m in list) {
            if (m.date > 0) {
                cal.timeInMillis = m.date
                val key = cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR)
                if (key != lastKey) {
                    lastKey = key
                    val diff = (today.get(Calendar.YEAR) * 400 + today.get(Calendar.DAY_OF_YEAR)) - key
                    val label = when (diff) {
                        0 -> "Today"
                        1 -> "Yesterday"
                        else -> fmt.format(Date(m.date))
                    }
                    out.add(Row(ROW_DAY, label))
                }
            }
            out.add(Row(ROW_MSG, msg = m))
        }
        return out
    }

    // ── Thread adapter ───────────────────────────────────────────

    private inner class DayVH(root: View, val tv: TextView) : RecyclerView.ViewHolder(root)

    private inner class MsgVH(
        val row: LinearLayout,
        val bubble: MaxWidthLayout,
        val body: TextView,
        val time: TextView
    ) : RecyclerView.ViewHolder(row)

    private inner class ThreadAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = rows[position].kind

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val mp = ViewGroup.LayoutParams.MATCH_PARENT
            val wc = ViewGroup.LayoutParams.WRAP_CONTENT
            if (viewType == ROW_DAY) {
                val tv = TextView(act).apply {
                    setTextColor(W11.TEXT_DIM)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    background = round(DAY_CHIP, 10)
                    setPadding(dp(12), dp(3), dp(12), dp(3))
                }
                // centre the chip: wrap it
                val holder = LinearLayout(act).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(6))
                    layoutParams = RecyclerView.LayoutParams(mp, wc)
                    addView(tv, LinearLayout.LayoutParams(wc, wc))
                }
                return DayVH(holder, tv)
            }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(mp, wc).apply { setMargins(0, dp(2), 0, dp(2)) }
            }
            val bubble = MaxWidthLayout(act, if (wide) 0.62f else 0.82f).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(6))
            }
            val body = TextView(act).apply {
                setTextColor(Color.WHITE)
                textSize = 14.5f
                setLineSpacing(dpf(2f), 1f)
                layoutParams = LinearLayout.LayoutParams(wc, wc)
            }
            val time = TextView(act).apply {
                setTextColor(Color.parseColor("#B3FFFFFF"))
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(wc, wc).apply {
                    gravity = Gravity.END
                    topMargin = dp(2)
                }
            }
            bubble.addView(body)
            bubble.addView(time)
            row.addView(bubble, LinearLayout.LayoutParams(wc, wc))
            return MsgVH(row, bubble, body, time)
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            if (r.kind == ROW_DAY) {
                (h as DayVH).tv.text = r.label
                return
            }
            val m = r.msg ?: return
            val vh = h as MsgVH
            val out = m.type == TYPE_SENT || m.type == TYPE_QUEUED || m.type == TYPE_FAILED || m.type == 3
            vh.row.gravity = if (out) Gravity.END else Gravity.START
            val big = dpf(14f)
            val small = dpf(4f)
            vh.bubble.background = GradientDrawable().apply {
                setColor(if (out) BUBBLE_OUT else BUBBLE_IN)
                if (!out) setStroke(dp(1).coerceAtLeast(1), BUBBLE_IN_STROKE)
                // order: top-left, top-right, bottom-right, bottom-left
                cornerRadii = if (out) floatArrayOf(big, big, big, big, small, small, big, big)
                else floatArrayOf(big, big, big, big, big, big, small, small)
            }
            vh.body.text = m.body
            val t = if (m.date > 0) SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(m.date)) else ""
            when (m.type) {
                TYPE_FAILED -> {
                    vh.time.text = "$t  -  Not sent"
                    vh.time.setTextColor(DANGER)
                }
                TYPE_QUEUED -> {
                    vh.time.text = "$t  -  Sending..."
                    vh.time.setTextColor(Color.parseColor("#B3FFFFFF"))
                }
                else -> {
                    vh.time.text = t
                    vh.time.setTextColor(Color.parseColor("#B3FFFFFF"))
                }
            }
            vh.bubble.setOnLongClickListener {
                val menu = PopupMenu(act, vh.bubble)
                menu.menu.add(0, 1, 0, "Copy text")
                menu.setOnMenuItemClickListener {
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("message", m.body))
                    toast("Copied")
                    true
                }
                menu.show()
                true
            }
        }
    }

    // =================================================================
    // Sending
    // =================================================================

    private fun sendFromComposer() {
        val text = replyEdit.text.toString().trim()
        val conv = current ?: return
        if (text.isEmpty()) return
        if (sendSms(conv.address, text)) {
            replyEdit.setText("")
            afterSent(conv.address, text)
        }
    }

    private fun sendSms(address: String, body: String): Boolean {
        if (!hasPerm(Manifest.permission.SEND_SMS)) {
            onNeedPermission()
            toast("SMS permission required")
            return false
        }
        return try {
            val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                act.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = sm.divideMessage(body)
            if (parts.size == 1) sm.sendTextMessage(address, null, body, null, null)
            else sm.sendMultipartTextMessage(address, null, parts, null, null)
            true
        } catch (e: Throwable) {
            Toast.makeText(act, "SMS failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Show the sent message immediately and move the conversation to the top of the list. */
    private fun afterSent(address: String, text: String) {
        val now = System.currentTimeMillis()
        val idx = allConvs.indexOfFirst { normAddr(it.address) == normAddr(address) }
        val old = allConvs.getOrNull(idx)
        val updated = (old ?: Conv(-1, address, address, "", now, TYPE_SENT, 0))
            .copy(snippet = text.take(160), date = now, type = TYPE_SENT, unread = 0)
        allConvs = listOf(updated) + allConvs.filterIndexed { i, _ -> i != idx }
        val m = Msg(text, now, TYPE_SENT)
        localSent.getOrPut(updated.key) { ArrayList() }.add(m)
        applyFilter()

        if (current?.key == updated.key) {
            current = updated
            msgs = msgs + m
            threadAdapter.rows = buildRows(msgs)
            threadAdapter.notifyDataSetChanged()
            threadRecycler.scrollToPosition(threadAdapter.itemCount - 1)
        } else if (current == null || current?.let { normAddr(it.address) } == normAddr(address)) {
            openThread(updated)
        }
        // pick up the provider's copy (if any) shortly after
        ui.postDelayed({ if (!destroyed && shown) refresh() }, 1500)
    }

    // =================================================================
    // Dialogs (Fluent style)
    // =================================================================

    private fun inputField(hint: String, inputType: Int, multi: Boolean = false): EditText =
        EditText(act).apply {
            this.hint = hint
            this.inputType = inputType
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            background = round(Color.parseColor("#1F1F1F"), 6, FIELD_STROKE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (multi) {
                minLines = 3
                maxLines = 6
                gravity = Gravity.TOP
            }
            setOnFocusChangeListener { v, focus ->
                v.background = round(Color.parseColor("#1F1F1F"), 6, if (focus) W11.ACCENT else FIELD_STROKE)
            }
        }

    private fun showDialog(
        title: String, message: String?, content: View?, positive: String,
        destructive: Boolean, onOk: () -> Boolean
    ) {
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.parseColor("#2B2B2B"), 10, Color.parseColor("#3C3C3C"))
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        box.addView(TextView(act).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })
        if (message != null) {
            box.addView(TextView(act).apply {
                text = message
                setTextColor(W11.TEXT_DIM)
                textSize = 13.5f
                setPadding(0, dp(8), 0, 0)
            })
        }
        if (content != null) {
            content.layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(14) }
            box.addView(content)
        }
        val buttons = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(18) }
        }
        fun btn(label: String, bg: Int, fg: Int, bold: Boolean, click: () -> Unit) =
            TextView(act).apply {
                text = label
                setTextColor(fg)
                textSize = 13.5f
                if (bold) typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = ripple(round(bg, 6), 6)
                isClickable = true
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(0, wc, 1f).apply {
                    leftMargin = dp(4); rightMargin = dp(4)
                }
            }
        buttons.addView(btn("Cancel", Color.parseColor("#3A3A3A"), Color.WHITE, false) { dlg.dismiss() })
        buttons.addView(
            btn(
                positive,
                if (destructive) Color.parseColor("#C42B1C") else W11.ACCENT,
                if (destructive) Color.WHITE else Color.BLACK, true
            ) { if (onOk()) dlg.dismiss() }
        )
        box.addView(buttons)
        dlg.setContentView(box)
        dlg.show()
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (act.resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtMost(dp(420))
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun showComposeDialog(prefillAddress: String?) {
        val form = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val to = inputField("To (phone number)", InputType.TYPE_CLASS_PHONE)
        to.setText(prefillAddress ?: "")
        val msg = inputField(
            "Type a message",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, multi = true
        )
        form.addView(to, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(msg, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        showDialog("New message", null, form, "Send", false) {
            val num = to.text.toString().trim()
            val text = msg.text.toString().trim()
            if (num.isEmpty() || text.isEmpty()) {
                toast("Number and message required")
                false
            } else if (sendSms(num, text)) {
                afterSent(num, text)
                true
            } else false
        }
    }

    private fun confirmDelete(conv: Conv) {
        showDialog(
            "Delete conversation", "Delete all messages with ${conv.name}? This can't be undone.",
            null, "Delete", true
        ) {
            io.execute {
                val deleted = try {
                    val byThread = conv.threadId > 0
                    act.contentResolver.delete(
                        SMS_URI,
                        if (byThread) "thread_id = ?" else "address = ?",
                        arrayOf(if (byThread) conv.threadId.toString() else conv.address)
                    )
                } catch (e: Throwable) { -1 }
                ui.post {
                    if (destroyed) return@post
                    when {
                        deleted > 0 -> {
                            toast("Deleted $deleted message(s)")
                            localSent.remove(conv.key)
                            if (current?.key == conv.key) closeThread()
                            refresh()
                        }
                        else -> toast("Couldn't delete - set Win11 as the default SMS app to delete messages")
                    }
                }
            }
            true
        }
    }

    // =================================================================
    // Formatting
    // =================================================================

    private fun listTime(date: Long): String {
        if (date <= 0) return ""
        val now = Calendar.getInstance()
        val d = Calendar.getInstance().apply { timeInMillis = date }
        val dayDiff = (now.get(Calendar.YEAR) * 400 + now.get(Calendar.DAY_OF_YEAR)) -
            (d.get(Calendar.YEAR) * 400 + d.get(Calendar.DAY_OF_YEAR))
        return when {
            dayDiff == 0 -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(date))
            dayDiff == 1 -> "Yesterday"
            dayDiff in 2..6 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(date))
            now.get(Calendar.YEAR) == d.get(Calendar.YEAR) ->
                SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(date))
            else -> SimpleDateFormat("d/M/yy", Locale.getDefault()).format(Date(date))
        }
    }
}

/** "+8801712345678", "01712 345678" and "8801712345678" all map to the same key. */
private fun normAddr(address: String): String {
    val digits = address.filter { it.isDigit() }
    return if (digits.length >= 7) digits.takeLast(10) else address.lowercase()
}
