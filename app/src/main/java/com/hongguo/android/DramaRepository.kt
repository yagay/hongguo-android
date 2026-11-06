package com.hongguo.android

import com.hongguo.account.protocol.RankBoard
import com.hongguo.account.protocol.RankPage
import com.hongguo.account.protocol.RelatedWork
import com.hongguo.account.protocol.WebsiteCatalogClient
import com.hongguo.account.protocol.RelatedWorksClient
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import com.hongguo.api.service.Client
import com.hongguo.api.service.Stream
import com.hongguo.api.service.ProgressiveMedia
import com.hongguo.api.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.CancellationException

/** UI uses the on-device Java core directly; no localhost service is involved. */
class DramaRepository {
    suspend fun discover(feed: HomeFeed, offset: Int, seenIds: List<String>): DramaPage =
        runInterruptible(Dispatchers.IO) {
            val start = offset.coerceAtLeast(0)
            val seen = DiscoveryMapper.seenTail(seenIds)
            when (feed) {
                HomeFeed.RECOMMEND -> {
                    val page = Client.browsePage("short_play", mapOf("sort" to "hot_score", "limit" to "18"), seen, start)
                    DiscoveryMapper.page(page.items, page.hasMore, page.nextSkip, start, seen)
                }
                HomeFeed.GUESS -> throw IllegalStateException("猜你喜欢由账号推荐流分页加载")
                HomeFeed.LATEST -> {
                    val page = Client.latestPage("short_play", false, DiscoveryMapper.PAGE_SIZE, start == 0, seen, start)
                    DiscoveryMapper.page(page.items, page.hasMore, page.nextSkip, start, seen)
                }
                HomeFeed.COMIC -> {
                    val page = Client.rankPage("recommend", DiscoveryMapper.PAGE_SIZE, start)
                    DiscoveryMapper.page(page.items, page.hasMore, page.nextOffset, start, seen)
                }
            }
        }

    suspend fun filters(genre: String): List<FilterGroup> = runInterruptible(Dispatchers.IO) {
        DiscoveryMapper.filterGroups(Client.filters(genre))
    }

    suspend fun browse(selection: BrowseSelection, offset: Int, seenIds: List<String>): DramaPage =
        runInterruptible(Dispatchers.IO) {
            val start = offset.coerceAtLeast(0)
            val seen = DiscoveryMapper.seenTail(seenIds)
            val page = Client.browsePage(selection.genre, DiscoveryMapper.options(selection), seen, start)
            DiscoveryMapper.page(page.items, page.hasMore, page.nextSkip, start, seen)
        }

    suspend fun search(query: String, offset: Int): DramaPage = runInterruptible(Dispatchers.IO) {
        val page = Client.searchPage(query, 20, offset)
        val cards = page.items.mapNotNull { raw ->
            val item = Json.optObj(raw) ?: return@mapNotNull null
            val id = Json.optStr(item["series_id"], "")
            if (id.isBlank()) return@mapNotNull null
            DramaCard(
                id = id,
                title = Json.optStr(item["title"], "未命名短剧"),
                cover = Json.optStr(item["cover"], ""),
                episodeCount = Json.optInt(item["episode_cnt"], 0),
                summary = Json.optStr(item["abstract"], ""),
            )
        }
        DramaPage(cards, page.hasMore, page.nextSkip)
    }

    private val publicHttp by lazy { OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build() }
    private val relatedClient by lazy { RelatedWorksClient(publicHttp) }
    private val websiteClient by lazy { WebsiteCatalogClient(publicHttp) }

    suspend fun rank(board: RankBoard, page: Int): RankPage = runInterruptible(Dispatchers.IO) { websiteClient.rank(board, page) }

    suspend fun suggestions(query: String): List<String> = runInterruptible(Dispatchers.IO) { websiteClient.suggestions(query) }

    suspend fun related(id: String): List<RelatedWork> = runInterruptible(Dispatchers.IO) { relatedClient.related(id) }

    suspend fun details(card: DramaCard): DramaDetail = runInterruptible(Dispatchers.IO) {
        val result = Client.getEpisodes(card.id)
        val meta = Json.optObj(result["meta"]) ?: emptyMap<String, Any>()
        val episodes = Json.optArr(result["episodes"]).mapNotNull { raw ->
            val item = Json.optObj(raw) ?: return@mapNotNull null
            val id = Json.optStr(item["vid"], "")
            if (id.isBlank()) return@mapNotNull null
            Episode(id, Json.optInt(item["index"], 0), Json.optStr(item["title"], ""))
        }.sortedBy { it.index }
        if (episodes.isEmpty()) throw IOException("EMPTY_EPISODES")
        DramaDetail(
            id = card.id,
            title = Json.optStr(meta["title"], card.title),
            cover = Json.optStr(meta["cover"], card.cover),
            summary = Json.optStr(meta["abstract"], card.summary),
            episodes = episodes,
        )
    }

    suspend fun prepare(episode: Episode, allowFallback: Boolean = true,
                        progress: (String, Long, Long) -> Unit): PreparedVideo {
        val context = currentCoroutineContext()
        var allocated: ProgressiveMedia.Session? = null
        var handedOff = false
        try {
            val result = runInterruptible(Dispatchers.IO) {
                Stream.cachedPath(episode.id, "")?.let { return@runInterruptible PreparedVideo(episode.id, it) }
                var opening = true
                val listener = object : Stream.Progress {
                    override fun update(stage: String, done: Long, total: Long) { progress(stage, done, total) }
                    override fun isCancelled(): Boolean = Thread.currentThread().isInterrupted || (opening && !context.isActive)
                }
                try {
                    allocated = ProgressiveMedia.open(episode.id, "", listener)
                    opening = false
                    PreparedVideo(episode.id, "", session = allocated)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (unsupported: ProgressiveMedia.UnsupportedException) {
                    if (!allowFallback) throw unsupported
                    progress("compatibility", 0, -1)
                    val path = Stream.prepare(episode.id, "", object : Stream.Progress {
                        override fun update(stage: String, done: Long, total: Long) { if (context.isActive) progress(stage, done, total) }
                        override fun isCancelled(): Boolean = !context.isActive || Thread.currentThread().isInterrupted
                    })
                    PreparedVideo(episode.id, path)
                }
            }
            handedOff = true
            return result
        } finally {
            if (!handedOff) allocated?.close()
        }
    }

    /** Warm only a bounded prefix; no player/decoder and no whole-episode speculative download. */
    suspend fun warmNext(video: PreparedVideo, onReader: (ProgressiveMedia.Reader?) -> Unit = {}) {
        val session = video.session ?: return
        runInterruptible(Dispatchers.IO) {
            session.newReader().use { reader ->
                onReader(reader)
                try {
                    val buffer = ByteArray(64 * 1024)
                    val limit = minOf(session.length(), 512L * 1024)
                    var position = 0L
                    while (position < limit && !Thread.currentThread().isInterrupted) {
                        val count = reader.read(position, buffer, 0, minOf(buffer.size.toLong(), limit - position).toInt())
                        if (count <= 0) break
                        position += count
                    }
                } finally { onReader(null) }
            }
        }
    }
}

