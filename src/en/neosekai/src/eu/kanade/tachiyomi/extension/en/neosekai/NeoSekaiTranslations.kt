package eu.kanade.tachiyomi.extension.en.neosekai

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class NeoSekaiTranslations :
    MadaraNovel(
        baseUrl = "https://www.neosekaitranslations.com",
        name = "NeoSekai Translations",
        lang = "en",
    ) {
    // Try admin-ajax.php endpoint (useNewChapterEndpoint = false is default)
}
