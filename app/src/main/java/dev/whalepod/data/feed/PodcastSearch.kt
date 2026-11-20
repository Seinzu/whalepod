package dev.whalepod.data.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class SearchResult(
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val artistName: String? = null,
    val feedUrl: String? = null,
    val artworkUrl600: String? = null,
    val artworkUrl100: String? = null,
) {
    val artworkUrl: String? get() = artworkUrl600 ?: artworkUrl100
}

@Serializable
private data class SearchResponse(val results: List<SearchResult> = emptyList())

/** Podcast search via the public Apple Podcasts (iTunes) Search API — no key required. */
class PodcastSearch(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(term: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("limit", "30")
            .addQueryParameter("term", term.trim())
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Search failed (HTTP ${response.code})")
            val body = response.body?.string().orEmpty()
            json.decodeFromString<SearchResponse>(body).results.filter { !it.feedUrl.isNullOrBlank() }
        }
    }
}
