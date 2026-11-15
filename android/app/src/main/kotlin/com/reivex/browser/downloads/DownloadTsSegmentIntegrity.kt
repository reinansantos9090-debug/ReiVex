package com.reivex.browser.downloads

import java.io.IOException

/**
 * Validate decrypted MPEG-TS bytes *as they arrive*, before committing each
 * HLS segment. This catches HTML/error bodies returned with HTTP 200, damaged
 * packets, missing packets and unannounced continuity breaks that would
 * otherwise be glued into a seemingly complete video.
 *
 * A validator is shared by all segments of one playlist: continuity counters
 * are required to continue across segment boundaries by RFC 8216. It does
 * not store segments in memory or change the streaming download behavior.
 */
internal class DownloadTsSegmentIntegrity {
    private val carry=ByteArray(188)
    private var carried=0
    private var segmentPackets=0L
    private val lastPayloadCounter=IntArray(8192) {-1}

    fun accept(bytes:ByteArray,offset:Int,length:Int) {
        if(offset<0 || length<0 || offset>bytes.size-length)
            throw IndexOutOfBoundsException()
        var at=offset
        val end=offset+length
        if(carried>0) {
            val taken=minOf(188-carried,end-at)
            bytes.copyInto(carry,carried,at,at+taken)
            carried+=taken
            at+=taken
            if(carried==188) {
                packet(carry,0)
                carried=0
            }
        }
        while(at+188<=end) {
            packet(bytes,at)
            at+=188
        }
        if(at<end) {
            carried=end-at
            bytes.copyInto(carry,0,at,end)
        }
    }

    fun finishSegment() {
        if(carried!=0 || segmentPackets==0L)
            throw IOException("Segmento HLS MPEG-TS truncado ou sem pacotes completos")
        segmentPackets=0L
    }

    private fun packet(bytes:ByteArray,at:Int) {
        fun byte(i:Int)=bytes[at+i].toInt() and 0xff
        if(byte(0)!=0x47 || (byte(1) and 0x80)!=0)
            throw IOException("Pacote MPEG-TS inválido ou erro de transporte")
        val pid=((byte(1) and 0x1f) shl 8) or byte(2)
        val control=(byte(3) ushr 4) and 3
        if(control==0 || (byte(3) and 0xc0)!=0)
            throw IOException("Pacote MPEG-TS sem dados válidos ou ainda criptografado")
        var discontinuity=false
        if((control and 2)!=0) {
            val adaptationLength=byte(4)
            if(adaptationLength>183)
                throw IOException("Campo de adaptação MPEG-TS corrompido")
            if(adaptationLength>0 && (byte(5) and 0x80)!=0)
                discontinuity=true
        }
        if(pid!=0x1fff) {
            if(discontinuity) lastPayloadCounter[pid]=-1
            if((control and 1)!=0) {
                val current=byte(3) and 15
                val previous=lastPayloadCounter[pid]
                if(previous>=0 && current!=((previous+1) and 15))
                    throw IOException("Perda ou descontinuidade não sinalizada nos pacotes MPEG-TS")
                lastPayloadCounter[pid]=current
            }
        }
        segmentPackets++
    }
}
