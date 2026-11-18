package com.reivex.browser.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadRangesPlanTest {
    @Test fun generatesOrderedDisjointOneMegabyteRanges() {
        val mega=1024L*1024
        assertEquals(listOf(
            0L..(mega-1),
            mega..(2*mega-1),
            (2*mega)..(3*mega-1),
            (3*mega)..(4*mega-1),
        ),DownloadRanges.plan(0,9*mega,4))
    }

    @Test fun lastRangeIsClampedToFileEnd() {
        val mega=1024L*1024
        assertEquals(listOf(
            (mega+12)..(2*mega+11),
            (2*mega+12)..(2*mega+100),
        ),DownloadRanges.plan(mega+12,2*mega+101,8))
    }

    @Test fun noRangeWhenAlreadyCompleteAndNeverOverflowPastLongMax() {
        assertTrue(DownloadRanges.plan(50,50,16).isEmpty())
        assertEquals(listOf((Long.MAX_VALUE-3)..(Long.MAX_VALUE-1)),
            DownloadRanges.plan(Long.MAX_VALUE-3,Long.MAX_VALUE,16))
    }

    @Test fun respectsConnectionBounds() {
        assertEquals(1,DownloadRanges.plan(0,10L*1024*1024,0).size)
        assertEquals(16,DownloadRanges.plan(0,20L*1024*1024,99).size)
    }
}
