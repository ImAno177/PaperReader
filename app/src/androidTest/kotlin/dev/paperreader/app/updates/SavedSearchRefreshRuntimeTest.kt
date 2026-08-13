package dev.paperreader.app.updates

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.paperreader.app.PaperReaderApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedSearchRefreshRuntimeTest {
    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun enablingCreatesOneDurablePeriodicJobAndDisablingCancelsIt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperReaderApplication>()
        val workManager = WorkManager.getInstance(app)
        app.savedSearchRefreshScheduler.setEnabled(false)
        try {
            assertTrue(app.savedSearchRefreshScheduler.setEnabled(true))
            assertTrue(app.preferences.automaticSavedSearchRefreshEnabled.first())

            val scheduled = workManager
                .getWorkInfosForUniqueWork(SavedSearchRefreshScheduler.UNIQUE_WORK_NAME)
                .get(10, TimeUnit.SECONDS)
                .filterNot { it.state == WorkInfo.State.CANCELLED }
            assertEquals(1, scheduled.size)
            assertTrue(SavedSearchRefreshScheduler.WORK_TAG in scheduled.single().tags)
        } finally {
            assertTrue(app.savedSearchRefreshScheduler.setEnabled(false))
        }

        assertFalse(app.preferences.automaticSavedSearchRefreshEnabled.first())
        val remaining = workManager
            .getWorkInfosForUniqueWork(SavedSearchRefreshScheduler.UNIQUE_WORK_NAME)
            .get(10, TimeUnit.SECONDS)
        assertTrue(remaining.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun notificationChannelAndPayloadAreRealAndZeroResultsStaySilent() {
        val app = ApplicationProvider.getApplicationContext<PaperReaderApplication>()
        val manager = app.getSystemService(NotificationManager::class.java)
        val publisher = app.savedSearchNotificationPublisher
        manager.cancel(SavedSearchNotificationPublisher.NOTIFICATION_ID)
        manager.awaitNotificationAbsent(SavedSearchNotificationPublisher.NOTIFICATION_ID)

        publisher.createChannel()
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(SavedSearchNotificationPublisher.CHANNEL_ID).importance,
        )
        assertFalse(publisher.publish(0))
        assertTrue(publisher.publish(3))

        val posted = manager.awaitNotification(SavedSearchNotificationPublisher.NOTIFICATION_ID)
        assertEquals("Saved-search results updated", posted.extras.getString("android.title"))
        assertEquals("3 new or changed results are waiting in Updates.", posted.extras.getString("android.text"))
        assertTrue(posted.contentIntent != null)
        manager.cancel(SavedSearchNotificationPublisher.NOTIFICATION_ID)
    }

    private fun NotificationManager.awaitNotification(id: Int): Notification {
        val deadline = SystemClock.uptimeMillis() + NOTIFICATION_SYNC_TIMEOUT_MILLIS
        do {
            activeNotifications.firstOrNull { it.id == id }?.notification?.let { return it }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.yield()
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Notification $id did not become active")
    }

    private fun NotificationManager.awaitNotificationAbsent(id: Int) {
        val deadline = SystemClock.uptimeMillis() + NOTIFICATION_SYNC_TIMEOUT_MILLIS
        do {
            if (activeNotifications.none { it.id == id }) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.yield()
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Notification $id remained active after cancellation")
    }

    private companion object {
        const val NOTIFICATION_SYNC_TIMEOUT_MILLIS = 5_000L
    }
}
