package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import com.fasterxml.jackson.module.kotlin.readValue

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
                val dataUrl = mapper.writeValueAsString(links)
                
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

    // 2. Load: Display the list of sources
    override suspend fun load(url: String): LoadResponse {
        val linkList = mapper.readValue<List<String>>(url)

        return newTvSeriesLoadResponse("Match Sources", url, TvType.Live, emptyList()) {
            this.plot = "Select a source to watch."
            this.posterUrl = null 
            
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

        // Simplified Regex that catches m3u8 inside normal scripts and packed scripts
        val m3u8Regex = Regex("""["']([^"']+\.m3u8.*?)["']""")
        val match = m3u8Regex.find(doc)

        if (match != null) {
            val streamUrl = match.groupValues[1]
            
            // FIX: Use newExtractorLink instead of deprecated constructor
            callback.invoke(
                newExtractorLink(
                    source = "Hattrick",
                    name = "Hattrick Stream",
                    url = streamUrl,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        }

        // Try finding an Iframe recursively
        val iframeSrc = Jsoup.parse(doc).select("iframe").attr("src")
        if (iframeSrc.isNotBlank()) {
             return loadLinks(fixUrl(iframeSrc), isCasting, subtitleCallback, callback)
        }

        return false
    }
}