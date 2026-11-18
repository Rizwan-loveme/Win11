package com.srizwan.win11

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast

// =====================================================================
// NOTIFICATION SETTINGS: which chat apps may notify (on/off per app)
// =====================================================================

class NotifApp(
    val id: String,
    val label: String,
    val packages: Set<String>,
    val color: Int
)

object NotifPrefs {

    private const val FILE = "notif_settings"
    private const val KEY_MASTER = "master"
    private const val KEY_SOUND = "sound"

    /** Every package the listener watches belongs to exactly one entry here. */
    val apps: List<NotifApp> = listOf(
        NotifApp("whatsapp", "WhatsApp",
            setOf("com.whatsapp", "com.gbwhatsapp"), 0xFF25D366.toInt()),
        NotifApp("whatsapp_business", "WhatsApp Business",
            setOf("com.whatsapp.w4b", "com.whatsapp.business"), 0xFF128C7E.toInt()),
        NotifApp("messenger", "Messenger",
            setOf("com.facebook.orca"), 0xFF0084FF.toInt()),
        NotifApp("messenger_lite", "Messenger Lite",
            setOf("com.facebook.mlite", "com.facebook.messenger.lite"), 0xFF4CC2FF.toInt()),
        NotifApp("telegram", "Telegram",
            setOf("org.telegram.messenger", "org.telegram.messenger.web"), 0xFF2AABEE.toInt())
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun appFor(pkg: String): NotifApp? = apps.firstOrNull { pkg in it.packages }

    fun isMasterOn(ctx: Context): Boolean = p(ctx).getBoolean(KEY_MASTER, true)
    fun setMaster(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean(KEY_MASTER, on).apply() }

    fun isAppOn(ctx: Context, id: String): Boolean = p(ctx).getBoolean("app_$id", true)
    fun setAppOn(ctx: Context, id: String, on: Boolean) {
        p(ctx).edit().putBoolean("app_$id", on).apply()
    }

    fun isSoundOn(ctx: Context): Boolean = p(ctx).getBoolean(KEY_SOUND, true)
    fun setSound(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean(KEY_SOUND, on).apply() }

    /** Master switch AND the app's own switch must be on. */
    fun isAllowed(ctx: Context, pkg: String): Boolean {
        if (!isMasterOn(ctx)) return false
        val app = appFor(pkg) ?: return true
        return isAppOn(ctx, app.id)
    }

    /**
     * Shows a Toast and plays the system notification sound. Safe to call from any thread and
     * from a Service, so it works while the launcher is paused, stopped or in the background.
     */
    fun alert(ctx: Context, sender: String, message: String, app: String) {
        val appCtx = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                val shown = if (message.length > 80) message.substring(0, 80) + "\u2026" else message
                Toast.makeText(appCtx, "$app: $sender\n$shown", Toast.LENGTH_LONG).show()
            } catch (e: Exception) { }
            if (isSoundOn(appCtx)) playSound(appCtx)
        }
    }

    fun playSound(ctx: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(ctx.applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            }
            ringtone.play()
        } catch (e: Exception) { }
    }
}
