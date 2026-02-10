package eu.kanade.tachiyomi.extension.en.freewebnovel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * FreeWebNovel extension
 * - Search: POST to /search with searchkey, no pagination
 * - Genre browsing: GET /genre/GenreName/page
 * - Popular/Latest: GET /sort/X/page
 */
class FreeWebNovel :
    HttpSource(),
    NovelSource {

    override val name = "FreeWebNovel"
    override val baseUrl = "https://freewebnovel.com"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        // TS: pageAsPath: true - page 1 has no page number in URL
        // TS: noPages: ["sort/most-popular"] - skip pagination after page 1
        if (page > 1) {
            // Most popular only shows page 1 (per TS noPages config)
            return GET("$baseUrl/sort/most-popular", headers)
        }
        return GET("$baseUrl/sort/most-popular", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        val novels = parseNovelList(doc)

        // TS: Check if we're on most-popular (noPages config)
        val currentUrl = response.request.url.toString()
        val isMostPopular = currentUrl.contains("/sort/most-popular")

        // TS: most-popular only shows first page
        if (isMostPopular) {
            return MangasPage(novels, false)
        }

        // TS: pageAsPath - page 1 has no number in URL, page 2+ has /{page}
        val currentPage = Regex("""/(\\d+)$""").find(currentUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        // TS: Check for pagination - novels.size >= 20 indicates more pages likely exist
        val hasNext = novels.size >= 20 || doc.select("div.pages a, ul.pagination a").any { link ->
            val pageNum = link.text().toIntOrNull()
            pageNum != null && pageNum > currentPage
        }

        return MangasPage(novels, hasNext)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        // TS: pageAsPath: true - page 1 has no page number, page 2+ has /{page}
        return if (page > 1) {
            GET("$baseUrl/sort/latest-novels/$page", headers)
        } else {
            GET("$baseUrl/sort/latest-novels", headers)
        }
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Check if genre filter is selected
        var selectedGenre: String? = null
        filters.forEach { filter ->
            if (filter is GenreFilter && filter.state != 0) {
                selectedGenre = filter.toUriPart()
            }
        }

        return if (query.isNotBlank()) {
            // Text search - POST, no pagination
            val body = FormBody.Builder()
                .add("searchkey", query)
                .build()
            POST("$baseUrl/search", headers, body)
        } else if (selectedGenre != null) {
            // Genre browsing - GET with pagination (TS: pageAsPath: true)
            if (page > 1) {
                GET("$baseUrl/genre/$selectedGenre/$page", headers)
            } else {
                GET("$baseUrl/genre/$selectedGenre", headers)
            }
        } else {
            // No query, no genre - return popular
            popularMangaRequest(page)
        }
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ======================== Parse Novels ========================

    private fun parseNovelList(doc: org.jsoup.nodes.Document): List<SManga> {
        // TS template: finds h3 > a elements within .col-content or .archive wrapper
        // Direct approach: select all h3 a elements (simpler and matches TS behavior)
        return doc.select("h3.tit a, h3.novel-title a").mapNotNull { link ->
            SManga.create().apply {
                title = link.attr("title").ifEmpty { link.text().trim() }
                setUrlWithoutDomain(link.attr("abs:href"))

                // Find image - traverse up from link to find nearest img
                thumbnail_url = link.parents().firstNotNullOfOrNull { parent ->
                    parent.selectFirst("img")
                }?.let { img ->
                    img.attr("data-src").ifEmpty { img.attr("src") }
                }?.let { if (it.startsWith("/")) "$baseUrl$it" else it }
            }
        }
    }

    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            doc.selectFirst("div.books, div.book")?.let { info ->
                thumbnail_url = info.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }?.let { if (it.startsWith("/")) "$baseUrl$it" else it }
                title = info.selectFirst("h3.title, h1.tit")?.text() ?: ""
            }

            doc.select("div.info div, ul.info-meta li").forEach { element ->
                val text = element.text()
                when {
                    text.contains("Author", ignoreCase = true) ->
                        author = element.select("a").joinToString { it.text().trim() }
                            .ifEmpty { text.substringAfter(":").trim() }

                    text.contains("Genre", ignoreCase = true) ->
                        genre = element.select("a").joinToString { it.text().trim() }
                            .ifEmpty { text.substringAfter(":").trim() }

                    text.contains("Status", ignoreCase = true) ->
                        status = parseStatus(text.substringAfter(":").trim())
                }
            }

            description = doc.selectFirst("div.desc-text, div.inner, div.desc")?.text()?.trim()
        }
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
        status.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ======================== Chapters ========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())
        return doc.select("ul#idData li a, div.chapter-list a, ul.list-chapter li a").mapIndexedNotNull { index, element ->
            val chapterUrl = element.attr("abs:href")
            if (chapterUrl.isBlank()) return@mapIndexedNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(chapterUrl)
                name = element.attr("title").ifEmpty { element.text().trim() }
                chapter_number = (index + 1).toFloat()
            }
        }
    }

    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override fun imageUrlParse(response: Response) = ""

    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())

        val contentSelectors = listOf(
            "div#chr-content",
            "div#chapter-content",
            "div#article",
            "div.txt",
            "div.chapter-content",
        )

        for (selector in contentSelectors) {
            val content = doc.selectFirst(selector)
            if (content != null) {
                content.select("div.ads, script, ins, .adsbygoogle").remove()
                return content.html()
            }
        }
        return ""
    }

    // ======================== Filters ========================

    override fun getFilterList() = FilterList(
        Filter.Header("Text search ignores filters"),
        GenreFilter(),
    )

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf(
                "All", "Action", "Adult", "Adventure", "Comedy", "Drama", "Ecchi",
                "Fantasy", "Gender Bender", "Harem", "Historical", "Horror", "Josei",
                "Martial Arts", "Mature", "Mecha", "Mystery", "Psychological", "Romance",
                "School Life", "Sci-fi", "Seinen", "Shoujo", "Shounen", "Slice of Life",
                "Smut", "Sports", "Supernatural", "Tragedy", "Wuxia", "Xianxia", "Xuanhuan", "Yaoi", "Yuri",
            ),
            0,
        ) {
        fun toUriPart(): String {
            val genreNames = arrayOf(
                "", "Action", "Adult", "Adventure", "Comedy", "Drama", "Ecchi",
                "Fantasy", "Gender-Bender", "Harem", "Historical", "Horror", "Josei",
                "Martial-Arts", "Mature", "Mecha", "Mystery", "Psychological", "Romance",
                "School-Life", "Sci-fi", "Seinen", "Shoujo", "Shounen", "Slice-of-Life",
                "Smut", "Sports", "Supernatural", "Tragedy", "Wuxia", "Xianxia", "Xuanhuan", "Yaoi", "Yuri",
            )
            return genreNames[state]
        }
    }
}
