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
初始不配置服务器时进入官网公开目录模式，能正常列出资源时显示结果，失败时显示脱敏错误代码。

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
