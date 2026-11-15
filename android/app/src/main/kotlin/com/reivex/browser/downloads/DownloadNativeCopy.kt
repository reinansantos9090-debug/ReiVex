package com.reivex.browser.downloads

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes a completed temporary Media3 MP4 to its public destination without
 * another private staging copy. Uses NDK sendfile when the provider supports
 * kernel-side transfer, otherwise a 512 KiB buffer.
 */
internal object DownloadNativeCopy {
    private const val CHUNK = 4L * 1024L * 1024L
    private const val BUFFER = 512 * 1024
    private val available: Boolean = runCatching {
        System.loadLibrary("reivex_fastio")
        true
    }.getOrDefault(false)

    private external fun sendChunk(input: FileDescriptor, output: FileDescriptor, bytes: Long): Long

    suspend fun copyToUri(context: Context, source: File, destination: Uri): Long =
        withContext(Dispatchers.IO) {
            var total = 0L
            context.contentResolver.openFileDescriptor(destination, "rw")?.use { descriptor ->
                FileInputStream(source).use { input ->
                    FileOutputStream(descriptor.fileDescriptor).use { output ->
                        val buffer = ByteArray(BUFFER)
                        var nativeAllowed = available
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytes = if (nativeAllowed)
                                runCatching { sendChunk(input.fd, output.fd, CHUNK) }.getOrDefault(-2L)
                            else -2L
                            if (bytes == 0L) break
                            if (bytes > 0L) {
                                total += bytes
                                continue
                            }
                            nativeAllowed = false
                            // Read/write both share the same file descriptors,
                            // so a partial sendfile + fallback resumes safely.
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            total += count
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
            } ?: throw IOException("Não foi possível abrir MP4 de destino")
            if (total != source.length() || total <= 0L)
                throw IOException("MP4 não foi copiado integralmente")
            total
        }
}
