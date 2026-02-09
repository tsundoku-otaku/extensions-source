package eu.kanade.tachiyomi.extension.en.novelmultiverse

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class NovelMultiverse :
    MadaraNovel(
        baseUrl = "https://novelmultiverse.com",
        name = "NovelMultiverse",
        lang = "en",
    ) {
    // Try admin-ajax.php endpoint (useNewChapterEndpoint = false is default)
}
