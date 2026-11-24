# Android 架构审查与渐进式优化（2026-11-06）

> 范围：`main` 分支的应用源码、Gradle 配置、Java 媒体核心、账号协议、更新器、测试及构建文件。
> 本轮采用独立分支，不更改已有 Android 包名、签名、API 协议、播放器格式、收藏数据格式或公开版本号。
> 以下分为“已从源码确认”和“风险/后续验证”；源码评审不等于实体设备验收。

## 当前结构和依赖方向

| 层 | 主要位置 | 职责和依赖 |
| --- | --- | --- |
| App / UI | `com.hongguo.android` | Compose 导航、3 套主题、发现/搜索、详情、播放器、片单、更新 UI |
| 状态控制 | `HongguoViewModel` / `AccountViewModel` / `UpdateViewModel` | StateFlow、请求取消与最新结果判断、会话和播放状态 |
| 内容适配 | `DramaRepository` / `DiscoveryModels` | 连接 Java 内容服务与 UI 数据模型；使用 `runInterruptible(Dispatchers.IO)` |
| 播放 | `VideoPlayer`, `ProgressiveVideoDataSource`, `EpisodePlayerScreen` | Media3 / ExoPlayer、进度、后台暂停、MediaSource 生命周期 |
| Java 内容服务 | `com.hongguo.api.service` | 请求、片源、流式/兼容播放、磁盘缓存和解密 |
| 底层媒体 | `com.hongguo.api.core` | MP4 / CENC 解析和签名等协议算法 |
| 本地片单 | `LibraryStore` / `LibraryCodec` | JSON 快照、收藏、历史、续播；保留原数据格式 |
| 账号 | `com.hongguo.account` 及 `protocol` | 登录状态、账户存储、官网与手机云身份/操作边界 |
| 更新 | `UpdateRepository` / `UpdateSources` / `UpdateInstaller` | 更新检查、URL 限制、下载校验、安装包签名核验 |
| 验证 | `app/src/test`, `app/src/androidTest` | JVM 单元测试及需设备/模拟器的 UI/集成测试 |

项目配置：Kotlin 2.2.20、AGP 8.13.2、Gradle 8.13、JDK 17、compile/target SDK 36、min SDK 26，Media3 1.8.0。当前为单个 `:app` 模块，Java/Kotlin 通过包区分边界，并没有物理 Gradle 模块隔离。

## 重要发现与处理

| 级别 | 证据 / 影响 | 本轮处理 |
| --- | --- | --- |
| P0 | Fork 原先没有 `.github/workflows`；不能持续验证每次修改是否仍能生成 APK | 新增仅 Debug 的 Android CI，包含构建、单测、lint、APK artifact，不生成 Release |
| P1 | `HongguoViewModel.kt` 原先约 852 行，混放几十个 UI 字段与异步编排 | 无行为变化地提取 `HongguoUiState.kt`（`AppTab`、`FeedState`、`HongguoUiState`） |
| P1 | `LoadAhead.kt` 每次新的海报 URL 都加入一个永不裁剪的 `HashSet`，长时间滚动会持续增长 | 改为有最大条目数的近期去重器；列表禁用加载时也不预取封面 |
| P1 | `DramaRepository.kt` 同时包含内容访问与用户可见异常文本匹配 | 把脱敏消息映射提取为 `UiFailureMessages.kt`，保留原文案行为 |
| P2 | `previewFirstEpisode` 直接 `episodes.first()`，空集列表只产生通用异常 | 改成显式 `empty_episodes`，复用已有“暂时没有剧集”分类 |
| P1 / 待验证 | `savePosition` 默认每 2 秒触发本地片单 JSON 更新；`LibraryStore.change` 归一化并序列化整个快照 | 本轮不改记录频率或格式；建议实机测 200 条片单下的主线程耗时，再决定批量/合并写入 |
| P1 / 待验证 | `Stream` 和 `ProgressiveMedia` 各有 512 MiB 缓存额度；两套上限独立 | 后续加入统一容量统计和受控清理，避免误把两种缓存视为一套 512 MiB 预算 |
| P1 / 待验证 | `AccountRepository.kt` 包含大量同步协调和较多网络任务；`Client.java`、`Mp4.java`、`PureSign.java` 为大文件 | 先加接口级测试与性能基准，再做拆分；不要在同一个 PR 重新实现协议和签名 |
| P2 | 多套大 Compose 屏幕文件，如 `ImmersiveScreens.kt`、`CardsScreens.kt`、`MyScreen.kt` | 后续可按首页、发现、我的、播放器分别抽取无副作用的组件，保持 UI 样式和手势不变 |
| P1 | `VideoPlayer` 在 ON_START 建立并在 ON_STOP 释放播放器；VM 另管理预热与媒体清理 | 保持生命周期协议，本轮不引入后台播放/Root/LSPosed，也不重新设计 ExoPlayer |

