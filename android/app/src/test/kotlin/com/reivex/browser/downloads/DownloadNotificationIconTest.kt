package com.reivex.browser.downloads

import com.reivex.browser.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadNotificationIconTest {
    @Test fun activeAndPendingDownloadsUseTheOfficialReiVexMark() {
        listOf("QUEUED", "DOWNLOADING", "WAITING", "VERIFYING", "SAVING").forEach { state ->
            assertEquals(state, R.drawable.ic_reivex_download_active, DownloadScheduling.downloadStateIcon(state))
        }
        assertEquals(R.drawable.ic_reivex_download_active, DownloadScheduling.downloadStateIcon(null))
    }

    @Test fun completedDownloadsKeepTheirDedicatedCheckMark() {
        assertEquals(R.drawable.ic_reivex_download_done, DownloadScheduling.downloadStateIcon("COMPLETE"))
    }

    @Test fun pausedAndFailedDownloadsPreserveTheirDistinctIndicators() {
        assertEquals(R.drawable.ic_reivex_download_paused, DownloadScheduling.downloadStateIcon("PAUSED"))
        listOf("ERROR", "CORRUPT").forEach { state ->
            assertEquals(state, R.drawable.ic_reivex_download_error, DownloadScheduling.downloadStateIcon(state))
        }
    }

    @Test fun correctedServerFilenameRefreshesTheNotificationMarker() {
        fun item(name:String)=DownloadItem(JSONObject()
            .put("id","test")
            .put("name",name)
            .put("serverNameResolved",true)
            .put("state","DOWNLOADING")
            .put("bytes",0)
            .put("total",-1)
            .put("virtualUri","content://com.reivex.downloads/test/original.mp4"))

        assertNotEquals(
            DownloadScheduling.notificationMarker(item("original.mp4")),
            DownloadScheduling.notificationMarker(item("resolved-episode.mp4")),
        )
    }

    @Test fun completionDismissalIsPersistentAndOnlyAppliesToCompletedItems() {
        val completed=JSONObject().put("state","COMPLETE")
        assertFalse(DownloadCompletionNotificationPolicy.isDismissed(DownloadItem(completed)))
        DownloadCompletionNotificationPolicy.markDismissed(completed)
        assertTrue(DownloadCompletionNotificationPolicy.isDismissed(DownloadItem(completed)))

        val active=JSONObject().put("state","DOWNLOADING")
        DownloadCompletionNotificationPolicy.markDismissed(active)
        assertFalse(DownloadCompletionNotificationPolicy.isDismissed(DownloadItem(active)))
    }

    @Test fun completedNotificationHasAnExplicitDismissAction() {
        val item=DownloadItem(JSONObject()
            .put("id","12345678-1234-1234-1234-123456789abc")
            .put("name","Episode 01.mp4")
            .put("serverNameResolved",true)
            .put("state","COMPLETE")
            .put("bytes",4096)
            .put("total",4096)
            .put("virtualUri","content://com.reivex.downloads/test/Episode%2001.mp4"))

        val notification=DownloadScheduling.itemNotification(
            RuntimeEnvironment.getApplication(),item,"",
        )
        assertNotNull(notification.deleteIntent)
    }
}
