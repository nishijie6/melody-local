package com.melody.local.lyrics.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.max

data class LyricsHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    fun firstHeader(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
}

fun interface LyricsHttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): LyricsHttpResponse
}

class UrlConnectionLyricsHttpTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : LyricsHttpTransport {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): LyricsHttpResponse = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            headers.forEach(::setRequestProperty)
        }
        try {
            val statusCode = connection.responseCode
            val input = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = input?.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IOException("歌词服务响应过大")
                    output.write(buffer, 0, count)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }.orEmpty()
            LyricsHttpResponse(
                statusCode = statusCode,
                body = body,
                headers = connection.headerFields.entries.mapNotNull { (name, values) ->
                    name?.let { it to values.orEmpty() }
                }.toMap(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}

/**
 * Read-only LRCLIB client. LRCLIB requires an identifying User-Agent, sequential requests and a
 * short delay between requests. A 429 response extends the delay for the next request according to
 * Retry-After; it is surfaced to the UI instead of being retried in a hidden loop.
 */
class LrclibLyricsSource(
    private val transport: LyricsHttpTransport = UrlConnectionLyricsHttpTransport(),
    private val baseUrl: String = "https://lrclib.net/api",
    private val clientIdentifier: String =
        "Yinlan/1.4.0 (https://github.com/nishijie6/melody-local)",
    private val minimumRequestIntervalMs: Long = 300L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val waitMs: suspend (Long) -> Unit = { delay(it) },
) : OnlineLyricsSource {
    private val requestMutex = Mutex()
    private var lastRequestAtMs: Long? = null
    private var blockedUntilMs: Long = 0L

    override suspend fun search(
        request: LyricsSearchRequest,
    ): RemoteLyricsResult<List<RankedOnlineLyrics>> {
        val query = request.keywords?.trim()?.takeIf(String::isNotEmpty)?.let { keywords ->
            listOf("q" to keywords)
        } ?: buildList {
            add("track_name" to request.track.title)
            request.track.artist.takeUnless(::isUnknownMetadata)?.let { add("artist_name" to it) }
            request.track.album.takeUnless(::isUnknownMetadata)?.let { add("album_name" to it) }
        }
        val response = execute("${normalizedBaseUrl()}/search?${encodeQuery(query)}")
            ?: return RemoteLyricsResult.NetworkFailure("无法连接歌词服务")
        return response.toSearchResult(request.track)
    }

    override suspend fun download(recordId: Long): RemoteLyricsResult<LrclibLyricsRecord> {
        require(recordId > 0L) { "LRCLIB record ID must be positive" }
        val response = execute("${normalizedBaseUrl()}/get/$recordId")
            ?: return RemoteLyricsResult.NetworkFailure("无法连接歌词服务")
        return response.toDownloadResult()
    }

    private suspend fun execute(url: String): LyricsHttpResponse? = requestMutex.withLock {
        val now = nowMs()
        val sinceLastRequest = lastRequestAtMs?.let { now - it } ?: Long.MAX_VALUE
        val normalWait = (minimumRequestIntervalMs - sinceLastRequest).coerceAtLeast(0L)
        val rateLimitWait = (blockedUntilMs - now).coerceAtLeast(0L)
        val requiredWait = max(normalWait, rateLimitWait)
        if (requiredWait > 0L) waitMs(requiredWait)

        try {
            transport.get(
                url = url,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to clientIdentifier,
                ),
            ).also { response ->
                val completedAt = nowMs()
                lastRequestAtMs = completedAt
                if (response.statusCode == HTTP_TOO_MANY_REQUESTS) {
                    val retrySeconds = response.retryAfterSeconds()
                    blockedUntilMs = completedAt + retrySeconds * 1_000L
                }
            }
        } catch (_: IOException) {
            lastRequestAtMs = nowMs()
            null
        } catch (_: SecurityException) {
            lastRequestAtMs = nowMs()
            null
        }
    }

    private fun LyricsHttpResponse.toSearchResult(
        track: LyricsTrack,
    ): RemoteLyricsResult<List<RankedOnlineLyrics>> = when (statusCode) {
        HttpURLConnection.HTTP_OK -> runCatching {
            parseRecordList(body)
        }.fold(
            onSuccess = { records ->
                if (records.isEmpty()) {
                    RemoteLyricsResult.NoResults
                } else {
                    RemoteLyricsResult.Success(LyricsMatchScorer.rankOnline(track, records))
                }
            },
            onFailure = { error ->
                RemoteLyricsResult.ServiceFailure(
                    statusCode = statusCode,
                    message = "歌词服务返回了无法解析的数据：${error.message.orEmpty()}",
                )
            },
        )
        HttpURLConnection.HTTP_NOT_FOUND -> RemoteLyricsResult.NoResults
        HTTP_TOO_MANY_REQUESTS -> RemoteLyricsResult.RateLimited(retryAfterSeconds())
        else -> RemoteLyricsResult.ServiceFailure(statusCode, serviceErrorMessage())
    }

    private fun LyricsHttpResponse.toDownloadResult(): RemoteLyricsResult<LrclibLyricsRecord> =
        when (statusCode) {
            HttpURLConnection.HTTP_OK -> runCatching { parseRecord(body) }.fold(
                onSuccess = { RemoteLyricsResult.Success(it) },
                onFailure = { error ->
                    RemoteLyricsResult.ServiceFailure(
                        statusCode = statusCode,
                        message = "歌词服务返回了无法解析的数据：${error.message.orEmpty()}",
                    )
                },
            )
            HttpURLConnection.HTTP_NOT_FOUND -> RemoteLyricsResult.NoResults
            HTTP_TOO_MANY_REQUESTS -> RemoteLyricsResult.RateLimited(retryAfterSeconds())
            else -> RemoteLyricsResult.ServiceFailure(statusCode, serviceErrorMessage())
        }

    private fun LyricsHttpResponse.retryAfterSeconds(): Long =
        firstHeader("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(1L, 3_600L) ?: 1L

    private fun LyricsHttpResponse.serviceErrorMessage(): String = runCatching {
        val root = SimpleJson.parse(body).asObject()
        root.string("message") ?: "歌词服务请求失败（HTTP $statusCode）"
    }.getOrDefault("歌词服务请求失败（HTTP $statusCode）")

    private fun parseRecordList(json: String): List<LrclibLyricsRecord> =
        SimpleJson.parse(json).asArray().map { parseRecordObject(it.asObject()) }

    private fun parseRecord(json: String): LrclibLyricsRecord =
        parseRecordObject(SimpleJson.parse(json).asObject())

    private fun parseRecordObject(root: Map<String, Any?>): LrclibLyricsRecord = LrclibLyricsRecord(
        id = requireNotNull(root.number("id")) { "缺少 id" }.toLong(),
        trackName = root.string("trackName") ?: root.string("name").orEmpty(),
        artistName = root.string("artistName").orEmpty(),
        albumName = root.string("albumName").orEmpty(),
        durationSeconds = root.number("duration")?.toDouble() ?: 0.0,
        instrumental = root.boolean("instrumental") ?: false,
        plainLyrics = root.string("plainLyrics"),
        syncedLyrics = root.string("syncedLyrics"),
    )

    private fun normalizedBaseUrl() = baseUrl.trimEnd('/')

    private fun encodeQuery(parameters: List<Pair<String, String>>): String = parameters.joinToString("&") {
        "${urlEncode(it.first)}=${urlEncode(it.second)}"
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun isUnknownMetadata(value: String): Boolean {
        val comparable = LyricsMatchScorer.comparable(value)
        return comparable.isBlank() || comparable in UNKNOWN_METADATA
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        val UNKNOWN_METADATA = setOf(
            LyricsMatchScorer.comparable("未知歌手"),
            LyricsMatchScorer.comparable("未知专辑"),
            LyricsMatchScorer.comparable("unknown artist"),
            LyricsMatchScorer.comparable("unknown album"),
            LyricsMatchScorer.comparable("<unknown>"),
        )
    }
}

private fun Map<String, Any?>.string(key: String): String? = this[key] as? String
private fun Map<String, Any?>.number(key: String): Number? = this[key] as? Number
private fun Map<String, Any?>.boolean(key: String): Boolean? = this[key] as? Boolean

@Suppress("UNCHECKED_CAST")
private fun Any?.asObject(): Map<String, Any?> = this as? Map<String, Any?>
    ?: throw IllegalArgumentException("应为 JSON 对象")

@Suppress("UNCHECKED_CAST")
private fun Any?.asArray(): List<Any?> = this as? List<Any?>
    ?: throw IllegalArgumentException("应为 JSON 数组")
