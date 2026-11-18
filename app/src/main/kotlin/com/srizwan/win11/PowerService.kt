package com.srizwan.win11

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Tiny accessibility service (no XML config; set up in code) whose only job is to
 * perform the global actions Android allows launchers to trigger:
 * lock screen (API 28+) and the system power dialog (API 31+).
 */
class PowerService : AccessibilityService() {

    companion object {
        @Volatile var instance: PowerService? = null
            private set

        val isEnabled: Boolean get() = instance != null

        fun lock(): Boolean =
            Build.VERSION.SDK_INT >= 28 &&
                instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true

        fun powerDialog(): Boolean =
            Build.VERSION.SDK_INT >= 31 &&
                instance?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) == true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
