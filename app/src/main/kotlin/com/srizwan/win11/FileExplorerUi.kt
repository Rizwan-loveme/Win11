package com.srizwan.win11

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================
// WINDOWS 11 "THIS PC" / FILE EXPLORER STYLE UI  (mobile friendly)
// Pure Kotlin views – no XML / drawable resources, same as the rest of
// the project. All behaviour (open, delete, rename, ZIP, PDF …) stays in
// MainActivity and is reached through the callbacks below.
// =====================================================================

internal object WX {
    val BG = Color.parseColor("#191919")          // explorer content area
    val BAR = Color.parseColor("#202020")         // title / command bar (same as taskbar)
    val CARD = Color.parseColor("#2B2B2B")
    val CARD_BORDER = Color.parseColor("#383838")
    val TRACK = Color.parseColor("#454545")
    val TEXT = Color.WHITE
    val DIM = Color.parseColor("#9D9D9D")
    val ACCENT = Color.parseColor("#4CC2FF")
    val WARN = Color.parseColor("#FF6B6B")
    val RIPPLE = Color.parseColor("#33FFFFFF")
}

/** Small "N items" / size helpers shared by the explorer. */
private fun fmtSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val dg = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, dg.toDouble())) + " " + units[dg]
}

internal fun fileTypeColor(ext: String): Int = when (ext) {
    "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "wmv" -> Color.parseColor("#A78BFA")
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic" -> Color.parseColor("#29A3E0")
    "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus" -> Color.parseColor("#E8579B")
    "pdf" -> Color.parseColor("#E5484D")
    "txt", "log", "md", "json", "xml", "html" -> Color.parseColor("#6E7B8B")
    "doc", "docx" -> Color.parseColor("#2B7CD3")
    "xls", "xlsx", "csv" -> Color.parseColor("#21A366")
    "ppt", "pptx" -> Color.parseColor("#D24726")
    "zip", "rar", "7z", "tar", "gz" -> Color.parseColor("#E8A33D")
    "apk" -> Color.parseColor("#4CAF50")
    else -> Color.parseColor("#8A8A8A")
}


// ---------------------------------------------------------------------
// File / folder icon (drawn with Canvas, tinted by file type)
// ---------------------------------------------------------------------

class FileIconView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()
    private val doc = Path().apply {
        moveTo(6f, 2f); lineTo(14f, 2f); lineTo(20f, 8f); lineTo(20f, 20f)
        quadTo(20f, 22f, 18f, 22f); lineTo(6f, 22f); quadTo(4f, 22f, 4f, 20f)
        lineTo(4f, 4f); quadTo(4f, 2f, 6f, 2f); close()
    }
    private val fold = Path().apply {
        moveTo(14f, 2f); lineTo(14f, 6f); quadTo(14f, 8f, 16f, 8f); lineTo(20f, 8f); close()
    }

    private var isFolder = false
    private var tint = Color.parseColor("#8A8A8A")
    private var label = ""

    fun setFolder(hidden: Boolean) {
        isFolder = true
        label = ""
        alpha = if (hidden) 0.6f else 1f
        invalidate()
    }

    fun setFile(color: Int, ext: String, hidden: Boolean) {
        isFolder = false
        tint = color
        label = ext
        alpha = if (hidden) 0.6f else 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height) / 24f
        canvas.save()
        canvas.translate((width - 24f * s) / 2f, (height - 24f * s) / 2f)
        canvas.scale(s, s)
        if (isFolder) {
            // back tab + back panel (darker), front panel (lighter) – Win11 folder look
            paint.color = Color.parseColor("#E5A100")
            rect.set(2f, 3.5f, 11f, 9f); canvas.drawRoundRect(rect, 1.6f, 1.6f, paint)
            rect.set(2f, 6f, 22f, 20.5f); canvas.drawRoundRect(rect, 2f, 2f, paint)
            paint.color = Color.parseColor("#FFD25E")
            rect.set(2f, 8.5f, 22f, 20.5f); canvas.drawRoundRect(rect, 2f, 2f, paint)
        } else {
            paint.color = tint
            canvas.drawPath(doc, paint)
            paint.color = Color.parseColor("#66FFFFFF")
            canvas.drawPath(fold, paint)
            if (label.isNotEmpty()) {
                textPaint.textSize = 5.4f
                val w = textPaint.measureText(label)
                if (w > 12.5f) textPaint.textSize = 5.4f * 12.5f / w
                canvas.drawText(label, 12f, 17.4f, textPaint)
            }
        }
        canvas.restore()
    }
}

/** Rounded storage-usage bar (like the drive bar in Windows "This PC"). */
internal class UsageBar(context: Context) : View(context) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var fillColor = WX.ACCENT
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = WX.TRACK
        r.set(0f, 0f, w, h)
        canvas.drawRoundRect(r, h / 2f, h / 2f, paint)
        if (fraction > 0f) {
            paint.color = fillColor
            r.set(0f, 0f, maxOf(w * fraction, h), h)
            canvas.drawRoundRect(r, h / 2f, h / 2f, paint)
        }
    }
}

