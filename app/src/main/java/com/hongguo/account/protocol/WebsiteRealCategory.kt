// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.account.protocol

import org.json.JSONObject

/**
 * Public website /category/real-drama[/topic][?page=N].
 * Adapted from tsukiyo-zero/hongguoTV's GPL-3.0-only DramaDiscovery.kt.
 * Rejects unconfirmed routes and page numbers rather than silently substituting
 * the unfiltered catalogue or another genre.
 */
data class RealCategoryTopic(val id: String, val label: String)
data class RealCategoryEntry(val series: Series, val episodeCount: Int)
data class RealCategoryPage(
    val entries: List<RealCategoryEntry>,
    val topics: List<RealCategoryTopic>,
    val page: Int,
    val totalPages: Int,
) {
    val hasMore get() = entries.isNotEmpty() && page < totalPages
}

internal object WebsiteRealCategory {
    private const val ROUTE = "/category/real-drama"
    private val TOPIC = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val SERIES_ID = Regex("[0-9]{1,30}")

    fun validTopic(topic: String) = topic.isEmpty() || (topic.length <= 80 && TOPIC.matches(topic))

    fun path(topic: String): String {
        require(validTopic(topic)) { "真人剧题材编号无效" }
        return ROUTE + if (topic.isEmpty()) "" else "/" + topic
    }

    /** Brace-balanced JSON read. Escaped quotes and brace characters in strings are safe. */
    private fun extractRouter(html: String): JSONObject {
        val marker = html.indexOf("_ROUTER_DATA")
        if (marker < 0) throw AccountProtocolException("真人剧库页面未返回分类数据")
        val first = html.indexOf('{', marker)
        if (first < 0) throw AccountProtocolException("真人剧库数据格式已变化")
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in first until html.length) {
            val ch = html[i]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> quoted = false
                }
                continue
            }
            when (ch) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return try { JSONObject(html.substring(first, i + 1)) }
                        catch (_: Exception) { throw AccountProtocolException("真人剧库数据格式已变化") }
                }
            }
        }
        throw AccountProtocolException("真人剧库数据不完整")
    }

    fun parse(html: String, topic: String, page: Int): RealCategoryPage {
        require(validTopic(topic) && page in 1..100) { "真人剧分类请求参数无效" }
        val section = extractRouter(html).optJSONObject("loaderData")?.let { data ->
            data.optJSONObject("category_$") ?: data.optJSONObject("category_page")
        } ?: throw AccountProtocolException("真人剧库分类数据已变化")
        if (!section.optBoolean("isSuccess")) throw AccountProtocolException("真人剧库暂不可用")
        val route = section.optJSONObject("categoryRoute")
            ?: throw AccountProtocolException("真人剧库路由信息缺失")
        if (!route.optBoolean("enabled") || route.str("contentType") != "real-drama" ||
            route.str("canonicalPath") != path(topic)) {
            throw AccountProtocolException("真人剧题材未被官网确认，请重新选择")
        }
        val pagination = section.optJSONObject("pagination")
            ?: throw AccountProtocolException("真人剧库分页信息缺失")
        val total = pagination.optInt("totalPages", -1)
        if (total !in 1..100 || pagination.optInt("pageNum", -1) != page || page > total)
            throw AccountProtocolException("真人剧库分页已变化，请重新加载")
        val selectors = section.optJSONArray("selectorList")
            ?: throw AccountProtocolException("真人剧题材选项已变化")
        val topics = selectors.objects()
            .flatMap { row -> row.optJSONArray("items")?.objects().orEmpty() }
            .mapNotNull { option ->
                val id = option.str("selector_item_id")
                val label = option.str("show_name")
                if (id.length in 1..80 && TOPIC.matches(id) && label.isNotBlank() && label.length <= 40)
                    RealCategoryTopic(id, label) else null
            }.distinctBy { it.id }.take(128)
        if (topic.isNotEmpty() && topics.none { it.id == topic })
            throw AccountProtocolException("真人剧题材已变化，请重新选择")
        val rows = section.optJSONArray("recommendList")
            ?: throw AccountProtocolException("真人剧库作品列表缺失")
        val entries = rows.objects().mapNotNull { raw ->
            val data = raw.optJSONObject("video_data") ?: raw
            val id = data.str("series_id")
            val title = data.str("series_name").ifBlank {
                data.str("series_title").ifBlank { raw.str("name") }
            }
            if (!SERIES_ID.matches(id) || title.isBlank()) return@mapNotNull null
            val count = data.optInt("episode_cnt",
                data.optJSONObject("series_episode_info")?.optInt("episode_cnt", 0) ?: 0).coerceAtLeast(0)
            RealCategoryEntry(Series(id, title, cleanUrl(data.str("series_cover")),
                data.str("series_intro"), data.str("episode_right_text")), count)
        }.distinctBy { it.series.id }
        if (rows.length() > 0 && entries.isEmpty()) throw AccountProtocolException("真人剧库作品格式已变化")
        return RealCategoryPage(entries, topics, page, total)
    }
}
