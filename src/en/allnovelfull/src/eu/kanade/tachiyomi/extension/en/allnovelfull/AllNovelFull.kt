package eu.kanade.tachiyomi.extension.en.allnovelfull

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull

class AllNovelFull :
    ReadNovelFull(
        name = "AllNovelFull",
        baseUrl = "https://novgo.net",
        lang = "en",
    ) {
    override val chapterListing = "ajax-chapter-option"
}
