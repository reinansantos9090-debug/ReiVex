package com.reivex.browser.downloads

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Read-only access to ReiVex-owned downloads for Android's file pickers and media apps. */
class DownloadProvider : ContentProvider() {
    override fun onCreate() = true

    private fun item(uri: Uri): DownloadItem? = uri.pathSegments.firstOrNull()
        ?.let { DownloadStore(requireNotNull(context)).get(it) }

    override fun getType(uri: Uri): String? = item(uri)?.mime

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        order: String?,
    ): Cursor {
        val item = item(uri) ?: throw FileNotFoundException()
        val names = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(names).apply {
            addRow(names.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> item.name
                    OpenableColumns.SIZE -> if (item.total > 0) item.total else item.bytes
                    else -> null
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Somente leitura")
        val item = item(uri) ?: throw FileNotFoundException()
        if (item.state in setOf("CANCELLED", "CORRUPT")) throw FileNotFoundException("Download indisponível")
        if (item.state == "COMPLETE" && item.output.isNotBlank()) {
            return context!!.contentResolver.openFileDescriptor(Uri.parse(item.output), "r")
                ?: throw FileNotFoundException("Arquivo concluído indisponível")
        }
        val store = DownloadStore(context!!)
        val transfer = DownloadTransferFile(context!!, store, item.id)
        return transfer.uri?.let { context!!.contentResolver.openFileDescriptor(it, "r")
            ?: throw FileNotFoundException("Download público não está disponível") }
            ?: ParcelFileDescriptor.open(store.partial(item.id), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
