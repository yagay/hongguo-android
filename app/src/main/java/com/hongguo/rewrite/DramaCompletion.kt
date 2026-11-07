package com.hongguo.rewrite

/** A numeric episode count is not evidence that the series has finished airing. */
internal object DramaCompletion {
    fun fromOfficialText(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        if ("未完结" in raw || "尚未完结" in raw) return null
        return when {
            "已完结" in raw || "完结" in raw -> "已完结"
            "更新中" in raw || "连载中" in raw -> "更新中"
            else -> null
        }
    }

    /**
     * The official App video_detail series_status is not the novel book's
     * creation_status. 0 = updating, 1 = finished. Others are not verified.
     */
    fun fromSeriesStatus(value: Any?): String? = when (value?.toString()) {
        "0" -> "更新中"
        "1" -> "已完结"
        else -> null
    }

    fun episodeLabel(total: Int, status: String?, episodeIndex: Int?): String =
        buildList {
            if (total > 0) add("全${total}集")
            if (status == "已完结" || status == "更新中") add(status)
            if (episodeIndex != null && episodeIndex > 0) add("第${episodeIndex}集")
        }.joinToString(" · ")
}
