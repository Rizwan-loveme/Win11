package com.srizwan.win11

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

// =====================================================================
// START MENU DATA (Step 4.2/4.3): launchable apps + pinned list + hidden apps
// =====================================================================

class AppEntry(
    val label: String,
    val pkg: String,
    val cls: String,
    val icon: Drawable,
    /** Non-null = built-in app that opens inside Win11 itself (e.g. "browser"). */
    val section: String? = null
) {
    val key: String get() = "$pkg/$cls"
    val isBuiltIn: Boolean get() = section != null

    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setClassName(pkg, cls)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}

/** Vector-only icon for built-in apps (same path data as the taskbar icons, no emoji/bitmaps). */
class BuiltInIconDrawable(pathData: String, private val tint: Int) : Drawable() {
    private val path = PathParser.createPathFromPathData(pathData)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = tint }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        canvas.scale(s, s)
        path?.let { canvas.drawPath(it, fill) }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Loads launchable apps from PackageManager (background thread) and keeps the
 * ordered pinned list in SharedPreferences ("start_menu" / "pinned").
 * Step 4.3 (All apps) reuses [apps], [isPinned] and [pin]/[unpin].
 */
class AppRepository(private val ctx: Context) {

    private val pm: PackageManager = ctx.packageManager
    private val prefs = ctx.getSharedPreferences("start_menu", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    var apps: List<AppEntry> = emptyList()
        private set

    private val pinnedKeys: MutableList<String> = (prefs.getString(KEY_PINNED, null)
        ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()).toMutableList()

    private val hiddenKeys: MutableSet<String> = (prefs.getString(KEY_HIDDEN, null)
        ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()).toMutableSet()

    companion object {
        private const val KEY_PINNED = "pinned"
        private const val KEY_HIDDEN = "hidden"
        private const val DEFAULT_COUNT = 12
        private const val KEY_BROWSER_SEEDED = "builtin_browser_seeded"
        private const val BUILTIN_CLS_BROWSER = "builtin.browser"
        private const val KEY_PHONE_SEEDED = "builtin_phone_seeded"
        private const val KEY_FILES_SEEDED = "builtin_files_seeded"
        private const val KEY_SMS_SEEDED = "builtin_sms_seeded"
        private const val KEY_NOTIF_SEEDED = "builtin_notif_seeded"
        private const val BUILTIN_CLS_NOTIF = "builtin.notifications"

        // Same bell path as VectorIconView.BELL
        private const val NOTIF_PATH =
            "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
        private const val BUILTIN_CLS_SMS = "builtin.sms"

        // Same bubble path as VectorIconView.MESSAGES
        private const val SMS_PATH =
            "M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2z"
        private const val BUILTIN_CLS_FILES = "builtin.files"

        // Same folder path as VectorIconView.FILES
        private const val FILES_PATH =
            "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z"
        private const val BUILTIN_CLS_PHONE = "builtin.phone"

        // Same handset path as VectorIconView.PHONE
        private const val PHONE_PATH =
            "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"

        // Same globe path as VectorIconView.BROWSER
        private const val BROWSER_PATH =
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM11,19.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79L9,15v1c0,1.1 0.9,2 2,2v1.93zM17.9,17.39c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1H8v-2h2c0.55,0 1,-0.45 1,-1V7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41 0,2.08 -0.8,3.97 -2.1,5.39z"

        private val PREFERRED = listOf(
            "com.android.chrome",
            "com.google.android.apps.messaging",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.contacts",
            "com.google.android.contacts",
            "com.android.camera",
            "com.android.camera2",
            "com.google.android.GoogleCamera",
            "com.google.android.apps.photos",
            "com.google.android.gm",
            "com.google.android.apps.maps",
            "com.google.android.youtube",
            "com.android.vending",
            "com.android.settings"
        )
    }

    /** Reloads the app list in the background; [onDone] runs on the main thread. */
    fun load(onDone: (changed: Boolean) -> Unit) {
        io.execute {
            val list = try { query() } catch (e: Exception) { null }
            main.post {
                if (list == null) return@post
                val changed = list.map { it.key } != apps.map { it.key }
                apps = list
                if (!prefs.contains(KEY_PINNED)) {
                    pinnedKeys.clear()
                    pinnedKeys.addAll(defaultPins(list))
                    save()
                    prefs.edit().putBoolean(KEY_BROWSER_SEEDED, true)
                        .putBoolean(KEY_PHONE_SEEDED, true)
                        .putBoolean(KEY_FILES_SEEDED, true)
                        .putBoolean(KEY_SMS_SEEDED, true)
                        .putBoolean(KEY_NOTIF_SEEDED, true).apply()
                    onDone(true)
                } else {
                    // Existing installs: pin built-in Browser once (unpinning later is respected)
                    var seeded = false
                    val bk = "${ctx.packageName}/$BUILTIN_CLS_BROWSER"
                    if (!prefs.getBoolean(KEY_BROWSER_SEEDED, false)) {
                        if (!pinnedKeys.contains(bk)) { pinnedKeys.add(0, bk); save() }
                        prefs.edit().putBoolean(KEY_BROWSER_SEEDED, true).apply()
                        seeded = true
                    }
                    // Phone built-in: pinned once too (right after Browser if it is pinned)
                    val pk = "${ctx.packageName}/$BUILTIN_CLS_PHONE"
                    if (!prefs.getBoolean(KEY_PHONE_SEEDED, false)) {
                        if (!pinnedKeys.contains(pk)) {
                            val at = pinnedKeys.indexOf(bk)
                            pinnedKeys.add(if (at >= 0) at + 1 else 0, pk); save()
                        }
                        prefs.edit().putBoolean(KEY_PHONE_SEEDED, true).apply()
                        seeded = true
                    }
                    // Files built-in: pinned once, at the front (taskbar order: Files, Browser, Phone)
                    val fk = "${ctx.packageName}/$BUILTIN_CLS_FILES"
                    if (!prefs.getBoolean(KEY_FILES_SEEDED, false)) {
                        if (!pinnedKeys.contains(fk)) { pinnedKeys.add(0, fk); save() }
                        prefs.edit().putBoolean(KEY_FILES_SEEDED, true).apply()
                        seeded = true
                    }
                    // Messages built-in: pinned once, after Phone (Files, Browser, Phone, Messages)
                    val mk = "${ctx.packageName}/$BUILTIN_CLS_SMS"
                    if (!prefs.getBoolean(KEY_SMS_SEEDED, false)) {
                        if (!pinnedKeys.contains(mk)) {
                            val at = pinnedKeys.indexOf(pk)
                            pinnedKeys.add(if (at >= 0) at + 1 else pinnedKeys.size.coerceAtMost(3), mk); save()
                        }
                        prefs.edit().putBoolean(KEY_SMS_SEEDED, true).apply()
                        seeded = true
                    }
                    // Notifications built-in: pinned once, after Messages
                    val nk = "${ctx.packageName}/$BUILTIN_CLS_NOTIF"
                    if (!prefs.getBoolean(KEY_NOTIF_SEEDED, false)) {
                        if (!pinnedKeys.contains(nk)) {
                            val at = pinnedKeys.indexOf(mk)
                            pinnedKeys.add(if (at >= 0) at + 1 else pinnedKeys.size.coerceAtMost(4), nk); save()
                        }
                        prefs.edit().putBoolean(KEY_NOTIF_SEEDED, true).apply()
                        seeded = true
                    }
                    onDone(if (seeded) true else changed)
                }
            }
        }
    }

    private fun builtInApps(): List<AppEntry> = listOf(
        AppEntry(
            "Files", ctx.packageName, BUILTIN_CLS_FILES,
            BuiltInIconDrawable(FILES_PATH, Color.parseColor("#FFC83D")),
            section = "files"
        ),
        AppEntry(
            "Browser", ctx.packageName, BUILTIN_CLS_BROWSER,
            BuiltInIconDrawable(BROWSER_PATH, Color.parseColor("#4CC2FF")),
            section = "browser"
        ),
        AppEntry(
            "Notifications", ctx.packageName, BUILTIN_CLS_NOTIF,
            BuiltInIconDrawable(NOTIF_PATH, Color.parseColor("#FFB900")),
            section = "notifications"
        ),
        AppEntry(
            "Messages", ctx.packageName, BUILTIN_CLS_SMS,
            BuiltInIconDrawable(SMS_PATH, Color.parseColor("#7AB8FF")),
            section = "sms"
        ),
        AppEntry(
            "Phone", ctx.packageName, BUILTIN_CLS_PHONE,
            BuiltInIconDrawable(PHONE_PATH, Color.parseColor("#6CCB5F")),
            section = "dialer"
        )
    )

    @Suppress("DEPRECATION")
    private fun query(): List<AppEntry> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val res = if (Build.VERSION.SDK_INT >= 33)
            pm.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0L))
        else pm.queryIntentActivities(i, 0)
        return res.asSequence()
            .filter { it.activityInfo != null && it.activityInfo.packageName != ctx.packageName }
            .map {
                AppEntry(
                    it.loadLabel(pm).toString(),
                    it.activityInfo.packageName,
                    it.activityInfo.name,
                    it.loadIcon(pm)
                )
            }
            .plus(builtInApps())
            .distinctBy { it.key }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    private fun defaultPins(list: List<AppEntry>): List<String> {
        val out = LinkedHashSet<String>()
        list.firstOrNull { it.isBuiltIn && it.section == "files" }?.let { out.add(it.key) }
        list.firstOrNull { it.isBuiltIn && it.section == "browser" }?.let { out.add(it.key) }
        list.firstOrNull { it.isBuiltIn && it.section == "dialer" }?.let { out.add(it.key) }
        list.firstOrNull { it.isBuiltIn && it.section == "sms" }?.let { out.add(it.key) }
        list.firstOrNull { it.isBuiltIn && it.section == "notifications" }?.let { out.add(it.key) }
        for (p in PREFERRED) {
            list.firstOrNull { it.pkg == p }?.let { out.add(it.key) }
            if (out.size >= DEFAULT_COUNT) break
        }
        for (a in list) {
            if (out.size >= DEFAULT_COUNT) break
            out.add(a.key)
        }
        return out.toList()
    }

    private fun save() {
        prefs.edit().putString(KEY_PINNED, pinnedKeys.joinToString("\n")).apply()
    }

    private fun saveHidden() {
        prefs.edit().putString(KEY_HIDDEN, hiddenKeys.joinToString("\n")).apply()
    }

    /** Pinned apps, in order, excluding hidden ones. */
    fun pinned(): List<AppEntry> {
        val map = apps.associateBy { it.key }
        return pinnedKeys.mapNotNull { map[it] }.filter { it.key !in hiddenKeys }
    }

    // ── Hidden apps (Step 4.3) ───────────────────────────────────
    fun isHidden(e: AppEntry): Boolean = hiddenKeys.contains(e.key)

    /** Hiding also removes the app from Pinned. */
    fun hide(e: AppEntry) {
        unpin(e)
        if (hiddenKeys.add(e.key)) saveHidden()
    }

    fun unhide(e: AppEntry) {
        if (hiddenKeys.remove(e.key)) saveHidden()
    }

    fun visibleApps(): List<AppEntry> = apps.filter { it.key !in hiddenKeys }

    fun hiddenApps(): List<AppEntry> = apps.filter { it.key in hiddenKeys }

    fun isPinned(e: AppEntry): Boolean = pinnedKeys.contains(e.key)

    fun pin(e: AppEntry) {
        if (!pinnedKeys.contains(e.key)) { pinnedKeys.add(e.key); save() }
    }

    fun unpin(e: AppEntry) {
        if (pinnedKeys.remove(e.key)) save()
    }

    fun togglePin(e: AppEntry): Boolean {
        if (isPinned(e)) unpin(e) else pin(e)
        return isPinned(e)
    }

    fun moveToFront(e: AppEntry) {
        if (pinnedKeys.remove(e.key)) { pinnedKeys.add(0, e.key); save() }
    }
}
