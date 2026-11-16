package com.supun.sweeper.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.supun.sweeper.MainActivity
import com.supun.sweeper.R

/** Intent action that opens the Malware screen (in every build type). */
const val ACTION_OPEN_MALWARE = "com.supun.sweeper.action.OPEN_MALWARE"

/** The summary notification after each scheduled run. */
class ScheduledScanNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun show(summary: ScanSummary) {
        val nm = manager ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Scheduled scans", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A short summary after each scheduled malware scan"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val open = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_MALWARE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tap = PendingIntent.getActivity(appContext, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // What the lock screen shows when the phone hides private notifications: no counts, no names.
        val hidden = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("Scheduled scan finished")
            .build()
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(summary.title)
            .setContentText(summary.body)
            .setStyle(Notification.BigTextStyle().bigText(summary.body))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "scheduled_scans"
        const val NOTIFICATION_ID = 29001
    }
}
