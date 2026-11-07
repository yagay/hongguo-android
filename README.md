# 红果短剧 Android · 原生重写（开发分支）

这是 **全新 Android 工程**，不是旧版 `com.hongguo.android` 的 UI 修补。

## 设计基准

- 网页版导航结构：推荐 / 榜单 / 最新 / 筛选，顶部搜索、历史、收藏、分享与下载任务。
- 顶部明确可见「设置」入口：独立设置页包含内容来源、API 地址、详细日志预览、日志开关、清空和 TXT 导出。
- 统一 Insets：Activity 显式 edge-to-edge，ApplicationRoot 一次性处理 safeDrawing + IME；首页、设置页 Scaffold 禁止重复处理系统栏 Insets；播放器底部控制区可滚动，适配手势导航、三键导航和刘海屏。
- 诊断事件存储在本机私有目录（256 KB 上限），记录阶段、耗时、HTTP 状态和错误代码，不收集 URL、搜索内容和凭据；经 Android 文档选择器导出。
- 所有页面使用 Kotlin + Jetpack Compose；视频使用 AndroidX Media3。
- 不引入旧 Android 项目的 Activity、播放器、设备注册、缓存、状态或 Hook。
- 单向数据流：DramaViewModel -> AppState -> Compose。
- 统一的剧集、分集、分页和错误模型；收藏/历史保存在本机并限定为 200 条。
- 历史只在 Media3 实际渲染首帧后记录，避免播放失败留下误记录。
- 独立包名 `com.hongguo.rewrite`，能与旧版并排安装进行验收。

## 当前工程状态（重要）

**这是重写工程的第一阶段，不是已完成的独立客户端。**

当前集成独立的原生内容和播放核心 `com.hongguotv.core`，来源为
`tsukiyo-zero/hongguoTV`（GPL-3.0-only），并使用独立适配器 `NativeHongguoSource`
与 Media3 `NativeVideoDataSource` 接入。默认无需 Node 服务：推荐、真人/漫剧/AI 榜单、
分类、题材筛选、搜索、分集读取和匿名视频播放均走原生链路。

官网目录数据源 `NativeWebsiteSource` 保留作公开内容参考；
**上游是否返回有效视频模型受其当前服务状态和风控影响，本工程不保证一定可播放。**

可选连接开源 `sunnysky123/hongguo-web` 的 **HTTP API 合约**，以使用视频播放、
分类筛选、更多榜单与下载任务；手机需要使用可达的服务器地址。
接口包括：`/api-key`, `/rank`, `/latest`, `/browse`, `/filters`,
`/search`, `/episodes`, `/stream`, `/img`, `/share/resolve`, `/download/task`。
初始不配置服务器时优先使用原生推荐、搜索及视频接口；推荐接口不可用时回退官网公开目录。失败时显示脱敏错误代码。

**仍未完成或需要验收**：
- 真机验证匿名设备注册、签名 GET/POST 取址、解密及首帧；上游可能返回空响应。设备注册 DNS/超时、播放模型空响应和不支持的解码格式均有独立诊断代码；
- 已接入本机单集 MP4 导出，使用 Android 文件选择器选择保存位置，下载中可查看进度和取消；
  进程退出后不支持断点续传，跨设备账号同步与批量下载仍待完善；
- 真机实测及无障碍/旋转/网络切换的完整回归。

播放请求失败可能源于上游设备注册/风控，不是简单更换播放器或重做界面就能保证解决。

## 构建 Debug

