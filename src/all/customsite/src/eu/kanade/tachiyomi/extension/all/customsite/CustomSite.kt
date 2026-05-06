package eu.kanade.tachiyomi.extension.all.customsite

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * CustomSite — a Tachiyomi/Mihon extension that lets the user supply
 * any base URL in the source settings.  The extension then treats that
 * URL as a simple image-gallery / manga site and attempts a best-effort
 * scrape.  Extend / override the parse methods below for specific sites.
 */
class CustomSite : HttpSource(), ConfigurableSource {

    // ────────────────────────────────────────────────────────────
    // Source identity
    // ────────────────────────────────────────────────────────────

    override val name = "Custom Site"
    override val lang = "all"
    override val supportsLatest = false

    // baseUrl is read from SharedPreferences at call-time so that
    // a settings change takes effect without restarting the app.
    override val baseUrl: String
        get() = preferences.getString(PREF_KEY_BASE_URL, DEFAULT_BASE_URL)!!
            .trimEnd('/')

    // ────────────────────────────────────────────────────────────
    // SharedPreferences
    // ────────────────────────────────────────────────────────────

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences(
            "source_${id}",
            android.content.Context.MODE_PRIVATE,
        )
    }

    // ────────────────────────────────────────────────────────────
    // ConfigurableSource — builds the Settings screen shown in the
    // app when the user taps the gear icon next to this source.
    // ────────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // ── Base URL input ──────────────────────────────────────
        EditTextPreference(screen.context).apply {
            key = PREF_KEY_BASE_URL
            title = "Site URL"
            summary = "Enter the full URL of the site (e.g. https://example.com)"
            setDefaultValue(DEFAULT_BASE_URL)
            dialogTitle = "Site URL"
            dialogMessage = "Include https:// and no trailing slash."
            // Show the current value as the summary so the user
            // can see what they set at a glance.
            setOnPreferenceChangeListener { _, newValue ->
                val url = newValue as String
                summary = if (url.isBlank()) "Not set" else url
                true
            }
            // Initialise summary with whatever is already saved.
            summary = preferences.getString(key, DEFAULT_BASE_URL)
                ?.takeIf { it.isNotBlank() } ?: "Not set"
        }.also(screen::addPreference)

        // ── Optional: manga list CSS selector ──────────────────
        EditTextPreference(screen.context).apply {
            key = PREF_KEY_MANGA_SELECTOR
            title = "Manga list CSS selector"
            summary = preferences.getString(key, DEFAULT_MANGA_SELECTOR)
            setDefaultValue(DEFAULT_MANGA_SELECTOR)
            dialogTitle = "Manga list CSS selector"
            dialogMessage =
                "CSS selector that matches each manga card on the listing page.\n" +
                "Default: $DEFAULT_MANGA_SELECTOR"
            setOnPreferenceChangeListener { _, newValue ->
                summary = newValue as String
                true
            }
        }.also(screen::addPreference)

        // ── Optional: manga title attribute ────────────────────
        EditTextPreference(screen.context).apply {
            key = PREF_KEY_TITLE_ATTR
            title = "Title attribute / selector"
            summary = preferences.getString(key, DEFAULT_TITLE_ATTR)
            setDefaultValue(DEFAULT_TITLE_ATTR)
            dialogTitle = "Title attribute"
            dialogMessage =
                "Attribute on the manga element that holds the title.\n" +
                "Default: $DEFAULT_TITLE_ATTR"
            setOnPreferenceChangeListener { _, newValue ->
                summary = newValue as String
                true
            }
        }.also(screen::addPreference)

        // ── Optional: thumbnail attribute ──────────────────────
        EditTextPreference(screen.context).apply {
            key = PREF_KEY_THUMB_ATTR
            title = "Thumbnail img attribute"
            summary = preferences.getString(key, DEFAULT_THUMB_ATTR)
            setDefaultValue(DEFAULT_THUMB_ATTR)
            dialogTitle = "Thumbnail attribute"
            dialogMessage =
                "img attribute on each manga card that contains the cover URL.\n" +
                "Default: $DEFAULT_THUMB_ATTR"
            setOnPreferenceChangeListener { _, newValue ->
                summary = newValue as String
                true
            }
        }.also(screen::addPreference)
    }

    // ────────────────────────────────────────────────────────────
    // Helper: read selectors/attributes from prefs at call-time
    // ────────────────────────────────────────────────────────────

    private val mangaSelector: String
        get() = preferences.getString(PREF_KEY_MANGA_SELECTOR, DEFAULT_MANGA_SELECTOR)!!

    private val titleAttr: String
        get() = preferences.getString(PREF_KEY_TITLE_ATTR, DEFAULT_TITLE_ATTR)!!

    private val thumbAttr: String
        get() = preferences.getString(PREF_KEY_THUMB_ATTR, DEFAULT_THUMB_ATTR)!!

    // ────────────────────────────────────────────────────────────
    // Popular manga  (re-uses the home/listing page of the site)
    // ────────────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request =
        GET(if (page == 1) baseUrl else "$baseUrl/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val mangas = doc.select(mangaSelector).map { el ->
            SManga.create().apply {
                // Title: try the configured attribute first, then common fallbacks
                title = el.attr(titleAttr).ifBlank {
                    el.selectFirst("a[title]")?.attr("title")
                        ?: el.selectFirst("img[alt]")?.attr("alt")
                        ?: el.text()
                }.trim()

                // URL: look for the first anchor inside the card
                setUrlWithoutDomain(
                    el.selectFirst("a[href]")?.absUrl("href")
                        ?: el.absUrl("href"),
                )

                // Thumbnail
                thumbnail_url = el.selectFirst("img")?.run {
                    absUrl(thumbAttr).ifBlank { absUrl("src") }
                }
            }
        }
        // Very naive "hasNextPage": assume there is one unless the
        // page returned zero results.
        return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
    }

    // ────────────────────────────────────────────────────────────
    // Search  (appended as a query parameter — works for many sites)
    // ────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isBlank()) {
            if (page == 1) baseUrl else "$baseUrl/page/$page/"
        } else {
            "$baseUrl/?s=${query.trim().replace(" ", "+")}&page=$page"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        popularMangaParse(response)

    // ────────────────────────────────────────────────────────────
    // Latest  (disabled — set supportsLatest = true and implement
    //          latestUpdatesRequest/latestUpdatesParse if needed)
    // ────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException("Not used")

    override fun latestUpdatesParse(response: Response): MangasPage =
        throw UnsupportedOperationException("Not used")

    // ────────────────────────────────────────────────────────────
    // Manga details  (best-effort meta scrape from the detail page)
    // ────────────────────────────────────────────────────────────

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        return SManga.create().apply {
            title = doc.selectFirst("h1, h2, .entry-title, .post-title")
                ?.text()?.trim() ?: ""
            description = doc.selectFirst(
                ".description, .summary, .synopsis, .entry-content p",
            )?.text()?.trim()
            thumbnail_url = doc.selectFirst("img.cover, img.thumbnail, article img")
                ?.absUrl("src")
        }
    }

    // ────────────────────────────────────────────────────────────
    // Chapter list  (each anchor on the detail page becomes a chapter)
    // ────────────────────────────────────────────────────────────

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        // Try common chapter-list selectors; fall back to every link.
        val elements = doc.select(
            ".chapter-list a, .chapters a, .wp-manga-chapter a, ul.row-content-chapter a",
        ).ifEmpty {
            doc.select("a[href]")
        }
        return elements.mapIndexed { index, el ->
            SChapter.create().apply {
                name = el.text().trim().ifBlank { "Chapter ${elements.size - index}" }
                setUrlWithoutDomain(el.absUrl("href"))
                chapter_number = (elements.size - index).toFloat()
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // Page list  (every img on the chapter page becomes a page)
    // ────────────────────────────────────────────────────────────

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        return doc.select("img").mapIndexedNotNull { index, img ->
            val url = img.absUrl("src")
                .ifBlank { img.absUrl("data-src") }
                .ifBlank { img.absUrl("data-lazy-src") }
            if (url.isBlank()) null else Page(index, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")

    // ────────────────────────────────────────────────────────────
    // Constants
    // ────────────────────────────────────────────────────────────

    companion object {
        private const val PREF_KEY_BASE_URL = "base_url"
        private const val PREF_KEY_MANGA_SELECTOR = "manga_selector"
        private const val PREF_KEY_TITLE_ATTR = "title_attr"
        private const val PREF_KEY_THUMB_ATTR = "thumb_attr"

        private const val DEFAULT_BASE_URL = "https://example.com"
        private const val DEFAULT_MANGA_SELECTOR = ".manga-item, .post, article"
        private const val DEFAULT_TITLE_ATTR = "title"
        private const val DEFAULT_THUMB_ATTR = "src"
    }
}
