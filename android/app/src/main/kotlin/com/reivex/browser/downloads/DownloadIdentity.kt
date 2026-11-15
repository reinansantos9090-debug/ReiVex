package com.reivex.browser.downloads

import com.reivex.browser.data.media.MediaFilenameParser

/** A season or episode marker alone is not a media title. Do not infer a title from the marker alone. */
object DownloadIdentity {
    private val episodeMarker = Regex("""(?i)(?:^|[\s._-])(?:epis[oó]dio|episode|ep)[\s._-]*\d{1,4}(?:$|[\s._-])""")
    private val genericFile = Regex("""(?i)^(?:videoplayback|downloadfile|download|stream|playlist|master|index|video|file|media)(?:[-_ ]*\d+)?$""")

    /** Provider filenames such as videoplayback.bin.mp4 contain no anime identity. */
    fun isGenericMediaName(value: String): Boolean {
        var stem = value.substringBefore('?').substringBefore('#')
            .substringAfterLast('/').trim()
        repeat(3) {
            val ext = stem.substringAfterLast('.', "").lowercase()
            if (ext in DownloadContainerDetector.knownExtensions + setOf("bin", "m3u8", "mpd"))
                stem = stem.substringBeforeLast('.')
        }
        return stem.isBlank() || genericFile.matches(stem.trim()) || DownloadResourceIdentity.opaque(value)
    }

    private fun cleanedPageTitle(title: String): String = title
        .substringBefore(" | ").substringBefore(" – ")
        .replace(Regex("""(?i)\s*-\s*Animes Online(?:\s*TV)?\s*$"""), "")
        .trim()

    private fun usefulPageTitle(value: String): Boolean {
        val title = cleanedPageTitle(value)
        if (title.length < 3 || title.equals("Animes Online", true) ||
            title.equals("Assistir Animes Online", true) || title.equals("Home", true) ||
            isGenericMediaName(title)) return false
        // A title consisting only of "Episode 4" cannot identify its series.
        val withoutEpisode = title.replace(Regex("""(?i)\b(?:epis[oó]dio|episode|ep)\s*\d{1,4}\b"""), "")
        return withoutEpisode.count(Char::isLetter) >= 3
    }

