package com.TapLinkX3.app

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationService"
        const val ACTION_NOTIFICATION_POSTED = "com.TapLinkX3.app.NOTIFICATION_POSTED"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            val packageName = it.packageName
            val extras = it.notification.extras
            val title = extras.getString("android.title")
            val text = extras.getCharSequence("android.text")?.toString()

            DebugLog.d(TAG, "Notification received: $packageName - $title: $text")

            val intent = Intent(ACTION_NOTIFICATION_POSTED).apply {
                setPackage(this@NotificationService.packageName)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            sendBroadcast(intent)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        DebugLog.d(TAG, "Notification removed: ${sbn?.packageName}")
    }
}
