package com.reivex.browser.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DownloadState {
    val pending = setOf("QUEUED", "DOWNLOADING", "WAITING", "VERIFYING", "SAVING")
    val labels = mapOf("QUEUED" to "Na fila", "DOWNLOADING" to "Baixando", "WAITING" to "Aguardando conexão / nova tentativa",
        "VERIFYING" to "Preparando arquivo", "SAVING" to "Salvando na pasta", "PAUSED" to "Pausado",
        "COMPLETE" to "Concluído", "ERROR" to "Falhou", "CORRUPT" to "Integridade não confere", "CANCELLED" to "Cancelado")
}

data class DownloadItem(val json: JSONObject) {
    val id: String get() = json.getString("id")
    // The app-controlled virtual URI protects the original episode filename from
    // a generic CDN/provider rename. When remux changes TS <-> MP4, the new
    // container extension recorded by the engine must take precedence instead.
    val name: String get() {
        val stored = json.optString("name")
        if(json.optBoolean("serverNameResolved")) return stored
        val virtual = Uri.parse(json.optString("virtualUri")).lastPathSegment
            ?.takeIf { it.isNotBlank() } ?: return stored
        val mimeExtension = when (json.optString("mime").substringBefore(';').lowercase()) {
            "video/mp4" -> "mp4"
            "video/mp2t" -> "ts"
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "video/x-msvideo" -> "avi"
            "video/quicktime" -> "mov"
            "video/x-ms-wmv" -> "wmv"
            "video/x-flv" -> "flv"
            "video/3gpp" -> "3gp"
            else -> null
        }
        val storedExtension = stored.substringAfterLast('.', "").lowercase()
        val virtualExtension = virtual.substringAfterLast('.', "").lowercase()
        if (mimeExtension != null && storedExtension == mimeExtension &&
            virtualExtension != mimeExtension) return stored
        return virtual
    }
    val state: String get() = json.optString("state", "QUEUED")
    val bytes: Long get() = json.optLong("bytes")
    val total: Long get() = json.optLong("total", -1)
    val generation: Long get() = json.optLong("generation")
    val mime: String get() = json.optString("mime", "application/octet-stream")
    val output: String get() = json.optString("output")
    val virtualUri: Uri get() = Uri.parse(json.getString("virtualUri"))
}

