package com.x12

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element

// ─── DATA CLASSES ───────────────────────────────────────────────────────────
// These represent the JSON responses from AnimePahe's internal API

data class SearchResponse(
    val data: List<SearchItem>
)
data class SearchItem(
    val id: Int,
    val title: String,
    val type: String,
    val episodes: Int?,
    val status: String,
    val season: String,
    val year: Int?,
    val score: Double?,
    val poster: String,
    val session: String
)

data class EpisodeList(
    val total: Int,
    val data: List<EpisodeItem>,
    val current_page: Int,
    val last_page: Int
)
data class EpisodeItem(
    val id: Int,
    val anime_id: Int,
    val episode: Double,
    val episode2: Double,
    val edition: String,
    val title: String?,
    val snapshot: String,
    val disc: String?,
    val audio: String?,
    val duration: String,
    val session: String,
    val filler: Int
)

// ─── MAIN PROVIDER CLASS ────────────────────────────────────────────────────

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

    // ── Helper: convert AnimePahe type string to CloudStream TvType
    private fun getType(t: String): TvType {
        return when (t.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    // ── Helper: convert a SearchItem to a CloudStream AnimeSearchResponse
    private fun SearchItem.toSearchResult(): AnimeSearchResponse {
        return newAnimeSearchResponse(
            title,
            "$mainUrl/anime/$session",
            getType(type)
        ) {
            this.posterUrl = poster
        }
    }

    // ─── HOME PAGE ──────────────────────────────────────────────────────────
    // Defines what "sections" appear on the home page

    override val mainPage = mainPageOf(
        "airing"    to "Currently Airing",
        "upcoming"  to "Upcoming",
        "tv"        to "TV Series",
        "movie"     to "Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // AnimePahe has a simple JSON API
        val apiUrl = "$mainUrl/api?m=${request.data}&page=$page"
        val response = app.get(apiUrl).parsedSafe<SearchResponse>() ?: return newHomePageResponse(
            request.name,
            emptyList<AnimeSearchResponse>()
        )
        val results = response.data.map { it.toSearchResult() }
        return newHomePageResponse(request.name, results)
    }

    // ─── SEARCH ─────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/api?m=search&q=${query.encodeURL()}"
        ).parsedSafe<SearchResponse>() ?: return emptyList()

        return response.data.map { it.toSearchResult() }
    }

    // ─── LOAD ANIME DETAIL PAGE ─────────────────────────────────────────────
    // Called when user taps on a result. Returns title, description, episode list.

    override suspend fun load(url: String): LoadResponse {
        // Extract session from url: https://animepahe.pw/anime/SESSION
        val session = url.substringAfterLast("/anime/")
        val doc = app.get(url).document

        // Grab metadata from the page HTML
        val title     = doc.selectFirst("h1.title-name")?.text()
            ?: doc.selectFirst(".anime-title")?.text() ?: "Unknown"
        val poster    = doc.selectFirst(".anime-poster img")?.attr("data-src")
            ?: doc.selectFirst(".anime-poster img")?.attr("src")
        val plot      = doc.selectFirst(".anime-synopsis")?.text()
        val year      = doc.selectFirst(".anime-aired")?.text()
            ?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val tags      = doc.select(".anime-genre a").map { it.text() }

        // Get ALL episodes across all pages
        val episodes  = mutableListOf<Episode>()
        var page      = 1
        var lastPage  = 1

        do {
            val epListUrl = "$mainUrl/api?m=release&id=$session&sort=episode_asc&page=$page"
            val epList    = app.get(epListUrl).parsedSafe<EpisodeList>() ?: break
            lastPage      = epList.last_page

            epList.data.forEach { ep ->
                episodes.add(
                    newEpisode(
                        // We pass both sessions so we can use them when loading links
                        Pair(session, ep.session).toJson()
                    ) {
                        this.name        = ep.title ?: "Episode ${ep.episode.toInt()}"
                        this.episode     = ep.episode.toInt()
                        this.posterUrl   = ep.snapshot
                        this.runTime     = ep.duration.let {
                            // "23m" -> 23
                            Regex("(\\d+)m").find(it)?.groupValues?.get(1)?.toIntOrNull()
                        }
                    }
                )
            }
            page++
        } while (page <= lastPage)

        // Determine if it's a movie or series
        val tvType = if (episodes.size == 1) TvType.AnimeMovie else TvType.Anime

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl      = poster
            this.plot           = plot
            this.year           = year
            this.tags           = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ─── LOAD VIDEO LINKS ───────────────────────────────────────────────────
    // The hardest part! AnimePahe uses Kwik.cx as video host.
    // CloudStream already has a built-in Kwik extractor — we just need to find the Kwik URL.

    override suspend fun loadLinks(
        data: String,        // This is the JSON we stored: Pair(animeSession, episodeSession)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Decode the stored data
        val parsed       = parseJson<Pair<String, String>>(data)
        val animeSession = parsed.first
        val epSession    = parsed.second

        // Fetch the episode play page
        val playUrl  = "$mainUrl/play/$animeSession/$epSession"
        val document = app.get(playUrl).document

        // AnimePahe lists Kwik embeds in a <div> called #pickfornow or similar
        // Each quality option has a Kwik link
        document.select("div#resolutionMenu button").forEach { button ->
            val kwikUrl = button.attr("data-src")
            if (kwikUrl.isNotBlank()) {
                // CloudStream's built-in loadExtractor handles Kwik automatically!
                loadExtractor(kwikUrl, playUrl, subtitleCallback, callback)
            }
        }

        // Also check the <a> tags in case the layout is different
        document.select("a.dropdown-item[data-src]").forEach { a ->
            val kwikUrl = a.attr("data-src")
            if (kwikUrl.isNotBlank()) {
                loadExtractor(kwikUrl, playUrl, subtitleCallback, callback)
            }
        }

        return true
    }

    // ── Small helper to URL-encode search queries
    private fun String.encodeURL() = java.net.URLEncoder.encode(this, "UTF-8")
}