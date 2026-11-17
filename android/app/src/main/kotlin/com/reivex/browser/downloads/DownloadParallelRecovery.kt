package com.reivex.browser.downloads

/**
 * A failed parallel wave must not erase earlier waves that were fully received,
 * synced to disk and recorded in DownloadStore. The serial retry will truncate
 * the file to the last committed offset before sending Range + If-Range.
 *
 * Never preserve bytes without the same strong entity validator.
 */
internal object DownloadParallelRecovery {
    fun keepCommittedBytes(
        committedBytes: Long,
        fileBytes: Long,
        storedValidator: String?,
        expectedValidator: String?,
    ): Boolean =
        committedBytes >= 0 &&
        fileBytes >= committedBytes &&
        !expectedValidator.isNullOrBlank() &&
        expectedValidator.startsWith("\"") &&
        !expectedValidator.startsWith("W/") &&
        storedValidator == expectedValidator
}
