package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class HattrickProvider : MainAPI() {
    override var mainUrl = "https://www.hattrick.ws"
    override var name = "Hattrick"
    override val supportedTypes = setOf(TvType.Live)

    // 1. Search/Home: Parse the list of matches
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = ArrayList<HomePageList>()
        val liveEvents = ArrayList<SearchResponse>()

        document.select("div.events div.row").forEach { row ->
            val nameInfo = row.select(".details .game-name").text().trim()
            val imgUrl = row.select(".logos img").attr("src")
            
            // Get all links from the buttons
            val links = row.select(".details button a").map { it.attr("href") }

            if (nameInfo.isNotBlank() && links.isNotEmpty()) {
                // We serialize the list of links into the URL field
                val dataUrl = AppUtils.toJson(links)
                
                liveEvents.add(
                    newLiveSearchResponse(nameInfo, dataUrl, TvType.Live) {
                        this.posterUrl = imgUrl
                    }
                )
            }
        }

        items.add(HomePageList("Live Events", liveEvents))
        return newHomePageResponse(items)
    }

    // 2. Load: Display the list of sources (Mirrors)
    override suspend fun load(url: String): LoadResponse {
        // Decode the list of links
        val linkList = AppUtils.parseJson<List<String>>(url)

        return newTvSeriesLoadResponse("Match Sources", url, TvType.Live, linkList) {
            this.plot = "Select a source to watch."
            this.posterUrl = null // Optional: Add a placeholder image if you want
            
            // Map each link to an "Episode"
            this.episodes = linkList.mapIndexed { index, linkUrl ->
                val fixedUrl = fixUrl(linkUrl)
                newEpisode(fixedUrl) {
                    this.name = "Source ${index + 1}"
                    this.episode = index + 1
                }
            }
        }
    }

    // 3. Extract: Get the actual video stream
    override suspend fun loadLinks(
        url: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'url' is the specific page for that button (e.g. sport24.htm)
        val doc = app.get(url, headers = mapOf("Referer" to mainUrl)).text

        // 1. Try finding m3u8 directly in the HTML
        val m3u8Regex = Regex("""["']([^"']+\.m3u8.*?)["']""")
        val match = m3u8Regex.find(doc)

        if (match != null) {
            val streamUrl = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = "Hattrick",
                    name = "Hattrick Stream",
                    url = streamUrl,
                    referer = url, // Important: Referer is the page we are on
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        }

        // 2. Try finding Obfuscated/Packed JS
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d.*""")
        val packedMatch = packedRegex.find(doc)

        if (packedMatch != null) {
            val unpackedJs = JsUnpacker.unpack(packedMatch.value)
            if (unpackedJs != null) {
                val m3u8MatchPacked = Regex("""["']([^"']+\.m3u8.*?)["']""").find(unpackedJs)
                if (m3u8MatchPacked != null) {
                    val streamUrl = m3u8MatchPacked.groupValues[1]
                    callback.invoke(
                        newExtractorLink(
                            source = "Hattrick",
                            name = "Hattrick Stream (Unpacked)",
                            url = streamUrl,
                            referer = url,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                    return true
                }
            }
        }

        // 3. Try finding an Iframe
        val iframeSrc = Jsoup.parse(doc).select("iframe").attr("src")
        if (iframeSrc.isNotBlank()) {
             // Recursively load the iframe
             return loadLinks(fixUrl(iframeSrc), isCasting, subtitleCallback, callback)
        }

        return false
    }
}