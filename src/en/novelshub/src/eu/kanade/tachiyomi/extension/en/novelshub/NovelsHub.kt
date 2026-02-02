package eu.kanade.tachiyomi.extension.en.novelshub

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

/**
 * NovelsHub.org - Novel reading extension
 * Uses RSC (React Server Components) for data fetching
 * @see instructions.html for RSC parsing details
 */
class NovelsHub : HttpSource(), NovelSource {

    override val name = "NovelsHub"
    override val baseUrl = "https://novelshub.org"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    // RSC headers - required for x-component response
    private fun rscHeaders(): Headers = headers.newBuilder()
        .add("rsc", "1")
        .add("Accept", "*/*")
        .build()

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/?page=$page", rscHeaders())
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        val novels = mutableListOf<SManga>()
        val doc = Jsoup.parse(body)

        // Per instructions.html: Popular novels from wrapper elements with cover image
        // Selector: div.wrapper img.cover-image + h1 for title
        doc.select("div.wrapper").forEach { wrapper ->
            try {
                val img = wrapper.selectFirst("img.cover-image, img[alt*=Cover]")
                val cover = img?.attr("src")?.ifEmpty { null } ?: img?.attr("data-nimg")
                val titleElement = wrapper.selectFirst("h1")
                val title = titleElement?.text()?.trim() ?: return@forEach

                // Find the parent link to get URL
                val link = wrapper.parent()?.closest("a[href*=/series/]")
                    ?: wrapper.selectFirst("a[href*=/series/]")
                val url = link?.attr("href")?.replace(baseUrl, "") ?: return@forEach

                novels.add(
                    SManga.create().apply {
                        this.title = title
                        this.url = url
                        thumbnail_url = cover
                    },
                )
            } catch (e: Exception) {
                // Skip
            }
        }

        // Per instructions.html: Latest novels from figure elements
        // figure.relative > a[href*=/series/] with img and title link
        doc.select("figure.relative, figure").forEach { figure ->
            try {
                val link = figure.selectFirst("a[href*=/series/]") ?: return@forEach
                val url = link.attr("href").replace(baseUrl, "")
                if (url.isEmpty() || novels.any { it.url == url }) return@forEach

                val img = figure.selectFirst("img")
                val cover = img?.attr("src")?.ifEmpty { null } ?: img?.attr("data-nimg")

                val titleLink = figure.selectFirst("a.text-sm, a.font-bold, a[title]")
                    ?: figure.select("a[href*=/series/]").lastOrNull()
                val title = titleLink?.attr("title")?.ifEmpty { null }
                    ?: titleLink?.text()?.trim()
                    ?: link.attr("title")
                    ?: return@forEach

                novels.add(
                    SManga.create().apply {
                        this.title = title
                        this.url = url
                        thumbnail_url = cover
                    },
                )
            } catch (e: Exception) {
                // Skip
            }
        }

        // Additional: Try card-group elements
        doc.select(".card-group, div[class*=card]").forEach { card ->
            try {
                val link = card.selectFirst("a[href*=/series/]") ?: return@forEach
                val url = link.attr("href").replace(baseUrl, "")
                if (url.isEmpty() || novels.any { it.url == url }) return@forEach

                val img = card.selectFirst("img")
                val cover = img?.attr("src")?.ifEmpty { null }

                val title = link.attr("title")?.ifEmpty { null }
                    ?: card.selectFirst("a.font-bold, .line-clamp-2")?.text()?.trim()
                    ?: return@forEach

                novels.add(
                    SManga.create().apply {
                        this.title = title
                        this.url = url
                        thumbnail_url = cover
                    },
                )
            } catch (e: Exception) {
                // Skip
            }
        }

