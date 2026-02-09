package eu.kanade.tachiyomi.multisrc.novelcool

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

abstract class NovelCool(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val langCode: String,
) : HttpSource() {

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val apiUrl = "https://api.novelcool.com"

    private val userAgent = "Android/Package:com.zuoyou.novel - Version Name:2.3 - Phone Info:sdk_gphone_x86_64(Android Version:13)"
    private val appId = "202201290625004"
    private val secret = "c73a8590641781f203660afca1d37ada"
    private val packageName = "com.zuoyou.novel"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", userAgent)

    private fun apiHeaders() = headersBuilder()
        .add("Content-Type", "application/x-www-form-urlencoded")
        .build()

    private fun baseBodyBuilder(): FormBody.Builder = FormBody.Builder()
        .add("appId", appId)
        .add("secret", secret)
        .add("package_name", packageName)
        .add("lang", langCode)

    override fun popularMangaRequest(page: Int): Request {
        val body = baseBodyBuilder()
            .add("lc_type", "novel")
            .add("page", page.toString())
            .add("page_size", "20")
            .build()

        return POST("$apiUrl/elite/hot", apiHeaders(), body)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonObject = json.parseToJsonElement(response.body.string()).jsonObject
        val list = jsonObject["list"]?.jsonArray ?: return MangasPage(emptyList(), false)

        val mangas = list.map { element ->
            val obj = element.jsonObject
            SManga.create().apply {
                title = obj["name"]!!.jsonPrimitive.content
                thumbnail_url = obj["cover"]!!.jsonPrimitive.content
                url = obj["visit_path"]!!.jsonPrimitive.content + "?id=" + obj["id"]!!.jsonPrimitive.content
            }
        }

        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val body = baseBodyBuilder()
            .add("lc_type", "novel")
            .add("page", page.toString())
            .add("page_size", "20")
            .build()

        return POST("$apiUrl/elite/latest", apiHeaders(), body)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val body = baseBodyBuilder()
            .add("keyword", query)
            .add("page", page.toString())
            .add("page_size", "20")
            .build()

        return POST("$apiUrl/book/search/", apiHeaders(), body)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringAfter("?id=")
        val body = baseBodyBuilder()
            .add("book_id", id)
            .build()

        return POST("$apiUrl/book/info/", apiHeaders(), body)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val jsonObject = json.parseToJsonElement(response.body.string()).jsonObject
        val info = jsonObject["info"]!!.jsonObject

        return SManga.create().apply {
            title = info["name"]!!.jsonPrimitive.content
            thumbnail_url = info["cover"]!!.jsonPrimitive.content
            author = info["author"]?.jsonPrimitive?.contentOrNull
            artist = info["artist"]?.jsonPrimitive?.contentOrNull
            description = info["intro"]?.jsonPrimitive?.contentOrNull
            genre = info["category_list"]?.jsonArray?.joinToString { it.jsonPrimitive.content }
            status = if (info["completed"]?.jsonPrimitive?.content == "YES") SManga.COMPLETED else SManga.ONGOING
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfter("?id=")
        val body = baseBodyBuilder()
            .add("book_id", id)
            .build()

        return POST("$apiUrl/chapter/book_list/", apiHeaders(), body)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonObject = json.parseToJsonElement(response.body.string()).jsonObject
        val list = jsonObject["list"]?.jsonArray ?: return emptyList()

        return list.mapNotNull { element ->
            val obj = element.jsonObject
            val isLocked = obj["is_locked"]?.jsonPrimitive?.content
            if (isLocked == "1" || isLocked == "true") {
                return@mapNotNull null
            }

            SChapter.create().apply {
                name = obj["title"]!!.jsonPrimitive.content
                url = obj["book_id"]!!.jsonPrimitive.content + "/" + obj["id"]!!.jsonPrimitive.content
                date_upload = obj["last_modify"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000) ?: 0L
                chapter_number = obj["order_id"]?.jsonPrimitive?.content?.toFloatOrNull() ?: -1f
            }
        }.reversed()
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfter("/")
        val body = baseBodyBuilder()
            .add("chapter_id", chapterId)
            .build()

        return POST("$apiUrl/chapter/info/", apiHeaders(), body)
    }

    override fun pageListParse(response: Response): List<Page> = emptyList()

    override fun imageUrlParse(response: Response) = ""
}
