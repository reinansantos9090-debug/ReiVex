package com.reivex.browser.data.media

import java.text.Normalizer
import java.util.Locale

/** Exact title normalization for candidate lookup; deliberately no fuzzy matching. */
object MediaTitleNormalizer {
    fun normalizeTitle(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace(Regex("[._\\-‐‑‒–—]+"), " ")
            .replace(Regex("[\\p{Punct}&&[^']]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    /** Removes release metadata while retaining the source title's casing for presentation. */
    fun cleanMediaTitle(value: String?): String {
        if (value.isNullOrBlank()) return ""
        var result = value.trim()
            .replace(Regex("^\\s*\\[[^\\]]{1,120}]\\s*"), "")
            .replace(Regex("\\[[^\\]]{0,120}]"), " ")
        for (pattern in technicalPatterns) result = pattern.replace(result, " ")
        result = recordingTimestamp.replace(result, " ")
        result = standaloneTimestamp.replace(result, " ")
        result = result.replace(Regex("\\b(?:complete|batch|season[ ._-]*pack)\\b", RegexOption.IGNORE_CASE), " ")
        result = result.replace(Regex("[._]+"), " ")
        result = result.replace(Regex("\\s+"), " ")
        return result.trim().trim(' ', '-', '_', '[', ']', '(', ')', '{', '}')
    }

    /** Comparison form of the cleaned title; never proves identity on its own. */
    fun normalizeMediaTitle(value: String?): String = normalizeTitle(cleanMediaTitle(value))

    /**
     * Search-only representations of a local title. The original spelling is always
     * retained as the first query; cleaned forms are additional candidates only and
     * must never be written back to the user's library.
     */
    fun searchTitleVariants(value: String?): List<String> {
        val original = value?.trim()?.replace(SEARCH_WHITESPACE, " ")?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val cleaned = cleanMediaTitle(original)
        val episodeCleaned = cleaned
            .replace(SEARCH_EPISODE_MARKERS, " ")
            .replace(SEARCH_SEASON_MARKERS, " ")
            .replace(SEARCH_WHITESPACE, " ")
            .trim()
        val prefixless = stripOrganizationalPrefix(episodeCleaned)
        return buildList {
            // Query the exact local spelling first, then the most useful canonical
            // form, so technical metadata and organizer folders do not crowd out
            // native/romaji/English aliases in the bounded provider query budget.
            add(original)
            add(prefixless)
            add(episodeCleaned)
            add(cleaned)
            add(foldDiacritics(prefixless))
        }.asSequence()
            .map { it.trim().replace(SEARCH_WHITESPACE, " ") }
            .filter { it.length in 2..120 }
            .distinctBy(::normalizeTitle)
            .toList()
    }

    private fun stripOrganizationalPrefix(value: String): String {
        val match = HOME_ANIMES_PREFIX.find(value) ?: return value
        return match.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) ?: value
    }

    private fun foldDiacritics(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD).filterNot {
            val type = Character.getType(it)
            type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
        }

    private val SEARCH_WHITESPACE = Regex("\\s+")
    private val HOME_ANIMES_PREFIX = Regex(
        """(?i)^\s*home[\s._-]+animes[\s._:|—–-]+(.+?)\s*$""",
    )
    private val SEARCH_EPISODE_MARKERS = Regex(
        """(?i)\b(?:s\d{1,3}[ ._-]*e(?:p(?:isode)?)?[ ._-]*\d{1,4}|(?:e(?:p(?:isode)?)?|episode|epis[oó]dio)[ ._-]*\d{1,4})\b""",
    )
    private val SEARCH_SEASON_MARKERS = Regex(
        """(?i)\b(?:season|temporada|temp)[ ._-]*\d{1,3}\b|\b\d{1,2}(?:st|nd|rd|th)[ ._-]*season\b""",
    )

    /**
     * Mirrors core.library_parser._episode_title_after exactly.
     *
     * This is deliberately narrower than cleanMediaTitle: the legacy parser keeps
     * ordinary words in an episode suffix (for example "Complete Batch") and only
     * strips bracket annotations and technical release tokens from that suffix.
     */
    internal fun episodeTitleAfter(stem: String, marker: MatchResult?): String? {
        if (marker == null) return null
        var tail = stem.substring(marker.range.last + 1)
            .replace(Regex("^[ ._\\-–—]+"), "")
            .replace(Regex("\\[[^]]*]"), " ")
        technicalPatterns.forEach { pattern -> tail = pattern.replace(tail, " ") }
        // Removing dotted/underscored release tokens must not leave their separators
        // as fake text in the episode title (for example: "Title.1080p.WEB-DL").
        tail = tail.replace(Regex("[._\\s]+"), " ")
        tail = tail.replace(Regex("\\s+"), " ").trim(' ', '-', '_')
        if (tail.isBlank() || Regex("(?i)v?\\d+").matches(tail)) return null
        return tail
    }

    /** Returns recognized release/technical tokens in their original spelling and order by pattern. */
    fun extractTechnicalTokens(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val found = linkedSetOf<String>()
        technicalPatterns.forEach { pattern -> pattern.findAll(value).forEach { found += it.value } }
        return found.toList()
    }

    private val technicalPatterns = listOf(
        Regex("(?<![A-Za-z0-9])(?:2160p|1080p|720p|480p|4320p|4k)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])(?:x264|x265|h264|h\\.264|h265|h\\.265|hevc|av1|avc)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])(?:8bit|10bit|12bit|hdr10|hdr|dv|dolby[ ._-]*vision)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])(?:web[ ._-]*dl|webrip|bluray|bdrip|bdremux|hdtv)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])(?:aac|ac3|eac3|flac|dts|dual[ ._-]*audio|multi[ ._-]*audio)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])(?:pt[-_ ]?br|pt|portugu[eê]s|eng|english|jpn|japanese|dub|dublado|sub|legendado)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
    )
    private val recordingTimestamp = Regex("\\b(?:recording|recorded)[ ._-]*(?:19\\d{2}|20\\d{2})\\d{4}\\s+(?:[01]?\\d|2[0-3])\\d{4}\\b", RegexOption.IGNORE_CASE)
    private val standaloneTimestamp = Regex("\\b(?:19\\d{2}|20\\d{2})\\d{4}\\s+(?:[01]?\\d|2[0-3])\\d{4}\\b")
}