    /** Use an explicit episode slug only, never a guessed CDN ID or sequence. */
    private fun episodeTitleFromPageUrl(pageUrl: String, pageTitle: String = ""): String {
        val uri = runCatching { java.net.URI(pageUrl) }.getOrNull() ?: return ""
        if (uri.scheme !in setOf("http", "https")) return ""
        val host = uri.host.orEmpty().lowercase()
        if (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) return ""

        val ignored = setOf("anime", "animes", "watch", "assistir", "episodios", "episodio",
            "episode", "episodes", "player", "video", "videos", "stream", "play")
        val parts = uri.path.orEmpty().split('/').filter(String::isNotBlank)
            .map { it.replace(Regex("[-_]+"), " ").trim() }
        fun numericEpisode(value: String): String? = Regex(
            """(?i)^(?:epis[oó]dio|episode|ep)[\s._-]*0*(\d{1,4})$"""
        ).matchEntire(value.trim())?.groupValues?.get(1)?.toIntOrNull()?.toString()
        fun seriesCandidate(index: Int): String {
            for (i in index.coerceAtMost(parts.lastIndex) downTo 0) {
                val candidate = parts[i].replace(Regex("""(?i)\b(?:epis[oó]dio|episode|ep)[\s._-]*0*\d{1,4}\b"""), "")
                    .replace(Regex("""(?i)\bS\d{1,2}\s*E\d{1,4}\b"""), "")
                    .trim()
                if (candidate.count(Char::isLetter) >= 3 &&
                    candidate.lowercase() !in ignored && !candidate.matches(Regex("""\d+""")))
                    return candidate
            }
            return cleanedPageTitle(pageTitle).takeIf(::usefulPageTitle).orEmpty()
        }

        var episode: String? = null
        var series = ""
        for (i in parts.indices.reversed()) {
            val token = parts[i]
            val combined = Regex("""(?i)\bS\d{1,2}\s*E0*(\d{1,4})\b""").find(token)
            if (combined != null) {
                episode = combined.groupValues[1].toIntOrNull()?.toString()
                series = seriesCandidate(i)
                break
            }
            episode = numericEpisode(token)
            if (episode != null) {
                series = seriesCandidate(i - 1)
                break
            }
            if (token.lowercase() in setOf("episodio", "episode", "ep")) {
                val next = parts.getOrNull(i + 1)?.trim()?.toIntOrNull()
                if (next != null && next in 0..9999) {
                    episode = next.toString()
                    series = seriesCandidate(i - 1)
                    break
                }
            }
        }

        if (episode == null) {
            val query = uri.rawQuery.orEmpty().split('&').take(80).mapNotNull { item ->
                val key = runCatching { java.net.URLDecoder.decode(item.substringBefore('='), "UTF-8") }
                    .getOrDefault(item.substringBefore('=')).lowercase()
                val value = runCatching { java.net.URLDecoder.decode(item.substringAfter('=', ""), "UTF-8") }
                    .getOrDefault(item.substringAfter('=', ""))
                key to value
            }.toMap()
            val episodeKeys = setOf("episode", "episode_number", "ep", "epno", "ep_num", "episodio", "numeroepisodio")
            episode = query.entries.firstNotNullOfOrNull { (key, value) ->
                if (key !in episodeKeys) null else value.trim().toIntOrNull()
                    ?.takeIf { it in 0..9999 }?.toString()
            }
            if (episode != null) series = seriesCandidate(parts.lastIndex)
        }

        val number = episode ?: return ""
        if (series.isBlank()) return ""
        val canonicalSeries=series.replace(Regex("""(?i)^re[ -]+zero\b"""),"Re:Zero")
        val formattedSeries = canonicalSeries.split(Regex("""\s+""")).joinToString(" ") { word ->
            if (word.equals("re:zero", true)) "Re:Zero"
            else word.replaceFirstChar { it.titlecase() }
        }.take(150).trim()
        val title = "$formattedSeries Episódio $number"
        return title.takeIf { usefulPageTitle(it) }?.take(180).orEmpty()
    }

    /** Prefer actual episode metadata; never label the CDN videoplayback as an anime. */
    fun episodeTitleFromPage(pageTitle: String, pageUrl: String): String {
        val title = cleanedPageTitle(pageTitle)
        // Episode pages often say "One Piece Dublado 005", without "Episódio".
        // Require an audio marker so a series title ending in a number is preserved.
        val numbered=Regex("""(?i)^(.+\b(?:dublado|legendado))\s+0*(\d{1,4})$""").matchEntire(title)
        if(numbered!=null && usefulPageTitle(numbered.groupValues[1]))
            return numbered.groupValues[1]+" Episódio "+numbered.groupValues[2].toInt()
        if (usefulPageTitle(title) && episodeMarker.containsMatchIn(title)) return title
        val fromUrl = episodeTitleFromPageUrl(pageUrl, title)
        if (fromUrl.isNotBlank()) return fromUrl
        return title.takeIf(::usefulPageTitle).orEmpty()
    }

