package eu.kanade.tachiyomi.extension.en.arcanetranslations

import eu.kanade.tachiyomi.multisrc.lightnovelwp.LightNovelWP

class ArcaneTranslations :
    LightNovelWP(
        name = "Arcane Translations",
        baseUrl = "https://arcanetranslations.com",
        lang = "en",
    ) {
    override val reverseChapters = true
}
