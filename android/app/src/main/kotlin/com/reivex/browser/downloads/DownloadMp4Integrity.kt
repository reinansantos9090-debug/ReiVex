package com.reivex.browser.downloads

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Checks a FINISHED MP4 before it is published. Merely opening an MP4 header
 * does not prove that its samples, audio, and timeline are usable.
 *
 * Reads encoded samples without decoding/re-encoding them. Never reads the
 * incomplete source while it is still being downloaded.
 */
internal object DownloadMp4Integrity {
    data class SourceTracks(val hasAudio: Boolean, val durationUs: Long?)

    fun sourceTracks(context: Context, source: Uri): SourceTracks? {
        val extractor=MediaExtractor()
        return try {
            extractor.setDataSource(context,source,null)
            val mimes=(0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            if(mimes.none {it.startsWith("video/")}) return null
            val duration=(0 until extractor.trackCount).mapNotNull {
                extractor.getTrackFormat(it).let { format ->
                    if(format.containsKey(MediaFormat.KEY_DURATION))
                        format.getLong(MediaFormat.KEY_DURATION).takeIf {d -> d>0L}
                    else null
                }
            }.maxOrNull()
            SourceTracks(mimes.any {it.startsWith("audio/")},duration)
        } catch(_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * Fast final check for an MP4 already remuxed by native FFmpeg with
     * -xerror. FFmpeg has consumed every packet and constructed the index;
     * a second whole-file read adds I/O without decoding the bitstream.
     *
     * Validate stream presence, declared duration, and seekable video/audio
     * samples near the beginning, middle and end. Unknown durations or
     * suspicious timelines use the exhaustive verifier instead.
     */
    suspend fun verifyFast(
        context: Context,
        output: Uri,
        expectedAudio: Boolean?,
        expectedDurationUs: Long?
    ): Boolean = withContext(Dispatchers.IO) {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(context,output,null)
            val formats=(0 until extractor.trackCount).map(extractor::getTrackFormat)
            val mimes=formats.map {it.getString(MediaFormat.KEY_MIME).orEmpty()}
            val videoIndices=mimes.indices.filter {mimes[it].startsWith("video/")}
            val audioIndices=mimes.indices.filter {mimes[it].startsWith("audio/")}
            if(videoIndices.isEmpty() || expectedAudio==true && audioIndices.isEmpty())
                return@withContext false
            val duration=videoIndices.mapNotNull {i ->
                val format=formats[i]
                if(format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION).takeIf {it>0L}
                else null
            }.maxOrNull() ?: return@withContext false
            if(expectedDurationUs!=null && expectedDurationUs>0L) {
                val tolerance=max(12_000_000L,expectedDurationUs/25L)
                if(abs(duration-expectedDurationUs)>tolerance)
                    return@withContext false
            }
            (videoIndices+audioIndices).forEach(extractor::selectTrack)
            val positions=listOf(0L,duration/3L,duration*2L/3L,
                (duration-1_000_000L).coerceAtLeast(0L)).distinct()
            val observedAudio=mutableSetOf<Int>()
            for((checkpointIndex,position) in positions.withIndex()) {
                currentCoroutineContext().ensureActive()
                extractor.seekTo(position,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var observedVideo=false
                var firstTime=-1L
                for(step in 0 until 128) {
                    val sampleIndex=extractor.sampleTrackIndex
                    if(sampleIndex<0) break
                    val time=extractor.sampleTime
                    if(time<0L || extractor.sampleSize<=0L ||
                        (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)
                        return@withContext false
                    if(firstTime<0L) firstTime=time
                    if(sampleIndex in videoIndices) observedVideo=true
                    if(sampleIndex in audioIndices) observedAudio+=sampleIndex
                    if(!extractor.advance()) break
                }
                if(!observedVideo || firstTime<0L) return@withContext false
                // The first playable frame must not start many seconds
                // after opening the file; tolerate normal GOP keyframes.
                if(checkpointIndex==0 && firstTime>5_000_000L)
                    return@withContext false
                if(position>0L && abs(position-firstTime)>45_000_000L)
                    return@withContext false
            }
            // Empty audio tracks are not an acceptable successful remux.
            if(expectedAudio==true && !observedAudio.containsAll(audioIndices))
                return@withContext false
            true
        } catch(cancelled: CancellationException) {
            throw cancelled
        } catch(_: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    private class Track(val mime:String) {
        var samples=0L
        var firstUs=Long.MAX_VALUE
        var lastUs=-1L
        var maxUs=-1L
        var sync=false
    }

    /**
     * expectedAudio is taken from the original container (when readable).
     * expectedDurationUs is preferentially the HLS playlist duration, or a
     * reliable duration exposed by the original media container.
     *
     * A failed check does not mean the file should be deleted: the caller can
     * retry another conversion method or retain the original, correctly named
     * container instead of publishing a bad MP4.
     */
    suspend fun verify(
        context: Context,
        output: Uri,
        expectedAudio: Boolean? = null,
        expectedDurationUs: Long? = null,
        normalizedStart: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(context,output,null)
            val stats=(0 until extractor.trackCount).map {
                Track(extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty())
            }
            val mediaIndices=stats.indices.filter {i ->
                stats[i].mime.startsWith("video/") || stats[i].mime.startsWith("audio/")
            }
            if(mediaIndices.none {stats[it].mime.startsWith("video/")}) return@withContext false
            if(expectedAudio==true && mediaIndices.none {stats[it].mime.startsWith("audio/")})
                return@withContext false

            mediaIndices.forEach(extractor::selectTrack)
            val buffer=ByteBuffer.allocateDirect(8*1024*1024)
            var samples=0L
            while(true) {
                if(samples%2048L==0L) currentCoroutineContext().ensureActive()
                val index=extractor.sampleTrackIndex
                if(index<0) break
                if(index !in stats.indices || index !in mediaIndices) return@withContext false
                val time=extractor.sampleTime
                if(time<0L || (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)
                    return@withContext false
                if(Build.VERSION.SDK_INT>=28 && extractor.sampleSize>buffer.capacity())
                    return@withContext false
                buffer.clear()
                val size=extractor.readSampleData(buffer,0)
                if(size<=0) return@withContext false
                val track=stats[index]
                if(track.samples>0L) {
                    // Small negative presentation-time steps are legal with
                    // reordered frames. Large jumps or gaps indicate a broken
                    // MP4 timeline or missing media segments.
                    if(time<track.lastUs-2_000_000L || time>track.lastUs+45_000_000L)
                        return@withContext false
                }
                if(track.samples==0L) track.firstUs=time
                track.lastUs=time
                track.maxUs=max(track.maxUs,time)
                track.samples++
                if((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC)!=0)
                    track.sync=true
                samples++
                if(!extractor.advance()) break
            }
            val videos=stats.filter {it.mime.startsWith("video/")}
            val audios=stats.filter {it.mime.startsWith("audio/")}
            if(videos.any {it.samples<2L || !it.sync || it.maxUs<=it.firstUs})
                return@withContext false
            if(audios.any {it.samples==0L}) return@withContext false
            val videoStart=videos.minOf {it.firstUs}
            val videoEnd=videos.maxOf {it.maxUs}
            if(normalizedStart && videoStart>5_000_000L) return@withContext false
            if(audios.isNotEmpty()) {
                val audioStart=audios.minOf {it.firstUs}
                val audioEnd=audios.maxOf {it.maxUs}
                if(abs(audioStart-videoStart)>15_000_000L) return@withContext false
                if(abs(audioEnd-videoEnd)>max(20_000_000L,(videoEnd-videoStart)/20L))
                    return@withContext false
            }
            val observedDuration=videoEnd-videoStart
            if(expectedDurationUs!=null && expectedDurationUs>0L) {
                val tolerance=max(12_000_000L,expectedDurationUs/25L)
                if(abs(observedDuration-expectedDurationUs)>tolerance)
                    return@withContext false
            }
            true
        } catch(cancelled: CancellationException) {
            throw cancelled
        } catch(_: Exception) {
            false
        } finally {
            extractor.release()
        }
    }
}
