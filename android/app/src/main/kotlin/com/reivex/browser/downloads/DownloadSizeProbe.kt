package com.reivex.browser.downloads

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Non-downloading size inspection, analogous to open download-manager HTTP
 * probing: HEAD -> GET bytes=0-0 -> HLS playlist/fragment metadata. The last
 * fallback may be an estimate, never a fictional exact byte count.
 *
 * No media bodies are downloaded. HTTP connections share the downloader's
 * existing redirect policy (no cross-origin cookie/authorization forwarding).
 */
internal object DownloadSizeProbe {
    data class Result(val bytes: Long?, val exact: Boolean, val source: String,
        val name: String? = null, val mime: String? = null,
        val resolvedUrl: String? = null) {
        companion object { val Unknown = Result(null, false, "unknown") }
    }
    internal data class HlsPlan(val segments: List<String>, val init: String?, val durationSeconds: Double,
        val encrypted: Boolean)
    internal data class MasterChoice(val url: String, val bandwidth: Long?,val averageBandwidth: Long? = null)

    private const val NETWORK_TIMEOUT_MS = 2_100
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_EXACT_SEGMENTS = 40
    private const val MAX_SAMPLE_SEGMENTS = 12
    private const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024
    private const val MAX_HLS_SIZE_SAMPLES = 5

    fun internalAvailableBytes(context: Context): Long? = runCatching {
        val folder = runCatching { Environment.getExternalStorageDirectory() }
            .getOrElse { context.filesDir }
        StatFs(folder.absolutePath).availableBytes.takeIf { it >= 0 }
    }.getOrNull()

