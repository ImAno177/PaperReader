package dev.paperreader.app.updates

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.paperreader.app.MainActivity
import dev.paperreader.app.R

class SavedSearchNotificationPublisher(context: Context) {
    private val context = context.applicationContext
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.saved_search_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.saved_search_notification_channel_description)
            },
        )
    }

    fun canPost(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelEnabled = notificationManager.getNotificationChannel(CHANNEL_ID)?.importance
            ?.let { it != NotificationManager.IMPORTANCE_NONE } ?: true
        return permissionGranted && notificationManager.areNotificationsEnabled() && channelEnabled
    }

    fun publish(newResultCount: Int): Boolean {
        if (newResultCount <= 0 || !canPost()) return false
        createChannel()
        val openUpdates = PendingIntent.getActivity(
            context,
            OPEN_UPDATES_REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_UPDATES)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_paper)
            .setContentTitle(context.getString(R.string.saved_search_notification_title))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.saved_search_notification_body,
                    newResultCount,
                    newResultCount,
                ),
            )
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(openUpdates)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
        return true
    }

    companion object {
        const val ACTION_OPEN_UPDATES = "dev.paperreader.app.action.OPEN_UPDATES"
        internal const val CHANNEL_ID = "saved-search-updates"
        internal const val NOTIFICATION_ID = 2_001
        private const val OPEN_UPDATES_REQUEST_CODE = 2_001
    }
}
