package com.reivex.browser.downloads

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DownloadOrdinaryFileTest {
    private val githubPage="GitHub - recloudstream/cloudstream: Android App for streaming and downloading media"
    private val apkUrl="https://github.com/recloudstream/cloudstream/releases/download/v1/CloudStream.apk"
    private val zipHeader=byteArrayOf(0x50,0x4b,0x03,0x04)+ByteArray(512)
    private val pdfHeader="%PDF-1.7\n".toByteArray()+ByteArray(512)

    @Test fun githubApkIsNotConsideredAStreamingVideo() {
        assertFalse(DownloadFileKind.isVideoDownload(
            apkUrl,null,"application/vnd.android.package-archive",false))
        assertFalse(DownloadFileKind.isVideoDownload(
            apkUrl,null,"application/octet-stream",false))
        assertFalse(DownloadFileKind.isVideoDownload(
            "https://example.com/download?id=42",
            "attachment; filename=\"cloudstream.apk\"","application/octet-stream",false))
        assertEquals("CloudStream.apk",DownloadResourceIdentity.bestName(
            null,apkUrl,"application/octet-stream"))
        assertEquals("CloudStream.apk",DownloadIdentity.suggestedName(
            githubPage,apkUrl,"CloudStream.apk"))
    }

    @Test fun mediaPageCannotOverrideNonVideoExtension() {
        assertEquals("CloudStream.apk",DownloadIdentity.capturedFileName(
            githubPage,apkUrl,"application/vnd.android.package-archive"))
        assertEquals("manual.pdf",DownloadIdentity.capturedFileName(
            "Documentation","https://example.com/manual.pdf","application/pdf"))
        assertEquals("files.zip",DownloadIdentity.capturedFileName(
            "Project archive","https://example.com/files.zip","application/zip"))
        assertEquals("releases.zip",DownloadIdentity.suggestedName(
            "Project on GitHub","https://example.com/releases.zip","releases.zip"))
        assertEquals("guide.pdf",DownloadResourceIdentity.bestName(
            "attachment; filename=\"guide.pdf\"",
            "https://example.com/fetch","video/mp4"))
    }

    @Test fun recoverMislabeledApkButNeverGuessApkFromZipBytesAlone() {
        assertEquals("CloudStream.apk",DownloadFileKind.recoveredName(
            "GitHub - recloudstream cloudstream.mp4","CloudStream.apk",
            "application/octet-stream",zipHeader))
        assertEquals("CloudStream.zip",DownloadFileKind.recoveredName(
            "CloudStream.mp4",null,"application/octet-stream",zipHeader))
        assertEquals("CloudStream.apk",DownloadFileKind.recoveredName(
            "CloudStream.mp4",null,"application/vnd.android.package-archive",zipHeader))
        assertNull(DownloadFileKind.recoveredName(
            "CloudStream.apk","CloudStream.apk",null,zipHeader))
    }

    @Test fun recoverMislabeledPdfAndOtherNonVideoFiles() {
        assertEquals("Document.pdf",DownloadFileKind.recoveredName(
            "Document.mp4",null,"application/octet-stream",pdfHeader))
        assertEquals("Document.pdf",DownloadFileKind.recoveredName(
            "Document.bin",null,"application/pdf",pdfHeader))
        assertNull(DownloadFileKind.recoveredName(
            "Video.mp4","Project.apk","application/vnd.android.package-archive",
            byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()))
    }

    @Test fun keepActualVideoFlowsIntact() {
        val page="Solo Leveling - Episódio 01"
        val videoUrl="https://cdn.example/solo.mp4"
        assertTrue(DownloadFileKind.isVideoDownload(videoUrl,null,"video/mp4",false))
        assertTrue(DownloadFileKind.isVideoDownload(
            "https://cdn.example/index.m3u8",null,
            "application/vnd.apple.mpegurl",false))
        assertEquals("Solo Leveling - Episódio 01.mp4",
            DownloadIdentity.capturedFileName(page,videoUrl,"video/mp4"))
        assertEquals("Solo Leveling - Episódio 01.mp4",
            DownloadIdentity.suggestedName(page,"https://cdn.example/index.m3u8","index.m3u8"))
    }

    @Test fun unknownGenericDownloadMustNotBecomeGithubMp4() {
        assertFalse(DownloadFileKind.isVideoDownload(
            "https://github.com/some/project/releases/download/v1/opaque-token",
            null,"application/octet-stream",false))
        assertEquals("download.bin",DownloadIdentity.suggestedName(
            githubPage,"https://example.com/a/0123456789abcdef0123456789abcdef",
            "download.bin"))
    }
}
