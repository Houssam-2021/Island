package com.blurify.island

import android.service.notification.NotificationListenerService

/**
 * Only here so Android lets the island see the current media session. When notification access is switched on
 * (possibly after the island is already running) it tells the island to start listening.
 */
class IslandService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        IslandAccessibilityService.instance?.controller?.attachMedia()
    }
}
