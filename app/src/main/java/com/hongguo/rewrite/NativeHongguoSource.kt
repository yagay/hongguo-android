package com.hongguo.rewrite

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.DramaCategory
import com.hongguotv.core.DiscoveryCursor
import com.hongguotv.core.DiscoverySelection
import com.hongguotv.core.DiscoveryGroup
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
    private val websiteCovers = NativeWebsiteSource()
    private val discoveryTopics = mutableMapOf<String, List<FilterOption>>()
    private val nativeFilterGroups = mutableMapOf<String, List<FilterGroup>>()
    private val filterCursors = mutableMapOf<String, MutableMap<Int, DiscoveryCursor>>()

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

        private val filterKeys = mapOf(
            "theme" to "category_dim_theme",
            "setting" to "category_dim_role",
            "background" to "category_dim_epoch",
            "days" to "online_time",
            "status" to "creation_status",
        )

        /** Match the website's first-party landpage selector values. */
        fun selectionFor(
            genre: String, active: Map<String, String> = emptyMap(),
            latest: Boolean = false,
        ): DiscoverySelection {
            val values = linkedMapOf(
                "genre" to genre,
                "sort" to "online_time",
            )
            if (latest && genre != "short_play") values["online_time"] = "days_7"
            for ((key, value) in active) {
                if (value.isBlank()) continue
                val upstream = filterKeys[key] ?: key
                if (upstream in DiscoverySelection.labels) values[upstream] = value
            }
            return DiscoverySelection(values)
        }
    }

    suspend fun list(
        section: Section, offset: Int, genre: String, board: String,
        query: String, filters: Map<String, String>,
    ): CatalogPage = withContext(Dispatchers.IO) {
        val page = (offset / PAGE_SIZE + 1).coerceIn(1, 100)
        // Unlike a generic /category route, the landpage selector explicitly
        // sorts by online_time, so the "最新" tab does not silently show random
        // category items. Its continuation cursor is bound to the selection.
        if (section == Section.LATEST) {
            return@withContext filteredPage(genre, emptyMap(), page, latest = true)
        }
        if (section == Section.BROWSE) {
            try {
                return@withContext filteredPage(genre, filters, page, latest = false)
            } catch (problem: IOException) {
                // Website categories are a fallback only when no advanced
                // filters were requested. Never pretend to have applied them.
                if (filters.keys.any { it != "topic" }) throw problem
                diagnostics?.record("BROWSE", "WEBSITE_FALLBACK")
            }
        }
        val origin = when (section) {
            Section.RECOMMEND -> repository.home(page)
            Section.RANK -> repository.rankPage(boardFor(board), page)
            Section.BROWSE -> repository.discover(
                categoryFor(genre), filters["topic"].orEmpty(), page)
            Section.LATEST -> error("handled above")
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

    /** Keep cursor state for a single native category/selection.
     * A refresh starts a new session, never reuses an old offset token.
     */
    private fun filteredPage(
        genre: String, active: Map<String, String>, page: Int, latest: Boolean,
    ): CatalogPage {
        val selection = selectionFor(genre, active, latest)
        val key = (if (latest) "LATEST:" else "BROWSE:") +
            genre + ":" + selection.values.toSortedMap().toString()
        val cursor = if (page <= 1) null else synchronized(filterCursors) {
            filterCursors[key]?.get(page)
        } ?: if (page > 1) throw IOException("FILTER_CURSOR_EXPIRED") else null
        val result = repository.filteredDiscovery(selection, cursor)
        if (page == 1) {
            synchronized(filterCursors) {
                filterCursors[key] = mutableMapOf()
                while (filterCursors.size > 20) filterCursors.remove(filterCursors.keys.first())
            }
            if (!latest) synchronized(nativeFilterGroups) {
                nativeFilterGroups[genre] = mapGroups(result.groups)
            }
        }
        result.next?.let { next ->
            synchronized(filterCursors) { filterCursors[key]?.set(page + 1, next) }
        }
        return CatalogPage(result.items.map { it.toDrama() },
            result.next != null, page * PAGE_SIZE)
    }

    suspend fun filters(genre: String): List<FilterGroup> = withContext(Dispatchers.IO) {
        synchronized(nativeFilterGroups) { nativeFilterGroups[genre] }?.let {
            return@withContext it
        }
        // The native response provides all official categories, not merely
        // the single "topic" option exposed by the SSR website.
        val fromApi = runCatching {
            repository.filteredDiscovery(selectionFor(genre)).groups
        }.getOrNull()
        if (fromApi != null) {
            val mapped = mapGroups(fromApi)
            synchronized(nativeFilterGroups) { nativeFilterGroups[genre] = mapped }
            return@withContext mapped
        }
        val topics = synchronized(discoveryTopics) { discoveryTopics[genre] }
            ?: repository.discover(categoryFor(genre)).discovery?.topics
                ?.map { FilterOption(it.id, it.label) }.orEmpty()
        synchronized(discoveryTopics) { discoveryTopics[genre] = topics }
        listOf(FilterGroup("topic", "题材", listOf(FilterOption("", "全部")) + topics))
    }

    private fun mapGroups(groups: List<DiscoveryGroup>): List<FilterGroup> =
        groups.filter { it.type != "genre" }.map { group ->
            val key = when (group.type) {
                "category_dim_theme" -> "theme"
                "category_dim_role" -> "setting"
                "category_dim_epoch" -> "background"
                "online_time" -> "days"
                "creation_status" -> "status"
                else -> group.type
            }
            FilterGroup(key, group.label, listOf(FilterOption("", "全部")) +
                group.options.map { FilterOption(it.id, it.label) })
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

    fun coverUrl(image: String) = websiteCovers.coverUrl(image)

    fun resolveShare(message: String): Pair<String, Int> {
        // Accept a first-party permalink only. Short links and other domains
        // require a separate, explicitly verified resolver.
        val match = Regex(
            """https?://(?:www\.)?hongguoduanju\.com/(?:player|detail)/([0-9]{1,30})(?:/([0-9]{1,30}))?"""
        ).find(message) ?: throw IOException("SHARE_LINK_NOT_SUPPORTED")
        return match.groupValues[1] to
            (match.groupValues.getOrNull(2)?.toIntOrNull() ?: 1).coerceAtLeast(1)
    }

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
