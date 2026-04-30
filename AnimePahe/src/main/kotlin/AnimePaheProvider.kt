package com.x12

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimePaheProvider : MainAPI() {

    override var mainUrl = "https://ww192.pencurimoviesubmalay.motorcycles"
    override var name = "PencuriMovie"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    override var lang = "ms"
    override val hasMainPage = true
    override val hasQuickSearch = false

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val url    = anchor.attr("href") ?: return null
        val title  = anchor.attr("title")
            ?: this.selectFirst("h3")?.text()
            ?: return null
        val poster = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("img")?.attr("data-src")

        return if (url.contains("/tvshows/")) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ─── HOME PAGE ──────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/movies/"               to "Movies Terbaru",
        "$mainUrl/tvshows/"              to "TV Shows Terbaru",
        "$mainUrl/group_movie/malaysub/" to "MalaySub Movies",
        "$mainUrl/group_movie/english/"  to "English Movies",
        "$mainUrl/group_movie/korean/"   to "Korean Movies",
        "$mainUrl/group_tv/korean/"      to "Korean Drama",
        "$mainUrl/group_tv/english/"     to "English Series",
        "$mainUrl/release/2025/"         to "2025 Releases"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data
        else "${request.data}?page=$page"

        val doc     = app.get(url).document
        val results = doc.select("div.ml-item, article.item, div.item")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, results)
    }

    // ─── SEARCH ─────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        ).document

        return doc.select("div.ml-item, article.item, div.item")
            .mapNotNull { it.toSearchResult() }
    }

    // ─── LOAD ────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc   = app.get(url).document
        val isTv  = url.contains("/tvshows/")

        val title  = doc.selectFirst("h3.movieTitle, h1, .entry-title")?.text()
            ?: "Unknown"
        val poster = doc.selectFirst("div.movieImgs img, .poster img")?.attr("src")
            ?: doc.selectFirst("img.img-responsive")?.attr("src")
        val plot   = doc.selectFirst("div.desc, .entry-content p, p.plot")?.text()
        val year   = doc.selectFirst("span.year, .year a")?.text()?.toIntOrNull()
        val tags   = doc.select("span.genre a, .genres a").map { it.text() }

        return if (isTv) {
            val episodes = mutableListOf<Episode>()

            doc.select("div.episodios li, .episodio, ul.episodios li").forEach { li ->
                val epLink  = li.selectFirst("a")?.attr("href") ?: return@forEach
                val epTitle = li.selectFirst("a")?.text() ?: ""
                val epNum   = Regex("(\\d+)").find(epTitle)?.value?.toIntOrNull()
                val epThumb = li.selectFirst("img")?.attr("src")

                episodes.add(newEpisode(epLink) {
                    this.name      = epTitle
                    this.episode   = epNum
                    this.posterUrl = epThumb
                })
            }

            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) {
                    this.name = title
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        }
    }

    // ─── LOAD LINKS ──────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("ul.server-list li, div.server-list a, .tabcontent iframe").forEach { el ->
            val src = when {
                el.tagName() == "iframe" -> el.attr("src")
                el.tagName() == "a"      -> el.attr("href")
                else -> el.selectFirst("a")?.attr("href") ?: ""
            }
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        doc.select("a.mvi-cover, a[href*='esuhandout'], a[href*='embed']").forEach { a ->
            val src = a.attr("href")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}