package com.reivex.browser.downloads

import java.util.Locale

/**
 * Distinguish general downloads from video streams. A browser page title is
 * not evidence that its APK/PDF/ZIP download contains a movie.
 *
 * Preserve explicit filenames first; never infer APK from ZIP magic alone
 * because APK, JAR, EPUB and Office documents are all ZIP-based.
 */
internal object DownloadFileKind {
    private val nonVideoExtensions=setOf(
        "apk","apks","apkm","xapk","aab","zip","7z","rar","tar","gz","bz2","xz","zst",
        "jar","war","epub","pdf","txt","csv","json","xml","html","htm","md","log",
        "doc","docx","xls","xlsx","ppt","pptx","odt","ods","odp",
        "exe","msi","dmg","deb","rpm","iso","torrent","db","sqlite","sql",
        "png","jpg","jpeg","gif","webp","bmp","svg","ico","heic","avif",
        "mp3","m4a","aac","wav","flac","ogg","opus","wma","mid","midi"
    )
    private val mimeExtension=mapOf(
        "application/vnd.android.package-archive" to "apk",
        "application/x-android-package" to "apk",
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/x-7z-compressed" to "7z",
        "application/x-rar-compressed" to "rar",
        "application/epub+zip" to "epub",
        "application/java-archive" to "jar",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/webp" to "webp",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/flac" to "flac",
        "text/plain" to "txt"
    )
    private val mediaManifestMime=setOf(
        "application/vnd.apple.mpegurl","application/x-mpegurl",
        "application/dash+xml","application/vnd.ms-sstr+xml"
    )
    fun extension(name:String):String =
        name.substringBefore('?').substringBefore('#').substringAfterLast('/',name)
            .substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isOrdinaryFileName(name:String?):Boolean =
        !name.isNullOrBlank() && extension(name) in nonVideoExtensions

    fun ordinaryExtensionFromMime(mime:String?):String? =
        mimeExtension[mime.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)]

    fun isOrdinaryMime(mime:String?):Boolean {
        val type=mime.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if(type.isBlank() || type=="application/octet-stream" || type=="binary/octet-stream" ||
            type in mediaManifestMime) return false
        return type.startsWith("audio/") || type.startsWith("image/") ||
            type.startsWith("text/") ||
            type.startsWith("application/") && !type.startsWith("application/vnd.apple.mpegurl")
    }

    fun isVideoDownload(
        url:String, disposition:String?, mime:String?, capturedVideo:Boolean
    ):Boolean {
        val suppliedName=DownloadResourceIdentity.disposition(disposition)
            ?: DownloadResourceIdentity.fromUrl(url)
        // Explicit APK/PDF/ZIP names or a non-video content-type always win
        // over a generic GitHub/site page title.
        if(isOrdinaryFileName(suppliedName) || isOrdinaryMime(mime)) return false
        val type=mime.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if(capturedVideo || type.startsWith("video/") || type in mediaManifestMime)
            return true
        return extension(suppliedName.orEmpty()) in DownloadContainerDetector.knownExtensions ||
            extension(suppliedName.orEmpty()) in setOf("m3u8","mpd")
    }

    private fun starts(data:ByteArray,magic:String):Boolean =
        data.size>=magic.length && magic.indices.all {data[it]==magic[it].code.toByte()}

    fun signatureExtension(header:ByteArray):String? = when {
        starts(header,"%PDF-") -> "pdf"
        header.size>=4 && header[0]==0x50.toByte() && header[1]==0x4b.toByte() &&
            (header[2]==0x03.toByte() && header[3]==0x04.toByte() ||
                header[2]==0x05.toByte() && header[3]==0x06.toByte() ||
                header[2]==0x07.toByte() && header[3]==0x08.toByte()) -> "zip"
        header.size>=8 && header.take(8)==listOf(0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)
            .map(Int::toByte) -> "png"
        header.size>=3 && header[0]==0xff.toByte() && header[1]==0xd8.toByte() &&
            header[2]==0xff.toByte() -> "jpg"
        starts(header,"GIF87a") || starts(header,"GIF89a") -> "gif"
        else -> null
    }

    /**
     * Correct a non-video falsely named .mp4/.bin. The server's original
     * filename or a specific MIME can distinguish APK from ZIP; bytes alone
     * must never guess an APK from the common PK signature.
     */
    fun recoveredName(
        initial:String, responseName:String?, responseMime:String?, header:ByteArray
    ):String? {
        // A valid video signature always wins over contradictory server MIME
        // or filename hints. Never relabel a playable MP4 as an APK.
        if(DownloadContainerDetector.sniff(header)!=null) return null
        val nameExtension=extension(initial)
        val detected=signatureExtension(header)
        val declared=ordinaryExtensionFromMime(responseMime)
        val reliableServerName=responseName?.takeIf(::isOrdinaryFileName)
        val serverExtension=reliableServerName?.let(::extension)
        val realExtension=when {
            detected=="zip" && serverExtension in setOf("apk","apks","apkm","xapk","aab",
                "zip","jar","war","epub","docx","xlsx","pptx","odt","ods","odp") ->
                serverExtension
            detected=="zip" && declared in setOf("apk","zip","epub","jar") -> declared
            detected=="zip" -> "zip"
            detected!=null -> detected
            declared!=null -> declared
            else -> null
        } ?: return null

        if(nameExtension==realExtension ||
            nameExtension !in DownloadContainerDetector.knownExtensions+setOf("bin","m3u8","mpd"))
            return null
        if(serverExtension==realExtension && !reliableServerName.isNullOrBlank())
            return reliableServerName
        return DownloadContainerDetector.outputName(initial,realExtension)
    }
}
