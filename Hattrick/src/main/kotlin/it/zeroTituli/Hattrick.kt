package it.zeroTituli

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response

class Hattrick : MainAPI() {
    private var M3U8_URL = "https://raw.githubusercontent.com/fixered/htk/master/hattrickveloce.m3u8"
    override var name = "Hattrick"
    override val hasMainPage = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    
    private val cfKiller = CloudflareKiller()
    private val LOGO = "https://www.tuttotech.net/wp-content/uploads/2020/08/NOW-TV-Sky-On-Demand-logo-2.png"

    data class Channel(
        val name: String,
        val url: String,
        val logo: String,
        val groupTitle: String
    )

    private suspend fun parseM3U8Playlist(): List<Channel> {
        val channels = mutableListOf<Channel>()
        
        try {
            val response = app.get(M3U8_URL).text
            val lines = response.split("\n")
            
            var currentName = ""
            var currentLogo = LOGO
            var currentGroup = "Canali Live"
            
            for (i in lines.indices) {
                val line = lines[i].trim()
                
                // Parsing linea EXTINF
                if (line.startsWith("#EXTINF:")) {
                    // Estrai il nome del canale (dopo l'ultima virgola)
                    currentName = line.substringAfterLast(",").trim()
                    
                    // Estrai tvg-logo se presente
                    val logoMatch = """tvg-logo="([^"]+)"""".toRegex().find(line)
                    if (logoMatch != null) {
                        currentLogo = logoMatch.groupValues[1]
                    }
                    
                    // Estrai group-title se presente
                    val groupMatch = """group-title="([^"]+)"""".toRegex().find(line)
                    if (groupMatch != null) {
                        currentGroup = groupMatch.groupValues[1]
                    }
                }
                // La linea successiva dovrebbe contenere l'URL
                else if (line.isNotEmpty() && !line.startsWith("#") && currentName.isNotEmpty()) {
                    channels.add(
                        Channel(
                            name = currentName,
                            url = line,
                            logo = currentLogo,
                            groupTitle = currentGroup
                        )
                    )
                    
                    // Reset per il prossimo canale
                    currentName = ""
                    currentLogo = LOGO
                    currentGroup = "Canali Live"
                }
            }
            
            Log.d("Hattrick", "Caricati ${channels.size} canali da M3U8")
        } catch (e: Exception) {
            Log.e("Hattrick", "Errore nel parsing M3U8: ${e.message}")
        }
        
        return channels
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = parseM3U8Playlist()
        
        // Raggruppa i canali per gruppo (se necessario)
        val groupedChannels = channels.groupBy { it.groupTitle }
        
        val homePageLists = groupedChannels.map { (groupName, channelList) ->
            HomePageList(
                name = groupName,
                list = channelList.map { channel ->
                    newLiveSearchResponse(channel.name, channel.url, TvType.Live) {
                        this.posterUrl = channel.logo
                    }
                },
                isHorizontalImages = true
            )
        }

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val channels = parseM3U8Playlist()
        
        return channels.filter { 
            it.name.contains(query, ignoreCase = true) 
        }.map { channel ->
            newLiveSearchResponse(channel.name, channel.url, TvType.Live) {
                this.posterUrl = channel.logo
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // L'URL contiene già il link diretto allo stream M3U8
        val channelName = url.substringAfterLast("/")
            .substringBefore("?")
            .replace("index.fmp4.m3u8", "")
            .replace("-", " ")
            .trim()

        return newLiveStreamLoadResponse(
            name = channelName.ifEmpty { "Live Stream" },
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = LOGO
            this.plot = "Diretta streaming"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Il data è già l'URL diretto dello stream M3U8
        Log.d("Hattrick", "Caricamento stream: $data")
        
        try {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "Hattrick (Veloce)",
                    url = data,
                    referer = "https://planetary.lovecdn.ru/",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
            
            return true
        } catch (e: Exception) {
            Log.e("Hattrick", "Errore caricamento link: ${e.message}")
            return false
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                return cfKiller.intercept(chain)
            }
        }
    }
}