JDK 17 + Android SDK 36：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --build-cache
```

CI 只执行 Debug 构建，不生成 Release/Magisk 包。

## 来源与许可

原型参考 `sunnysky123/hongguo-web` 的页面结构和 API 文档；
该项目采用 MIT 许可证。当前分支沿用原项目 GPL-3.0-only LICENSE；
网络通信实现是重新编写的独立客户端，而非复制旧版 Java 服务层。


## 抖音式交互与公开评论（2026-11-07）
- 全屏 `VerticalPager`：**上滑下一集 / 下滑上一集**，仅当前页有 Media3 播放器；切换时取消旧剧集请求。
- 单击画面暂停/继续，双击收藏（带爱心反馈），长按临时 2× 倍速、松开恢复；进入后台会暂停播放。
- 切换分集时沿用独立核心的短期下一集播放模型预取；一次请求最多带一集预测，避免连续滑动产生无限后台请求。
- 右侧固定操作栏：收藏、分集评论、弹幕开关、Android 系统分享、单集下载、选集。
- 评论底部抽屉：匿名只读分集评论，支持分页；上游不可用时尝试官网有限公开评论，并明确标记。
- 弹幕：时间点读取、播放时间同步、横向移动；默认关闭，可在播放器快捷开关和播放器内「播放设置」面板启用。禁止虚构弹幕。
- 交互分离：视频纵向翻页、评论抽屉竖向滚动、进度条拖动互不共用手势识别器。
- 目前**不支持评论发布、服务器点赞或账户授权**；相关功能不能用虚假的本地行为代替。真实评论/弹幕可用性需手机端验证。


## 原生界面重构

布局参考 [compose-reels](https://github.com/manjees/compose-reels) 和
[ComposeReels](https://github.com/clown6613/ComposeReels) 的媒体界面分层，
保留 [hongguo-web](https://github.com/sunnysky123/hongguo-web) 的
推荐/榜单/最新/筛选四个主入口。这里参考的是交互结构，
未直接复制第三方 UI 资源。

- \`MainActivity.kt\` 只处理生命周期、系统 Insets 和页面路由；
  \`CatalogScreen.kt\`、\`LibrarySheets.kt\`、\`SettingsScreen.kt\`
  独立承载内容与 UI；\`AppDesign.kt\` 统一色彩。
- 首页压缩为品牌及快捷入口、搜索、四个导航标签。海报采用
  等宽自适应网格和固定纵向比例，消除不同屏宽下的固定高度失真。
- 筛选采用底部抽屉，选择后点击「查看筛选结果」才正式刷新。
- 观看历史、收藏、分享解析、下载任务改为同一套可滚动底部抽屉。
- 播放器底部渐变遮罩、剧名与简介、紧凑右侧互动操作、
  独立选集抽屉和带头像评论列表；不改变已实现的 Media3 播放/弹幕接口。
- \`SettingsScreen\` 按内容来源、诊断日志和关于分组；播放体验选项统一移至播放器右上角「播放设置」；
  **日志记录开关、日志预览、清空、TXT 导出功能保留**。
- 所有界面在应用根层处理系统安全 Insets，子 \`Scaffold\` 不重复扣除。
- CI 只构建 Debug 和运行单元测试。**真实手机上的截图/手势/折叠屏/大字体仍需验收**。


## Danmaku and video obstruction fix

- The native video player now owns a **separate video viewport** above the full-drama dock.
  The bottom CTA and orange seek control are outside the video; neither can cover
  burned-in subtitles.
- Default aspect mode is \`FIT\` to preserve the entire image. The top-right
  overflow menu can optionally switch to full-bleed \`ZOOM\` and back.
- Timed comments use measured text width to travel completely from the right
  edge to the left edge; deterministic four-lane assignment avoids text
  collisions and preserves lane positions through pause/seek.
- Movement is driven by Media3 position, not wall-clock time. Seeking and
  pausing update/stop the overlay.
- Diagnostics distinguish \`DANMAKU READ_EMPTY\` from \`READ_NONEMPTY\` so a
  successful request with no visible comments can be diagnosed separately.
- Independent unit tests cover lane contention, non-overlap, seek progress,
  width-aware animation boundaries, and stable ordering.


## 自动加载右侧评论数

- 进入分集后立刻独立请求首批评论及服务端 `total`，无需先点击右侧评论图标；不阻塞视频取址和首帧。
- 右侧评论按钮显示公开服务端评论总数（包含 0）；正在加载时显示省略号，失败时保留「评论」文字，不假装 0。
- 点击评论只展示正在加载或已缓存的结果，不取消重发；仍保留手动刷新和分页。
- 切集或退出时取消过时评论请求并清空旧数字，确保不会混用其他分集的统计。
- 收藏和喜欢的公开计数现从整剧官网与原生分集统计读取；分享总数没有可靠来源，仍不展示虚构统计。


## 自动公开互动统计（真人短剧播放器）

现在打开剧集后会并行读取两路只读数据，完全独立于 Media3
播放地址、评论列表和弹幕请求：

1. 红果官网播放器页面 \`seriesSocialInfo\`：
   \`series_favorite_count\` 为全剧收藏数，
   \`series_like_count\` 为全剧点赞数。
   该格式可参考 zfdang/hongguo-open-player 的
   \`src/server/native/hongguo.js\`（仅参考协议字段；未复制其 AGPL 代码）。
2. 红果匿名原生 \`POST /novel/player/video_detail/v1/\`：
   \`followed_cnt\` 为全剧追剧/关注数，
   \`digg_cnt\` 为全剧点赞数，
   \`series_play_cnt\` 为全剧播放量；
   各集 \`video_list[].comment_count\` / \`digged_count\` 分别为分集评论数和点赞数。
   接口与字段参考 Apache-2.0 的
   [beilunyang/openhongguo](https://github.com/beilunyang/openhongguo)。

所有数字必须校验短剧 ID 和分集视频 ID。**缺失或异常字段显示为未知，
不写成 0；客户端点击收藏或本地喜欢不修改公开数字。** 收藏和追剧不是同一
指标：如果只能拿到 \`followed_cnt\`，界面会额外标记「追剧」；
爱心优先显示分集点赞数，只有全剧点赞数时会额外标记「全剧」。
分享总数尚无可核实的数据字段，不显示虚构数值。

两路读取有独立失败日志 \`SOCIAL WEB_FAIL\` / \`SOCIAL APP_FAIL\`；
成功时为 \`SOCIAL WEB_READY\` / \`SOCIAL APP_READY\`。缓存最近打开剧集
的结果约两分钟，但每次进入仍会刷新；切集不重复请求全剧数据，
直接按当前分集 ID 取对应指标。实际数据能否返回取决于上游服务，
不能以 Debug CI 通过代替真机网络验证。


## 功能扩展 · 2026-11-07

本次变更只触及 \`rewrite-web-native\` 开发分支，继续维持单一原生
Kotlin/Compose/Media3 工程及 Debug-only CI。

### 内容发现与连续浏览
- **搜索联想**：直接复用已存在的
  \`ContentRepository.searchSuggestions()\`，输入 2 字后延迟约 300ms
  查询并取消过期请求；搜索历史只保存本机，最多 12 条，可清空。
- **官方匿名推荐**：接入 \`RecommendationFeed\`，服务端会话分页和去重
  由独立核心负责；匿名接口失败时第一屏回退到官网公开推荐，并保持
  后续分页来源一致。
- **跨剧竖滑**：推荐页有「刷短剧」入口，采用独立
  \`feedMode\`；一次观看一部作品的代表分集，上滑下一部、下滑上一部。
  不改变正常「看全集」的逐集 VerticalPager 语义。切换时预读下一部
  的公开剧集详情，但不会预先下载整部视频。
- **多季及相关作品**：既有 \`ContentRepository.related(id)\`
  官方 signed plan 读取直接接入选集抽屉。待上线短剧有明确标记且不能
  触发播放，不按标题相似度擅自归并。

### 播放器与本机设置
- **统一播放设置入口**：播放器右上角 `···` 打开可滚动底部面板，集中管理画面适应/铺满、倍速、画质上限、弹幕开关/速度/轨道/字号、定时停止和本集下载。全局设置页不再重复显示播放体验选项，保留 API 地址、日志预览和 TXT 导出。
- **精确断点续看**：为每集保存播放位置和时长（最多 200 个记录）。
  首帧确认后每四秒保存，手动退出额外刷新一次；Media3 就绪后自动
  seek。前 5 秒或片尾附近不自动恢复，完成播放时移除该集进度；
  与历史、收藏的本机数据独立存储。
- **画质上限**：支持自动/360/480/720/1080P，依据媒体模型和
  手机解码器选择最接近的可用档位，必要时回退；这里的「上限」并非
  对服务端码率的保证。切换时重建媒体源并尝试恢复位置。
- **定时停止**：15/30/60 分钟或播完本集停止，参考
  \`hongguoTV.PlaybackComfort.SleepTimer\`，使用单调时钟、仅当前会话生效。
- **弹幕显示设置**：可选择速度（慢/标准/快）、轨道（1–5）、
  字号（12/14/16/18sp）；位置仍完全基于 Media3 播放时钟。
- 播放、内容和弹幕功能依赖上游匿名接口，Debug CI 只能证明源码构建及
  单元测试成功，不能保证网络取址、弹幕和真机手势结果。

### 尚未实现（不要把 UI 占位当成已完成）
完整公开上新日历、演员主页/作品榜、账号登录与云同步、评论发布、
批量下载断点续传以及 App 内更新；这些依赖新的协议、认证或独立
工作流。后续须优先确认服务端可用性与授权前提。
