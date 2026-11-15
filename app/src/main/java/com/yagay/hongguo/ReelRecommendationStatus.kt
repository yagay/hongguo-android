package com.yagay.hongguo

import com.hongguotv.core.PersonalizationEvidence

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
    ANONYMOUS_OFFICIAL_RECEIVED(
        "匿名官方推荐 · 未用账号",
        "该作品来自不带账号会话的官方动态推荐；不能当作账号画像推荐。",
        false, true,
    ),
    ANONYMOUS_OFFICIAL_CATEGORY(
        "匿名官方分类 · 未用账号",
        "该作品来自官方分类选择接口，不属于动态个性化推荐流。",
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
        "账号官方推荐已接入",
        "该作品来自携带登录 Cookie 的官方动态推荐，说明账号请求已经成功。实际是否使用设备签名以诊断中的 SIGNED/UNSIGNED 为准；服务端未明确确认是否按个人偏好排序，可使用独立对照检测进一步观察。",
        true, true,
    ),
    ACCOUNT_SERVER_ENABLED(
        "账号推荐 · 个性化候选标记",
        "当前作品的响应含候选个性化布尔字段，但其语义尚未经过红果官方 APK 的协议核验，不能据此认定已经完全接入账号个性化。",
        true, true,
    ),
    ACCOUNT_SERVER_DISABLED(
        "账号推荐 · 候选标记为关闭",
        "该作品的响应包含候选个性化字段且取值为关闭，但字段语义还未核验，暂不能对真实排序作最终判断。",
        true, true,
    ),
    ACCOUNT_CATEGORY_FALLBACK(
        "账号请求 · 分类推荐兜底",
        "该作品来自官方分类选择接口，即使请求携带账号 Cookie，也不能判为动态个性化推荐；同一列表里可能有不同来源。",
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

    fun received(
        account: Boolean,
        newItems: Boolean,
        evidence: PersonalizationEvidence = PersonalizationEvidence.UNDECLARED,
        categoryFallback: Boolean = false,
    ): ReelRecommendationStatus =
        if (account) {
            if (!newItems) ReelRecommendationStatus.ACCOUNT_EMPTY
            else if (categoryFallback) ReelRecommendationStatus.ACCOUNT_CATEGORY_FALLBACK
            else when (evidence) {
                PersonalizationEvidence.SERVER_ENABLED -> ReelRecommendationStatus.ACCOUNT_SERVER_ENABLED
                PersonalizationEvidence.SERVER_DISABLED -> ReelRecommendationStatus.ACCOUNT_SERVER_DISABLED
                PersonalizationEvidence.UNDECLARED -> ReelRecommendationStatus.ACCOUNT_RECEIVED
            }
        } else {
            if (newItems) ReelRecommendationStatus.ANONYMOUS_RECEIVED
            else ReelRecommendationStatus.ANONYMOUS_EMPTY
        }

    fun failed(account: Boolean): ReelRecommendationStatus =
        if (account) ReelRecommendationStatus.ACCOUNT_FAILED
        else ReelRecommendationStatus.ANONYMOUS_FAILED
}
