package it.zeroTituli

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URI

class Hattrick : MainAPI() {
    override var mainUrl = "https://hattrick.ws"
    override var name = "Hattrick"
    override val hasMainPage = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    
    private val cfKiller = CloudflareKiller()
    private val LOGO = "https://www.tuttotech.net/wp-content/uploads/2020/08/NOW-TV-Sky-On-Demand-logo-2.png"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document

        val channels = document.select("button a[href$='.htm']").mapNotNull { element ->
            val channelName = element.text().trim()
            val href = fixUrl(element.attr("href"))

            if (channelName.isNotEmpty()) {
                newLiveSearchResponse(channelName, href, TvType.Live) {
                    this.posterUrl = LOGO
                }
            } else null
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = "Canali Live",
                    list = channels,
                    isHorizontalImages = true
                )
            ),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.title().replace("Hattrick", "").trim()

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = LOGO
            this.plot = "Diretta streaming di $title"
        }
    }

    private fun extractIframeUrl(document: Document): String? {
        // 1. Cerca iframe con "planetary" o "token=" direttamente
        var iframeUrl = document.select("iframe[src*='planetary']").attr("src")
        if (iframeUrl.isEmpty()) {
            iframeUrl = document.select("iframe[src*='token=']").attr("src")
        }
        
        // 2. Cerca con regex nell'HTML completo
        if (iframeUrl.isEmpty()) {
            val html = document.html()
            val regex = """(https?://[^\s"'<>]+(?:planetary|token=)[^\s"'<>]+)""".toRegex()
            val match = regex.find(html)
            if (match != null) {
                iframeUrl = match.value.replace("\\/", "/")
            }
        }
        
        // 3. Priorità assoluta: planetary.lovecdn.ru
        if (iframeUrl.isNotEmpty() && iframeUrl.contains("planetary.lovecdn.ru")) {
            return iframeUrl
        }
        
        return iframeUrl.ifEmpty { null }
    }

    private fun buildStreamUrls(iframeUrl: String): Pair<String, String>? {
        try {
            val uri = URI(iframeUrl)
            val queryParams = uri.query?.split("&")?.associate {
                val split = it.split("=")
                if (split.size > 1) split[0] to split[1] else split[0] to ""
            } ?: return null

            val token = queryParams["token"] ?: return null
            
            val path = uri.path
            val basePath = path.substringBeforeLast("/")
            val canonical = "$basePath/index.fmp4.m3u8?token=$token"
            
            // Due versioni come nello script Python
            val urlLenta = "${uri.scheme}://${uri.host}$canonical"
            val urlVeloce = "https://planetary.lovecdn.ru$canonical"
            
            return urlLenta to urlVeloce
        } catch (e: Exception) {
            Log.e("Hattrick", "Errore nella costruzione URL: ${e.message}")
            return null
        }
    }

    private suspend fun extractVideoStream(url: String, ref: String, depth: Int = 0): String? {
        if (url.toHttpUrlOrNull() == null || depth > 5) return null

        try {
            val doc = app.get(url, referer = ref).document
            
            // Cerca iframe nella pagina
            val iframeUrl = extractIframeUrl(doc)
            
            if (iframeUrl != null) {
                Log.d("Hattrick", "Iframe trovato: $iframeUrl")
                return iframeUrl
            }
            
            // Se c'è un altro iframe, segui ricorsivamente
            val nextIframe = doc.selectFirst("iframe")?.attr("src")
            if (nextIframe != null) {
                return extractVideoStream(fixUrl(nextIframe), url, depth + 1)
            }
            
        } catch (e: Exception) {
            Log.e("Hattrick", "Errore estrazione: ${e.message}")
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
        
        // Estrai l'iframe con planetary/token
        val iframeUrl = extractVideoStream(data, data) ?: return false
        
        // Costruisci gli URL lento e veloce
        val urls = buildStreamUrls(iframeUrl) ?: return false
        val (urlLenta, urlVeloce) = urls
        
        Log.d("Hattrick", "URL Lenta: $urlLenta")
        Log.d("Hattrick", "URL Veloce: $urlVeloce")
        
        // Aggiungi entrambe le versioni
        callback(
            newExtractorLink(
                source = this.name,
                name = "Hattrick (Lento)",
                url = urlLenta,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = iframeUrl.substringBefore("/embed")
                this.quality = Qualities.Unknown.value
            }
        )
        
        callback(
            newExtractorLink(
                source = this.name,
                name = "Hattrick (Veloce)",
                url = urlVeloce,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://planetary.lovecdn.ru/"
                this.quality = Qualities.Unknown.value
            }
        )
        
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                return cfKiller.intercept(chain)
            }
        }
    }
}