package com.x12

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URLEncoder

// ─── DATA CLASSES ───────────────────────────────────────────────────────────

data class PaheSearchResponse(
    val data: List<PaheSearchItem>? = null
)

data class PaheSearchItem(
    val id: Int? = null,
    val title: String = "",
    val type: String = "TV",
    val episodes: Int? = null,
    val status: String? = null,
    val season: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val poster: String? = null,
    val session: String = ""
)

data class PaheEpisodeList(
    val total: Int? = null,
    val data: List<PaheEpisodeItem>? = null,
    val current_page: Int? = null,
    val last_page: Int? = null
)

data class PaheEpisodeItem(
    val id: Int? = null,
    val anime_id: Int? = null,
    val episode: Double? = null,
    val title: String? = null,
    val snapshot: String? = null,
    val duration: String? = null,
    val session: String = "",
    val filler: Int? = null
)

// ─── MAIN PROVIDER ──────────────────────────────────────────────────────────

class AnimePaheProvider : MainAPI() {

    override var mainUrl = "https://animepahe.pw"
    override var name = "AnimePahe"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false

    private fun getType(t: String): TvType {
        return when (t.lowercase()) {
            "movie"            -> TvType.AnimeMovie
            "ova", "ona",
            "special"          -> TvType.OVA
            else               -> TvType.Anime
        }
    }

    private fun PaheSearchItem.toSearchResult(): AnimeSearchResponse {
        return newAnimeSearchResponse(
            title,
            "$mainUrl/anime/$session",
            getType(type)
        ) {
            this.posterUrl = poster
        }
    }

    // ─── HOME PAGE ──────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "airing"   to "Currently Airing",
        "upcoming" to "Upcoming",
        "tv"       to "TV Series",
        "movie"    to "Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val response = app.get(
            "$mainUrl/api?m=${request.data}&page=$page"
        ).parsedSafe<PaheSearchResponse>()

        val results = response?.data?.map { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, results)
    }

    // ─── SEARCH ─────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/api?m=search&q=${URLEncoder.encode(query, "UTF-8")}"
        ).parsedSafe<PaheSearchResponse>()

        return response?.data?.map { it.toSearchResult() } ?: emptyList()
    }

    // ─── LOAD ────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val session = url.substringAfterLast("/anime/")
        val doc     = app.get(url).document

        val title   = doc.selectFirst("h1.title-name")?.text()
            ?: doc.selectFirst(".anime-title")?.text()
            ?: "Unknown"
        val poster  = doc.selectFirst(".anime-poster img")?.attr("data-src")
            ?: doc.selectFirst(".anime-poster img")?.attr("src")
        val plot    = doc.selectFirst(".anime-synopsis")?.text()
        val year    = doc.selectFirst(".anime-aired")?.text()
            ?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val tags    = doc.select(".anime-genre a").map { it.text() }

        val episodes = mutableListOf<Episode>()
        var page     = 1
        var lastPage = 1

        do {
            val epList = app.get(
                "$mainUrl/api?m=release&id=$session&sort=episode_asc&page=$page"
            ).parsedSafe<PaheEpisodeList>()

            lastPage = epList?.last_page ?: 1

            epList?.data?.forEach { ep ->
                episodes.add(
                    newEpisode(Pair(session, ep.session).toJson()) {
                        this.name      = ep.title ?: "Episode ${ep.episode?.toInt()}"
                        this.episode   = ep.episode?.toInt()
                        this.posterUrl = ep.snapshot
                    }
                )
            }
            page++
        } while (page <= lastPage)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ─── LOAD LINKS ──────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed       = parseJson<Pair<String, String>>(data)
        val animeSession = parsed.first
        val epSession    = parsed.second

        val playUrl  = "$mainUrl/play/$animeSession/$epSession"
        val document = app.get(playUrl).document

        // Find all Kwik links from the resolution buttons
        document.select("div#resolutionMenu button").forEach { button: Element ->
            val kwikUrl = button.attr("data-src")
            if (kwikUrl.isNotBlank()) {
                loadExtractor(kwikUrl, playUrl, subtitleCallback, callback)
            }
        }

        document.select("a.dropdown-item[data-src]").forEach { a: Element ->
            val kwikUrl = a.attr("data-src")
            if (kwikUrl.isNotBlank()) {
                loadExtractor(kwikUrl, playUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}