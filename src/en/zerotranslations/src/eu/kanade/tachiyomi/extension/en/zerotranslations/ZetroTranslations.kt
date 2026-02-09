package eu.kanade.tachiyomi.extension.en.zerotranslations

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * ZetroTranslations - Madara-based source
 * @see https://github.com/LNReader/lnreader-plugins ZetroTranslation[madara].ts
 * Features: hasLocked chapters, genre filters, status filters
 */
class ZetroTranslations :
    MadaraNovel(
        baseUrl = "https://zetrotranslation.com",
        name = "Zetro Translations",
        lang = "en",
    ) {
    // LN Reader TS doesn't set useNewChapterEndpoint (defaults to false)
    // ID extracted from shortlink or rating-post-id or manga-chapters-holder
    override val useNewChapterEndpointDefault = false

    // ======================= Filters (from LN Reader) =======================

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
        GenreOperatorFilter(),
        AuthorFilter(),
        ArtistFilter(),
        YearFilter(),
        AdultContentFilter(),
        StatusFilter(),
        OrderByFilter(),
    )

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addQueryParameter("genre[]", it.uriPart)
                    }
                }

                is GenreOperatorFilter -> {
                    if (filter.state) {
                        url.addQueryParameter("op", "1")
                    }
                }

                is AuthorFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("author", filter.state)
                    }
                }

                is ArtistFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("artist", filter.state)
                    }
                }

                is YearFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("release", filter.state)
                    }
                }

                is AdultContentFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("adult", filter.toUriPart())
                    }
                }

                is StatusFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addQueryParameter("status[]", it.uriPart)
                    }
                }

                is OrderByFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("m_orderby", filter.toUriPart())
                    }
                }

                else -> {}
            }
        }

        return GET(url.build().toString(), headers)
    }

    // ======================= Filter Classes =======================

    private class Genre(name: String, val uriPart: String) : Filter.CheckBox(name)

    private class GenreFilter :
        Filter.Group<Genre>(
            "Genres",
            listOf(
                Genre("Action", "action"),
                Genre("Adventure", "adventure"),
                Genre("Comedy", "comedy"),
                Genre("Dark Elf", "dark-elf"),
                Genre("Drama", "drama"),
                Genre("Ecchi", "ecchi"),
                Genre("Fantasy", "fantasy"),
                Genre("Harem", "harem"),
                Genre("Horror", "horror"),
                Genre("Isekai", "isekai"),
                Genre("Mecha", "mecha"),
                Genre("Mystery", "mystery"),
                Genre("NTR", "ntr"),
                Genre("Original Works", "original-works"),
                Genre("Rom-Com", "rom-com"),
                Genre("Romance", "romance"),
                Genre("School", "school"),
                Genre("Shoujo", "shoujo"),
                Genre("Slice of Life", "slice-of-life"),
                Genre("Villain", "villain"),
                Genre("Yuri", "yuri"),
            ),
        )

    private class GenreOperatorFilter : Filter.CheckBox("Require ALL selected genres", false)

    private class AuthorFilter : Filter.Text("Author")

    private class ArtistFilter : Filter.Text("Artist")

    private class YearFilter : Filter.Text("Year of Release")

    private class AdultContentFilter :
        Filter.Select<String>(
            "Adult Content",
            arrayOf("All", "None adult content", "Only adult content"),
        ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "0"
            2 -> "1"
            else -> ""
        }
    }

    private class Status(name: String, val uriPart: String) : Filter.CheckBox(name)

    private class StatusFilter :
        Filter.Group<Status>(
            "Status",
            listOf(
                Status("Completed", "complete"),
                Status("Ongoing", "on-going"),
                Status("Canceled", "canceled"),
                Status("On Hold", "on-hold"),
            ),
        )

    private class OrderByFilter :
        Filter.Select<String>(
            "Order by",
            arrayOf("Relevance", "Latest", "A-Z", "Rating", "Trending", "Most Views", "New"),
        ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "latest"
            2 -> "alphabet"
            3 -> "rating"
            4 -> "trending"
            5 -> "views"
            6 -> "new-manga"
            else -> ""
        }
    }
}
