package com.reivex.browser.downloads

/**
 * A failed parallel wave must not erase earlier waves that were fully received,
 * synced to disk and recorded in DownloadStore. The serial retry will truncate
 * the file to the last committed offset before sending Range + If-Range.
 *
 * Never preserve bytes without the same strong entity validator.
 */
internal object DownloadParallelRecovery {
    /** RFC 9110 entity-tag syntax, excluding weak validators and malformed values. */
    fun isStrongETag(value:String?):Boolean {
        if(value==null || value.length<2 || value.first()!='"' || value.last()!='"')return false
        return value.substring(1,value.lastIndex).all { char ->
            char=='!' || char.code in 0x23..0x7e || char.code in 0x80..0xff
        }
    }

    fun resumeValidator(etag:String?,lastModified:String?):String? =
        etag?.takeIf {isStrongETag(it)} ?: lastModified?.takeIf {it.isNotBlank()}

    fun keepCommittedBytes(
        committedBytes: Long,
        fileBytes: Long,
        storedValidator: String?,
        expectedValidator: String?,
    ): Boolean =
        committedBytes >= 0 &&
        fileBytes >= committedBytes &&
        isStrongETag(expectedValidator) &&
        storedValidator == expectedValidator
}
