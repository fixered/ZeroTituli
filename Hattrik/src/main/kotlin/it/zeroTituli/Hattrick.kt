package it.zeroTituli

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Hattrick : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://www.hattrick.ws"
    override var name = "Hattrick"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val cfKiller = CloudflareKiller()

    // Helper to ensure absolute URLs
    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return url
        return if (url.startsWith("http")) url else (mainUrl.trimEnd('/') + "/" + url.trimStart('/'))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document

        val lists = ArrayList<HomePageList>()

        // 1) Collect channel buttons from the big "Canali On Line" area (buttons inside .details .btn > a)
        val channelButtons = document.select(".details .btn a")
            .mapNotNull { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = a.text().ifBlank { a.attr("title").ifBlank { a.attr("href") } }
                val poster = a.selectFirst("img")?.attr("src") ?: ""
                newLiveSearchResponse(title, fixUrl(href), TvType.Live) {
                    this.posterUrl = if (poster.isNotBlank()) fixUrl(poster) else ""
                }
            }
        if (channelButtons.isNotEmpty()) {
            lists.add(HomePageList("Canali On Line", channelButtons, isHorizontalImages = false))
        }

        // 2) Collect events rows - each .events .row representing a match with buttons
        val eventRows = document.select(".events .row").mapNotNull { row ->
            try {
                val title = row.selectFirst(".game-name")?.text()?.trim() ?: row.selectFirst(".details a")?.text()?.trim() ?: "Live"
                val date = row.selectFirst(".date")?.text()?.trim() ?: ""
                // collect all buttons/links inside this row
                val buttons = row.select("button a").mapNotNull { a ->
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val name = a.text().ifBlank { title }
                    newLiveSearchResponse("$title ${if (date.isNotBlank()) " — $date" else ""}", fixUrl(href), TvType.Live) {
                        // try to capture a logo inside the row if present
                        val img = row.selectFirst(".logos img")?.attr("src") ?: ""
                        this.posterUrl = if (img.isNullOrBlank()) "" else fixUrl(img)
                    }
                }
                if (buttons.isEmpty()) return@mapNotNull null
                HomePageList(title, buttons, isHorizontalImages = false)
            } catch (e: Exception) {
                null
            }
        }
        // add events lists (flattened — each match becomes a small list)
        lists.addAll(eventRows)

        if (lists.isEmpty()) throw ErrorLoadingException()

        return newHomePageResponse(lists, false)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // Title / poster / description extraction
        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst(".game-name")?.text()
            ?: document.title().ifBlank { name }
        val poster = document.selectFirst(".mascot img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""
        val description = document.selectFirst(".date")?.text()
            ?: document.selectFirst(".compz")?.text()
            ?: document.selectFirst("meta[name=description]")?.attr("content") ?: ""

        return newLiveStreamLoadResponse(name = title, url = url, dataUrl = url) {
            this.posterUrl = if (poster.isNullOrBlank()) "" else fixUrl(poster)
            this.plot = description
        }
    }

    // Attempt to parse obfuscated scripts similarly to CalcioStreaming
    private fun getStreamUrl(document: Document): String? {
        // Look for obvious iframe src
        val iframe = document.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrBlank()) return fixUrl(iframe)

        // If there's an obfuscated script (eval), attempt to find src inside its unpacked data
        val scripts = document.body().select("script")
        val ob = scripts.findLast { it.data().contains("eval(") || it.data().contains("split(\"z\")") }
        val data = ob?.data()?.let {
            try {
                getAndUnpack(it)
            } catch (e: Exception) {
                null
            }
        }
        if (!data.isNullOrBlank()) {
            // common pattern: src="..."; look for src="..." or src:'...'
            val regex = "(?<=src=\\\")([^\"]+)|(?<=src=')([^']+)".toRegex()
            val match = regex.find(data)
            val found = match?.value ?: regex.find(data)?.value
            if (!found.isNullOrBlank()) return fixUrl(found)
        }
        return null
    }

    // Recursively follow iframe/embed pages to reach a final playable URL
    private suspend fun extractVideoStream(url: String, ref: String, n: Int = 1): Pair<String, String>? {
        if (url.toHttpUrlOrNull() == null) return null
        if (n > 10) return null
        val doc = app.get(url).document
        // Try iframe
        val iframe = doc.selectFirst("iframe")?.attr("src")
        val streamCandidate = getStreamUrl(doc) ?: iframe
        if (!streamCandidate.isNullOrBlank()) {
            val fixed = fixUrl(streamCandidate)
            // Heuristic: if the candidate looks like m3u8 or contains /playlist or ends with .m3u8 -> return
            if (fixed.contains(".m3u8") || fixed.contains("playlist") || fixed.contains("m3u")) {
                return fixed to fixUrl(url)
            }
            // Otherwise, follow it once more
            return extractVideoStream(fixed, ref = url, n = n + 1)
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

        // 1) If the page includes a direct iframe to live player, try to resolve it
        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        val links = mutableListOf<Pair<String, String>>()

        // If there are language buttons like ".embed-container > .langs > button" (as in CalcioStreaming), parse them
        val languageButtons = document.select("div.embed-container > div.langs > button")
        if (languageButtons.isNotEmpty()) {
            languageButtons.forEach { btn ->
                val lang = btn.text().ifBlank { "LIVE" }
                val url = btn.attr("data-link").ifBlank { btn.attr("data-src") }
                if (url.isNullOrBlank()) return@forEach
                val resolved = extractVideoStream(url, ref = data, n = 1)
                if (resolved != null) links.add(resolved.first to resolved.second)
                else links.add(fixUrl(url) to data)
            }
        }

        // 2) If no language buttons, try iframe or internal links (.details a / .btn a)
        if (links.isEmpty()) {
            // prefer iframe
            if (!iframeSrc.isNullOrBlank()) {
                val resolved = extractVideoStream(fixUrl(iframeSrc), ref = data, n = 1)
                if (resolved != null) links.add(resolved.first to resolved.second)
                else links.add(fixUrl(iframeSrc) to data)
            } else {
                // fallback: search for buttons/anchors in the page and try them
                val anchors = document.select("a").mapNotNull { it.attr("href").takeIf { h -> h.isNotBlank() } }
                for (a in anchors) {
                    // prefer internal .htm pages that look like channels
                    if (a.contains(".htm") || a.contains("live")) {
                        val resolved = extractVideoStream(fixUrl(a), ref = data, n = 1)
                        if (resolved != null) {
                            links.add(resolved.first to resolved.second)
                            break
                        }
                    }
                }
            }
        }

        // Emit extractor links
        links.forEachIndexed { idx, pair ->
            val url = pair.first
            val ref = pair.second
            Log.d("Hattrick", "Found link: $url (ref: $ref)")
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "Hattrick ${idx + 1}",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = 0
                    this.referer = ref
                }
            )
        }

        // If we found nothing, try simple fallback: if the page contains direct .m3u8 anywhere in HTML
        if (links.isEmpty()) {
            val m3u8 = document.html().let {
                val r = "(https?:\\\\?/\\\\?/[^\"]+\\.m3u8)".toRegex()
                r.find(it)?.value?.replace("\\", "")
                    ?: "(https?://[^\\s'\"<>]+\\.m3u8)".toRegex().find(it)?.value
            }
            if (!m3u8.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "Hattrick (direct)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = 0
                        this.referer = data
                    }
                )
                return true
            }
        }

        return links.isNotEmpty()
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
