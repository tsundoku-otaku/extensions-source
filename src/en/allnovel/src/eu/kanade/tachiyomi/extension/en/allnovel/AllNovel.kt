package eu.kanade.tachiyomi.extension.en.allnovel

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull

class AllNovel :
    ReadNovelFull(
        name = "AllNovel",
        baseUrl = "https://allnovel.org",
        lang = "en",
    ) {
    override val chapterListing = "ajax-chapter-option"
}