        // Fallback: Parse RSC response for JSON objects with novel data
        // The RSC response contains embedded JSON with "slug", "postTitle", "featuredImage" fields
        if (novels.isEmpty()) {
            // Pattern to extract novel data: "slug":"xxx","postTitle":"xxx"
            val slugTitlePattern = Regex(""""slug"\s*:\s*"([^"]+)"\s*,\s*"postTitle"\s*:\s*"([^"]+)"""")
            val imagePattern = Regex(""""featuredImage"\s*:\s*"([^"]+)"""")

            // Find all slug/title pairs
            slugTitlePattern.findAll(body).forEach { match ->
                try {
                    val slug = match.groupValues[1]
                    val title = match.groupValues[2]

                    // Skip chapter slugs (like "chapter-1")
                    if (slug.startsWith("chapter-")) return@forEach

                    // Try to find the corresponding image (search nearby in the text)
                    val startIdx = maxOf(0, match.range.first - 200)
                    val endIdx = minOf(body.length, match.range.last + 500)
                    val nearbyText = body.substring(startIdx, endIdx)
                    val cover = imagePattern.find(nearbyText)?.groupValues?.get(1)

                    novels.add(
                        SManga.create().apply {
                            this.title = title
                            url = "/series/$slug"
                            thumbnail_url = cover
                        },
                    )
                } catch (e: Exception) {
                    // Skip malformed entries
                }
            }
        }

        return MangasPage(novels.distinctBy { it.url }, novels.size >= 10)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?q=$encodedQuery&page=$page", rscHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, rscHeaders())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()

        return SManga.create().apply {
            // Extract postTitle
            title = Regex(""""postTitle"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1) ?: ""

            // Extract postContent (description) - HTML content
            val postContent = Regex(""""postContent"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
                ?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")
                ?.replace("\\/", "/")
            description = postContent?.let { Jsoup.parse(it).text() }

            // Extract featuredImage for cover
            thumbnail_url = Regex(""""featuredImage"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1)
                ?: Regex(""""ImageObject"[^}]*"url"\s*:\s*"([^"]+)"""").find(body)
                    ?.groupValues?.get(1)

            // Extract author
            author = Regex(""""author"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1)

            // Extract genres array
            val genresMatch = Regex(""""genres"\s*:\s*\[(.*?)\]""").find(body)
            genre = genresMatch?.groupValues?.get(1)?.let { genresStr ->
                Regex(""""name"\s*:\s*"([^"]+)"""").findAll(genresStr)
                    .map { it.groupValues[1].trim() }
                    .joinToString(", ")
            }

            // Extract status
            val statusStr = Regex(""""seriesStatus"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1)
            status = when (statusStr?.uppercase()) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, rscHeaders())
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val novelPath = response.request.url.encodedPath

        val chapters = mutableListOf<SChapter>()

        // Per instructions.html: Look for "_count":{"chapters":N} for total chapter count
        val totalChapters = Regex(""""_count"\s*:\s*\{[^}]*"chapters"\s*:\s*(\d+)""").find(body)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""Chapters\s*\(\s*(\d+)\s*\)""").find(body)
                ?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

        // Extract novel slug for URL construction
        val novelSlug = Regex(""""slug"\s*:\s*"([^"]+)"""").find(body)
            ?.groupValues?.get(1)
            ?: novelPath.split("/").lastOrNull { it.isNotEmpty() }
            ?: return emptyList()

        // First, try to extract chapters directly from the RSC response
        // RSC format has: {"id":152012,"slug":"chapter-166","number":166,"title":"",..."mangaPost":{...},...}
        // Use simpler regex that matches "slug":"chapter-X" and "number":X pairs directly
        // Find all occurrences of "id":...,slug":"chapter-X","number":X pattern (before mangaPost)
        Regex(""""id":\d+,"slug":"(chapter-\d+)","number":(\d+)""")
            .findAll(body)
            .forEach { match ->
                val slug = match.groupValues[1]
                val number = match.groupValues[2].toIntOrNull() ?: return@forEach
                chapters.add(
                    SChapter.create().apply {
                        url = "/series/$novelSlug/$slug"
                        name = "Chapter $number"
                        chapter_number = number.toFloat()
                    },
                )
            }

        // Fallback: generate chapters from _count if parsing failed
        if (chapters.isEmpty() && totalChapters > 0) {
            for (chapterNum in 1..totalChapters) {
                chapters.add(
                    SChapter.create().apply {
                        url = "/series/$novelSlug/chapter-$chapterNum"
                        name = "Chapter $chapterNum"
                        chapter_number = chapterNum.toFloat()
                    },
                )
            }
        }

        return chapters.reversed()
    }

    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    override fun imageUrlParse(response: Response): String = ""

    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        // Per instructions.html: Use RSC request with rsc:1 header
        val rscRequest = GET(baseUrl + page.url, rscHeaders())
        val response = client.newCall(rscRequest).execute()
        val body = response.body.string()

        // RSC T-tag format: NUMBER:THEX,<p>content</p>
        // Example: 21:T4844,<p>-----------------------------------------------------------------</p><p>Translator...
        // The pattern is: digits:T followed by hex digits, then comma, then HTML content
        val tTagPattern = Regex("""\d+:T[0-9a-f]+,(<p>.*)""", RegexOption.DOT_MATCHES_ALL)
        val tTagMatch = tTagPattern.find(body)

        if (tTagMatch != null) {
            var content = tTagMatch.groupValues[1]

            // The content usually ends before the next JSON block (e.g., 4:["$","$Lc",...)
            // We look for the last closing paragraph tag
            val lastP = content.lastIndexOf("</p>")
            if (lastP != -1) {
                content = content.substring(0, lastP + 4)
            }

            // Clean up the content - remove separator lines and metadata
            content = content.replace(Regex("""<p>-+</p>"""), "")
            content = content.replace(Regex("""<p>Translator:.*?</p>""", RegexOption.IGNORE_CASE), "")
            content = content.replace(Regex("""<p>Chapter:.*?</p>""", RegexOption.IGNORE_CASE), "")
            content = content.replace(Regex("""<p>Chapter Title:.*?</p>""", RegexOption.IGNORE_CASE), "")

            // Remove any trailing JSON artifacts if they slipped through
            if (content.contains(":[") || content.contains("\":")) {
                val jsonStart = content.indexOf("\":[")
                if (jsonStart != -1) {
                    content = content.substring(0, jsonStart)
                    val lastValidP = content.lastIndexOf("</p>")
                    if (lastValidP != -1) {
                        content = content.substring(0, lastValidP + 4)
                    }
                }
            }

            return content.trim()
        }

        // Alternative pattern: look for "content" field reference
        val contentPattern = Regex(""""content"\s*:\s*"\$(\d+)"""")
        val contentMatch = contentPattern.find(body)
        if (contentMatch != null) {
            val contentRef = contentMatch.groupValues[1]
            // Find the referenced content block
            val refPattern = Regex("""$contentRef:T[0-9a-f]+,(.+?)(?=\d+:\[|\d+:|$)""", RegexOption.DOT_MATCHES_ALL)
            val refMatch = refPattern.find(body)
            if (refMatch != null) {
                return refMatch.groupValues[1].trim()
            }
        }

        // Fallback: Extract all <p> tags that look like content (not metadata)
        val paragraphs = Regex("""<p>([^<]*(?:(?!</p>)<[^<]*)*)</p>""").findAll(body)
            .map { it.value }
            .filter { p ->
                !p.contains("---") &&
                    !p.contains("Translator:") &&
                    !p.contains("Chapter Title:") &&
                    !p.startsWith("<p>Chapter:") &&
                    p.length > 20
            }
            .toList()

        if (paragraphs.isNotEmpty()) {
            return paragraphs.joinToString("\n")
        }

        // Last resort: Try HTML parsing
        val doc = Jsoup.parse(body)
        return doc.selectFirst("div.prose, article, .chapter-content")?.html() ?: ""
    }

    override fun getFilterList(): FilterList = FilterList()
    private class TagFilter : Filter.Group<ExcludableCheckBox>(
    "Genres",
    listOf(
        ExcludableCheckBox("REINCARNATION", "1"),
        ExcludableCheckBox("SYSTEM", "2"),
        ExcludableCheckBox("MYSTERY", "3"),
        ExcludableCheckBox("Action ", "4"),
        ExcludableCheckBox("DETECTIVE CONAN", "5"),
        ExcludableCheckBox("ISEKAI", "6"),
        ExcludableCheckBox("WEAKTOSTRONG", "7"),
        ExcludableCheckBox("ANIME", "8"),
        ExcludableCheckBox("Romance ", "9"),
        ExcludableCheckBox("School Life", "10"),
        ExcludableCheckBox("Wuxia", "11"),
        ExcludableCheckBox("Fantasy", "12"),
        ExcludableCheckBox("Drama", "13"),
        ExcludableCheckBox("Comedy", "14"),
        ExcludableCheckBox("Martial Arts", "15"),
        ExcludableCheckBox("Supernatural", "16"),
        ExcludableCheckBox("Cunning Protagonist", "17"),
        ExcludableCheckBox("Light Novel", "18"),
        ExcludableCheckBox("Military", "19"),
        ExcludableCheckBox("Harem", "20"),
        ExcludableCheckBox("Modern Day", "21"),
        ExcludableCheckBox("Transmigration", "22"),
        ExcludableCheckBox("Urban Fantasy", "23"),
        ExcludableCheckBox("Adopted Sister", "24"),
        ExcludableCheckBox("Male Protagonist", "25"),
        ExcludableCheckBox("Faction Building", "26"),
        ExcludableCheckBox("Superpowers", "27"),
        ExcludableCheckBox("Science", "28"),
        ExcludableCheckBox(" Fiction", "29"),
        ExcludableCheckBox("Space-Time Travel", "30"),
        ExcludableCheckBox(" Dimensional Travel", "31"),
        ExcludableCheckBox("MAGIC", "32"),
        ExcludableCheckBox("WIZARDS", "33"),
        ExcludableCheckBox("Adult", "34"),
        ExcludableCheckBox("Life ", "35"),
        ExcludableCheckBox("Adventure ", "36"),
        ExcludableCheckBox(" Shounen", "37"),
        ExcludableCheckBox("Psychological", "38"),
        ExcludableCheckBox("Academy ", "39"),
        ExcludableCheckBox("Character Growth ", "40"),
        ExcludableCheckBox("Game ", "41"),
        ExcludableCheckBox("Elements ", "42"),
        ExcludableCheckBox("Transported into a Game World", "43"),
        ExcludableCheckBox("Gender Bender ", "44"),
        ExcludableCheckBox("Slice of Life", "45"),
        ExcludableCheckBox("Sports", "46"),
        ExcludableCheckBox("Revenge", "47"),
        ExcludableCheckBox("Hard Work", "48"),
        ExcludableCheckBox("Survival", "49"),
        ExcludableCheckBox("Historical", "50"),
        ExcludableCheckBox("Healing Romance", "51"),
        ExcludableCheckBox("shoujo", "52"),
        ExcludableCheckBox("Possession", "53"),
        ExcludableCheckBox("Regression", "54"),
        ExcludableCheckBox("Seinen", "55"),
        ExcludableCheckBox("Sci-Fi", "56"),
        ExcludableCheckBox("Tragedy", "57"),
        ExcludableCheckBox("Shounen", "58"),
        ExcludableCheckBox("Mature", "59"),
        ExcludableCheckBox("cultivation-elements", "60"),
        ExcludableCheckBox("secret-organization", "61"),
        ExcludableCheckBox("Horror", "62"),
        ExcludableCheckBox("weak-to-strong", "63"),
        ExcludableCheckBox("Crime", "64"),
        ExcludableCheckBox("Police", "65"),
        ExcludableCheckBox("Urban Life", "66"),
        ExcludableCheckBox("Workplace", "67"),
        ExcludableCheckBox("Finance", "68"),
        ExcludableCheckBox("Business Management", "69"),
        ExcludableCheckBox("wall-street", "70"),
        ExcludableCheckBox("beautiful-female-leads", "71"),
        ExcludableCheckBox("wealth-building", "72"),
        ExcludableCheckBox("stock-market", "73"),
        ExcludableCheckBox("Second Chance", "74"),
        ExcludableCheckBox("silicon-valley", "75"),
        ExcludableCheckBox("financial-warfare", "76"),
        ExcludableCheckBox("Dystopia", "77"),
        ExcludableCheckBox("Another World", "78"),
        ExcludableCheckBox("Thriller", "79"),
        ExcludableCheckBox("Genius Protagonist", "80"),
        ExcludableCheckBox("Business / Management", "81"),
        ExcludableCheckBox("gallery", "82"),
        ExcludableCheckBox("Investor", "83"),
        ExcludableCheckBox("Obsession", "84"),
        ExcludableCheckBox("Misunderstandings", "85"),
        ExcludableCheckBox("Ecchi", "86"),
        ExcludableCheckBox("Yuri", "87"),
        ExcludableCheckBox("Shoujo AI", "88"),
        ExcludableCheckBox("summoned to a tower, gallery system", "89"),
        ExcludableCheckBox("game element", "90"),
        ExcludableCheckBox("Xianxia", "91"),
        ExcludableCheckBox("Serial Killers", "92"),
        ExcludableCheckBox("Murders", "93"),
        ExcludableCheckBox("Unconditional Love", "94"),
        ExcludableCheckBox("Demons ", "95"),
        ExcludableCheckBox("Regret", "96"),
        ExcludableCheckBox("Josei", "97"),
        ExcludableCheckBox("murim", "98"),
        ExcludableCheckBox("Dark Fantasy", "99"),
        ExcludableCheckBox("Game World", "100"),
        ExcludableCheckBox("religious", "101"),
        ExcludableCheckBox("TerritoryManagement", "102"),
        ExcludableCheckBox("Genius", "103"),
        ExcludableCheckBox("Scoundrel", "104"),
        ExcludableCheckBox("Nobility", "105"),
        ExcludableCheckBox("Tower Climbing", "106"),
        ExcludableCheckBox("Professional", "107"),
        ExcludableCheckBox("Overpowered", "108"),
        ExcludableCheckBox("Singer", "109"),
        ExcludableCheckBox("Veteran", "110"),
        ExcludableCheckBox("Effort", "111"),
        ExcludableCheckBox("Manager", "112"),
        ExcludableCheckBox("Supernatural Ability", "113"),
        ExcludableCheckBox("Devour or Absorption", "114"),
        ExcludableCheckBox("Artifact", "115"),
        ExcludableCheckBox("Mortal Path", "116"),
        ExcludableCheckBox("Decisive and Ruthless", "117"),
        ExcludableCheckBox("Idol", "118"),
        ExcludableCheckBox("Heroes", "119"),
        ExcludableCheckBox("Cultivation", "120"),
        ExcludableCheckBox("Love Triangle", "121"),
        ExcludableCheckBox("First Love", "122"),
        ExcludableCheckBox("Reverse Harem", "123"),
        ExcludableCheckBox("One-Sided Love", "124"),
        ExcludableCheckBox("Smut", "125"),
        ExcludableCheckBox("War", "126"),
        ExcludableCheckBox("Apocalypse", "127"),
        ExcludableCheckBox("Chaos", "128"),
        ExcludableCheckBox("Magic and sword", "129"),
        ExcludableCheckBox("Mecha ", "130"),
        ExcludableCheckBox("Actor", "131"),
        ExcludableCheckBox("MMORPG", "132"),
        ExcludableCheckBox("Virtual Reality", "133"),
        ExcludableCheckBox("Xuanhuan ", "134"),
        ExcludableCheckBox("Yaoi", "135"),
        ExcludableCheckBox("matur", "136"),
        ExcludableCheckBox("ghoststory", "137"),
        ExcludableCheckBox("GL", "138"),
        ExcludableCheckBox("Necrosmith", "139"),
        ExcludableCheckBox("Necromancer", "140"),
        ExcludableCheckBox("Blacksmith", "141"),
        ExcludableCheckBox("artist", "142"),
        ExcludableCheckBox("Childcare", "143"),
        ExcludableCheckBox("Streaming", "144"),
        ExcludableCheckBox("All-Rounder", "145"),
        ExcludableCheckBox("OP(Munchkin)", "146"),
        ExcludableCheckBox("gambling", "147"),
        ExcludableCheckBox("money", "148"),
        ExcludableCheckBox("r18", "149"),
        ExcludableCheckBox("Tsundere", "150"),
        ExcludableCheckBox("Proactive Protagonist", "151"),
        ExcludableCheckBox(" Cute Story", "152"),
        ExcludableCheckBox("Alternate Universe", "153"),
        ExcludableCheckBox("Movie", "154"),
        ExcludableCheckBox("adhesion", "155"),
        ExcludableCheckBox("illusion", "156"),
        ExcludableCheckBox("Villain role", "157"),
        ExcludableCheckBox("ModernFantasy", "158"),
        ExcludableCheckBox("hunter", "159"),
        ExcludableCheckBox("TS", "160"),
        ExcludableCheckBox("munchkin", "161"),
        ExcludableCheckBox("tower", "162"),
        ExcludableCheckBox("hyundai", "163"),
        ExcludableCheckBox("modern fantasy", "164"),
        ExcludableCheckBox("alchemy", "165"),
        ExcludableCheckBox("worldwar", "166"),
        ExcludableCheckBox("WarHero", "167"),
        ExcludableCheckBox("#AlternativeHistory", "168"),
        ExcludableCheckBox("famous famaily", "169"),
        ExcludableCheckBox("dark", "170"),
        ExcludableCheckBox("yandere", "171"),
        ExcludableCheckBox("ghost", "172"),
        ExcludableCheckBox("catfight", "173"),
        ExcludableCheckBox("sauce", "174"),
        ExcludableCheckBox("food", "175"),
        ExcludableCheckBox("cook", "176"),
        ExcludableCheckBox("cyberpunk", "177"),
        ExcludableCheckBox("mind control", "178"),
        ExcludableCheckBox("hypnosis", "179"),
        ExcludableCheckBox("# Mukbang/Cooking", "180"),
        ExcludableCheckBox("fusion", "181"),
        ExcludableCheckBox("Awakening", "182"),
        ExcludableCheckBox("Farming", "183"),
        ExcludableCheckBox("Pure Love", "184"),
        ExcludableCheckBox("slave", "185"),
        ExcludableCheckBox("Kingdom Building", "186"),
        ExcludableCheckBox("Political", "187"),
        ExcludableCheckBox("Redemption", "188"),
        ExcludableCheckBox("Ai", "189"),
        ExcludableCheckBox("showbiz", "190"),
        ExcludableCheckBox("Orthodox", "191"),
        ExcludableCheckBox("EntertainmentIndustry", "192"),
        ExcludableCheckBox("writer", "193"),
        ExcludableCheckBox("Healing", "194"),
        ExcludableCheckBox("Medical", "195"),
        ExcludableCheckBox("Mana", "196"),
        ExcludableCheckBox("Medieval", "197"),
        ExcludableCheckBox("Schemes ", "198"),
        ExcludableCheckBox("love", "199"),
        ExcludableCheckBox("Marriage ", "200"),
        ExcludableCheckBox("netrori", "201"),
        ExcludableCheckBox("gods", "202"),
        ExcludableCheckBox("crazy love interest ", "203"),
        ExcludableCheckBox("MMA", "204"),
        ExcludableCheckBox("ice age", "205"),
        ExcludableCheckBox("management", "206"),
        ExcludableCheckBox("Female Protagonist", "207"),
        ExcludableCheckBox("Royalty", "208"),
        ExcludableCheckBox("Mob Protagonist", "209"),
        ExcludableCheckBox("climbing", "210"),
        ExcludableCheckBox("middleAge", "211"),
        ExcludableCheckBox("romance fantasy", "212"),
        ExcludableCheckBox("cooking", "213"),
        ExcludableCheckBox("return", "214"),
        ExcludableCheckBox("northern air force", "215"),
        ExcludableCheckBox("National Management", "216"),
        ExcludableCheckBox("#immortality", "217"),
        ExcludableCheckBox("Fist Techniques", "218"),
        ExcludableCheckBox("Retired Expert", "219"),
        ExcludableCheckBox("Returnee", "220"),
        ExcludableCheckBox("Hidden Identity", "221"),
        ExcludableCheckBox("Zombie", "222"),
        ExcludableCheckBox("Knight", "223"),
        ExcludableCheckBox("NTL", "224"),
        ExcludableCheckBox("bitcoins", "225"),
        ExcludableCheckBox("crypto", "226"),
        ExcludableCheckBox("actia", "227"),
        ExcludableCheckBox("Brainwashing", "228"),
        ExcludableCheckBox("Tentacles", "229"),
        ExcludableCheckBox("Slime", "230"),
        ExcludableCheckBox("cultivators", "231"),
        ExcludableCheckBox("bully", "232"),
        ExcludableCheckBox("#university", "233"),
        ExcludableCheckBox("BL", "234"),
        ExcludableCheckBox("Omegaverse", "235"),
        ExcludableCheckBox("Girl's Love", "236"),
        ExcludableCheckBox("theater", "237"),
        ExcludableCheckBox("Broadcasting", "238"),
        ExcludableCheckBox("Success", "239"),
        ExcludableCheckBox("Internet Broadcasting", "240"),
        ExcludableCheckBox("rape", "241"),
        ExcludableCheckBox("Madman", "242"),
        ExcludableCheckBox("Soccer", "243"),
        ExcludableCheckBox("#SoloProtagonist", "244"),
        ExcludableCheckBox("#Underworld", "245"),
        ExcludableCheckBox("#Politics", "246"),
        ExcludableCheckBox("#Army", "247"),
        ExcludableCheckBox("#ThreeKingdoms", "248"),
        ExcludableCheckBox("#Conspiracy", "249"),
        ExcludableCheckBox(" Possessive Characters", "250"),
        ExcludableCheckBox("European Ambience", "251"),
        ExcludableCheckBox("Love Interest Falls in Love First", "252"),
        ExcludableCheckBox("Reincarnated in a Game World", "253"),
        ExcludableCheckBox("Male Yandere", "254"),
        ExcludableCheckBox("Handsome Male Lead ", "255"),
        ExcludableCheckBox("Monsters ", "256"),
        ExcludableCheckBox("Urban Legend", "257"),
        ExcludableCheckBox("modern", "258"),
        ExcludableCheckBox("summoning", "259"),
        ExcludableCheckBox("LightNovel", "260"),
        ExcludableCheckBox("vampire", "261"),
        ExcludableCheckBox("GameDevelopment", "262"),
        ExcludableCheckBox("Normalization", "263"),
        ExcludableCheckBox("GameFantasy", "264"),
        ExcludableCheckBox("VirtualReality", "265"),
        ExcludableCheckBox("Infinite Money Glitch", "266"),
        ExcludableCheckBox("Tycoon", "267"),
        ExcludableCheckBox("#CampusLife", "268"),
        ExcludableCheckBox("#Regression", "269"),
        ExcludableCheckBox("#Chaebol", "270"),
        ExcludableCheckBox("#Business", "271"),
        ExcludableCheckBox("#RealEstate", "272"),
        ExcludableCheckBox("#Revenge", "273"),
        ExcludableCheckBox("#Healing", "274"),
        ExcludableCheckBox("SF", "275"),
        ExcludableCheckBox("Community", "276"),
        ExcludableCheckBox("Anomaly", "277"),
        ExcludableCheckBox("CosmicHorror", "278"),
        ExcludableCheckBox("CreepypastaUniverse", "279"),
        ExcludableCheckBox("growth", "280"),
        ExcludableCheckBox("Bingyi", "281"),
        ExcludableCheckBox("Healer", "282"),
        ExcludableCheckBox("#TSHeroine", "283"),
        ExcludableCheckBox("#management", "284"),
        ExcludableCheckBox("#GoldenSun", "285"),
        ExcludableCheckBox("GrowthMunchkin", "286"),
        ExcludableCheckBox("Fundamentals", "287"),
        ExcludableCheckBox("broadcast", "288"),
        ExcludableCheckBox("Luck", "289"),
        ExcludableCheckBox("Investment", "290"),
        ExcludableCheckBox("Divorced", "291"),
        ExcludableCheckBox("#mercenary", "292"),
        ExcludableCheckBox("#Art", "293"),
        ExcludableCheckBox("#All-Rounder", "294"),
        ExcludableCheckBox("#EntertainmentIndustry", "295"),
        ExcludableCheckBox("#Music", "296"),
        ExcludableCheckBox("Villain", "297"),
        ExcludableCheckBox("Psychopath", "298"),
        ExcludableCheckBox("Battle Royale", "299"),
        ExcludableCheckBox("Progression", "300"),
        ExcludableCheckBox("Billionaire", "301"),
        ExcludableCheckBox("Beast Tamer", "302"),
        ExcludableCheckBox("#HighIntensity", "303"),
        ExcludableCheckBox("#Enterprise", "304"),
        ExcludableCheckBox("#Growth", "305"),
        ExcludableCheckBox("#Obsession", "306"),
        ExcludableCheckBox("#Multiverse", "307"),
        ExcludableCheckBox("#Academy", "308"),
        ExcludableCheckBox("#NTL", "309"),
        ExcludableCheckBox("#MaleOriented", "310"),
        ExcludableCheckBox("#Possession", "311"),
        ExcludableCheckBox("#Isekai", "312"),
        ExcludableCheckBox("#Idol", "313"),
        ExcludableCheckBox("#Filming", "314"),
        ExcludableCheckBox("#Training", "315"),
        ExcludableCheckBox("Hitler", "316"),
        ExcludableCheckBox("Early Modern", "317"),
        ExcludableCheckBox("Alternate History", "318"),
        ExcludableCheckBox("Salvation", "319"),
        ExcludableCheckBox("fate", "320"),
        ExcludableCheckBox("DevotedMaleLead", "321"),
        ExcludableCheckBox("PowerfulMaleLead", "322"),
        ExcludableCheckBox("StrongAbility", "323"),
        ExcludableCheckBox("gate", "324"),
        ExcludableCheckBox("childbirth", "325"),
        ExcludableCheckBox("Hetrosexual", "326"),
        ExcludableCheckBox("ClubOwner", "327"),
        ExcludableCheckBox("SlowPaced", "328"),
        ExcludableCheckBox("Western", "329"),
        ExcludableCheckBox("Cheat", "330"),
        ExcludableCheckBox("Gunslinger", "331"),
        ExcludableCheckBox("Pure Romance", "332"),
        ExcludableCheckBox("Humiliation", "333"),
        ExcludableCheckBox("#Territory", "334"),
        ExcludableCheckBox("Assistant", "335"),
        ExcludableCheckBox("Rich", "336"),
        ExcludableCheckBox("#Zombie", "337"),
        ExcludableCheckBox("#StatusWindow", "338"),
        ExcludableCheckBox("#Apocalypse", "339"),
        ExcludableCheckBox("#GirlGroup", "340"),
        ExcludableCheckBox("Labyrinth", "341"),
        ExcludableCheckBox("Gender Reversal", "342")
    )
)

}

private val kotlinx.serialization.json.JsonElement.jsonObject: kotlinx.serialization.json.JsonObject
    get() = this as kotlinx.serialization.json.JsonObject

private val kotlinx.serialization.json.JsonElement.jsonPrimitive: kotlinx.serialization.json.JsonPrimitive
    get() = this as kotlinx.serialization.json.JsonPrimitive

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else null
