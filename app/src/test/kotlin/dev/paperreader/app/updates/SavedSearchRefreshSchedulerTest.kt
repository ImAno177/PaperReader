package dev.paperreader.app.updates

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSearchRefreshSchedulerTest {
    @Test
    fun `periodic request is unique daily network constrained work with bounded retry`() {
        val request = SavedSearchRefreshScheduler.periodicRequest()
        val spec = request.workSpec

        assertEquals(TimeUnit.HOURS.toMillis(24), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(30), spec.backoffDelayDuration)
        assertTrue(SavedSearchRefreshScheduler.WORK_TAG in request.tags)
    }
}
