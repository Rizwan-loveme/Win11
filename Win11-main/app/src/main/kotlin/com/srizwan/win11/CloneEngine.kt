package com.srizwan.win11

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.GmsCore
import java.util.concurrent.Executors

/** A cloned app living inside the virtual space [userId]. */
data class CloneItem(val label: String, val icon: Drawable?, val packageName: String, val userId: Int)

/**
 * Thin wrapper around BlackBoxCore (the Parallel engine), used by the launcher.
 * Any app can be cloned from the launcher EXCEPT the ones in [BLOCKED].
 * Every call is safe to use from a background thread; run them on [io].
 */
object CloneEngine {
    const val MAX_SPACES = 10

    /** Apps that may NOT be cloned: bKash, Nagad, WhatsApp (banking/anti-tamper apps that break when virtualized). */
    private val BLOCKED = setOf(
        "com.bKash.customerapp",
        "com.konasl.nagad",
        "com.whatsapp"
    )

    fun isAllowed(pkg: String): Boolean = BLOCKED.none { it.equals(pkg, ignoreCase = true) }

    /** One background thread for all engine work (lives longer than any Activity). */
    val io = Executors.newSingleThreadExecutor()

    private val core: BlackBoxCore get() = BlackBoxCore.get()

    private fun labelOf(ai: ApplicationInfo): String = try {
        BlackBoxCore.getPackageManager().getApplicationLabel(ai).toString()
    } catch (e: Throwable) {
        ai.packageName ?: "?"
    }

    private fun iconOf(ai: ApplicationInfo): Drawable? = try {
        BlackBoxCore.getPackageManager().getApplicationIcon(ai)
    } catch (e: Throwable) {
        null
    }

    /** [callback] runs when the engine service is available (may never run if the engine failed to start). */
    fun onReady(callback: () -> Unit) {
        try {
            core.addServiceAvailableCallback(Runnable { callback() })
        } catch (e: Throwable) {
            // listClones() is also called directly, so a missing callback is not fatal
        }
    }

    /** The clones that exist now (only allowed apps; Google support packages are hidden). */
    fun listClones(): List<CloneItem> {
        val out = ArrayList<CloneItem>()
        val users = try { core.users } catch (e: Throwable) { null } ?: return out
        for (u in users) {
            val apps = try { core.getInstalledApplications(0, u.id) } catch (e: Throwable) { null } ?: continue
            for (ai in apps) {
                val pkg = ai.packageName ?: continue
                // Google Play Services / Play Store / GSF etc. are auto-installed into a space
                // as background support (see clone()). They are not something the user asked for.
                if (GmsCore.isGoogleAppOrService(pkg)) continue
                if (!isAllowed(pkg)) continue
                out.add(CloneItem(labelOf(ai), iconOf(ai), pkg, u.id))
            }
        }
        return out
    }

    /** Clones [pkg] into the first free space. Returns null on success, else an error text. */
    fun clone(pkg: String): String? {
        if (!isAllowed(pkg)) return "এই অ্যাপটি clone করা সাপোর্টেড না"
        var space = -1
        for (i in 0 until MAX_SPACES) {
            // NOTE: core.isInstalled() falls back to the HOST package manager when the engine
            // service is not ready, and then answers "true" for every space (=> false "max" error).
            // So we ask the engine's own per-space app list instead.
            val used = try {
                core.getInstalledApplications(0, i)?.any { it.packageName == pkg } ?: false
            } catch (e: Throwable) { false }
            if (!used) { space = i; break }
        }
        if (space < 0) return "সর্বোচ্চ $MAX_SPACES টি কপি তৈরি করা যায়"
        return try {
            val r = core.installPackageAsUser(pkg, space)
            if (r != null && r.success) {
                // Best-effort: also clone (fake) Google Play Services into this space.
                // Only works if the HOST device has real Google Play Services; otherwise a no-op.
                try {
                    if (core.isSupportGms() && !core.isInstallGms(space)) {
                        core.installGms(space)
                    }
                } catch (e: Throwable) {
                    // Non-fatal: the app clone itself already succeeded above.
                }
                null
            } else (r?.msg ?: "ইনস্টল ব্যর্থ হয়েছে")
        } catch (e: Throwable) {
            e.message ?: "ইনস্টল ব্যর্থ হয়েছে"
        }
    }

    fun launch(pkg: String, userId: Int): Boolean = try {
        core.launchApk(pkg, userId)
    } catch (e: Throwable) {
        false
    }

    fun clearData(pkg: String, userId: Int) {
        try { core.clearPackage(pkg, userId) } catch (e: Throwable) { }
    }

    fun remove(pkg: String, userId: Int) {
        try { core.uninstallPackageAsUser(pkg, userId) } catch (e: Throwable) { }
        trimEmptySpaces()
    }

    /** Drops trailing spaces that no longer hold any app. */
    private fun trimEmptySpaces() {
        try {
            while (true) {
                val users = core.users
                if (users.isNullOrEmpty()) return
                val last = users.last().id
                val apps = core.getInstalledApplications(0, last)
                // Ignore the auto-installed Google support packages here too, otherwise a space
                // whose real app was removed never gets trimmed.
                val realApps = apps?.filter { !GmsCore.isGoogleAppOrService(it.packageName ?: "") }
                if (!realApps.isNullOrEmpty()) return
                core.deleteUser(last)
            }
        } catch (e: Throwable) {
            // best effort
        }
    }
}
