package it.zeroTituli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DaddyLive : MainAPI() {
    override var mainUrl = "https://daddyhd.com"
    override var name = "DaddyLive"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    data class Channel(val id: String, val name: String)

    private val daznChannels = listOf(
        Channel("877", "Zona DAZN"),
    )

    private val skyChannels = listOf(
        Channel("870", "Sky Sport Calcio"),
        Channel("871", "Sky Calcio 1"),
        Channel("872", "Sky Calcio 2"),
        Channel("873", "Sky Calcio 3"),
        Channel("874", "Sky Calcio 4"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val daznList = daznChannels.map { channel ->
            newLiveSearchResponse(
                channel.name,
                "$mainUrl/watch.php?id=${channel.id}"
            )
        }

        val skyList = skyChannels.map { channel ->
            newLiveSearchResponse(
                channel.name,
                "$mainUrl/watch.php?id=${channel.id}"
            )
        }

        return newHomePageResponse(
            listOf(
                HomePageList("DAZN Channels", daznList),
                HomePageList("Sky Channels", skyList)
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allChannels = daznChannels + skyChannels

        return allChannels.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.id.contains(query, ignoreCase = true)
        }.map { channel ->
            newLiveSearchResponse(
                channel.name,
                "$mainUrl/watch.php?id=${channel.id}"
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val channelId = url.substringAfter("id=")
        val allChannels = daznChannels + skyChannels
        val channel = allChannels.find { it.id == channelId }

        return LiveStreamLoadResponse(
            name = channel?.name ?: "Channel $channelId",
            url = url,
            type = TvType.Live,
            dataUrl = url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val channelId = data.substringAfter("id=")

        val players = listOf(
            "stream" to "Player 1",
            "cast" to "Player 2",
            "watch" to "Player 3",
            "plus" to "Player 4",
            "casting" to "Player 5",
            "player" to "Player 6"
        )

        players.forEach { (playerType, playerName) ->
            try {
                val playerUrl = "$mainUrl/$playerType/stream-$channelId.php"
                val doc = app.get(playerUrl).document
                val iframe = doc.selectFirst("iframe[src]")
                val streamUrl = iframe?.attr("src")

                if (!streamUrl.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - $playerName",
                            url = fixUrl(streamUrl),
                            type = ExtractorLinkType.M3U8
                        )
                    )
                }
            } catch (e: Exception) {
                // ignora player non disponibili
            }
        }

        return true
    }
}
