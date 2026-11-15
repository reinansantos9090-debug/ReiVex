package com.reivex.browser.downloads

import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadTsSegmentIntegrityTest {
    private fun packet(counter:Int):ByteArray=ByteArray(188){0xff.toByte()}.apply {
        this[0]=0x47
        this[1]=0x01 // PID 256
        this[2]=0x00
        this[3]=(0x10 or (counter and 15)).toByte()
    }

    @Test fun acceptsUnalignedNetworkReadsAndContinuousSegments() {
        val guard=DownloadTsSegmentIntegrity()
        val first=packet(0)+packet(1)
        guard.accept(first,0,113)
        guard.accept(first,113,first.size-113)
        guard.finishSegment()
        val next=packet(2)
        guard.accept(next,0,next.size)
        guard.finishSegment()
    }

    @Test fun rejectsMissingTransportPackets() {
        val guard=DownloadTsSegmentIntegrity()
        val first=packet(0)
        guard.accept(first,0,first.size)
        guard.finishSegment()
        val skipped=packet(2)
        assertThrows(IOException::class.java) {
            guard.accept(skipped,0,skipped.size)
        }
    }

    @Test fun rejectsCorruptOrTruncatedSegments() {
        val malformed=packet(0).apply { this[1]=0x81.toByte() }
        val guard=DownloadTsSegmentIntegrity()
        assertThrows(IOException::class.java) {
            guard.accept(malformed,0,malformed.size)
        }
        val incomplete=DownloadTsSegmentIntegrity()
        incomplete.accept(packet(0),0,120)
        assertThrows(IOException::class.java) { incomplete.finishSegment() }
    }
}
