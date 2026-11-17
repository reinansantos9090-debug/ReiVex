package com.reivex.browser.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadParallelRecoveryTest {
    @Test fun savesVerifiedPrefixWhenLaterParallelPartFails() {
        assertTrue(DownloadParallelRecovery.keepCommittedBytes(
            7L * 1024 * 1024, 7L * 1024 * 1024,
            "\"file-v4\"", "\"file-v4\"",
        ))
        // Uncommitted bytes may be truncated safely on the next attempt.
        assertTrue(DownloadParallelRecovery.keepCommittedBytes(
            3L * 1024 * 1024, 4L * 1024 * 1024,
            "\"file-v4\"", "\"file-v4\"",
        ))
    }

    @Test fun resetsWhenProgressOrIdentityCannotBeTrusted() {
        assertFalse(DownloadParallelRecovery.keepCommittedBytes(
            512, 256, "\"a\"", "\"a\"",
        ))
        assertFalse(DownloadParallelRecovery.keepCommittedBytes(
            -1, 4096, "\"a\"", "\"a\"",
        ))
        assertFalse(DownloadParallelRecovery.keepCommittedBytes(
            512, 512, "\"old\"", "\"new\"",
        ))
        assertFalse(DownloadParallelRecovery.keepCommittedBytes(
            512, 512, null, "\"a\"",
        ))
        assertFalse(DownloadParallelRecovery.keepCommittedBytes(
            512, 512, "W/\"a\"", "W/\"a\"",
        ))
        assertFalse(DownloadParallelRecovery.keepCommittedBytes(
            512, 512, "Wed, 01 Jan 2025 00:00:00 GMT", "Wed, 01 Jan 2025 00:00:00 GMT",
        ))
    }
}
