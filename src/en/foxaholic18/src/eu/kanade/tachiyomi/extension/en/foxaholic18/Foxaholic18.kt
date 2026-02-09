package eu.kanade.tachiyomi.extension.en.foxaholic18

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class Foxaholic18 :
    MadaraNovel(
        baseUrl = "https://18.foxaholic.com",
        name = "Foxaholic 18+",
        lang = "en",
    ) {
    // Uses new chapter endpoint (/ajax/chapters/) which returns clean chapter HTML
    // The old admin-ajax.php endpoint returns the full page instead of chapter list
    override val useNewChapterEndpointDefault = true
}
