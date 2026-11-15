package com.reivex.browser.downloads

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Native FFmpeg/JNI copy-only remux. Called *after* a download completes.
 *
 * Writes directly to the destination's pending MediaStore/SAF document via
 * FFmpegKit's SAF protocol: no second full-sized private video and no
 * re-encoding. An unsupported codec/format returns false and the Android
 * MediaMuxer/Media3 fallback handles it without losing the original.
 */
internal object DownloadFfmpegRemux {
    private const val TAG = "ReiVexFfmpegRemux"
    private val lock = Mutex()

    suspend fun tryRemux(context: Context, input: Uri, destination: Uri): Boolean =
        lock.withLock {
            val inputArgument = argument(context, input, false) ?: return@withLock false
            val outputArgument = argument(context, destination, true) ?: return@withLock false
            val args = arrayOf(
                "-hide_banner", "-loglevel", "error", "-nostdin",
                "-xerror", "-err_detect", "explode",
                "-fflags", "+genpts", "-i", inputArgument,
                // Preserve every source stream, including alternate audio.
                // Unsupported subtitles/codec cause fallback, not silent loss.
                "-map", "0", "-c", "copy",
                "-avoid_negative_ts", "make_zero",
                "-f", "mp4", "-y", outputArgument
            )
            try {
                withContext(Dispatchers.IO) {
                    suspendCancellableCoroutine { continuation ->
                        val session = AtomicReference<com.arthenica.ffmpegkit.FFmpegSession?>(null)
                        continuation.invokeOnCancellation {
                            session.get()?.let { FFmpegKit.cancel(it.sessionId) }
                        }
                        try {
                            val started = FFmpegKit.executeWithArgumentsAsync(args, { finished ->
                                if (continuation.isActive)
                                    continuation.resume(ReturnCode.isSuccess(finished.returnCode))
                            })
                            session.set(started)
                            if (!continuation.isActive) FFmpegKit.cancel(started.sessionId)
                        } catch (error: Exception) {
                            Log.i(TAG, "Native remux unavailable: " + error.javaClass.simpleName)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.i(TAG, "Keeping Android remux fallback: " + error.javaClass.simpleName)
                false
            } catch (error: LinkageError) {
                Log.i(TAG, "Native FFmpeg libraries not loadable; using Android fallback")
                false
            }
        }

    private fun argument(context: Context, uri: Uri, output: Boolean): String? =
        when (uri.scheme) {
            "file" -> uri.path?.takeIf { it.isNotBlank() }
            "content" -> runCatching {
                if (output) FFmpegKitConfig.getSafParameterForWrite(context,uri)
                else FFmpegKitConfig.getSafParameterForRead(context,uri)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            else -> null
        }
}
