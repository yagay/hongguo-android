package com.hongguo.rewrite

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.DramaCategory
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.Rendition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Offline-server-free adapter for the GPL native core from
 * tsukiyo-zero/hongguoTV; the Compose UI knows only app models.
 *
 * All blocking I/O stays on Dispatchers.IO. The core owns anonymous
 * registration and bounded GET/POST retries. No WebView or local HTTP server.
 */
class NativeHongguoSource(
    private val repository: ContentRepository = ContentRepository(),
    private val diagnostics: DiagnosticLog? = null,
) {
    private val public = NativeWebsiteSource()
    private val discoveryTopics = mutableMapOf<String, List<FilterOption>>()

    companion object {
        const val PAGE_SIZE = 24

        fun boardFor(value: String): RankBoard = when (value) {
            "real" -> RankBoard.REAL
            "comic" -> RankBoard.COMIC
            "ai" -> RankBoard.AI
            else -> RankBoard.ALL
        }

        fun categoryFor(genre: String): DramaCategory = when (genre) {
            "comic_series" -> DramaCategory.COMIC
            "ai_series" -> DramaCategory.AI
            else -> DramaCategory.REAL
        }
    }

    suspend fun list(
        section: Section, offset: Int, genre: String, board: String,
        query: String, filters: Map<String, String>,
    ): CatalogPage = withContext(Dispatchers.IO) {
        val page = (offset / PAGE_SIZE + 1).coerceIn(1, 100)
        val origin = when (section) {
            Section.RECOMMEND -> repository.home(page)
            Section.RANK -> repository.rankPage(boardFor(board), page)
            Section.LATEST, Section.BROWSE -> {
                val category = categoryFor(genre)
                val topic = if (section == Section.BROWSE) filters["topic"].orEmpty() else ""
                repository.discover(category, topic, page)
            }
            Section.SEARCH -> {
                if (query.isBlank()) return@withContext CatalogPage(emptyList(), false, 0)
                // The public cross-category endpoint does not need a registered device.
                repository.searchAll(query.trim().take(80), page)
            }
        }
        if (section == Section.BROWSE) {
            origin.discovery?.topics?.let { topics ->
                synchronized(discoveryTopics) {
                    discoveryTopics[genre] = topics.map { FilterOption(it.id, it.label) }
                }
            }
        }
        CatalogPage(
            items = origin.items.map { it.toDrama() },
            hasMore = origin.hasMore,
            nextOffset = page * PAGE_SIZE,
        )
    }

    suspend fun filters(genre: String): List<FilterGroup> = withContext(Dispatchers.IO) {
        val topics = synchronized(discoveryTopics) { discoveryTopics[genre] }
            ?: repository.discover(categoryFor(genre)).discovery?.topics
                ?.map { FilterOption(it.id, it.label) }.orEmpty()
        synchronized(discoveryTopics) { discoveryTopics[genre] = topics }
        listOf(FilterGroup("topic", "题材", listOf(FilterOption("", "全部")) + topics))
    }

    suspend fun detail(id: String): DramaDetail = withContext(Dispatchers.IO) {
        val detail = repository.detail(id)
        DramaDetail(detail.series.toDrama(), detail.episodes.mapIndexed { i, vid ->
            Episode(i + 1, vid)
        })
    }

    /**
     * Open the actual MP4 range stream, rather than fabricating a playback URL.
     * Decryption is performed by the GPL core, and Media3 reads via NativeVideoDataSource.
     *
     * Prefer an Android-compatible AVC rendition, falling back to HEVC only
     * when available; VVC/bytevc2 is never sent to the decoder.
     */
    suspend fun openVideo(vid: String): RemoteVideo = withContext(Dispatchers.IO) {
        diagnostics?.record("PLAYBACK", "REGISTER_AND_MODEL_START")
        val started = System.nanoTime()
        val ladder = try {
            repository.streamLadder(vid)
        } catch (error: Exception) {
            diagnostics?.record("PLAYBACK", "MODEL_FAIL",
                (System.nanoTime() - started) / 1_000_000)
            throw error
        }
        diagnostics?.record("PLAYBACK", "MODEL_READY",
            (System.nanoTime() - started) / 1_000_000)
        val eligible = ladder.renditions.filter(::canDecode)
        if (eligible.isEmpty()) {
            diagnostics?.record("PLAYBACK", "CODEC_UNSUPPORTED")
            throw IOException("CODEC_NOT_SUPPORTED")
        }
        val candidates = eligible.sortedWith(
            compareByDescending<Rendition> { it.quality <= 1080 }
                .thenByDescending { it.quality }
                .thenByDescending { codecName(it.codec) == "video/avc" }
        ).take(3)
        var last: IOException? = null
        for (candidate in candidates) {
            val video = RemoteVideo(repository.http, candidate.toStreamInfo())
            try {
                video.prepare(initialBytes = 64 * 1024)
                diagnostics?.record("PLAYBACK", "RANGE_PREPARED")
                return@withContext video
            } catch (error: IOException) {
                video.close()
                last = error
                diagnostics?.record("PLAYBACK", "RANGE_FAIL")
            }
        }
        throw last ?: IOException("MEDIA_PREPARATION_FAILED")
    }

    fun coverUrl(image: String) = public.coverUrl(image)

    private fun com.hongguotv.core.Series.toDrama(): Drama {
        val episodes = Regex("[0-9]+").find(badge)?.value?.toIntOrNull() ?: 0
        return Drama(id = id, title = title, cover = cover,
            total = episodes, synopsis = description)
    }

    private fun canDecode(row: Rendition): Boolean {
        val mime = codecName(row.codec) ?: return false
        return android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            .codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
            }
    }

    private fun codecName(codec: String): String? = when (codec.lowercase()) {
        "h264", "avc", "avc1", "avc3", "bytevc0" -> "video/avc"
        "h265", "hevc", "hvc1", "hev1", "bytevc1" -> "video/hevc"
        else -> null
    }
}
