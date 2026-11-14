package com.reivex.browser.downloads

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.IOException

/** Only automatic browser captures are gated. Probe local bytes, never make another network request. */
internal object DownloadCaptureGuard {
    data class Facts(val duration: Double,val width: Int,val height: Int)
    class Rejected(message: String): IOException(message)
    private fun facts(context: Context,file: File?,uri: Uri?): Facts? {
        val retriever=runCatching {MediaMetadataRetriever()}.getOrNull() ?: return null
        return try {
            if(file!=null)retriever.setDataSource(file.path)
            else {require(uri?.scheme in setOf("file","content"));retriever.setDataSource(context,uri!!)}
            val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: return null
            val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            if(duration>0 && width>0 && height>0)Facts(duration,width,height) else null
        } catch(_: Exception) {null} finally {runCatching {retriever.release()}}
    }
    private fun valid(facts: Facts)=facts.duration>=120 && facts.width.toDouble()/facts.height in 0.45..3.2
    fun probe(context: Context,store: DownloadStore,id: String,source: Uri,bytes: Long,complete: Boolean = false) {
        val item=store.get(id) ?: return
        if(!item.json.has("playerCapture") || item.json.optBoolean("captureApproved"))return
        val stage=item.json.optInt("captureProbeStage")
        val thresholds=longArrayOf(256L*1024,2L*1024*1024,16L*1024*1024,128L*1024*1024)
        if(!complete && (stage>=thresholds.size || bytes<thresholds[stage]))return
        store.mutate(id){it.put("captureProbeStage",stage+1)}
        val actual=if(source.scheme=="file") facts(context,File(source.path.orEmpty()),null) else facts(context,null,source)
        val expected=item.json.getJSONObject("playerCapture").optDouble("duration")
        val matches=actual!=null && valid(actual) && PlayerMediaCandidate.durationMatches(expected,actual.duration)
        if(actual!=null && (actual.width.toDouble()/actual.height !in 0.45..3.2 || (complete && !matches)))throw Rejected("O recurso capturado não corresponde a um episódio: vídeo curto ou formato de banner. Use o download do próprio site.")
        if(actual==null && complete)throw Rejected("Não foi possível confirmar o vídeo capturado. Use o link do download do próprio site.")
        if(matches)approve(context,store,id)
    }
    fun approveHls(context: Context,store: DownloadStore,id: String,duration: Double) {
        val item=store.get(id) ?: return
        val capture=item.json.optJSONObject("playerCapture") ?: return
        val expected=capture.optDouble("duration")
        if(duration<120 || !PlayerMediaCandidate.durationMatches(expected,duration))throw Rejected("A playlist não corresponde à duração do episódio no player.")
        approve(context,store,id)
    }
    private fun approve(context: Context,store: DownloadStore,id: String) {
        val item=store.get(id) ?: return
        if(item.state !in DownloadState.pending && item.state!="PAUSED")return
        store.mutate(id){it.put("captureApproved",true)}
    }

}
