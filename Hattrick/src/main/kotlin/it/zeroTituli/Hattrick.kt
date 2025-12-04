class HattrickProvider : MainAPI() {
    override var mainUrl = "https://www.hattrick.ws"
    override var name = "Hattrick"
    override val supportedTypes = setOf(TvType.Live)

    // 1. Parsing the Schedule Page
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // In a real plugin, you fetch the URL. 
        // For this example, assume 'document' is the Jsoup parsed HTML you provided.
        val document = app.get(mainUrl).document 
        
        val items = ArrayList<HomePageList>()
        val liveEvents = ArrayList<SearchResponse>()

        // The matches are contained in div.events -> div.row
        document.select("div.events div.row").forEach { row ->
            // Extract Team Names
            val nameInfo = row.select(".details .game-name").text().trim()
            
            // Extract Time/League info
            val dateInfo = row.select(".details .date").text().trim()
            
            // Extract Image
            val imgUrl = row.select(".logos img").attr("src")

            // We need to pass ALL the links to the next stage (load)
            // We verify the row has actual buttons/links
            val links = row.select(".details button a").map { it.attr("href") }

            if (nameInfo.isNotBlank() && links.isNotEmpty()) {
                liveEvents.add(
                    LiveSearchResponse(
                        // Name: "Athletic Bilbao - Real Madrid"
                        name = nameInfo,
                        // URL: We pass all links encoded in JSON or similar to the load function
                        // For simplicity, we pass the first one, but ideally, you save the list
                        url = ProxyMapper.toJson(links), 
                        apiName = this@HattrickProvider.name,
                        type = TvType.Live,
                        posterUrl = imgUrl
                    ).apply {
                        // Add the time/league as plot or description
                        this.plot = dateInfo
                    }
                )
            }
        }

        items.add(HomePageList("Live Events", liveEvents))
        return HomePageResponse(items)
    }

    // 2. Loading the Links (When user clicks the match)
    override suspend fun load(url: String): LoadResponse {
        // We decoded the list of links we saved earlier
        val linkList = ProxyMapper.toObject<List<String>>(url)

        return LiveStreamLoadResponse(
            name = "Match Options",
            url = url,
            apiName = this.name,
            type = TvType.Live,
            plot = "Choose a source",
            episodes = linkList.mapIndexed { index, linkUrl ->
                // Create a clickable episode for each button (Sport 24, Dazn 1, etc)
                // We need to fix relative URLs if they exist
                val fixedUrl = fixUrl(linkUrl)
                
                LiveStreamEpisode(
                    name = "Source ${index + 1}", // Or parse the button text if you passed it
                    url = fixedUrl // This is the link to the .htm page containing the player
                )
            }
        )
    }

    // 3. Extracting the Stream (The Token Logic)
    override suspend fun loadLinks(
        url: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'url' here is the page (e.g., https://www.hattrick.ws/sport24hd.htm)
        
        // Fetch the page source
        val doc = app.get(url, headers = mapOf("Referer" to mainUrl)).text

        // --- THE SOLUTION TO YOUR QUESTION ---
        // We look for the .m3u8 inside the HTML source. 
        // It usually includes the token automatically.
        
        // Common regex to find m3u8 in scripts (Clappr, JWPlayer, etc.)
        val m3u8Regex = Regex("""["']([^"']+\.m3u8.*?)["']""")
        val match = m3u8Regex.find(doc)

        if (match != null) {
            val streamUrl = match.groupValues[1]
            
            // Generate the stream link
            callback.invoke(
                ExtractorLink(
                    source = "Hattrick",
                    name = "Hattrick Stream",
                    url = streamUrl, // This URL contains the token
                    referer = url, // IMPORTANT: The Referer must be the page providing the video
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        } 
        
        // If not found directly, check for IFrames
        val iframeSrc = Jsoup.parse(doc).select("iframe").attr("src")
        if (iframeSrc.isNotBlank()) {
            // Recursively load the iframe content
             return loadLinks(fixUrl(iframeSrc), isCasting, subtitleCallback, callback)
        }

        return false
    }
}