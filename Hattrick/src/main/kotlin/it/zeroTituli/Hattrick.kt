package it.zeroTituli

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document

class Hattrick : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://www.hattrick.ws"
    override var name = "Hattrick"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val cfKiller = CloudflareKiller()

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return url
        return if (url.startsWith("http")) url else (mainUrl.trimEnd('/') + "/" + url.trimStart('/'))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val lists = ArrayList<HomePageList>()

        // 1) Collect channel buttons - they are inside <button><a>
        val channelButtons = document.select("button.btn a[href]")
            .filter { it.attr("href").contains(".htm") }
            .mapNotNull { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = a.text().ifBlank { "Live Channel" }
                val poster = a.closest("div.row")?.selectFirst("img.mascot")?.attr("src") ?: ""
                
                newLiveSearchResponse(title, fixUrl(href), TvType.Live) {
                    this.posterUrl = if (poster.isNotBlank()) fixUrl(poster) else ""
                }
            }
        
        if (channelButtons.isNotEmpty()) {
            lists.add(HomePageList("Canali On Line", channelButtons.take(15), isHorizontalImages = false))
        }

        // 2) Collect event rows - each row contains match info and buttons
        val eventRows = document.select("div.events div.row").drop(1).mapNotNull { row ->
            try {
                // Get match title from game-name span
                val title = row.selectFirst("a.game-name span")?.text()?.trim() 
                    ?: row.selectFirst("span")?.text()?.trim() 
                    ?: return@mapNotNull null
                
                // Skip if it's the channel list row
                if (title.contains("Canali On Line", ignoreCase = true)) return@mapNotNull null
                
                val date = row.selectFirst("p.date")?.text()?.trim() ?: ""
                val logo = row.selectFirst("img.mascot")?.attr("src") ?: ""
                
                // Get all stream buttons in this row
                val buttons = row.select("button.btn a[href]")
                    .filter { it.attr("href").contains(".htm") }
                    .mapNotNull { a ->
                        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val linkName = a.text().ifBlank { title }
                        
                        newLiveSearchResponse(
                            "$title ${if (date.isNotBlank()) "— $date" else ""}", 
                            fixUrl(href), 
                            TvType.Live
                        ) {
                            this.posterUrl = if (logo.isNotBlank()) fixUrl(logo) else ""
                        }
                    }
                
                if (buttons.isEmpty()) return@mapNotNull null
                HomePageList(title, buttons, isHorizontalImages = false)
            } catch (e: Exception) {
                Log.e("Hattrick", "Error parsing event row: ${e.message}")
                null
            }
        }
        
        lists.addAll(eventRows)

        if (lists.isEmpty()) throw ErrorLoadingException("No content found")

        return newHomePageResponse(lists, false)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("title")?.text()
            ?: name
        
        val poster = document.selectFirst("img.mascot")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""
        
        val description = document.selectFirst("p.date")?.text()
            ?: document.selectFirst("meta[name=description]")?.attr("content") 
            ?: ""

        return newLiveStreamLoadResponse(name = title, url = url, dataUrl = url) {
            this.posterUrl = if (poster.isNotBlank()) fixUrl(poster) else ""
            this.plot = description
        }
    }

    private fun getStreamUrl(document: Document): String? {
        // Look for iframe src
        val iframe = document.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrBlank() && !iframe.contains("histats")) return fixUrl(iframe)

        // Look for obfuscated script
        val scripts = document.select("script")
        val obfuscated = scripts.findLast { 
            it.data().contains("eval(") || it.data().contains("split")
        }
        
        val data = obfuscated?.data()?.let {
            try {
                getAndUnpack(it)
            } catch (e: Exception) {
                null
            }
        }
        
        if (!data.isNullOrBlank()) {
            // Look for src= patterns
            val regex = """(?:src=["']|src:\s*["'])([^"']+)""".toRegex()
            val match = regex.find(data)?.groupValues?.get(1)
            if (!match.isNullOrBlank()) return fixUrl(match)
        }
        
        return null
    }

    private suspend fun extractVideoStream(url: String, ref: String, depth: Int = 1): Pair<String, String>? {
        if (url.toHttpUrlOrNull() == null) return null
        if (depth > 10) return null
        
        try {
            val doc = app.get(url, referer = ref).document
            val streamUrl = getStreamUrl(doc)
            
            if (!streamUrl.isNullOrBlank()) {
                val fixed = fixUrl(streamUrl)
                // If it looks like a playable stream, return it
                if (fixed.contains(".m3u8") || fixed.contains("playlist") || 
                    fixed.contains(".mpd") || fixed.contains("/live/")) {
                    return fixed to url
                }
                // Otherwise, follow it recursively
                return extractVideoStream(fixed, url, depth + 1)
            }
        } catch (e: Exception) {
            Log.e("Hattrick", "Error extracting stream: ${e.message}")
        }
        
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val links = mutableListOf<Pair<String, String>>()

        // Try to find iframe
        val iframeSrc = document.select("iframe")
            .map { it.attr("src") }
            .firstOrNull { it.isNotBlank() && !it.contains("histats") }

        if (!iframeSrc.isNullOrBlank()) {
            val resolved = extractVideoStream(fixUrl(iframeSrc), data, 1)
            if (resolved != null) {
                links.add(resolved)
            } else {
                links.add(fixUrl(iframeSrc) to data)
            }
        }

        // If no iframe, try direct m3u8 in HTML
        if (links.isEmpty()) {
            val m3u8Regex = """(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""".toRegex()
            val m3u8 = m3u8Regex.find(document.html())?.value
            if (!m3u8.isNullOrBlank()) {
                links.add(m3u8 to data)
            }
        }

        // Emit extractor links
        links.forEachIndexed { idx, (url, ref) ->
            Log.d("Hattrick", "Adding link: $url")
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "Hattrick ${idx + 1}",
                    url = url,
                    referer = ref,
                    quality = Qualities.Unknown.value,
                    type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }

        return links.isNotEmpty()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return cfKiller
    }
}