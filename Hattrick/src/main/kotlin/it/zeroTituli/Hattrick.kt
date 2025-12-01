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
    val cfKiller = CloudflareKiller()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homePageLists = mutableListOf<HomePageList>()
        
        // Parse tutti gli eventi dalla pagina
        val allEvents = document.select("div.events div.row").mapNotNull { row ->
            try {
                val details = row.selectFirst("div.details") ?: return@mapNotNull null
                val gameName = details.selectFirst("a.game-name span")?.text() ?: return@mapNotNull null
                val date = details.selectFirst("p.date")?.text() ?: ""
                
                // Estrai tutti i bottoni/link disponibili
                val buttons = details.select("button a[href]")
                if (buttons.isEmpty()) return@mapNotNull null
                
                val posterUrl = row.selectFirst("div.logos img")?.attr("src") 
                    ?: "https://logowiki.net/wp-content/uploads/imgp/Hattrick-Logo-1-5512.jpg"
                
                // Se è la sezione "Canali On Line", crea una lista separata
                if (gameName.contains("Canali On Line", ignoreCase = true)) {
                    val channels = buttons.mapNotNull { btn ->
                        val href = btn.attr("href")
                        val channelName = btn.text()
                        if (href.isNotBlank() && channelName.isNotBlank()) {
                            newLiveSearchResponse(channelName, fixUrl(href), TvType.Live) {
                                this.posterUrl = posterUrl
                            }
                        } else null
                    }
                    if (channels.isNotEmpty()) {
                        homePageLists.add(HomePageList("Canali Live", channels, isHorizontalImages = true))
                    }
                    return@mapNotNull null
                }
                
                // Per gli eventi, prendi il primo link disponibile come URL principale
                val firstButton = buttons.firstOrNull() ?: return@mapNotNull null
                val href = firstButton.attr("href")
                
                if (href.isBlank()) return@mapNotNull null
                
                newLiveSearchResponse("$gameName${if (date.isNotEmpty()) " - $date" else ""}", fixUrl(href), TvType.Live) {
                    this.posterUrl = posterUrl
                }
            } catch (e: Exception) {
                Log.d("Hattrick", "Error parsing event: ${e.message}")
                null
            }
        }
        
        // Aggiungi eventi in programma se ce ne sono
        if (allEvents.isNotEmpty()) {
            homePageLists.add(HomePageList("Eventi in Programma", allEvents, isHorizontalImages = true))
        }
        
        // Se non abbiamo trovato nulla, ritorna lista vuota
        if (homePageLists.isEmpty()) {
            throw ErrorLoadingException("Nessun evento trovato")
        }

        return newHomePageResponse(homePageLists, false)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("title")?.text()?.substringBefore(" -") 
            ?: url.substringAfterLast("/").substringBefore(".")
        
        return newLiveStreamLoadResponse(name = title, url = url, dataUrl = url) {
            this.posterUrl = "https://logowiki.net/wp-content/uploads/imgp/Hattrick-Logo-1-5512.jpg"
        }
    }

    private fun getStreamUrl(document: Document): String? {
        // Cerca iframe nella pagina
        val iframe = document.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrEmpty()) {
            return iframe
        }

        // Cerca script offuscati
        val scripts = document.body().select("script")
        val obfuscatedScript = scripts.findLast { it.data().contains("eval(") }
        
        return obfuscatedScript?.let {
            try {
                val data = getAndUnpack(it.data())
                val sourceRegex = "(?<=src=\")([^\"]+)".toRegex()
                val source = sourceRegex.find(data)?.value
                source
            } catch (e: Exception) {
                Log.d("Hattrick", "Error extracting stream: ${e.message}")
                null
            }
        }
    }

    private suspend fun extractVideoStream(url: String, ref: String, n: Int): Pair<String, String>? {
        if (url.toHttpUrlOrNull() == null) return null
        if (n > 10) return null

        try {
            val doc = app.get(url, referer = ref).document
            
            // Cerca iframe
            val iframeUrl = doc.selectFirst("iframe")?.attr("src")
            if (!iframeUrl.isNullOrEmpty()) {
                val newPage = app.get(fixUrl(iframeUrl), referer = url).document
                val streamUrl = getStreamUrl(newPage)
                
                if (!streamUrl.isNullOrEmpty()) {
                    return streamUrl to fixUrl(iframeUrl)
                }
                
                // Ricorsione
                return extractVideoStream(iframeUrl, url, n + 1)
            }
            
            // Cerca stream diretto
            val streamUrl = getStreamUrl(doc)
            if (!streamUrl.isNullOrEmpty()) {
                return streamUrl to url
            }
            
        } catch (e: Exception) {
            Log.d("Hattrick", "Error in extractVideoStream: ${e.message}")
        }
        
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            
            // Cerca iframe diretto nella pagina
            val directIframe = document.selectFirst("iframe.video, iframe[name='iframe_a']")?.attr("src")
            if (!directIframe.isNullOrEmpty()) {
                val link = extractVideoStream(fixUrl(directIframe), data, 1)
                if (link != null) {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "Stream",
                            url = link.first,
                            referer = link.second,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    return true
                }
            }
            
            // Cerca bottoni con link
            val buttons = document.select("button a[href], a[href*='.htm']")
            if (buttons.isNotEmpty()) {
                buttons.forEachIndexed { index, element ->
                    val url = element.attr("href")
                    val name = element.text().ifEmpty { "Stream ${index + 1}" }
                    
                    if (url.isNotEmpty()) {
                        val link = extractVideoStream(fixUrl(url), data, 1)
                        if (link != null) {
                            callback(
                                ExtractorLink(
                                    source = this.name,
                                    name = name,
                                    url = link.first,
                                    referer = link.second,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                        }
                    }
                }
            }
            
            // Cerca link diretti nello stream
            val streamLink = extractVideoStream(data, data, 1)
            if (streamLink != null) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "Direct Stream",
                        url = streamLink.first,
                        referer = streamLink.second,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                return true
            }
            
        } catch (e: Exception) {
            Log.d("Hattrick", "Error loading links: ${e.message}")
        }
        
        return false
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                return cfKiller.intercept(chain)
            }
        }
    }
}