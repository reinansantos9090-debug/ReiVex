/** Proven URI aliases of an app-owned output. A title or filename alone never proves a duplicate. */
internal object DownloadFileKeys {
    fun fileKeys(context: Context,value: String): Set<String> = buildSet {
        val uri=Uri.parse(value)
        add("uri:$value")
        if(uri.authority==context.packageName+".downloads")return@buildSet
        fun mediaKey(u: Uri) {
            if(u.authority=="media" && u.pathSegments.lastOrNull()?.toLongOrNull()!=null) {
                val volume=u.pathSegments.firstOrNull().orEmpty().let {if(it=="external_primary")"external" else it}
                add("media:$volume:${u.lastPathSegment}")
            }
        }
        mediaKey(uri)
        if(Build.VERSION.SDK_INT>=29 && uri.scheme=="content")runCatching {MediaStore.getMediaUri(context,uri)?.let(::mediaKey)}
        if(uri.authority=="com.android.externalstorage.documents")runCatching {
            add("document:"+uri.authority+":"+DocumentsContract.getDocumentId(uri))
        }
        runCatching {
            if(uri.scheme=="content")context.contentResolver.openFileDescriptor(uri,"r")?.use {fd ->
                val stat=Os.fstat(fd.fileDescriptor)
                if(OsConstants.S_ISREG(stat.st_mode) && stat.st_ino>0)add("inode:${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}")
            } else if(uri.scheme=="file" || uri.scheme==null) {
                val f=File(if(uri.scheme=="file")uri.path!! else value)
                val stat=Os.stat(f.path)
                if(OsConstants.S_ISREG(stat.st_mode) && stat.st_ino>0)add("inode:${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}")
            }
        }
    }
}
