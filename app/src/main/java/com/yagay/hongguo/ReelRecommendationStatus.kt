package com.yagay.hongguo

/**
 * Evidence about the LAST reel page request, not a guess based on login UI.
 *
 * ACCOUNT_RECEIVED means the account-aware official endpoint returned new
 * visible cards. It cannot prove that the server personalized their ranking.
 */
enum class ReelRecommendationStatus(
    val shortLabel: String,
    val explanation: String,
    val accountRequest: Boolean,
    val received: Boolean,
    val failed: Boolean = false,
) {
    NOT_REQUESTED(
        "推荐来源 · 待请求",
        "尚未取得本轮刷剧推荐。仅登录账号或开启开关，并不代表已经加载了账号推荐。",
        false, false,
    ),
    ANONYMOUS_LOADING(
        "匿名推荐 · 加载中",
        "正在通过开心果匿名接口读取推荐；本次请求没有使用红果登录账号。",
        false, false,
    ),
    ANONYMOUS_RECEIVED(
        "匿名推荐 · 未用账号",
        "推荐内容来自开心果匿名数据源，本次并未加载登录账号的官方个人推荐。",
        false, true,
    ),
    ANONYMOUS_EMPTY(
        "匿名推荐 · 暂无新内容",
        "匿名数据源已响应，但这次没有新增可展示的推荐作品。",
        false, false,
    ),
    ANONYMOUS_FAILED(
        "匿名推荐 · 加载失败",
        "本次匿名推荐请求未成功；当前视频可能是此前已经加载的内容。",
        false, false, true,
    ),
    ACCOUNT_LOADING(
        "账号官方推荐 · 加载中",
        "已使用登录账号会话请求官方推荐，尚未收到本轮可展示的作品。",
        true, false,
    ),
    ACCOUNT_RECEIVED(
        "账号官方推荐 · 已获取",
        "已通过登录账号会话向官方接口请求并成功收到新的推荐作品。但客户端无法确认服务端是否执行了个性化排序。",
        true, true,
    ),
    ACCOUNT_EMPTY(
        "账号官方推荐 · 暂无新内容",
        "账号请求已返回，但没有新增可展示的作品。不能据此确认个性化推荐已加载。",
        true, false,
    ),
    ACCOUNT_FAILED(
        "账号官方推荐 · 加载失败",
        "本次账号推荐请求失败。当前画面可能仍在播放之前加载的作品，并不表示本次加载成功。",
        true, false, true,
    ),
}

internal object ReelRecommendationStatusPolicy {
    fun loading(account: Boolean): ReelRecommendationStatus =
        if (account) ReelRecommendationStatus.ACCOUNT_LOADING
        else ReelRecommendationStatus.ANONYMOUS_LOADING

    fun received(account: Boolean, newItems: Boolean): ReelRecommendationStatus =
        if (account) {
            if (newItems) ReelRecommendationStatus.ACCOUNT_RECEIVED
            else ReelRecommendationStatus.ACCOUNT_EMPTY
        } else {
            if (newItems) ReelRecommendationStatus.ANONYMOUS_RECEIVED
            else ReelRecommendationStatus.ANONYMOUS_EMPTY
        }

    fun failed(account: Boolean): ReelRecommendationStatus =
        if (account) ReelRecommendationStatus.ACCOUNT_FAILED
        else ReelRecommendationStatus.ANONYMOUS_FAILED
}
