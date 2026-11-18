package com.reivex.browser.downloads

import kotlinx.coroutines.*
import java.io.IOException

/**
 * Parallel, in-order downloads: only complete, synced waves are published.
 * Reuse fixed-size buffers across waves to avoid continuous 1 MB allocations
 * and repeated GC pauses during large transfers.
 */
internal object DownloadRanges {
    private const val BLOCK_BYTES=1024*1024

    /** Disjoint, bounded HTTP byte ranges for the next contiguous wave. */
    internal fun plan(start:Long,total:Long,connections:Int):List<LongRange> {
        require(start>=0 && total>=start)
        return (0 until connections.coerceIn(1,16)).mapNotNull {index ->
            val distance=index*BLOCK_BYTES.toLong()
            if(distance>=total-start)return@mapNotNull null
            val offset=start+distance
            val length=minOf(BLOCK_BYTES.toLong(),total-offset)
            offset..(offset+length-1)
        }
    }

    suspend fun receive(
        context:android.content.Context,
        store:DownloadStore,
        id:String,
        url:String,
        headers:Map<String,String>,
        target:DownloadTransferFile,
        start:Long,
        total:Long,
        validator:String,
        connections:Int,
    ):Boolean=coroutineScope {
        var position=start
        // Buffers are private to their range lane and are not reused until
        // awaitAll completes. A completed wave is written in offset order.
        val buffers=Array(connections.coerceIn(1,16)){ByteArray(BLOCK_BYTES)}
        target.open().use {out ->
            out.setLength(start);out.seek(start)
            while(position<total) {
                ensureActive()
                if(store.get(id)?.state!="DOWNLOADING")return@coroutineScope false
                val timestamp=android.os.SystemClock.elapsedRealtime()
                val beginning=position
                val ranges=plan(beginning,total,buffers.size)
                val parts=ranges.mapIndexed {index,span -> async(Dispatchers.IO) {
                    val offset=span.first
                    val end=span.last
                    val expected=(end-offset+1).toInt()
                    val bytes=buffers[index]
                    val c=DownloadHttp.connect(url,headers,offset,validator,end,
                        allowMediaHttpDowngrade=true,
                        redirectCookieProvider=DownloadSessionHeaders::cookieForTarget)
                    try {
                        val returned=DownloadHttp.range(c.getHeaderField("Content-Range"))
                        if(c.responseCode!=206 || returned?.start!=offset ||
                            returned.end!=end || returned.total!=total ||
                            c.getHeaderField("ETag")!=validator) {
                            throw IOException("Servidor não confirmou as faixas paralelas")
                        }
                        if(c.getHeaderField("Content-Encoding")?.let {
                            !it.equals("identity",true)
                        }==true)throw IOException("Representação comprimida")
                        var read=0
                        var checkedAt=0L
                        c.inputStream.use {input ->
                            while(read<expected) {
                                ensureActive()
                                // Unlike a database read for every ~8 KB socket
                                // packet, check pause state at most each 250 ms.
                                val now=android.os.SystemClock.elapsedRealtime()
                                if(read==0 || now-checkedAt>=250) {
                                    checkedAt=now
                                    if(store.get(id)?.state!="DOWNLOADING")throw Paused()
                                }
                                val n=input.read(bytes,read,expected-read)
                                if(n<0)throw IOException("Faixa incompleta")
                                read+=n
                            }
                            if(input.read()!=-1)throw IOException("Faixa maior que o esperado")
                        }
                        expected
                    } finally {c.disconnect()}
                } }
                val lengths=parts.awaitAll()
                if(store.get(id)?.state!="DOWNLOADING")return@coroutineScope false
                lengths.forEachIndexed {index,length ->
                    out.write(buffers[index],0,length)
                    position+=length
                }
                out.sync()
                store.mutate(id) {
                    it.put("bytes",position).put("speed",
                        (position-beginning)*1000/
                            (android.os.SystemClock.elapsedRealtime()-timestamp).coerceAtLeast(1))
                }
                DownloadCaptureGuard.probe(context,store,id,target.readUri(),position)
            }
        }
        true
    }

    class Paused:IOException()
}
