package eu.kanade.tachiyomi.extension.ko.ntktoki

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class NTKBase(
    override val name: String,
    protected val contentKind: String,
) : HttpSource(), ConfigurableSource {

    private val json = Json { ignoreUnknownKeys = true }

    protected val apiHeaders
        get() = headers.newBuilder()
            .set("Accept", "application/json")
            .build()

    override val lang = "ko"
    override val supportsLatest = true
    protected val preferences by getPreferencesLazy()

    protected val rootUrl: String
        get() {
            val stored = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT).orEmpty()
            val domainNumber = stored.trim().trimStart('0').ifEmpty { PREF_DOMAIN_DEFAULT }
            if (domainNumber != stored) {
                preferences.edit().putString(PREF_DOMAIN_KEY, domainNumber).apply()
            }
            return "https://toki$domainNumber.com"
        }

    protected open val webViewPath: String get() = contentKind
    override val baseUrl: String get() = rootUrl

    private fun resolveSiteUrl(rawUrl: String): String {
        val absoluteUrl = rawUrl.toHttpUrlOrNull()
        if (absoluteUrl != null && !KNOWN_RABBIT_HOST_REGEX.matches(absoluteUrl.host)) {
            return absoluteUrl.toString()
        }

        val relativeUrl = if (absoluteUrl != null) {
            buildString {
                append(absoluteUrl.encodedPath)
                absoluteUrl.encodedQuery?.let { append('?').append(it) }
                absoluteUrl.encodedFragment?.let { append('#').append(it) }
            }
        } else {
            rawUrl
        }

        return rootUrl.toHttpUrl().resolve(relativeUrl)?.toString()
            ?: throw IllegalArgumentException("Invalid Toki URL: $rawUrl")
    }

    private fun isSiteHost(host: String): Boolean = SITE_HOST_REGEX.matches(host)

    private fun updateDomainFromUrl(url: String) {
        val host = url.toHttpUrlOrNull()?.host ?: return
        val newDomainNumber = SITE_HOST_REGEX.matchEntire(host)?.groupValues?.get(1) ?: return
        if (newDomainNumber != preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)) {
            preferences.edit().putString(PREF_DOMAIN_KEY, newDomainNumber).apply()
        }
    }

    override fun getMangaUrl(manga: SManga): String = resolveSiteUrl(manga.url)
    override fun getChapterUrl(chapter: SChapter): String = resolveSiteUrl(chapter.url)

    override fun mangaDetailsRequest(manga: SManga) = GET(resolveSiteUrl(manga.url), headers)
    override fun chapterListRequest(manga: SManga) = GET(resolveSiteUrl(manga.url), headers)

    override fun pageListRequest(chapter: SChapter) = GET(
        url = resolveSiteUrl(chapter.url),
        headers = headers.newBuilder().add(WEBVIEW_HEADER, "true").build(),
    )

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private val webViewInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.header(WEBVIEW_HEADER) == null) return@Interceptor chain.proceed(request)

        val cookie = CookieManager.getInstance().getCookie(rootUrl).orEmpty()
        if (!cookie.contains("cf_clearance=")) {
            runCatching {
                network.cloudflareClient.newCall(GET(rootUrl, headers)).execute().close()
            }
        }

        var finalPayload: String? = null
        val chapterUrl = request.url.toString()

        for (attempt in 1..2) {
            if (finalPayload != null) break

            val latch = CountDownLatch(1)
            val handler = Handler(Looper.getMainLooper())
            var webViewRef: WebView? = null

            handler.post {
                val context = Injekt.get<Application>()
                val webView = WebView(context)
                webViewRef = webView
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, 360, 640)
                webView.settings.userAgentString = request.header("User-Agent")
                    ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                webView.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        @Suppress("unused")
                        @Synchronized
                        fun exfiltrate(payload: String) {
                            if (finalPayload == null) {
                                finalPayload = payload
                                latch.countDown()
                            }
                        }
                    },
                    "TrojanTunnel",
                )

                val captureScript = IMAGE_CAPTURE_SCRIPT
                var preloadDone = attempt > 1

                fun inject(view: WebView) {
                    view.evaluateJavascript(captureScript, null)
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        updateDomainFromUrl(url)
                        if (preloadDone) inject(view)
                        super.onPageStarted(view, url, favicon)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        updateDomainFromUrl(url)
                        if (!preloadDone) {
                            preloadDone = true
                            view.postDelayed(
                                { if (finalPayload == null) view.loadUrl(chapterUrl) },
                                3_000L,
                            )
                        } else {
                            inject(view)
                        }
                        super.onPageFinished(view, url)
                    }
                }

                if (attempt == 1) webView.loadUrl(rootUrl) else webView.loadUrl(chapterUrl)
            }

            latch.await(20, TimeUnit.SECONDS)
            handler.post {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
        }

        finalPayload?.let { payload ->
            val mediaType = if (payload.trim().startsWith("{")) "application/json" else "text/html"
            return@Interceptor Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(payload.toResponseBody(mediaType.toMediaType()))
                .build()
        }

        throw Exception("WebView timed out loading $chapterUrl")
    }

    private val headerCleanerInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
        if (original.header("Accept") == null) {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        }
        chain.proceed(builder.build())
    }

    private val domainUpdateInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        updateDomainFromUrl(response.request.url.toString())
        response
    }

    private val imageRefererInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (isSiteHost(request.url.host)) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header("Referer", "$rootUrl/").build())
        }
    }

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .addInterceptor(headerCleanerInterceptor)
            .addInterceptor(domainUpdateInterceptor)
            .addInterceptor(imageRefererInterceptor)
            .addInterceptor(webViewInterceptor)
            .build()
    }

    @Serializable
    private data class WorksResponse(
        val works: List<Work> = emptyList(),
        val hasMore: Boolean = false,
    )

    @Serializable
    private data class Work(
        val sourceWorkId: String,
        val title: String? = null,
        val workTitle: String? = null,
        val thumbnailUrl: String? = null,
        val coverUrl: String? = null,
        val imageUrl: String? = null,
        val thumbnail: String? = null,
        val genre: String? = null,
        val author: String? = null,
    )

    @Serializable
    private data class PageImagesResponse(
        val images: List<PageImage> = emptyList(),
    )

    @Serializable
    private data class PageImage(
        val src: String = "",
        val srcCandidates: List<String> = emptyList(),
    )

    protected fun htmlSearchParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val legacy = document.select("#webtoon-list-all > li:has(a[href^=\"/$contentKind/\"])")
        val cards = if (legacy.isNotEmpty()) legacy else document.select("div.search-results-grid > a.card[href^=\"/$contentKind/\"]")

        val mangas = cards.mapNotNull { element ->
            val link = if (element.tagName() == "a") element else element.selectFirst("a[href^=\"/$contentKind/\"]")
            link?.let {
                SManga.create().apply {
                    setUrlWithoutDomain(it.absUrl("href").ifBlank { it.attr("href") })
                    title = element.select("span.title").text().ifBlank { element.select("p.subject").text() }
                    thumbnail_url = element.select("img.theme-thumb-img").attr("abs:src")
                        .ifBlank { element.select("img.search-thumb-img").attr("abs:src") }
                }
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<WorksResponse>()
        val mangas = data.works.map { work ->
            SManga.create().apply {
                url = "/$contentKind/${work.sourceWorkId}"
                title = work.workTitle ?: work.title ?: ""
                thumbnail_url = work.thumbnailUrl ?: work.coverUrl ?: work.imageUrl ?: work.thumbnail
                genre = work.genre
                author = work.author
            }
        }
        return MangasPage(mangas, data.hasMore)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val rscData = document.select("script").map { it.data() }.firstOrNull { "allCards" in it }
            ?: return htmlDocumentCards(document)

        val unescaped = rscData
            .substringAfter("[1,\"")
            .substringBeforeLast("\"])")
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\/", "/")

        val marker = "\"allCards\":"
        val arrayStart = unescaped.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length)
            ?: return htmlDocumentCards(document)

        var depth = 0
        var arrayEnd = -1
        for (i in arrayStart until unescaped.length) {
            when (unescaped[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        arrayEnd = i + 1
                        break
                    }
                }
            }
        }
        if (arrayEnd <= arrayStart) return htmlDocumentCards(document)

        val cards = runCatching {
            json.decodeFromString<List<Work>>(unescaped.substring(arrayStart, arrayEnd))
        }.getOrElse { return htmlDocumentCards(document) }

        val seen = mutableSetOf<String>()
        val mangas = cards.mapNotNull { card ->
            if (!seen.add(card.sourceWorkId)) return@mapNotNull null
            SManga.create().apply {
                url = "/$contentKind/${card.sourceWorkId}"
                title = card.workTitle ?: card.title ?: ""
                thumbnail_url = card.thumbnailUrl ?: card.coverUrl ?: card.imageUrl ?: card.thumbnail
                genre = card.genre
                author = card.author
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    private fun htmlDocumentCards(document: Document): MangasPage {
        val cards = document.select("#webtoon-list-all > li:has(a[href^=\"/$contentKind/\"]), div.card-grid > a.card[href^=\"/$contentKind/\"]")
        val mangas = cards.mapNotNull { element ->
            val link = if (element.tagName() == "a") element else element.selectFirst("a[href^=\"/$contentKind/\"]")
            link?.let {
                SManga.create().apply {
                    setUrlWithoutDomain(it.absUrl("href").ifBlank { it.attr("href") })
                    title = element.select("span.title").text().ifBlank { element.select("p.subject").text() }
                    thumbnail_url = element.select("img.theme-thumb-img").attr("abs:src")
                        .ifBlank { element.select("div.thumb img:not(.platform-icon)").attr("abs:src") }
                }
            }
        }
        return MangasPage(mangas, false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return if (response.header("Content-Type").orEmpty().contains("application/json")) {
            popularMangaParse(response)
        } else {
            htmlSearchParse(response)
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val legacyTitle = document.select(".theme-detail-title-line").text()
        return SManga.create().apply {
            if (legacyTitle.isNotBlank()) {
                title = legacyTitle
                author = document.select(".theme-detail-info-row:first-child .theme-detail-info-value").text()
                description = document.select(".theme-detail-description").text()
                thumbnail_url = document.select(".col-sm-4 img").attr("abs:src")
                val statusText = document.select(".theme-detail-info-row:nth-child(3) .theme-detail-info-value").text()
                status = statusFrom(statusText)
                genre = document.select(".theme-detail-info-row:nth-child(2) .theme-detail-info-value")
                    .joinToString(", ") { it.text().replace("#", "").trim() }
            } else {
                title = document.select("h1.hero-v2-title").text()
                author = document.select("div.hero-v2-author a").text()
                description = document.select("p.hero-v2-desc").text()
                thumbnail_url = document.select("div.hero-v2-thumb img").attr("abs:src")
                status = statusFrom(document.select("span.pill-status").text())
                genre = document.select("a.hero-v2-tag").joinToString(", ") {
                    it.text().replace("#", "").trim()
                }
            }
        }
    }

    private fun statusFrom(text: String): Int = when {
        text.contains("연재중") -> SManga.ONGOING
        text.contains("완결") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private val legacyDateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
    private val modernDateFormat = SimpleDateFormat("yy.MM.dd", Locale.KOREA)

    override fun chapterListParse(response: Response): List<SChapter> {
        val elements = collectChapterElements(response)
        val seen = mutableSetOf<String>()

        return elements.mapNotNull { element ->
            val legacyLink = element.selectFirst("a.item-subject")
            val modernLink = element.selectFirst("a.ep-row-v2-link")
            val link = legacyLink ?: modernLink ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (!seen.add(href)) return@mapNotNull null

            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = if (legacyLink != null) {
                    legacyLink.ownText().ifBlank { legacyLink.text() }
                } else {
                    element.select("div.ep-row-v2-title strong").text()
                }
                val dateText = element.select("div.wr-date").text()
                    .ifBlank { element.select("span.ep-row-v2-date").text() }
                date_upload = legacyDateFormat.tryParse(dateText).takeIf { it > 0 }
                    ?: modernDateFormat.tryParse(dateText)
                scanlator = if (element.selectFirst(".theme-paid-episode, span.ep-price-badge") != null) "🔒" else null
            }
        }
    }

    private fun collectChapterElements(response: Response): Elements {
        val initialUrl = response.request.url.toString()
        val initialPath = response.request.url.encodedPath
        val initialDocument = response.asJsoup()
        val documents = mutableMapOf(initialUrl to initialDocument)
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        val collected = Elements()
        queue.add(initialUrl)

        while (queue.isNotEmpty() && visited.size < MAX_CHAPTER_PAGES) {
            val currentUrl = queue.removeFirst()
            if (!visited.add(currentUrl)) continue

            val document = documents.remove(currentUrl) ?: runCatching {
                client.newCall(GET(currentUrl, headers)).execute().use { it.asJsoup() }
            }.getOrNull() ?: continue

            collected.addAll(document.select("div.serial-list ul.list-body > li.list-item, li.ep-row-v2"))

            val unescaped = document.select("script").joinToString("\n") { it.data() }
                .replace("\\u003c", "<", ignoreCase = true)
                .replace("\\u003e", ">", ignoreCase = true)
                .replace("\\u0026", "&", ignoreCase = true)
                .replace("\\\"", "\"")
            if ("ep-row-v2" in unescaped) {
                collected.addAll(Jsoup.parse(unescaped, currentUrl).select("li.ep-row-v2"))
            }

            document.select("a[href]").forEach { anchor ->
                enqueueChapterPage(anchor.absUrl("href"), initialPath, currentUrl, queue, visited)
            }
            EPAGE_REGEX.findAll(unescaped).forEach { match ->
                enqueueChapterPage(match.groupValues[1], initialPath, currentUrl, queue, visited)
            }
        }
        return collected
    }

    private fun enqueueChapterPage(
        rawUrl: String,
        initialPath: String,
        currentUrl: String,
        queue: ArrayDeque<String>,
        visited: Set<String>,
    ) {
        val url = currentUrl.toHttpUrlOrNull()?.resolve(rawUrl.replace("\\/", "/"))?.toString() ?: return
        if (initialPath in url && CHAPTER_PAGE_REGEX.containsMatchIn(url) && url !in visited && url !in queue) {
            queue.add(url)
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val payload = response.body.string()
        if ("ad_ack_required" in payload) {
            throw Exception("광고 확인이 필요합니다. 웹뷰에서 사이트를 한 번 연 뒤 다시 시도하세요.")
        }
        if ("fingerprint_required" in payload) {
            throw Exception("브라우저 인증이 필요합니다. 웹뷰에서 사이트를 한 번 연 뒤 다시 시도하세요.")
        }

        val data = json.decodeFromString<PageImagesResponse>(payload)
        if (data.images.isEmpty()) throw Exception("이미지 목록을 불러오지 못했습니다. 다시 시도하세요.")

        return data.images.mapIndexed { index, image ->
            val imageUrl = image.src.ifBlank { image.srcCandidates.lastOrNull().orEmpty() }
            if (imageUrl.isBlank()) throw Exception("$index 페이지의 이미지 주소가 비어 있습니다.")
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "도메인 번호 (toki#.com)"
            summary = "현재 도메인 번호: ${preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)}\n숫자만 입력하세요 (예: 1, 2, 300)"
            setDefaultValue(PREF_DOMAIN_DEFAULT)
        }.also(screen::addPreference)
    }

    companion object {
        private const val WEBVIEW_HEADER = "X-WebView-Intercept"
        private const val PREF_DOMAIN_KEY = "pref_toki_domain_key"
        private const val PREF_DOMAIN_DEFAULT = "31"
        private val SITE_HOST_REGEX = Regex("""toki(\d+)\.com""")
        private val KNOWN_RABBIT_HOST_REGEX = Regex("""(?:newtoki\d+\.org|toki\d+\.com|sbxh\d+\.com)""")
        private val CHAPTER_PAGE_REGEX = Regex("""[?&](?:page|epage)=\d+""")
        private val EPAGE_REGEX = Regex("\"href\":\"([^\"]*[?&]epage=\\d+[^\"]*)\"")
        private const val MAX_CHAPTER_PAGES = 200
        const val PAGE_SIZE = 49

        private val IMAGE_CAPTURE_SCRIPT = """
            (function() {
              function sendAck() { try {
                var scope = window.location.pathname;
                window.__ntk_ad_ack_scope = scope;
                window.dispatchEvent(new CustomEvent('ntk-ad-ack-ready', { detail: { scope: scope } }));
              } catch (_) {} }
              sendAck(); setTimeout(sendAck, 500); setTimeout(sendAck, 1500);
              if (window.__rabbitCaptureInstalled) return;
              window.__rabbitCaptureInstalled = true;
              var done = false, tick = 0, stable = 0, lastKey = '', apiPayload = null;
              function finish(payload) {
                if (done) return;
                done = true;
                try { window.TrojanTunnel.exfiltrate(JSON.stringify(payload)); } catch (_) {}
              }
              function normalizeApi(data) {
                if (!data || !Array.isArray(data.images)) return data;
                return {
                  ok: data.ok !== false,
                  images: data.images.map(function(item, index) {
                    var candidates = Array.isArray(item.srcCandidates) ? item.srcCandidates.filter(Boolean) : [];
                    var src = candidates.length ? candidates[candidates.length - 1] : item.src;
                    return { src: src || item.src || '', page: item.page || index + 1 };
                  }).filter(function(item) { return !!item.src; })
                };
              }
              var originalFetch = window.fetch;
              if (originalFetch) {
                window.fetch = async function() {
                  var response = await originalFetch.apply(this, arguments);
                  var arg = arguments[0];
                  var requestUrl = arg && arg.url ? arg.url : arg;
                  if (requestUrl && /\/api\/(?:manga|manhwa|webtoon)-images/.test(String(requestUrl))) {
                    response.clone().json().then(function(data) {
                      var adRequired = data && (data.ad_ack_required || data.error === 'ad_ack_required');
                      if (adRequired) {
                        var reloadKey = '__rabbit_ack_reload:' + window.location.pathname;
                        var reloads = parseInt(sessionStorage.getItem(reloadKey) || '0', 10);
                        if (reloads < 1) {
                          sessionStorage.setItem(reloadKey, String(reloads + 1));
                          sendAck(); setTimeout(function() { window.location.reload(); }, 1200);
                        } else finish(data);
                      } else if (data && (data.fingerprint_required || data.error === 'fingerprint_required')) {
                        finish(data);
                      } else {
                        try { sessionStorage.removeItem('__rabbit_ack_reload:' + window.location.pathname); } catch (_) {}
                        apiPayload = normalizeApi(data);
                      }
                    }).catch(function() {});
                  }
                  return response;
                };
              }
              var timer = setInterval(function() {
                try {
                  var legacy = Array.prototype.slice.call(document.querySelectorAll('[data-theme-viewer-images] .theme-viewer-image img[src]'));
                  var modern = Array.prototype.slice.call(document.querySelectorAll('.vw-imgs img.viewer-ratio-img[src]'));
                  var nodes = modern.length ? modern : legacy;
                  var expectedNode = document.querySelector('.vw-imgs[data-viewer-image-count]');
                  var expected = expectedNode ? parseInt(expectedNode.getAttribute('data-viewer-image-count') || '0', 10) : 0;
                  var images = nodes.map(function(img, index) {
                    var src = img.currentSrc || img.src || '';
                    var altMatch = String(img.alt || '').match(/\d+/);
                    return { src: src, page: altMatch ? parseInt(altMatch[0], 10) : index + 1 };
                  }).filter(function(item) {
                    return item.src && item.src.indexOf('data:') !== 0 && item.src.indexOf('blob:') !== 0;
                  });
                  if (images.length && (!expected || images.length >= expected)) {
                    var key = images.map(function(item) { return item.src; }).join('|');
                    if (key === lastKey) stable++; else { lastKey = key; stable = 0; }
                    if (stable >= 5) { clearInterval(timer); finish({ ok: true, images: images }); return; }
                  }
                  tick++;
                  if (tick > 190 && apiPayload && apiPayload.images && apiPayload.images.length) {
                    clearInterval(timer); finish(apiPayload); return;
                  }
                  if (tick > 200) clearInterval(timer);
                } catch (_) {}
              }, 100);
            })();
        """.trimIndent()
    }
}