// ---------------------------------------------------------------------
// The explorer screen
// ---------------------------------------------------------------------

class ExplorerUi(
    private val ctx: Context,
    private val onUp: () -> Unit,
    private val onNewFolder: () -> Unit,
    private val onOpenFolder: (File) -> Unit,
    private val onItemClick: (File) -> Unit,
    private val onItemMenu: (File) -> Unit
) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val dateFmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
    private val itemCountCache = HashMap<String, Int>()

    val root: LinearLayout
    private val crumbRow: LinearLayout
    private val crumbScroll: HorizontalScrollView
    private val pathCaption: TextView
    private val itemsLabel: TextView
    private val listView: ListView
    private val emptyView: TextView
    private val driveTitle: TextView
    private val driveSub: TextView
    private val usageBar: UsageBar
    private val adapter = FileAdapter()

    init {
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(WX.BG)
        }

        // ── Title row: up button + address bar ───────────────────
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WX.BAR)
            setPadding(dp(8), dp(8), dp(12), dp(4))
        }
        val up = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            background = ripple(22)
            isClickable = true
            contentDescription = "Up"
            setOnClickListener { onUp() }
            addView(
                VectorIconView(ctx, VectorIconView.BACK),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            )
        }
        titleRow.addView(up)

        val addressBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(4) }
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(WX.CARD)
                setStroke(1, WX.CARD_BORDER)
            }
            setPadding(dp(12), 0, dp(6), 0)
        }
        addressBar.addView(VectorIconView(ctx, VectorIconView.PC).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
        crumbRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        crumbScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                .apply { leftMargin = dp(4) }
            addView(
                crumbRow,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        addressBar.addView(crumbScroll)
        titleRow.addView(addressBar)
        root.addView(titleRow)

        // ── Command bar: New folder … N items ────────────────────
        val commandBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WX.BAR)
            setPadding(dp(8), dp(0), dp(16), dp(6))
        }
        val newFolder = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
            )
            setPadding(dp(10), 0, dp(14), 0)
            background = ripple(8)
            isClickable = true
            setOnClickListener { onNewFolder() }
        }
        newFolder.addView(FileIconView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            setFolder(false)
        })
        newFolder.addView(TextView(ctx).apply {
            text = "+ New folder"
            setTextColor(WX.TEXT)
            textSize = 14f
            setPadding(dp(8), 0, 0, 0)
        })
        commandBar.addView(newFolder)
        commandBar.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        itemsLabel = TextView(ctx).apply {
            setTextColor(WX.DIM)
            textSize = 12f
        }
        commandBar.addView(itemsLabel)
        root.addView(commandBar)

        root.addView(View(ctx).apply {
            setBackgroundColor(WX.CARD_BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })

        // ── Exact path (kept from the old UI) ────────────────────
        pathCaption = TextView(ctx).apply {
            setTextColor(WX.DIM)
            textSize = 10.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        root.addView(pathCaption)

        // ── Drive card (header of the list, scrolls with it) ─────
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(WX.CARD)
                setStroke(1, WX.CARD_BORDER)
            }
        }
        val cardTop = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cardTop.addView(VectorIconView(ctx, VectorIconView.PC).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        })
        driveTitle = TextView(ctx).apply {
            text = "Internal storage"
            setTextColor(WX.TEXT)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, 0, 0)
        }
        cardTop.addView(driveTitle)
        card.addView(cardTop)
        usageBar = UsageBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)
            ).apply { topMargin = dp(10) }
        }
        card.addView(usageBar)
        driveSub = TextView(ctx).apply {
            setTextColor(WX.DIM)
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(driveSub)
        val cardHolder = FrameLayout(ctx).apply {
            setPadding(dp(12), dp(10), dp(12), dp(6))
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        emptyView = TextView(ctx).apply {
            text = "This folder is empty"
            setTextColor(WX.DIM)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, dp(48))
            layoutParams = absListLp()
            visibility = View.GONE
        }

        listView = ListView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            setBackgroundColor(WX.BG)
            isFastScrollEnabled = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            addHeaderView(cardHolder, null, false)
            addFooterView(emptyView, null, false)
            this.adapter = this@ExplorerUi.adapter
            setOnItemClickListener { parent, _, position, _ ->
                (parent.getItemAtPosition(position) as? File)?.let { f ->
                    if (f.isDirectory) onOpenFolder(f) else onItemClick(f)
                }
            }
            setOnItemLongClickListener { parent, _, position, _ ->
                (parent.getItemAtPosition(position) as? File)?.let { onItemMenu(it) }
                true
            }
        }
        root.addView(listView)
    }

    private fun absListLp(): android.widget.AbsListView.LayoutParams =
        android.widget.AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun ripple(radiusDp: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(WX.RIPPLE), null, mask)
    }

    // ── Public API ───────────────────────────────────────────────

    /** Show [dir] with its [files]. [storageRoot] is /storage/emulated/0. */
    fun show(dir: File, storageRoot: File, files: List<File>, free: Long, total: Long) {
        itemCountCache.clear()
        renderCrumbs(dir, storageRoot)

        val storagePath = storageRoot.absolutePath
        pathCaption.text = dir.absolutePath.replace(storagePath, "/storage/emulated/0")

        itemsLabel.text = if (files.size == 1) "1 item" else "${files.size} items"

        if (total > 0) {
            val used = (total - free).coerceAtLeast(0)
            val frac = used.toFloat() / total.toFloat()
            usageBar.fraction = frac
            usageBar.fillColor = if (frac > 0.9f) WX.WARN else WX.ACCENT
            driveSub.text = "${fmtSize(free)} free of ${fmtSize(total)}"
        } else {
            usageBar.fraction = 0f
            driveSub.text = ""
        }

        emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        adapter.data = files
        adapter.notifyDataSetChanged()
        listView.setSelection(0)
    }

    private fun renderCrumbs(dir: File, storageRoot: File) {
        crumbRow.removeAllViews()
        val crumbs = ArrayList<Pair<String, File>>()
        val rootPath = storageRoot.absolutePath
        val path = dir.absolutePath
        val under = path == rootPath || path.startsWith("$rootPath/")
        var cur: File
        val rest: String
        if (under) {
            cur = storageRoot
            crumbs.add("Internal storage" to cur)
            rest = path.removePrefix(rootPath)
        } else {
            cur = File("/")
            crumbs.add("/" to cur)
            rest = path
        }
        rest.split("/").filter { it.isNotEmpty() }.forEach { part ->
            cur = File(cur, part)
            crumbs.add(part to cur)
        }

        crumbs.forEachIndexed { i, (label, file) ->
            if (i > 0) {
                crumbRow.addView(TextView(ctx).apply {
                    text = "›"
                    setTextColor(WX.DIM)
                    textSize = 16f
                    gravity = Gravity.CENTER
                })
            }
            val last = i == crumbs.lastIndex
            crumbRow.addView(TextView(ctx).apply {
                text = label
                setTextColor(if (last) WX.TEXT else Color.parseColor("#CFCFCF"))
                textSize = 14f
                if (last) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL
                setSingleLine(true)
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                background = ripple(16)
                isClickable = true
                setOnClickListener { if (!last) onOpenFolder(file) }
            })
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // ── Row adapter ──────────────────────────────────────────────

    private inner class Row : LinearLayout(ctx) {
        val icon = FileIconView(ctx)
        val name = TextView(ctx)
        val meta = TextView(ctx)
        val chevron = TextView(ctx)
        val more = TextView(ctx)

        init {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(6), dp(4), dp(6))
            layoutParams = android.widget.AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = ripple(8)

            addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))

            val texts = LinearLayout(ctx).apply {
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
            meta.apply {
                setTextColor(WX.DIM)
                textSize = 12f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            texts.addView(name)
            texts.addView(meta)
            addView(texts)

            chevron.apply {
                text = "›"
                setTextColor(WX.DIM)
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(44))
            }
            addView(chevron)

            more.apply {
                text = "⋮"
                setTextColor(WX.DIM)
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                background = ripple(22)
                isClickable = true
                isFocusable = false
                contentDescription = "More options"
            }
            addView(more)
        }

        fun bind(file: File) {
            val hidden = file.name.startsWith(".")
            name.text = file.name
            val date = try { dateFmt.format(Date(file.lastModified())) } catch (e: Exception) { "" }
            if (file.isDirectory) {
                icon.setFolder(hidden)
                chevron.visibility = View.VISIBLE
                val count = itemCountCache.getOrPut(file.absolutePath) {
                    try { file.list()?.size ?: -1 } catch (e: Exception) { -1 }
                }
                val countText = when {
                    count < 0 -> ""
                    count == 1 -> "1 item"
                    else -> "$count items"
                }
                meta.text = if (countText.isEmpty()) date else "$date  •  $countText"
            } else {
                val ext = file.extension.lowercase(Locale.ROOT)
                icon.setFile(fileTypeColor(ext), ext.uppercase(Locale.ROOT).take(4), hidden)
                chevron.visibility = View.GONE
                val size = try { fmtSize(file.length()) } catch (e: Exception) { "" }
                meta.text = if (size.isEmpty()) date else "$date  •  $size"
            }
            more.setOnClickListener { onItemMenu(file) }
        }
    }

    private inner class FileAdapter : BaseAdapter() {
        var data: List<File> = emptyList()
        override fun getCount(): Int = data.size
        override fun getItem(position: Int): File = data[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? Row) ?: Row()
            row.bind(data[position])
            return row
        }
    }
}
