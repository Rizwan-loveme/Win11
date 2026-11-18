package com.srizwan.win11

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// =====================================================================
// 1DM-style download engine
//  • multi-connection (segmented) downloads with pause / resume
//  • survives app restarts (progress is persisted)
//  • queue with N simultaneous downloads
//  • blob: / data: URL support (blob data is streamed out of the page)
//  • foreground service + progress notification
// =====================================================================

internal fun dlFmtSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val dg = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, dg.toDouble())) + " " + units[dg]
}

internal fun dlFmtEta(seconds: Long): String {
    if (seconds < 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

class DlTask(val id: Long) {
    var url = ""
    var fileName = ""
    var mime = ""
    var cookie = ""
    var ua = ""
    var referer = ""
    var isBlob = false
    @Volatile var status = DlEngine.QUEUED
    @Volatile var total = -1L
    val downloaded = AtomicLong(0)
    @Volatile var segStart = LongArray(0)
    @Volatile var segEnd = LongArray(0)
    @Volatile var segPos = LongArray(0)
    @Volatile var rangeOk = false
    @Volatile var error = ""
    var createdAt = System.currentTimeMillis()
    var savedUri = ""
    var savedPath = ""
    @Volatile var speed = 0L
    @Volatile var gen = 0

    // transient
    var lastBytes = 0L
    var lastTime = 0L
    @Volatile var finalUrl = ""
    val conns: MutableList<HttpURLConnection> = Collections.synchronizedList(ArrayList())
    var blobOut: FileOutputStream? = null

    val connectionCount: Int get() = segStart.size

    fun etaSeconds(): Long {
        if (total <= 0 || speed <= 0) return -1
        return (total - downloaded.get()) / speed
    }
}

object DlEngine {
    const val QUEUED = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val DONE = 3
    const val FAILED = 4
    const val SAVING = 5
    const val CANCELED = 6

    const val NOTIF_ONGOING = 7001
    private const val CH_PROGRESS = "dl_progress"
    private const val CH_DONE = "dl_done"
    private const val MIN_SEGMENT = 512L * 1024L

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    @Volatile private var inited = false

    val isReady: Boolean get() = inited

    val tasks = CopyOnWriteArrayList<DlTask>()
    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val pendingUi = AtomicBoolean(false)
    private var nextId = 1L
    private var serviceOn = false
    private var lastNotif = 0L
    private var lastPersist = 0L

    var connections: Int
        get() = prefs.getInt("conns", 4)
        set(v) { prefs.edit().putInt("conns", v.coerceIn(1, 8)).apply() }

    var maxParallel: Int
        get() = prefs.getInt("parallel", 3)
        set(v) { prefs.edit().putInt("parallel", v.coerceIn(1, 5)).apply(); pump() }

    // ── Init / persistence ──────────────────────────────────────

    @Synchronized
    fun init(ctx: Context) {
        if (inited) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("dl_engine", Context.MODE_PRIVATE)
        try {
            val arr = JSONArray(prefs.getString("tasks", "[]"))
            for (i in 0 until arr.length()) {
                try { fromJson(arr.getJSONObject(i))?.let { tasks.add(it); nextId = maxOf(nextId, it.id + 1) } }
                catch (e: Exception) { }
            }
        } catch (e: Exception) { }
        inited = true
    }

    private fun toJson(t: DlTask): JSONObject = JSONObject().apply {
        put("id", t.id); put("url", t.url); put("name", t.fileName); put("mime", t.mime)
        put("cookie", t.cookie); put("ua", t.ua); put("ref", t.referer)
        put("blob", t.isBlob); put("status", t.status); put("total", t.total)
        put("range", t.rangeOk); put("created", t.createdAt)
        put("uri", t.savedUri); put("path", t.savedPath); put("err", t.error)
        put("ss", JSONArray(t.segStart.toList()))
        put("se", JSONArray(t.segEnd.toList()))
        put("sp", JSONArray(t.segPos.toList()))
    }

    private fun longs(a: JSONArray?): LongArray =
        if (a == null) LongArray(0) else LongArray(a.length()) { a.getLong(it) }

    private fun fromJson(o: JSONObject): DlTask? {
        val t = DlTask(o.getLong("id"))
        t.url = o.optString("url"); t.fileName = o.optString("name"); t.mime = o.optString("mime")
        t.cookie = o.optString("cookie"); t.ua = o.optString("ua"); t.referer = o.optString("ref")
        t.isBlob = o.optBoolean("blob"); t.total = o.optLong("total", -1)
        t.rangeOk = o.optBoolean("range"); t.createdAt = o.optLong("created", System.currentTimeMillis())
        t.savedUri = o.optString("uri"); t.savedPath = o.optString("path"); t.error = o.optString("err")
        t.segStart = longs(o.optJSONArray("ss")); t.segEnd = longs(o.optJSONArray("se"))
        t.segPos = longs(o.optJSONArray("sp"))
        var st = o.optInt("status", FAILED)
        if (st == DONE) {
            t.downloaded.set(if (t.total > 0) t.total else 0)
        } else {
            if (t.isBlob) { st = FAILED; t.error = "Interrupted" }
            else if (st == RUNNING || st == QUEUED || st == SAVING) st = PAUSED
            recalc(t)
        }
        t.status = st
        return t
    }

    private fun recalc(t: DlTask) {
        var s = 0L
        val n = minOf(t.segStart.size, t.segPos.size)
        for (i in 0 until n) s += maxOf(0L, t.segPos[i] - t.segStart[i])
        t.downloaded.set(s)
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            for (t in tasks) arr.put(toJson(t))
            prefs.edit().putString("tasks", arr.toString()).apply()
        } catch (e: Exception) { }
    }

    // ── Listeners ───────────────────────────────────────────────

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun changed() {
        if (pendingUi.compareAndSet(false, true)) {
            main.postDelayed({
                pendingUi.set(false)
                for (l in listeners) try { l() } catch (e: Exception) { }
                updateNotification()
            }, 300)
        }
    }

    fun find(id: Long): DlTask? = tasks.firstOrNull { it.id == id }

    // ── Public API ──────────────────────────────────────────────

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    @Synchronized
    fun enqueue(
        url: String, fileName: String, mime: String, cookie: String,
        ua: String, referer: String, totalHint: Long = -1L
    ): DlTask {
        val t = DlTask(nextId++)
        t.url = url; t.fileName = fileName
        t.mime = if (mime.isBlank() || mime.endsWith("/*")) guessMime(fileName) else mime
        t.cookie = cookie; t.ua = ua; t.referer = referer
        t.total = totalHint
        t.status = QUEUED
        tasks.add(t)
        persist(); changed(); pump()
        return t
    }

    fun pause(id: Long) {
        val t = find(id) ?: return
        if (t.status != RUNNING && t.status != QUEUED) return
        if (t.isBlob) return
        t.status = PAUSED
        t.speed = 0
        closeConns(t)
        persist(); changed(); pump()
    }

    fun resume(id: Long) {
        val t = find(id) ?: return
        if (t.status != PAUSED && t.status != FAILED) return
        if (t.isBlob) return
        t.error = ""
        t.status = QUEUED
        persist(); changed(); pump()
    }

    /** Cancels a download and removes its temp data. Finished files are not touched. */
    fun cancel(id: Long) {
        val t = find(id) ?: return
        val wasDone = t.status == DONE
        t.status = CANCELED
        closeConns(t)
        try { t.blobOut?.close() } catch (e: Exception) { }
        tasks.remove(t)
        if (!wasDone) try { tempFile(t).delete() } catch (e: Exception) { }
        persist(); changed(); pump()
    }

    fun deleteFinishedFile(id: Long) {
        val t = find(id) ?: return
        try {
            if (t.savedUri.isNotEmpty()) app.contentResolver.delete(android.net.Uri.parse(t.savedUri), null, null)
            else if (t.savedPath.startsWith("/")) File(t.savedPath).delete()
        } catch (e: Exception) { }
        cancel(id)
    }

    fun clearFinished() {
        for (t in tasks.toList()) if (t.status == DONE || t.status == FAILED) cancel(t.id)
    }

    fun activeCount(): Int = tasks.count { it.status == RUNNING || it.status == QUEUED || it.status == SAVING }

    private fun closeConns(t: DlTask) {
        synchronized(t.conns) {
            for (c in t.conns) try { c.disconnect() } catch (e: Exception) { }
            t.conns.clear()
        }
    }

    private fun tempFile(t: DlTask): File {
        val dir = app.getExternalFilesDir("dl") ?: File(app.filesDir, "dl")
        dir.mkdirs()
        return File(dir, "${t.id}.part")
    }

    // ── Queue ───────────────────────────────────────────────────

    @Synchronized
    private fun pump() {
        var running = tasks.count { it.status == RUNNING && !it.isBlob }
        for (t in tasks) {
            if (running >= maxParallel) break
            if (t.status == QUEUED && !t.isBlob) {
                t.status = RUNNING
                t.speed = 0; t.lastTime = 0L; t.lastBytes = t.downloaded.get()
                t.gen++
                val g = t.gen
                running++
                pool.execute { runTask(t, g) }
            }
        }
    }

    private fun ok(t: DlTask, g: Int) = t.status == RUNNING && t.gen == g

    private fun fail(t: DlTask, g: Int, msg: String) {
        synchronized(t) {
            if (t.gen == g && (t.status == RUNNING || t.status == SAVING)) {
                t.status = FAILED
                t.error = msg
            }
        }
    }

    private fun runTask(t: DlTask, g: Int) {
        try {
            prepare(t, g)
            if (!ok(t, g)) return
            download(t, g)
            if (!ok(t, g)) return
            finish(t)
        } catch (e: Exception) {
            fail(t, g, e.message ?: e.javaClass.simpleName)
        } finally {
            if (t.gen == g) closeConns(t)
            if (t.gen == g && (t.status == DONE || t.status == FAILED)) notifyTerminal(t)
            persist(); changed(); pump()
        }
    }

    // ── HTTP ────────────────────────────────────────────────────

    private fun open(t: DlTask, start: Long, end: Long, ranged: Boolean): HttpURLConnection {
        var url = t.finalUrl.ifBlank { t.url }
        for (hop in 0 until 8) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty(
                "User-Agent",
                t.ua.ifBlank { "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" }
            )
            if (t.cookie.isNotBlank()) c.setRequestProperty("Cookie", t.cookie)
            if (t.referer.isNotBlank()) c.setRequestProperty("Referer", t.referer)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (ranged) c.setRequestProperty("Range", "bytes=$start-" + (if (end >= 0) end.toString() else ""))
            t.conns.add(c)
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")
                t.conns.remove(c)
                c.disconnect()
                if (loc.isNullOrBlank()) throw IOException("Bad redirect")
                url = URL(URL(url), loc).toString()
                continue
            }
            t.finalUrl = url
            return c
        }
        throw IOException("Too many redirects")
    }

    private fun probe(t: DlTask) {
        val c = open(t, 0, 0, true)
        try {
            val code = c.responseCode
            if (code == 206) {
                val cr = c.getHeaderField("Content-Range") ?: ""
                val total = cr.substringAfter('/', "").trim().toLongOrNull() ?: -1L
                t.total = total
                t.rangeOk = total > 0
            } else if (code in 200..299) {
                t.total = c.contentLengthLong
                t.rangeOk = false
            } else {
                throw IOException("HTTP $code")
            }
        } finally {
            t.conns.remove(c)
            try { c.disconnect() } catch (e: Exception) { }
        }
    }

    private fun prepare(t: DlTask, g: Int) {
        val temp = tempFile(t)
        if (t.segStart.isEmpty()) {
            probe(t)
            if (!ok(t, g)) return
            val n = if (t.rangeOk && t.total >= 2 * MIN_SEGMENT)
                minOf(connections.toLong(), t.total / MIN_SEGMENT).toInt().coerceAtLeast(1) else 1
            val ss = LongArray(n); val se = LongArray(n); val sp = LongArray(n)
            if (t.total > 0) {
                val size = t.total / n
                for (i in 0 until n) {
                    ss[i] = i * size
                    se[i] = if (i == n - 1) t.total - 1 else ss[i] + size - 1
                    sp[i] = ss[i]
                }
            } else {
                ss[0] = 0; se[0] = -1; sp[0] = 0
            }
            t.segStart = ss; t.segEnd = se; t.segPos = sp
            t.downloaded.set(0)
            try { temp.delete() } catch (e: Exception) { }
        } else {
            // resuming: make sure the partial file is still there
            val progressed = t.downloaded.get() > 0
            if (!temp.exists() || (progressed && !t.rangeOk)) {
                for (i in t.segPos.indices) t.segPos[i] = t.segStart[i]
                t.downloaded.set(0)
                try { temp.delete() } catch (e: Exception) { }
            }
        }
        t.lastBytes = t.downloaded.get(); t.lastTime = 0L
        persist(); changed()
    }

    private fun download(t: DlTask, g: Int) {
        val temp = tempFile(t)
        RandomAccessFile(temp, "rw").use { r ->
            if (t.total > 0 && r.length() != t.total) r.setLength(t.total)
        }
        val n = t.segStart.size
        val threads = ArrayList<Thread>()
        for (i in 0 until n) {
            val th = Thread {
                try { runSegment(t, g, i, temp) }
                catch (e: Exception) { fail(t, g, e.message ?: e.javaClass.simpleName) }
            }
            th.isDaemon = true
            threads.add(th)
            th.start()
        }
        while (threads.any { it.isAlive }) {
            try { Thread.sleep(400) } catch (e: InterruptedException) { }
            tick(t)
        }
        if (!ok(t, g)) return
        for (i in 0 until n) {
            if (t.segEnd[i] >= 0 && t.segPos[i] <= t.segEnd[i]) throw IOException("Incomplete download")
        }
    }

    private fun runSegment(t: DlTask, g: Int, i: Int, temp: File) {
        val raf = RandomAccessFile(temp, "rw")
        val buf = ByteArray(64 * 1024)
        var attempt = 0
        try {
            while (ok(t, g)) {
                val end = t.segEnd[i]
                var pos = t.segPos[i]
                if (end >= 0 && pos > end) break
                var conn: HttpURLConnection? = null
                try {
                    conn = open(t, pos, end, t.rangeOk)
                    val code = conn.responseCode
                    if (t.rangeOk && code != 206) throw IOException("Server rejected range (HTTP $code)")
                    if (!t.rangeOk && code !in 200..299) throw IOException("HTTP $code")
                    val ins = conn.inputStream
                    raf.seek(pos)
                    while (ok(t, g)) {
                        var want = buf.size
                        if (end >= 0) {
                            val rem = end - pos + 1
                            if (rem <= 0) break
                            if (rem < want) want = rem.toInt()
                        }
                        val n = ins.read(buf, 0, want)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        pos += n
                        t.segPos[i] = pos
                        t.downloaded.addAndGet(n.toLong())
                        attempt = 0
                    }
                    if (!ok(t, g)) return
                    if (end < 0) {
                        t.segEnd[i] = pos - 1
                        t.total = pos
                        break
                    }
                    if (pos <= end) throw IOException("Connection closed early")
                    break
                } catch (e: IOException) {
                    if (!ok(t, g)) return
                    attempt++
                    if (attempt > 6) throw e
                    if (!t.rangeOk && t.segPos[i] > t.segStart[i]) {
                        t.downloaded.addAndGet(-(t.segPos[i] - t.segStart[i]))
                        t.segPos[i] = t.segStart[i]
                        raf.setLength(0)
                    }
                    try { Thread.sleep(800L * attempt) } catch (ie: InterruptedException) { }
                } finally {
                    if (conn != null) {
                        t.conns.remove(conn)
                        try { conn.disconnect() } catch (e: Exception) { }
                    }
                }
            }
        } finally {
            try { raf.close() } catch (e: Exception) { }
        }
    }

    private fun tick(t: DlTask) {
        val now = SystemClock.elapsedRealtime()
        val d = t.downloaded.get()
        if (t.lastTime == 0L) { t.lastTime = now; t.lastBytes = d }
        val dt = now - t.lastTime
        if (dt >= 800) {
            val inst = (d - t.lastBytes) * 1000 / dt
            t.speed = if (t.speed == 0L) inst else (t.speed * 0.6 + inst * 0.4).toLong()
            t.lastBytes = d; t.lastTime = now
        }
        if (now - lastPersist > 3000) { lastPersist = now; persist() }
        changed()
    }

    // ── Save finished file to Downloads ────────────────────────

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    private fun finish(t: DlTask) {
        t.status = SAVING
        t.speed = 0
        changed()
        val temp = tempFile(t)
        val name = t.fileName.ifBlank { "download_${t.id}" }
        val mime = t.mime.ifBlank { guessMime(name) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val cr = app.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IOException("Cannot create file in Downloads")
            cr.openOutputStream(uri)?.use { out ->
                temp.inputStream().use { it.copyTo(out, 128 * 1024) }
            } ?: throw IOException("Cannot write file")
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            cr.update(uri, cv, null, null)
            try {
                cr.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) t.fileName = it.getString(0) ?: name
                }
            } catch (e: Exception) { }
            t.savedUri = uri.toString()
            t.savedPath = "Download/" + t.fileName
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dest = uniqueFile(dir, name)
            temp.copyTo(dest, true)
            t.fileName = dest.name
            t.savedPath = dest.absolutePath
            try {
                MediaScannerConnection.scanFile(app, arrayOf(dest.absolutePath), arrayOf(mime)) { _, uri ->
                    if (uri != null) { t.savedUri = uri.toString(); persist(); changed() }
                }
            } catch (e: Exception) { }
        }
        try { temp.delete() } catch (e: Exception) { }
        t.downloaded.set(if (t.total > 0) t.total else t.downloaded.get())
        t.status = DONE
    }

    // ── Blob / data support (bytes are pushed in by the page) ──

    @Synchronized
    fun beginBlob(fileName: String, mime: String, size: Long): DlTask {
        val t = DlTask(nextId++)
        t.fileName = fileName
        t.mime = if (mime.isBlank()) guessMime(fileName) else mime
        t.isBlob = true
        t.total = size
        t.status = RUNNING
        t.lastTime = 0L
        try { tempFile(t).delete() } catch (e: Exception) { }
        try { t.blobOut = FileOutputStream(tempFile(t)) } catch (e: Exception) {
            t.status = FAILED; t.error = e.message ?: "Cannot create file"
        }
        tasks.add(t)
        persist(); changed()
        return t
    }

    fun blobChunk(t: DlTask, bytes: ByteArray): Boolean {
        if (t.status != RUNNING) {
            try { t.blobOut?.close() } catch (e: Exception) { }
            return false
        }
        return try {
            t.blobOut?.write(bytes)
            t.downloaded.addAndGet(bytes.size.toLong())
            if (t.total < t.downloaded.get()) t.total = t.downloaded.get()
            tick(t)
            true
        } catch (e: Exception) {
            t.status = FAILED; t.error = e.message ?: "Write failed"
            try { t.blobOut?.close() } catch (x: Exception) { }
            notifyTerminal(t); persist(); changed()
            false
        }
    }

    fun blobDone(t: DlTask) {
        try { t.blobOut?.close() } catch (e: Exception) { }
        t.blobOut = null
        if (t.status != RUNNING) return
        pool.execute {
            try { finish(t) } catch (e: Exception) {
                t.status = FAILED; t.error = e.message ?: "Save failed"
            }
            notifyTerminal(t); persist(); changed()
        }
    }

    fun blobFail(t: DlTask, msg: String) {
        try { t.blobOut?.close() } catch (e: Exception) { }
        if (t.status == RUNNING) {
            t.status = FAILED; t.error = msg
            try { tempFile(t).delete() } catch (e: Exception) { }
            notifyTerminal(t)
        }
        persist(); changed()
    }

    fun saveBytes(fileName: String, mime: String, bytes: ByteArray): DlTask {
        val t = beginBlob(fileName, mime, bytes.size.toLong())
        if (blobChunk(t, bytes)) blobDone(t)
        return t
    }

    // ── Notifications / service ────────────────────────────────

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Download finished", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openIntent(): PendingIntent {
        val i = Intent(app, MainActivity::class.java)
            .putExtra("open_downloads", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(app, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun buildOngoing(): Notification {
        ensureChannels()
        val active = tasks.filter { it.status == RUNNING || it.status == QUEUED || it.status == SAVING }
        var total = 0L; var done = 0L; var speed = 0L
        for (t in active) {
            if (t.total > 0) { total += t.total; done += t.downloaded.get() }
            speed += t.speed
        }
        val title = when {
            active.isEmpty() -> "Downloads"
            active.size == 1 -> active[0].fileName
            else -> "${active.size} downloads"
        }
        val text = if (total > 0) "${dlFmtSize(done)} / ${dlFmtSize(total)}" + (if (speed > 0) "  •  ${dlFmtSize(speed)}/s" else "")
        else if (speed > 0) "${dlFmtSize(speed)}/s" else "Preparing…"
        val b = NotificationCompat.Builder(app, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent())
        if (total > 0) b.setProgress(100, (done * 100 / total).toInt().coerceIn(0, 100), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun updateNotification() {
        if (!inited) return
        try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (activeCount() == 0) {
                if (serviceOn) { app.stopService(Intent(app, DownloadService::class.java)); serviceOn = false }
                nm.cancel(NOTIF_ONGOING)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (serviceOn && now - lastNotif < 1000) return
            lastNotif = now
            if (!serviceOn) {
                try {
                    ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
                    serviceOn = true
                } catch (e: Exception) { }
            }
            nm.notify(NOTIF_ONGOING, buildOngoing())
        } catch (e: Exception) { }
    }

    private fun notifyTerminal(t: DlTask) {
        try {
            ensureChannels()
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ok = t.status == DONE
            val n = NotificationCompat.Builder(app, CH_DONE)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(t.fileName)
                .setContentText(if (ok) "Download complete" else "Download failed: ${t.error}")
                .setAutoCancel(true)
                .setContentIntent(openIntent())
                .build()
            nm.notify(10000 + (t.id % 80000).toInt(), n)
        } catch (e: Exception) { }
    }
}

// ---------------------------------------------------------------------
// Foreground service – keeps downloads alive while the app is in background
// ---------------------------------------------------------------------

class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!DlEngine.isReady) { stopSelf(); return START_NOT_STICKY }
        try {
            val n = DlEngine.buildOngoing()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(DlEngine.NOTIF_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(DlEngine.NOTIF_ONGOING, n)
        } catch (e: Exception) { stopSelf() }
        return START_NOT_STICKY
    }
}

// ---------------------------------------------------------------------
// JS bridge used to stream blob: contents out of the web page
// ---------------------------------------------------------------------

class BlobBridge {
    @JavascriptInterface
    fun chunk(id: String, b64: String): Boolean {
        val t = DlEngine.find(id.toLongOrNull() ?: return false) ?: return false
        return try { DlEngine.blobChunk(t, Base64.decode(b64, Base64.DEFAULT)) } catch (e: Exception) {
            DlEngine.blobFail(t, "Decode error"); false
        }
    }

    @JavascriptInterface
    fun done(id: String) {
        val t = DlEngine.find(id.toLongOrNull() ?: return) ?: return
        DlEngine.blobDone(t)
    }

    @JavascriptInterface
    fun fail(id: String, msg: String) {
        val t = DlEngine.find(id.toLongOrNull() ?: return) ?: return
        DlEngine.blobFail(t, msg)
    }
}

/** JS injected into every page: remembers blob objects + download names. */
internal const val BLOB_HOOK_JS = """
(function(){
  if (window.__w11hook) return; window.__w11hook = true;
  window.__w11names = {}; window.__w11types = {}; window.__w11blobs = {};
  try {
    var oc = URL.createObjectURL, orv = URL.revokeObjectURL;
    URL.createObjectURL = function(o){
      var u = oc.apply(this, arguments);
      try { window.__w11blobs[u] = o; window.__w11types[u] = {type: o.type || '', size: o.size || 0}; } catch(e){}
      return u;
    };
    URL.revokeObjectURL = function(u){
      setTimeout(function(){ try{ delete window.__w11blobs[u]; }catch(e){} }, 120000);
      return orv.apply(this, arguments);
    };
    var ac = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function(){
      try { if (this.download && this.href) window.__w11names[this.href] = this.download; } catch(e){}
      return ac.apply(this, arguments);
    };
    document.addEventListener('click', function(e){
      try {
        var a = e.target && e.target.closest ? e.target.closest('a') : null;
        if (a && a.download && a.href) window.__w11names[a.href] = a.download;
      } catch(x){}
    }, true);
  } catch(e){}
})();
"""

/** JS that streams a blob to Android in 256 KB chunks. */
internal fun blobTransferJs(blobUrlJson: String, taskId: Long): String = """
(function(u, id){
  function go(blob){
    var CH = 262144, off = 0;
    function next(){
      if (off >= blob.size) { AndroidBlobDl.done(id); return; }
      var end = Math.min(off + CH, blob.size);
      var r = new FileReader();
      r.onload = function(){
        var s = r.result, i = s.indexOf(',');
        var ok = AndroidBlobDl.chunk(id, s.substring(i + 1));
        off = end;
        if (ok === false) return;
        setTimeout(next, 0);
      };
      r.onerror = function(){ AndroidBlobDl.fail(id, 'read error'); };
      r.readAsDataURL(blob.slice(off, end));
    }
    next();
  }
  var b = (window.__w11blobs || {})[u];
  if (b && b.slice) { go(b); }
  else {
    fetch(u).then(function(r){ return r.blob(); }).then(go)
      .catch(function(e){ AndroidBlobDl.fail(id, String(e)); });
  }
})($blobUrlJson, "$taskId");
"""
