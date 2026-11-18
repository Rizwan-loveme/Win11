package com.srizwan.win11

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Locale

// =====================================================================
// Downloads screen (1DM style list) – Win11 dark look
// =====================================================================

class DownloadsUi(
    private val ctx: Context,
    private val onBack: () -> Unit,
    private val onOpen: (DlTask) -> Unit,
    private val onShowFolder: () -> Unit,
    private val onCopyLink: (String) -> Unit
) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    val root: LinearLayout
    private val list: ListView
    private val emptyView: TextView
    private val speedLabel: TextView
    private val chips = ArrayList<TextView>()
    private var filter = 0 // 0 all, 1 downloading, 2 completed
    private val adapter = DlAdapter()
    private val listener: () -> Unit = { refresh() }

    init {
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(WX.BG)
            visibility = View.GONE
            isClickable = true // swallow touches so the page underneath is not scrolled
        }

        // ── Title bar ───────────────────────────────────────────
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WX.BAR)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            background = ripple(22)
            isClickable = true
            contentDescription = "Back"
            setOnClickListener { onBack() }
            addView(
                VectorIconView(ctx, VectorIconView.BACK),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            )
        })
        bar.addView(TextView(ctx).apply {
            text = "Downloads"
            setTextColor(WX.TEXT)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        speedLabel = TextView(ctx).apply {
            setTextColor(WX.ACCENT)
            textSize = 12f
            setPadding(0, 0, dp(4), 0)
        }
        bar.addView(speedLabel)
        bar.addView(TextView(ctx).apply {
            text = "⋮"
            setTextColor(WX.TEXT)
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            background = ripple(22)
            isClickable = true
            contentDescription = "Download settings"
            setOnClickListener { showSettings() }
        })
        root.addView(bar)

        // ── Filter chips ────────────────────────────────────────
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(WX.BAR)
            setPadding(dp(12), dp(0), dp(12), dp(10))
        }
        listOf("All", "Downloading", "Completed").forEachIndexed { i, label ->
            val chip = TextView(ctx).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)
                ).apply { rightMargin = dp(8) }
                isClickable = true
                setOnClickListener { filter = i; refresh() }
            }
            chips.add(chip)
            chipRow.addView(chip)
        }
        root.addView(chipRow)
        root.addView(View(ctx).apply {
            setBackgroundColor(WX.CARD_BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })

        // ── List ────────────────────────────────────────────────
        emptyView = TextView(ctx).apply {
            text = "No downloads yet"
            setTextColor(WX.DIM)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(64), 0, dp(64))
            layoutParams = AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        list = ListView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setBackgroundColor(WX.BG)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(8))
            addFooterView(emptyView, null, false)
            this.adapter = this@DownloadsUi.adapter
            setOnItemClickListener { parent, _, position, _ ->
                (parent.getItemAtPosition(position) as? DlTask)?.let { t ->
                    when (t.status) {
                        DlEngine.DONE -> onOpen(t)
                        DlEngine.PAUSED, DlEngine.FAILED -> DlEngine.resume(t.id)
                        DlEngine.RUNNING, DlEngine.QUEUED -> { if (!t.isBlob) DlEngine.pause(t.id) }
                        else -> { }
                    }
                }
            }
            setOnItemLongClickListener { parent, _, position, _ ->
                (parent.getItemAtPosition(position) as? DlTask)?.let { showMenu(it) }
                true
            }
        }
        root.addView(list)
        updateChips()
    }

    private fun ripple(radiusDp: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(WX.RIPPLE), null, mask)
    }

    // ── Public ──────────────────────────────────────────────────

    fun onShow() {
        DlEngine.addListener(listener)
        refresh()
    }

    fun onHide() {
        DlEngine.removeListener(listener)
    }

    private fun updateChips() {
        chips.forEachIndexed { i, c ->
            val on = i == filter
            c.setTextColor(if (on) Color.BLACK else WX.TEXT)
            c.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                if (on) setColor(WX.ACCENT) else { setColor(WX.CARD); setStroke(1, WX.CARD_BORDER) }
            }
        }
    }

    private fun refresh() {
        updateChips()
        val all = DlEngine.tasks.toList().sortedByDescending { it.createdAt }
        adapter.data = when (filter) {
            1 -> all.filter { it.status != DlEngine.DONE }
            2 -> all.filter { it.status == DlEngine.DONE }
            else -> all
        }
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (adapter.data.isEmpty()) View.VISIBLE else View.GONE
        var speed = 0L
        for (t in all) if (t.status == DlEngine.RUNNING) speed += t.speed
        speedLabel.text = if (speed > 0) "${dlFmtSize(speed)}/s" else ""
    }

    // ── Menus ───────────────────────────────────────────────────

    private fun showMenu(t: DlTask) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        when (t.status) {
            DlEngine.DONE -> {
                opts.add("Open" to { onOpen(t) })
                opts.add("Show in Downloads folder" to { onShowFolder() })
                opts.add("Delete file" to { DlEngine.deleteFinishedFile(t.id) })
                opts.add("Remove from list" to { DlEngine.cancel(t.id) })
            }
            DlEngine.RUNNING, DlEngine.QUEUED -> {
                if (!t.isBlob) opts.add("Pause" to { DlEngine.pause(t.id) })
                opts.add("Cancel and delete" to { DlEngine.cancel(t.id) })
            }
            DlEngine.PAUSED -> {
                opts.add("Resume" to { DlEngine.resume(t.id) })
                opts.add("Cancel and delete" to { DlEngine.cancel(t.id) })
            }
            DlEngine.FAILED -> {
                if (!t.isBlob) opts.add("Retry" to { DlEngine.resume(t.id) })
                opts.add("Remove from list" to { DlEngine.cancel(t.id) })
            }
            else -> opts.add("Remove from list" to { DlEngine.cancel(t.id) })
        }
        if (t.url.isNotEmpty()) opts.add("Copy link" to { onCopyLink(t.url) })
        AlertDialog.Builder(ctx).setTitle(t.fileName)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .setNegativeButton("Close", null).show()
    }

    private fun showSettings() {
        val items = arrayOf(
            "Connections per download: ${DlEngine.connections}",
            "Simultaneous downloads: ${DlEngine.maxParallel}",
            "Clear finished and failed"
        )
        AlertDialog.Builder(ctx).setTitle("Download settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val v = intArrayOf(1, 2, 4, 6, 8)
                        AlertDialog.Builder(ctx).setTitle("Connections per download")
                            .setSingleChoiceItems(
                                v.map { it.toString() }.toTypedArray(),
                                v.indexOf(DlEngine.connections).coerceAtLeast(0)
                            ) { d, i -> DlEngine.connections = v[i]; d.dismiss() }
                            .setNegativeButton("Cancel", null).show()
                    }
                    1 -> {
                        val v = intArrayOf(1, 2, 3, 4, 5)
                        AlertDialog.Builder(ctx).setTitle("Simultaneous downloads")
                            .setSingleChoiceItems(
                                v.map { it.toString() }.toTypedArray(),
                                v.indexOf(DlEngine.maxParallel).coerceAtLeast(0)
                            ) { d, i -> DlEngine.maxParallel = v[i]; d.dismiss() }
                            .setNegativeButton("Cancel", null).show()
                    }
                    2 -> DlEngine.clearFinished()
                }
            }.setNegativeButton("Close", null).show()
    }

    // ── Row ─────────────────────────────────────────────────────

    private fun statusText(t: DlTask): String {
        val done = t.downloaded.get()
        val sizeText = if (t.total > 0) "${dlFmtSize(done)} of ${dlFmtSize(t.total)}" else dlFmtSize(done)
        return when (t.status) {
            DlEngine.RUNNING -> {
                val sb = StringBuilder(sizeText)
                if (t.speed > 0) sb.append("  •  ${dlFmtSize(t.speed)}/s")
                val eta = t.etaSeconds()
                if (eta >= 0) sb.append("  •  ${dlFmtEta(eta)} left")
                if (t.connectionCount > 1) sb.append("  •  ${t.connectionCount} conn")
                sb.toString()
            }
            DlEngine.QUEUED -> "Queued"
            DlEngine.PAUSED -> "Paused  •  $sizeText"
            DlEngine.SAVING -> "Saving to Downloads…"
            DlEngine.DONE -> (if (t.total > 0) dlFmtSize(t.total) else dlFmtSize(done)) + "  •  Completed"
            DlEngine.FAILED -> "Failed: ${t.error.ifBlank { "unknown error" }}"
            else -> ""
        }
    }

    private inner class Row : LinearLayout(ctx) {
        val icon = FileIconView(ctx)
        val name = TextView(ctx)
        val bar = UsageBar(ctx)
        val status = TextView(ctx)
        val action = TextView(ctx)
        val cancel = TextView(ctx)

        init {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(8), dp(4), dp(8))
            layoutParams = AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = ripple(8)

            addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(14); rightMargin = dp(4) }
            }
            name.apply {
                setTextColor(WX.TEXT)
                textSize = 15f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            bar.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)
            ).apply { topMargin = dp(6) }
            status.apply {
                setTextColor(WX.DIM)
                textSize = 12f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            }
            col.addView(name)
            col.addView(bar)
            col.addView(status)
            addView(col)

            action.apply {
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(WX.TEXT)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                background = ripple(22)
                isClickable = true
                isFocusable = false
            }
            cancel.apply {
                text = "✕"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(WX.DIM)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
                background = ripple(20)
                isClickable = true
                isFocusable = false
            }
            addView(action)
            addView(cancel)
        }

        fun bind(t: DlTask) {
            val ext = t.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            icon.setFile(fileTypeColor(ext), ext.uppercase(Locale.ROOT).take(4), false)
            name.text = t.fileName
            status.text = statusText(t)
            status.setTextColor(if (t.status == DlEngine.FAILED) WX.WARN else WX.DIM)

            val showBar = t.status != DlEngine.DONE
            bar.visibility = if (showBar) View.VISIBLE else View.GONE
            if (showBar) {
                bar.fraction = if (t.total > 0) t.downloaded.get().toFloat() / t.total.toFloat() else 0f
                bar.fillColor = when (t.status) {
                    DlEngine.FAILED -> WX.WARN
                    DlEngine.PAUSED, DlEngine.QUEUED -> Color.parseColor("#8A8A8A")
                    else -> WX.ACCENT
                }
            }

            when (t.status) {
                DlEngine.RUNNING, DlEngine.QUEUED -> {
                    action.visibility = if (t.isBlob) View.GONE else View.VISIBLE
                    action.text = "❚❚"
                    action.setOnClickListener { DlEngine.pause(t.id) }
                }
                DlEngine.PAUSED -> {
                    action.visibility = View.VISIBLE
                    action.text = "▶"
                    action.setOnClickListener { DlEngine.resume(t.id) }
                }
                DlEngine.FAILED -> {
                    action.visibility = if (t.isBlob) View.GONE else View.VISIBLE
                    action.text = "↻"
                    action.setOnClickListener { DlEngine.resume(t.id) }
                }
                else -> action.visibility = View.GONE
            }
            cancel.visibility = if (t.status == DlEngine.DONE || t.status == DlEngine.SAVING) View.GONE else View.VISIBLE
            cancel.setOnClickListener { DlEngine.cancel(t.id) }
        }
    }

    private inner class DlAdapter : BaseAdapter() {
        var data: List<DlTask> = emptyList()
        override fun getCount(): Int = data.size
        override fun getItem(position: Int): DlTask = data[position]
        override fun getItemId(position: Int): Long = data[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? Row) ?: Row()
            row.bind(data[position])
            return row
        }
    }
}