## 本次源码边界

- 保持播放请求 ID、版本戳和取消时序不变，防止旧播放器回调覆盖新剧集。
- 不接触账户令牌、Cookie、设备身份生成、账号隔离或协议签名算法。
- 不修改数据库/本地 JSON 结构，现有历史、收藏、进度可沿用。
- 媒体取流、缓存格式及解密原理不调整；重构 UI 不意味着已解决上游片源的网络问题。
- 只构建 Debug APK；仪器测试需要 Android 设备/模拟器，CI 的 JVM 单测不能替代播放实机验收。

## 进一步重构建议（分 PR、可回滚）

1. **播放**：增加 Request ID 与 Session 关闭顺序的 JVM/设备测试，随后把预加载槽和释放策略抽为单独的 playback coordinator。验证切集、前后台、暂停、回退、缓冲、连续播放、空列表和取消。
2. **列表持久化**：在 200 条收藏 + 200 条历史的样本上测主线程 `LibraryCodec.encode` 与 2 秒位置更新开销；只在有数据与性能证据后改为限频后台持久化，保证退出/崩溃数据正确。
3. **账号**：将 `AccountRepository` 的网络协议、凭据存储、同步状态及云端进度排队进一步分离；保留网站会话与手机云身份独立，不记录原始凭据。
4. **网络/媒体**：为 `Client`、`AppSource`、`ProgressiveMedia` 建立 fake transport、缓存上限和损坏媒体输入回归；不一次性重写签名及 CENC 栈。
5. **UI**：在测试覆盖前提下迁移大文件中的无状态 Compose 组件，并利用稳定键和局部状态缩小高频播放器进度引起的重组范围。
6. **数据层**：逐步用接口包装静态 Java 服务入口，便于离线测试，而非立即跨 Gradle 模块移动所有源码。

## Debug CI 与验收

CI 文件：`.github/workflows/android-debug.yml`

命令：`./gradlew --no-daemon --stacktrace :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`

- Push / PR 验证源代码；输出 `app/build/outputs/apk/debug/*.apk` 为 GitHub Actions artifact，**不**执行 `assembleRelease`、签名、发布 Release 或上传商店。
- 单元测试覆盖新增的有界海报去重和错误信息脱敏。
- 必须另在真机验证：搜索与列表翻页、第一帧记录、竖屏滑动切集、长按加速、前后台释放、缓存回收、登录退出和本机片单保留。
- 不应把 CI 成功等同于内容源保证可用、所有设备流畅或账户接口稳定。

## 上游合并策略

- 原上游 `OOIUa/hongguo-android` 保持为同步来源，本 Fork 的 `main` 暂不直接修改。
- 在 `refactor/architecture-hardening` 提交改动并以 PR 审查；通过 CI + 真机回归后再合并至 `main`。
- 后续每一轮尽量独立限定在 presentation、repository、playback 或 account 层，并附测试，减少同步上游时的冲突。