    /** A valid exact length is either a full identity response or a legal 206 total. */
    fun directLength(
        status: Int, contentLength: Long, contentRange: String?,
        contentEncoding: String?, contentType: String?,
    ): Long? {
        if (contentEncoding != null && !contentEncoding.equals("identity",true)) return null
        val mime=contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime.contains("mpegurl") || mime.contains("dash+xml") || mime.startsWith("text/") ||
            mime.contains("json") || mime.contains("xml")) return null
        return when (status) {
            HttpURLConnection.HTTP_PARTIAL ->
                DownloadHttp.range(contentRange)?.takeIf { it.start==0L }?.total
            HttpURLConnection.HTTP_OK -> contentLength.takeIf { it > 0 }
            else -> null
        }
    }

    fun parseMaster(text: String, sourceUrl: String): MasterChoice? {
        val lines=text.lineSequence().map(String::trim).toList()
        return lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF:",true) }
            .mapNotNull { index ->
                val attrs=DownloadHls.attributes(lines[index])
                val candidate=lines.drop(index+1).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                    ?: return@mapNotNull null
                val url=runCatching { URL(URL(sourceUrl),candidate).toString() }.getOrNull()
                    ?: return@mapNotNull null
                if (runCatching { DownloadHttp.validateUrl(url) }.isFailure) return@mapNotNull null
                // The existing downloader chooses the largest BANDWIDTH variant.
                MasterChoice(url,attrs["BANDWIDTH"]?.toLongOrNull(),attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull())
            }.maxByOrNull { it.bandwidth ?: 0L }
    }

    fun parseVod(text: String, sourceUrl: String): HlsPlan? {
        if (!text.trimStart().startsWith("#EXTM3U") || !text.contains("#EXT-X-ENDLIST")) return null
        val segments=mutableListOf<String>()
        var init: String?=null
        var duration=0.0
        var encrypted=false
        for(line in text.lineSequence().map { it.trim() }) {
            when {
                line.startsWith("#EXT-X-BYTERANGE") -> return null // not supported by the downloader
                line.startsWith("#EXT-X-DISCONTINUITY") -> return null
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri=DownloadHls.attributes(line)["URI"] ?: return null
                    if(DownloadHls.attributes(line)["BYTERANGE"]!=null)return null
                    val resolved=runCatching { URL(URL(sourceUrl),uri).toString() }.getOrNull() ?: return null
                    if(runCatching { DownloadHttp.validateUrl(resolved) }.isFailure)return null
                    if(init!=null && init!=resolved)return null
                    init=resolved
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    if(DownloadHls.attributes(line)["METHOD"] !in setOf(null,"NONE")) encrypted=true
                }
                line.startsWith("#EXTINF:") -> {
                    val n=line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    if(n != null && n.isFinite() && n>=0)duration+=n
                }
                line.isNotBlank() && !line.startsWith('#') -> {
                    if(segments.size>=20_000)return null
                    val url=runCatching { URL(URL(sourceUrl),line).toString() }.getOrNull() ?: return null
                    if(runCatching { DownloadHttp.validateUrl(url) }.isFailure)return null
                    segments+=url
                }
            }
        }
        return if(segments.isNotEmpty())HlsPlan(segments,init,duration,encrypted) else null
    }

    /**
     * Some Googlevideo VOD URLs carry the signed `clen` (content length)
     * parameter for this exact representation. Do not trust arbitrary
     * user-controlled `size` URL parameters on other domains.
     */
    fun trustedUrlLength(value: String): Long? = runCatching {
        val uri=DownloadHttp.validateUrl(value)
        val hostname=uri.host.orEmpty().lowercase(Locale.ROOT)
        if(hostname!="googlevideo.com" && !hostname.endsWith(".googlevideo.com"))return@runCatching null
        if(uri.path!="/videoplayback")return@runCatching null
        uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=')=="clen" }
            ?.substringAfter('=',"")?.toLongOrNull()?.takeIf { it>0L }
    }.getOrNull()

    /** Samples of complete media fragments produce an estimate, never an exact byte count. */
    fun sampledSegmentEstimate(sizes: List<Long?>, segmentCount: Int): Long? {
        if (segmentCount <= 0 || sizes.isEmpty()) return null
        val known = sizes.filterNotNull().filter { it > 0 }
        if (known.size < minOf(2, sizes.size)) return null
        val estimate = known.average() * segmentCount
        return estimate.takeIf { it.isFinite() && it in 1.0..Long.MAX_VALUE.toDouble() }?.toLong()
    }

    fun bitrateEstimate(durationSeconds: Double, bitsPerSecond: Long?): Long? {
        if(!durationSeconds.isFinite() || durationSeconds<=0 || bitsPerSecond==null || bitsPerSecond<=0)return null
        val total=durationSeconds*bitsPerSecond/8.0
        return total.takeIf { it.isFinite() && it in 1.0..Long.MAX_VALUE.toDouble() }?.toLong()
    }

    suspend fun inspect(url: String, headers: Map<String,String>, hlsHint: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val hint=DownloadResourceIdentity.bestName(null,url,null)
            try {
                DownloadHttp.validateUrl(url)
                val path=URL(url).path.lowercase(Locale.ROOT)
                if(DownloadSpecialMetadata.isManifest(url))
                    return@withContext DownloadSpecialMetadata.inspectManifest(url,headers)
                        ?: Result.Unknown
                val manifest=hlsHint || path.endsWith(".m3u8")
                if(path.endsWith(".mpd"))
                    return@withContext DownloadDashMetadata.inspect(url,headers).copy(name=hint)
                if(manifest) return@withContext inspectHls(url,headers).copy(name=hint)
                trustedUrlLength(url)?.let { return@withContext Result(it,true,"googlevideo-clen",hint) }
                // Public Google Cloud Storage objects have an authoritative
                // metadata endpoint. Ask it before spending several seconds
                // on generic range/HEAD retries against chunked media gateways.
                DownloadSpecialMetadata.inspectCloud(url)?.let {
                    return@withContext it.copy(name=it.name ?: hint)
                }
                val response=probe(url,headers)
                if(response.dash) return@withContext DownloadDashMetadata.inspect(url,headers)
                    .copy(name=response.name ?: hint, mime=response.mime)
                if(response.manifest) return@withContext inspectHls(url,headers)
                    .copy(name=response.name ?: hint, mime=response.mime)
                if(response.length!=null)
                    return@withContext Result(response.length,true,response.source,
                        response.name ?: hint,response.mime,response.resolvedUrl)
                if(response.page) DownloadSpecialMetadata.inspectPage(url,headers)?.let {
                    return@withContext it.copy(name=response.name ?: hint)
                }
                // Only after the fast, low-cost strategies fail: the advanced
                // bounded probes cover 416, suffix, multipart and validated
                // same-origin metadata. A vendor hint is not exact.
                val advanced=DownloadAdvancedHttp.inspect(url,headers)
                if(advanced?.exact==true)
                    return@withContext advanced.copy(name=advanced.name ?: response.name ?: hint,
                        mime=advanced.mime ?: response.mime)
                val native=DownloadContainerMetadata.inspect(url,headers)
                if(native!=null && native.exact)
                    return@withContext Result(native.bytes,true,native.source,response.name ?: hint,response.mime)
                if(advanced!=null) return@withContext advanced.copy(name=advanced.name ?: response.name ?: hint,
                    mime=advanced.mime ?: response.mime)
                native?.let {return@withContext Result(it.bytes,false,it.source,response.name ?: hint,response.mime)}
                DownloadSpecialMetadata.inspectWebDav(url,headers)?.let {
                    return@withContext it.copy(name=response.name ?: hint)
                }
                Result(null,false,"unknown",response.name ?: hint,response.mime,
                    response.resolvedUrl)
            } catch(c: kotlinx.coroutines.CancellationException) { throw c }
            catch(_: Exception) { Result(null,false,"unknown",hint) }
        }

    /**
     * Keep generic signed URLs HEAD-only. AWS SigV4 S3 links signed for GET
     * reject HEAD, so a narrowly validated one-byte Range GET reads only the
     * authoritative response headers and immediately disconnects.
     */
    suspend fun inspectSignedHeaders(url: String, headers: Map<String,String>): Result =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                trustedUrlLength(url)?.let { return@withContext Result(it,true,"googlevideo-clen") }
                val boundedS3Range=DownloadSignedLinkPolicy.allowsBoundedS3RangeProbe(url)
                connection=DownloadHttp.connect(url,headers,0,null,
                    method=if(boundedS3Range)"GET" else "HEAD",
                    probeRange=if(boundedS3Range)"bytes=0-0" else null,
                    closeAfterResponse=boundedS3Range,
                    connectTimeoutMs=NETWORK_TIMEOUT_MS,readTimeoutMs=NETWORK_TIMEOUT_MS,
                    redirectCookieProvider=DownloadSessionHeaders::cookieForTarget)
                val c=connection
                val bytes=directLength(c.responseCode,c.getHeaderFieldLong("Content-Length",-1),
                    c.getHeaderField("Content-Range"),c.contentEncoding,c.contentType)
                Result(bytes,bytes!=null,if(boundedS3Range)"signed-range" else "signed-head",
                    DownloadResourceIdentity.bestName(c.getHeaderField("Content-Disposition"),
                        c.url.toString(),c.contentType),c.contentType)
            } catch(c: kotlinx.coroutines.CancellationException) { throw c }
            catch(_: Exception) { Result.Unknown }
            finally { connection?.disconnect() }
        }

    private data class Headers(
        val length: Long?, val manifest: Boolean, val name: String?, val mime: String?, val source: String,
        val dash:Boolean = false, val page:Boolean = false, val resolvedUrl:String? = null
    )

    /**
     * Gopeed/Download Navi style: first request headers without a body, then
     * one-byte Range, then a plain GET when servers reject HEAD or Range.
     * Plain GET is disconnected after headers; the video is NOT downloaded here.
     * Name and MIME remain useful even when size is unavailable.
     */
    private fun probe(url: String, headers: Map<String,String>, credentialOrigin: String = url): Headers {
        var bestName=DownloadResourceIdentity.bestName(null,url,null)
        var bestMime:String?=null
        var manifest=false
        var resolvedMediaUrl:String?=null
        // Start with the same open-ended Range that direct transfers use.
        // Some CDNs reject HEAD and a one-byte range but serve bytes=0-.
        for(phase in listOf("GET_OPEN_RANGE","HEAD","GET_RANGE","GET")) {
            currentThreadCheck()
            var c:HttpURLConnection?=null
            try {
                c=DownloadHttp.connect(url,headers,0,null,
                    end=if(phase=="GET_RANGE")0 else null,
                    credentialOrigin=credentialOrigin,
                    method=if(phase=="HEAD")"HEAD" else "GET",
                    probeRange=if(phase=="GET_OPEN_RANGE")"bytes=0-" else null,
                    connectTimeoutMs=NETWORK_TIMEOUT_MS,readTimeoutMs=NETWORK_TIMEOUT_MS,
                    retryHtmlWithSelfReferer=(phase=="GET_OPEN_RANGE"),
                    allowMediaHttpDowngrade=true,
                    redirectCookieProvider=DownloadSessionHeaders::cookieForTarget)
                val code=c.responseCode
                val mime=c.contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
                val endpoint=c.url.toString()
                if(code in setOf(200,206) && endpoint!=url &&
                    (mime.startsWith("video/") ||
                        DownloadSourceSelection.kind(endpoint)==
                            DownloadSourceSelection.Kind.DIRECT_FILE)) {
                    resolvedMediaUrl=endpoint
                }
                if(mime.startsWith("video/") || mime.contains("mpegurl") || mime.contains("dash+xml"))
                    bestMime=mime
                if(mime.contains("dash+xml"))
                    return Headers(null,false,bestName,bestMime,"dash-manifest",dash=true)
                if(mime.startsWith("text/html"))
                    return Headers(null,false,bestName,bestMime,"html-page",page=true)
                val resolved=DownloadResourceIdentity.bestName(c.getHeaderField("Content-Disposition"),
                    c.url.toString(),c.contentType)
                if(resolved!=null && (bestName==null ||
                    DownloadResourceIdentity.opaque(bestName) || resolved.contains("Episódio",true)))
                    bestName=resolved
                if(mime.contains("mpegurl")) {manifest=true;break}
                val size=directLength(code,c.getHeaderFieldLong("Content-Length",-1),
                    c.getHeaderField("Content-Range"),c.getHeaderField("Content-Encoding"),c.contentType)
                if(size!=null)return Headers(size,false,bestName,bestMime,
                    phase.lowercase(Locale.ROOT),resolvedUrl=resolvedMediaUrl)
                // Some CDNs reject ranges but permit GET. A missing Content-Length
                // in a 200 chunked response cannot be treated as an exact size.
                if(code in setOf(401,403))break
            } catch(_:java.io.IOException) { /* try the next header-only strategy */ }
            finally {c?.disconnect()}
        }
        return Headers(null,manifest,bestName,bestMime,"unknown",
            resolvedUrl=resolvedMediaUrl)
    }

    private suspend fun inspectHls(url: String, headers: Map<String,String>): Result {
        val first=readManifest(url,headers,url) ?: return Result.Unknown
        val choice=parseMaster(first,url)
        val mediaUrl=choice?.url ?: url
        val playlist=if(choice!=null) readManifest(mediaUrl,headers,url) else first
        val richer=playlist?.let {DownloadHlsMetadata.parse(it,mediaUrl)}
        // Exact source bytes for HLS BYTERANGE and EXT-X-MAP are available
        // from the manifest, but remux/crypto may change final MP4 bytes.
        richer?.knownRangeSize?.let {
            return Result(it,false,"hls-ranges")
        }
        val plan=playlist?.let {parseVod(it,mediaUrl)}
        val duration=plan?.durationSeconds ?: richer?.duration ?: 0.0
        val bitrate=bitrateEstimate(duration,choice?.averageBandwidth ?: choice?.bandwidth)
        // A master's declared average bitrate yields an immediate estimate.
        // Do not lose it to the dialog timeout while inspecting many fragments.
        if(bitrate!=null && (plan==null || plan.segments.size>MAX_EXACT_SEGMENTS))return Result(bitrate,false,
            if(choice?.averageBandwidth!=null) "hls-average-bandwidth" else "hls-bitrate")
        if(plan==null)return bitrate?.let{Result(it,false,"hls-average-bandwidth")}
            ?: Result.Unknown
        val segments=plan.segments
        // Most HLS variants are media playlists with no declared bitrate. The
        // old path queried every segment (up to 40) before trying an estimate;
        // slow or metadata-hostile servers could outlive the dialog timeout.
        // A few representative fragments are enough for a labeled estimate.
        val indices=representativeIndices(segments.size,MAX_HLS_SIZE_SAMPLES)
        val sizes=coroutineScope {
            indices.map { index -> async(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    DownloadHlsMetadata.cachedSize(segments[index]) ?:
                        probeSegmentLength(segments[index],headers,url)?.also {
                            DownloadHlsMetadata.rememberSize(segments[index],it)
                        }
                }}.awaitAll()
        }
        val successes=sizes.filterNotNull()
        if(successes.size>=2) {
            val estimates=indices.zip(sizes).mapNotNull { (i,size) ->
                size?.let{i to it}
            }.toMap()
            val rawTotal=richer?.let { DownloadHlsMetadata.weightedEstimate(it.pieces,estimates) }
                ?: (successes.average()*segments.size).toLong()
            val initSize=plan.init?.let {probe(it,headers,credentialOrigin=url).length}
            if(plan.init!=null && initSize==null)return Result(
                bitrate ?: rawTotal,false,"hls-estimate")
            // AES-128/CBC PKCS#7 adds 1..16 bytes of ciphertext padding
            // per encrypted segment. Subtract a midpoint ONLY from the
            // approximate output estimate; original source byte totals remain
            // available through diagnostics and are never claimed exact.
            val encryptedPadding=if(plan.encrypted)
                (DownloadHlsMetadata.encryptedTransportOverhead(segments.size) ?: 0L)/2
                else 0L
            val bytes=(rawTotal-encryptedPadding).coerceAtLeast(1L)+(initSize ?: 0L)
            if(bytes>0) {
                // TS may be remuxed to MP4 (different exact output bytes).
                return Result(bytes,false,when {
                    plan.encrypted -> "hls-encrypted-adjusted-estimate"
                    else -> "hls-segment-sample"
                })
            }
        }
        if(bitrate!=null) return Result(bitrate,false,
            if(choice?.averageBandwidth!=null) "hls-average-bandwidth" else "hls-bitrate")
        // Some video hosts reject HEAD and Range requests, but permit normal
        // segment GETs. Sample only three *complete*, bounded segments. This
        // is expressly an estimate and is never used as a download limit.
        val sampleIndices=representativeIndices(segments.size,3)
        val sampleSizes=coroutineScope {
            sampleIndices.map { index -> async(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                sampleCompleteSegment(segments[index],headers,url)
            }}.awaitAll()
        }
        val sampled=sampledSegmentEstimate(sampleSizes,segments.size)
        if(sampled!=null)return Result(sampled,false,"hls-sampled")
        val tsRate=segments.firstOrNull()?.takeIf {
            it.substringBefore('?').endsWith(".ts",true) ||
                it.substringBefore('?').endsWith(".m2ts",true)
        }?.let {sampleTransportBitrate(it,headers,url)}
        return bitrateEstimate(plan.durationSeconds,tsRate)?.let{
            Result(it,false,"hls-transport-pcr")
        } ?: Result.Unknown
    }

    private fun representativeIndices(size:Int,limit:Int):List<Int> {
        if(size<=0 || limit<=0)return emptyList()
        if(size<=limit)return (0 until size).toList()
        return (0 until limit).map { slot ->
            (slot.toLong()*(size-1)/(limit-1).coerceAtLeast(1)).toInt()
        }.distinct()
    }

    /** One bounded Range request per HLS fragment; never reads the media body. */
    private fun probeSegmentLength(url:String,headers:Map<String,String>,origin:String):Long? {
        var connection:HttpURLConnection?=null
        return try {
            connection=DownloadHttp.connect(url,headers,0,null,credentialOrigin=origin,
                method="GET",probeRange="bytes=0-",closeAfterResponse=true,
                connectTimeoutMs=NETWORK_TIMEOUT_MS,readTimeoutMs=NETWORK_TIMEOUT_MS,
                redirectCookieProvider=DownloadSessionHeaders::cookieForTarget)
            val c=connection
            directLength(c.responseCode,c.getHeaderFieldLong("Content-Length",-1),
                c.getHeaderField("Content-Range"),c.getHeaderField("Content-Encoding"),c.contentType)
        }catch(_:java.io.IOException){null}
        finally{connection?.disconnect()}
    }

    private fun sampleTransportBitrate(url:String,headers:Map<String,String>,origin:String):Long? {
        var c:HttpURLConnection?=null
        return try {
            c=DownloadHttp.connect(url,headers,0,null,credentialOrigin=origin,
                probeRange="bytes=0-65535",connectTimeoutMs=1800,readTimeoutMs=1900)
            if(c.responseCode !in setOf(200,206))return null
            val bytes=ByteArray(65536)
            var count=0
            c.inputStream.use {input ->
                while(count<bytes.size) {
                    currentThreadCheck()
                    val n=input.read(bytes,count,bytes.size-count)
                    if(n<0)break
                    count+=n
                }
            }
            DownloadContainerMetadata.tsPcrBitsPerSecond(bytes.copyOf(count))
        }catch(_:java.io.IOException){null}
        finally{c?.disconnect()}
    }

    private fun sampleCompleteSegment(url: String, headers: Map<String,String>, origin: String): Long? {
        var connection: HttpURLConnection?=null
        return try {
            connection=DownloadHttp.connect(url,headers,0,null,credentialOrigin=origin,
                connectTimeoutMs=NETWORK_TIMEOUT_MS,readTimeoutMs=NETWORK_TIMEOUT_MS)
            if(connection.responseCode!=200 || connection.contentType.orEmpty().startsWith("text/") ||
                connection.getHeaderFieldLong("Content-Length",-1)>MAX_SAMPLE_BYTES) return null
            var total=0L
            connection.inputStream.use { input ->
                val buffer=ByteArray(32*1024)
                while(true) {
                    currentThreadCheck()
                    val n=input.read(buffer)
                    if(n<0) break
                    total+=n
                    if(total>MAX_SAMPLE_BYTES) return null
                }
            }
            total.takeIf { it>0L }
        } catch(_:java.io.IOException) { null }
        finally {connection?.disconnect()}
    }

    private suspend fun readManifest(url: String,headers: Map<String,String>,credentialOrigin: String): String? =
        withContext(Dispatchers.IO) {
            val conn=DownloadHttp.connect(url,headers,0,null,credentialOrigin=credentialOrigin,
                connectTimeoutMs=NETWORK_TIMEOUT_MS,readTimeoutMs=NETWORK_TIMEOUT_MS,
                allowMediaHttpDowngrade=true,
                redirectCookieProvider=DownloadSessionHeaders::cookieForTarget)
            try {
                if(conn.responseCode!=200 ||
                    conn.getHeaderFieldLong("Content-Length",-1)>MAX_MANIFEST_BYTES) return@withContext null
                conn.inputStream.use { input ->
                    val out=java.io.ByteArrayOutputStream()
                    val buffer=ByteArray(16*1024)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val n=input.read(buffer)
                        if(n<0)break
                        if(out.size()+n>MAX_MANIFEST_BYTES) return@withContext null
                        out.write(buffer,0,n)
                    }
                    out.toString(Charsets.UTF_8.name()).takeIf {it.trimStart().startsWith("#EXTM3U")}
                }
            } finally {conn.disconnect()}
        }

    private fun currentThreadCheck() {
        if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
    }
}