/** Queue records live in ReiVex's private download database. */
class DownloadStore(context: Context) {
    private val context = context.applicationContext
    private val db = ReiVexDownloadsDatabase(context)
    val directory: File get() = File(context.filesDir, "native-downloads").apply { mkdirs() }
    fun partial(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(directory, "$id.part")
    }
    suspend fun ready() = withContext(Dispatchers.IO) {
        db.read { Unit }
    }
    fun all(): List<DownloadItem> = db.read { connection ->
        connection.rawQuery("SELECT data FROM native_downloads ORDER BY rowid DESC", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(DownloadItem(JSONObject(cursor.getString(0)))) }
        }
    }
    fun get(id: String): DownloadItem? = db.read { connection ->
        connection.rawQuery("SELECT data FROM native_downloads WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) DownloadItem(JSONObject(it.getString(0))) else null
        }
    }
    fun mutate(id: String, change: (JSONObject) -> Unit): DownloadItem = db.transaction { connection ->
        val data = connection.rawQuery("SELECT data FROM native_downloads WHERE id=?", arrayOf(id)).use {
            check(it.moveToFirst()) { "Download não encontrado" }; JSONObject(it.getString(0))
        }
        change(data)
        connection.update("native_downloads", ContentValues().apply { put("data", data.toString()) }, "id=?", arrayOf(id))
        DownloadItem(data)
    }
    suspend fun enqueue(url: String, name: String, destination: String, expectedHash: String, hashType: String,
        headers: Map<String, String>, automaticName: Boolean = false,
        playerCapture: JSONObject? = null, hlsHint: Boolean = false,
        detectedSize: Long? = null, detectedExact: Boolean = false): DownloadItem = withContext(Dispatchers.IO) {
        ready()
        DownloadHttp.validateUrl(url)
        require(url.length<=16384) { "O link é longo demais" }
        require(headers.values.all { it.length<=16384 && it.none { c -> c=='\r' || c=='\n' } }) { "Cabeçalho de autenticação inválido" }
        require(name.isNotBlank() && name.length <= 200 && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.code < 32 }) { "Nome de arquivo inválido" }
        require(hashType in setOf("SHA-256", "SHA-1", "MD5"))
        val hash = expectedHash.trim().lowercase()
        require(hash.isEmpty() || hash.matches(Regex(when(hashType) { "MD5" -> "[0-9a-f]{32}"; "SHA-1" -> "[0-9a-f]{40}"; else -> "[0-9a-f]{64}" }))) { "Hash de referência inválido" }
        if(playerCapture!=null)require(PlayerMediaCandidate.parse(playerCapture)?.url==url.trim()) {"Não foi possível confirmar o player principal"}
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("url", url).put("headers", JSONObject(headers))
        val signature = digest(url.toByteArray())
        val data = JSONObject().put("id", id).put("name", name).put("state", "QUEUED").put("bytes", 0)
            .put("total", -1).put("generation", 0).put("destination", destination).put("expectedHash", hash)
            .put("hashType", hashType).put("secret", DownloadSecrets.encrypt(payload.toString())).put("signature", signature)
            .put("created", System.currentTimeMillis()).put("automaticName",automaticName)
            .put("hls",hlsHint)
            // Domain-only diagnostics. The URL path, query and tokens stay
            // encrypted with the download request and are never logged here.
            .put("sourceHost",DownloadHttp.validateUrl(url).host.orEmpty().take(255))
            .put("virtualUri", "content://${context.packageName}.downloads/$id/${Uri.encode(name)}")
            .put("mime", MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream")
        // Discovered size is display metadata only. Never use an HLS estimate
        // as the transfer's authoritative total or expected byte count.
        detectedSize?.takeIf { it>0L }?.let { size ->
            data.put("detectedSize",size).put("detectedSizeExact",detectedExact)
        }
        if(playerCapture!=null) {val evidence=JSONObject(playerCapture.toString());evidence.remove("url");evidence.remove("page");data.put("playerCapture",evidence)}
        // Sharing the same URL twice must reopen its existing job instead of
        // creating a duplicate file, restarting bytes, or throwing an error.
        // The decision and insertion are atomic in the same SQLite transaction.
        val existingId=db.transaction { connection ->
            val duplicate = connection.rawQuery("SELECT data FROM native_downloads", null).use { c ->
                var found: String? = null
                while(c.moveToNext()) {
                    val old=JSONObject(c.getString(0))
                    if(old.optString("signature")==signature &&
                        old.optString("state") !in setOf("CANCELLED","CORRUPT","ERROR")) {
                        found=old.optString("id")
                        break
                    }
                }
                found
            }
            if(duplicate==null) {
                connection.insertOrThrow("native_downloads", null, ContentValues().apply {
                    put("id", id); put("data", data.toString())
                })
            }
            duplicate
        }
        if(existingId!=null) {
            // A fresh share can include refreshed headers for the same URL.
            // Wake a waiting/paused job, but never modify an actively writing
            // or already finished download. Keep resumable files and ETag.
            val earlier=get(existingId) ?: error("Download existente não encontrado")
            if(earlier.state !in setOf("COMPLETE","CANCELLED","CORRUPT","ERROR")) {
                // New metadata from the originating app can improve the displayed
                // size for an existing job. Never change the transfer's total.
                mutate(existingId) {
                    val existing=it.optLong("detectedSize")
                    val isExact=it.optBoolean("detectedSizeExact")
                    if(detectedSize!=null && detectedSize>0L &&
                        (existing<=0L || (detectedExact && !isExact))) {
                        it.put("detectedSize",detectedSize)
                            .put("detectedSizeExact",detectedExact)
                    }
                    if(earlier.state in setOf("WAITING","PAUSED")) {
                        it.put("secret",DownloadSecrets.encrypt(payload.toString()))
                            .put("state","QUEUED").put("nextAttempt",0)
                            .put("attempts",0).put("message","")
                    }
                }
            }
            return@withContext get(existingId) ?: earlier
        }
        // Downloads are stored in ReiVex's private queue, without creating library records.
        get(id)!!
    }
    fun profile(url: String): JSONObject = db.read { c ->
        c.rawQuery("SELECT secret FROM native_download_profiles WHERE id=?",arrayOf(digest(url.trim().toByteArray()))).use {
            if(it.moveToFirst()) JSONObject(DownloadSecrets.decrypt(it.getString(0))) else JSONObject()
        }
    }
    fun saveProfile(url: String,data: JSONObject) {
        DownloadHttp.validateUrl(url)
        require(data.toString().length<65536)
        val hash=data.optString("hash").trim().lowercase()
        val size=when(data.optString("hashType","SHA-256")) { "MD5"->32; "SHA-1"->40; else->64 }
        require(hash.isBlank() || hash.matches(Regex("[0-9a-f]{$size}"))) { "Hash de referência inválido" }
        db.transaction { c -> c.insertWithOnConflict("native_download_profiles",null,ContentValues().apply {
            put("id",digest(url.trim().toByteArray())); put("secret",DownloadSecrets.encrypt(data.toString()))
        },android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) }
    }
    fun secret(item: DownloadItem): JSONObject = JSONObject(DownloadSecrets.decrypt(item.json.getString("secret")))
    fun complete(id: String, output: Uri, finalBytes: Long = -1L) {
        db.transaction { connection ->
            val data = connection.rawQuery("SELECT data FROM native_downloads WHERE id=?", arrayOf(id)).use { it.moveToFirst(); JSONObject(it.getString(0)) }
            check(data.optString("state")=="SAVING") { "O download não está na etapa de publicação" }
            data.put("state", "COMPLETE").put("output", output.toString()).put("message", "Concluído")
                .put("name", DownloadItem(data).name)
            // PR #296: the exported MP4 can have a different byte length from its TS source.
            if (finalBytes > 0L) data.put("bytes", finalBytes).put("total", finalBytes)
            connection.update("native_downloads", ContentValues().apply { put("data", data.toString()) }, "id=?", arrayOf(id))

        }
    }
    fun control(id: String, action: String) {
        val before=get(id) ?: return
        if(before.state=="SAVING") throw IllegalStateException("Aguarde o salvamento final")
        when(action) {
            "pause" -> mutate(id) { if(it.optString("state") in DownloadState.pending && it.optString("state")!="SAVING") it.put("state", "PAUSED") }
            "resume" -> { mutate(id) { if(it.optString("state") in setOf("PAUSED", "ERROR", "WAITING")) it.put("state", "QUEUED").put("attempts", 0).put("nextAttempt", 0) }
            "cancel" -> {
                var output=""
                val cancelled=mutate(id) {
                    if(it.optString("state") !in setOf("SAVING","COMPLETE")) { output=it.optString("output"); it.put("state","CANCELLED").put("speed",0) }
                }
                if(cancelled.state!="CANCELLED") return
                if(output.isNotBlank()) {
                    val removed=runCatching { val uri=Uri.parse(output)
                        if(uri.scheme=="file") File(uri.path!!).delete() else context.contentResolver.delete(uri,null,null)>0
                    }.getOrDefault(false)
                    mutate(id) { if(removed) it.put("output","") else it.put("message","Autorize novamente a pasta para remover o arquivo temporário.") }
                }
                DownloadTransferFile(context,this,id).let { target ->
                    target.uri?.let { target.discard(it) }
                }
                mutate(id) { it.remove("transferUri") }
            }
            "restart" -> { require(before.state !in setOf("DOWNLOADING","SAVING","VERIFYING","COMPLETE")); mutate(id) { require(it.optString("state") in setOf("PAUSED","ERROR","CORRUPT","CANCELLED")); it.put("restart",true).put("state","QUEUED").put("bytes",0).put("total",-1).put("attempts",0).put("nextAttempt",0) }
        }
    }
    fun updateSource(id: String,url: String,headers: Map<String,String>,hash: String,hashType: String,destination: String) {
        DownloadHttp.validateUrl(url)
        val item=get(id) ?: error("Download não encontrado")
        require(item.state in setOf("ERROR","PAUSED"))
        require(hashType in setOf("MD5","SHA-1","SHA-256"))
        val normalized=hash.trim().lowercase()
        val length=when(hashType) { "MD5"->32; "SHA-1"->40; else->64 }
        require(normalized.isBlank() || normalized.matches(Regex("[0-9a-f]{$length}"))) { "Hash inválido" }
        require(url.length<=16384 && headers.values.all { it.length<=16384 && it.none { c -> c=='\r' || c=='\n' } }) { "Link ou cabeçalho inválido" }
        val previousUrl=runCatching { secret(item).getString("url") }.getOrNull()
        val encrypted=DownloadSecrets.encrypt(JSONObject().put("url",url).put("headers",JSONObject(headers)).toString())
        var previousOutput=""
        var previousTransfer=""
        var destinationChanged=false
        mutate(id) {
            require(it.optString("state") in setOf("ERROR","PAUSED"))
            if(it.optString("destination")!=destination) {
                destinationChanged=true
                previousOutput=it.optString("output")
                previousTransfer=it.optString("transferUri")
                it.put("destination",destination).put("output","").remove("transferUri")
                it.put("restart",true).put("bytes",0).put("total",-1)
            }
            if(previousUrl!=url) {
                it.put("restart",true).put("bytes",0).put("total",-1).put("parallelDisabled",false)
                it.remove("hls");it.remove("playerCapture");it.remove("captureApproved");it.remove("captureProbeStage")
                // The editor probe described the old URL; never show it as
                // the byte count of an entirely different source.
                it.remove("detectedSize");it.remove("detectedSizeExact")
            }
            it.put("secret",encrypted).put("signature",digest(url.toByteArray())).put("expectedHash",normalized)
                .put("hashType",hashType).put("state","QUEUED").put("attempts",0).put("nextAttempt",0)
        }
        if(previousOutput.isNotBlank()) runCatching {
            val uri=Uri.parse(previousOutput)
            if(uri.scheme=="file") java.io.File(uri.path!!).delete() else context.contentResolver.delete(uri,null,null)
        }
        if(previousTransfer.isNotBlank() && previousTransfer!=previousOutput) runCatching {
            context.contentResolver.delete(Uri.parse(previousTransfer),null,null)
        }
        if(destinationChanged) partial(id).delete()
    }
    fun forget(id: String) {
        val item=get(id) ?: return
        require(item.state in setOf("COMPLETE", "CANCELLED", "ERROR", "CORRUPT"))
        require(item.state=="COMPLETE" || item.output.isBlank()) { "Conclua ou retome o salvamento antes de remover o registro" }
        db.transaction { connection ->
            connection.delete("native_downloads","id=?",arrayOf(id))
        }
        if(item.state!="COMPLETE") DownloadTransferFile(context,this,id).let { target ->
            target.uri?.let { target.discard(it) }
        }
        partial(id).delete()
    }
    companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Credential-bearing URLs and headers never appear in logs, WorkData or plaintext preferences. */
internal object DownloadSecrets {
    @Synchronized private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val currentAlias = "reivex.downloads"
        (store.getKey(currentAlias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("reivex.downloads",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(text: String): String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(cipher.iv+cipher.doFinal(text.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    fun decrypt(text: String): String {
        val bytes=Base64.decode(text,Base64.NO_WRAP)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }
}
