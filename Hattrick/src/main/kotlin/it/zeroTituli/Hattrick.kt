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
    override var mainUrl = "https://hattrick.ws"
    override var name = "Hattrick"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    val cfKiller = CloudflareKiller()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document

        val sections = document.select("div.row").filter { row ->
            row.select("button.btn a[href]").isNotEmpty()
        }

        if (sections.isEmpty()) throw ErrorLoadingException()

        return newHomePageResponse(sections.mapNotNull { row ->
            val categoryName = row.select("div.details > a.game-name > span").text().ifEmpty { 
                row.select("div.details > p.date").text() 
            }
            
            val shows = row.select("button.btn").mapNotNull { btn ->
                val href = btn.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                if (href.isBlank() || href == "#") return@mapNotNull null
                
                val name = btn.selectFirst("a")?.text() ?: return@mapNotNull null
                val posterUrl = row.select("div.logos > img").attr("src").let {
                    if (it.isNotBlank()) fixUrl(it) else null
                }
                
                newLiveSearchResponse(name, fixUrl(href), TvType.Live) {
                    this.posterUrl = posterUrl
                }
            }
            
            if (shows.isEmpty()) return@mapNotNull null
            
            HomePageList(
                categoryName,
                shows,
                isHorizontalImages = true
            )
        }, false)
    }

    override suspend fun load(url: String): LoadResponse {
        val posterUrl = "https://logowiki.net/wp-content/uploads/imgp/Hattrick-Logo-1-5512.jpg"
        val title = url.substringAfterLast("/").substringBefore(".htm").replaceFirstChar { it.uppercase() }
        val description = "Stream from hattrick.ws"
        
        return newLiveStreamLoadResponse(name = title, url = url, dataUrl = url) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    private fun extractIframeFromObfuscated(scriptData: String): String? {
        try {
            // Decodifica lo script offuscato
            val deobfuscated = getAndUnpack(scriptData)
            Log.d("Hattrick-Deobfuscated", deobfuscated)
            
            // Cerca l'URL dell'iframe nel codice decodificato
            val iframeRegex = """src=["']([^"']+)["']""".toRegex()
            val match = iframeRegex.find(deobfuscated)
            
            return match?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.e("Hattrick-Extract", "Error extracting iframe: ${e.message}")
            return null
        }
    }

    private fun getStreamUrl(document: Document): String? {
        try {
            // Primo tentativo: cerca iframe diretti
            val directIframe = document.select("iframe[src]").firstOrNull()?.attr("src")
            if (!directIframe.isNullOrBlank() && directIframe.contains("freeshot.live")) {
                Log.d("Hattrick-DirectIframe", directIframe)
                return directIframe
            }

            // Secondo tentativo: cerca negli script offuscati
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptData = script.data()
                if (scriptData.contains("eval(") || scriptData.contains("function(")) {
                    val iframeUrl = extractIframeFromObfuscated(scriptData)
                    if (iframeUrl != null) {
                        Log.d("Hattrick-ObfuscatedIframe", iframeUrl)
                        return iframeUrl
                    }
                }
            }

            return null
        } catch (e: Exception) {
            Log.e("Hattrick-GetStream", "Error: ${e.message}")
            return null
        }
    }

    private suspend fun extractVideoStream(url: String, ref: String, depth: Int = 1): Pair<String, String>? {
        if (url.toHttpUrlOrNull() == null) return null
        if (depth > 8) return null

        try {
            Log.d("Hattrick-Extract", "Depth $depth: $url")
            
            val doc = app.get(url, referer = ref).document
            
            // Cerca lo stream URL usando la funzione migliorata
            val streamUrl = getStreamUrl(doc)
            
            if (!streamUrl.isNullOrBlank()) {
                Log.d("Hattrick-Found", "Stream URL: $streamUrl")
                
                // Se troviamo un URL .m3u8, lo ritorniamo direttamente
                if (streamUrl.contains(".m3u8")) {
                    return streamUrl to url
                }
                
                // Altrimenti continuiamo a scavare
                return extractVideoStream(fixUrl(streamUrl), url, depth + 1)
            }
            
            // Se non troviamo niente, cerchiamo altri iframe
            val nextIframe = doc.select("iframe[src]").firstOrNull()?.attr("src")
            if (!nextIframe.isNullOrBlank()) {
                return extractVideoStream(fixUrl(nextIframe), url, depth + 1)
            }
            
            return null
        } catch (e: Exception) {
            Log.e("Hattrick-Extract", "Error at depth $depth: ${e.message}")
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            Log.d("Hattrick-LoadLinks", "Loading: $data")
            
            val document = app.get(data).document
            
            // Cerca tutti gli iframe nella pagina
            val iframes = document.select("iframe[src]")
            
            if (iframes.isEmpty()) {
                Log.d("Hattrick-LoadLinks", "No iframes found, searching in scripts")
                val streamUrl = getStreamUrl(document)
                if (streamUrl != null) {
                    val result = extractVideoStream(streamUrl, data, 1)
                    if (result != null) {
                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = "Hattrick",
                                url = result.first,
                                referer = result.second,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        return true
                    }
                }
            }
            
            val links = iframes.mapNotNull { iframe ->
                val iframeUrl = iframe.attr("src")
                if (iframeUrl.isBlank()) return@mapNotNull null
                
                Log.d("Hattrick-Iframe", "Found iframe: $iframeUrl")
                
                val domain = try {
                    val uri = java.net.URL(fixUrl(iframeUrl))
                    "${uri.protocol}://${uri.host}"
                } catch (e: Exception) {
                    null
                }
                
                if (domain == null) return@mapNotNull null
                
                val result = extractVideoStream(fixUrl(iframeUrl), data, 1)
                if (result == null) return@mapNotNull null
                
                Link("it", result.first, result.second)
            }
            
            links.forEach { link ->
                Log.d("Hattrick-Link", "Adding link: ${link.url}")
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = link.lang.uppercase(),
                        url = link.url,
                        referer = link.ref,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
            
            return links.isNotEmpty()
        } catch (e: Exception) {
            Log.e("Hattrick-LoadLinks", "Error: ${e.message}")
            return false
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return cfKiller
    }

    data class Link(
        val lang: String,
        val url: String,
        val ref: String
    )
}