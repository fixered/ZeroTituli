package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DaddyLive : MainAPI() {

    override var mainUrl = "https://daddyhd.com"
    override var name = "DaddyLive"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    data class Channel(val id: String, val name: String)


    private val channels = listOf(
        Channel("877", "Zona DAZN"),
        Channel("870", "Sky Sport Calcio"),
        Channel("871", "Sky Calcio 1"),
        Channel("872", "Sky Calcio 2"),
        Channel("873", "Sky Calcio 3"),
        Channel("874", "Sky Calcio 4"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        return newHomePageResponse(channels.map { channel ->
            val categoryName = "LIVE"
            val shows = channels.map { channel
                val href = "$mainUrl/watch.php?id=${channel.id}"
                val name =  channel.name
                val posterUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"
                newLiveSearchResponse(name, href, TvType.Live) {
                    this.posterUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"
                }
            }
            HomePageList(
                categoryName,
                shows,
                isHorizontalImages = true
            )
        }, false)

    }

    override suspend fun load(url: String): LoadResponse {
        val channelId = url.substringAfter("id=")
        val channel = channels.find { it.id == channelId }
        val title = channel?.name ?: "Channel $channelId"

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url,
            type = TvType.Live
        )
    }

   private fun getStreamUrl(document: Document): String? {
        val scripts = document.body().select("script")
        val obfuscatedScript = scripts.findLast { it.data().contains("eval(") }
        val url = obfuscatedScript?.let {
            val data = getAndUnpack(it.data())
//            Log.d("CalcioStreaming", data)
            val sourceRegex = "(?<=src=\")([^\"]+)".toRegex()
            val source = sourceRegex.find(data)?.value ?: return null
            source
        } ?: return null

        return url
    }

    private suspend fun extractVideoStream(url: String, ref: String, n: Int): Pair<String, String>? {
        if (url.toHttpUrlOrNull() == null) return null
        if (n > 10) return null

        val doc = app.get(url).document
        val link = doc.selectFirst("iframe")?.attr("src") ?: return null
        val newPage = app.get(fixUrl(link), referer = ref).document
        val streamUrl = getStreamUrl(newPage)
        return if (newPage.select("script").size >= 6 && !streamUrl.isNullOrEmpty()) {
            streamUrl to fixUrl(link)
        } else {
            extractVideoStream(url = link, ref = url, n = n + 1)
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val links = document.select("button.btn.player-btn").mapNotNull {
            val lang = it.text()
            val url = it.attr("data-url")
            val host = try {
                java.net.URL(url).host
            } 
            catch (e: Exception) {
                null
            }
            val link = extractVideoStream(url, host ?: return@mapNotNull null, 1)
            if (link == null) return@mapNotNull null
                Link(lang, link.first, link.second)
        }
        links.map {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = it.lang,
                    url = it.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = 0
                    this.referer = it.ref
                }
            )
        }
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = cfKiller.intercept(chain)
                return response
            }
        }
    }
}
