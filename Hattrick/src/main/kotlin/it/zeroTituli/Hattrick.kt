package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document

class Hattrick : MainAPI() {
    override var mainUrl = "https://hattrick.ws"
    override var name = "Hattrick"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Live)

    private val LOGO = "https://www.tuttotech.net/wp-content/uploads/2020/08/NOW-TV-Sky-On-Demand-logo-2.png"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
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
            list = listOf(
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

        var iframeUrl = document.select("iframe").attr("src")

        // Cerca iframe nel body se non trovato direttamente
        if (iframeUrl.isEmpty() || (!iframeUrl.contains("planetary") && !iframeUrl.contains("token="))) {
            val html = document.html()
            val regex = """(https?://[^\s"'<>]+token=[a-zA-Z0-9%\-_]+)""".toRegex()
            val match = regex.find(html)
            if (match != null) {
                iframeUrl = match.value
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Live, listOf()) {
            this.posterUrl = LOGO
            this.plot = "Diretta streaming di $title"
            this.tags = listOf(iframeUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        
        // Logica di ricerca iframe
        var iframeUrl = document.select("iframe[src*='token=']").attr("src")
        if (iframeUrl.isEmpty()) iframeUrl = document.select("iframe[src*='planetary']").attr("src")
        
        if (iframeUrl.isEmpty()) {
             val regex = """(https?://[^"']+(?:planetary|token=)[^"']+)""".toRegex()
             iframeUrl = regex.find(document.html())?.value ?: ""
        }

        if (iframeUrl.isNotEmpty()) {
            iframeUrl = iframeUrl.replace("\\/", "/")

            try {
                val uri = URI(iframeUrl)
                val queryParams = uri.query.split("&").associate {
                    val split = it.split("=")
                    if (split.size > 1) split[0] to split[1] else split[0] to ""
                }

                val token = queryParams["token"]

                if (token != null) {
                    val path = uri.path
                    val basePath = path.substringBeforeLast("/")
                    val m3u8Path = "$basePath/index.fmp4.m3u8?token=$token"
                    val finalUrl = "https://planetary.lovecdn.ru$m3u8Path"

                    // FIX: Sintassi aggiornata come in CalcioStreaming
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "Hattrick (Veloce)",
                            url = finalUrl,
                            type = ExtractorLinkType.M3U8 // Qui usiamo l'ENUM invece di boolean
                        ) {
                            // Qui dentro (nella lambda) si mettono referer e quality
                            this.referer = "https://planetary.lovecdn.ru/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
    }
}