    /** Page titles describe the episode; opaque CDN paths describe storage. */
    fun capturedFileName(pageTitle: String, url: String, mime: String?): String {
        val title=pageTitle.substringBefore(" | ").substringBefore(" – ")
            .map { if(it.isISOControl() || it in "\\/:*?\"<>|") ' ' else it }.joinToString("").trim().take(180).trim()
        val guessed=android.webkit.URLUtil.guessFileName(url,null,mime)
        if(title.length<3)return guessed
        val extension=guessed.substringAfterLast('.', "mp4").lowercase()
            .takeIf {it in setOf("mp4","mkv","webm","mov","m4v","ts")} ?: "mp4"
        return "$title.$extension"
    }
    /** A sharing app may supply a series/episode title while its CDN URL is only index.m3u8.
     * Do not fabricate an episode number when the sender provides no episode metadata. */
    fun suggestedName(
        sharedTitle: String, url: String, providedName: String, refererUrl: String = "",
    ): String {
        if (providedName.isNotBlank() && !isGenericMediaName(providedName)) return providedName
        val fromContext=DownloadResourceIdentity.contextualEpisodeName(url)
        val episode=episodeTitleFromPage(sharedTitle,url).takeIf {
            episodeMarker.containsMatchIn(it) && usefulPageTitle(it)
        } ?: fromContext ?: episodeTitleFromPage(sharedTitle,refererUrl).takeIf {
            episodeMarker.containsMatchIn(it) && usefulPageTitle(it)
        }
        if (!episode.isNullOrBlank()) {
            val normalized=episode.replace(
                Regex("""(?i)\b(?:epis[oó]dio|episode|ep)[\s._-]*(\d{1,4})\b"""),
            ) { match -> "Episódio " + match.groupValues[1].padStart(2,'0') }
                .replace(Regex("""\s+""")," ").trim()
            val safe=normalized.map {
                if(it.isISOControl() || it in "\\/:*?\"<>|") ' ' else it
            }.joinToString("").take(180).trim()
            if(safe.isNotBlank()) {
                val ext=providedName.substringAfterLast('.', "").lowercase()
                    .takeIf { it in DownloadContainerDetector.knownExtensions } ?: "mp4"
                return safe+"."+ext
            }
        }
        val plainTitle=sharedTitle.trim().takeIf { usefulPageTitle(it) }
        if(plainTitle!=null) {
            val clean=plainTitle.map {
                if(it.isISOControl() || it in "\\/:*?\"<>|") ' ' else it
            }.joinToString("").trim().take(180)
            if(clean.isNotBlank())return clean+".mp4"
        }
        DownloadResourceIdentity.bestName(null,url,null)?.takeUnless(::isGenericMediaName)
            ?.let { return it }
        return providedName.takeIf {it.isNotBlank() && !isGenericMediaName(it)} ?: "Episódio.mp4"
    }

    fun title(value: String): String {
        val clean=value.substringBefore(" | ").substringBefore(" – ").trim()
        if (!usefulPageTitle(clean)) return ""
        val parsed=MediaFilenameParser.parseVideoPath(clean).animeTitle.trim()
        if(parsed.isBlank() || parsed.equals("Arquivo não identificado",true)) return ""
        val letters=parsed.replace(Regex("(?i)\\b(?:s\\d+[ ._-]*e\\d+|epis[oó]dio[ ._-]*\\d+|episode[ ._-]*\\d+|downloadfile|download|video|arquivo|mkv|mp4|webm)\\b"),"").filter { it.isLetter() }
        if(letters.length<3 || parsed.matches(Regex("[a-fA-F0-9-]{16,}"))) return ""
        return parsed
    }
}

/** Display-only size: estimates never become an authoritative transfer limit. */
internal data class DownloadDisplaySize(val bytes: Long, val exact: Boolean)

internal fun downloadDisplaySize(item: DownloadItem): DownloadDisplaySize? {
    if(item.total>0L) return DownloadDisplaySize(item.total,true)
    val discovered=item.json.optLong("detectedSize")
    val estimatedHls=item.json.optLong("hlsEstimatedTotal")
    // Once enough segments have arrived, the downloader's running estimate
    // is more representative than an initial bitrate/sample-based estimate.
    if(estimatedHls>item.bytes && estimatedHls>0L &&
        !item.json.optBoolean("detectedSizeExact")) {
        return DownloadDisplaySize(estimatedHls,false)
    }
    if(discovered>0L && discovered>=item.bytes)
        return DownloadDisplaySize(discovered,item.json.optBoolean("detectedSizeExact"))
    return estimatedHls.takeIf { it>item.bytes }?.let { DownloadDisplaySize(it,false) }
}

internal fun downloadPercent(item: DownloadItem): Int? = when {
    item.state=="COMPLETE" -> 100
    item.total>0 -> (item.bytes.toDouble()*100/item.total).toInt().coerceIn(0,100)
    item.json.optInt("hlsCount")>0 -> (item.json.optInt("hlsIndex")*100/item.json.optInt("hlsCount")).coerceIn(0,100)
    !item.json.optBoolean("hls") && item.json.optBoolean("detectedSizeExact") -> {
        val total=downloadDisplaySize(item)?.bytes ?: 0L
        if(total>0L) (item.bytes.toDouble()*100/total).toInt().coerceIn(0,99) else null
    }
    else -> null
}
