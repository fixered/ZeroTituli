package it.zeroTituli

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document

class DaddyLive : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://daddyhd.com"
    override var name = "DaddyLive"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    private val cfKiller = CloudflareKiller()

    data class Channel(val id: String, val name: String)

    private val channels = listOf(
        Channel("877", "Zona DAZN"),
        Channel("870", "Sky Sport Calcio"),
        Channel("871", "Sky Calcio 1"),
        Channel("872", "Sky Calcio 2"),
        Channel("873", "Sky Calcio 3"),
        Channel("874", "Sky Calcio 4")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val shows = channels.map { channel ->
            newLiveSearchResponse(channel.name, "$mainUrl/watch.php?id=${channel.id}", TvType.Live) {}
        }
        return newHomePageResponse(listOf(
            HomePageList("Live Channels", shows, isHorizontalImages = true)
        ))
    }

    override suspend fun load(url: String): LoadResponse {
    val document = app.get(url).document

    val title = document.selectFirst("h2")?.text() ?: "Live Stream"

    // Extract iframe URLs for all players
    val playerButtons = document.select("button.player-btn")
    val links = playerButtons.mapNotNull { btn ->
        val playerName = btn.attr("title")
        val playerUrl = btn.attr("data-url")
        if (playerUrl.isNotEmpty()) {
            newExtractorLink(
                source = name,
                name = playerName,
                url = playerUrl,
                type = ExtractorLinkType.LIVE
            )
        } else null
    }

    return newLiveStreamLoadResponse(title, url, dataUrl = url) {
        links.forEach { link -> this.addExtractorLink(link) }
    }
}

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val buttons = document.select("button.player-btn")

        buttons.forEach { btn ->
            val playerName = btn.attr("title")
            val playerUrl = btn.attr("data-url")
            if (playerUrl.isNotEmpty()) {
                val iframeDoc = app.get(playerUrl, referer = data).document
                val iframeSrc = iframeDoc.selectFirst("iframe")?.attr("src") ?: playerUrl
                callback(
                    newExtractorLink(
                        source = name,
                        name = playerName,
                        url = iframeSrc,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                    }
                )
            }
        }

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
