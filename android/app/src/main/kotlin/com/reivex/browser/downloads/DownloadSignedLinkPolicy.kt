package com.reivex.browser.downloads

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Never perform speculative metadata GETs for an ephemeral signed URL.
 * Some endpoints issue URLs valid for a single transfer or a short time;
 * probing before the actual download can invalidate otherwise valid links.
 * The download's first GET is still allowed to report authoritative size.
 */
internal object DownloadSignedLinkPolicy {
    private val signatureKeys=setOf(
        "sign","signature","sig","token","auth","access_token","jwt",
        "expires","expiry","expiration","expire","x-amz-signature",
        "x-amz-expires","x-goog-signature","x-goog-expires",
        "hdntl","hdnts","policy","__token__","awsaccesskeyid"
    )
    fun avoidPrefetch(url:String):Boolean {
        val query=runCatching{URI(url).rawQuery}.getOrNull() ?: return false
        if(query.length>16_384)return true
        return query.split('&').take(100).any {part ->
            val key=part.substringBefore('=').lowercase(Locale.ROOT)
            key in signatureKeys
        }
    }

    /**
     * AWS SigV4 presigned video URLs are commonly signed for GET, not HEAD.
     * When only `host` is signed, a one-byte Range GET is still authorized and
     * lets S3 return the total from Content-Range without downloading the video.
     * Keep this exception narrow: never probe arbitrary one-time signed URLs.
     */
    fun allowsBoundedS3RangeProbe(url:String):Boolean = runCatching {
        val uri=URI(url)
        if(!uri.scheme.equals("https",true) || uri.host.isNullOrBlank())return@runCatching false
        val raw=uri.rawQuery ?: return@runCatching false
        if(raw.length>16_384)return@runCatching false
        val values=raw.split('&').take(100).mapNotNull { part ->
            val separator=part.indexOf('=')
            if(separator<=0)return@mapNotNull null
            val key=URLDecoder.decode(part.substring(0,separator),StandardCharsets.UTF_8.name())
                .lowercase(Locale.ROOT)
            val value=URLDecoder.decode(part.substring(separator+1),StandardCharsets.UTF_8.name())
            key to value
        }.groupBy({it.first},{it.second})
        fun one(name:String)=values[name]?.singleOrNull()
        val credential=one("x-amz-credential")?.split('/') ?: return@runCatching false
        uri.path.isNotBlank() &&
            one("x-amz-algorithm")=="AWS4-HMAC-SHA256" &&
            credential.size==5 && credential[1].matches(Regex("\\d{8}")) &&
            credential[3].equals("s3",true) && credential[4].equals("aws4_request",true) &&
            one("x-amz-signedheaders").equals("host",true) &&
            one("x-amz-date")?.matches(Regex("\\d{8}T\\d{6}Z"))==true &&
            one("x-amz-expires")?.toLongOrNull()?.let {it in 1L..604800L}==true &&
            one("x-amz-signature")?.matches(Regex("(?i)[0-9a-f]{64}"))==true
    }.getOrDefault(false)
}
