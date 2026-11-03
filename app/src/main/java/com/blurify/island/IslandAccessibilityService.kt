package com.blurify.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent

/** Hosts the island window. An accessibility overlay is the one kind that is drawn above the status bar. */
class IslandAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: IslandAccessibilityService? = null
    }

    var controller: IslandController? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (controller == null) controller = IslandController(this)
        controller?.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        controller?.stop()
        controller = null
        instance = null
        return super.onUnbind(intent)
    }
}
