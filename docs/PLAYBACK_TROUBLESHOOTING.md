# 红果 Android 播放诊断与排障

## 本次改动

- 播放失败不再统一显示“准备播放失败，请检查网络”。`PlaybackDiagnostic` 为已知错误提供固定代码、阶段与简短说明。
- 在播放器错误界面显示 `阶段 · 错误码`；点“复制诊断”可以复制安全摘要。
- 空的 HTTP 200 正文（`VIDEO_MODEL_EMPTY`）与有响应但未返回可用模型（`VIDEO_MODEL_INVALID`）分别处理。
- GET video_tab 遇到 HTTP 非 200 或真正空响应时不再无声返回 null；其他候选路由仍有机会尝试。
- 某条路线返回不完整模型时，只对已明确列出的“缺模型/响应格式不完整”重试备用签名路由；编码不兼容、媒体密钥、URL 白名单等拒绝不会被忽略。
- HTTP 底层向上保留已脱敏的 DNS/SSL 异常类型，便于区分网络原因。
- 不修改请求签名算法、版本号、注册身份创建流程、Cookie 处理、缓存协议或解密逻辑。

## 错误码

| Code | Stage | 含义 |
| --- | --- | --- |
| `DEVICE_REGISTER_FAILED` | `device_register` | 未分类的设备初始化失败 |
| `DEVICE_REGISTER_DNS_FAILED` / `DEVICE_REGISTER_TIMEOUT` / `DEVICE_REGISTER_TLS_FAILED` | `device_register` | 分别表示 DNS、超时和 TLS 安全连接失败 |
| `DEVICE_REGISTER_HTTP` | `device_register` | 注册接口返回非 200 HTTP 状态，诊断附带状态码 |
| `DEVICE_REGISTER_EMPTY` / `DEVICE_REGISTER_INVALID_JSON` | `device_register` | 返回空正文或非 JSON |
| `DEVICE_REGISTER_INVALID_IDS` / `DEVICE_REGISTER_REJECTED` | `device_register` | 服务器未返回有效注册编号或拒绝请求 |
| `DEVICE_REGISTER_NETWORK_FAILED` / `DEVICE_REGISTER_RESPONSE_TOO_LARGE` | `device_register` | 网络读取未完成或响应超出安全大小限制 |
| `VIDEO_MODEL_EMPTY` | `multi_video_model` | 片源接口返回 HTTP 200 但正文为空 |
| `VIDEO_MODEL_HTTP` | `multi_video_model` | 片源接口返回非 200 HTTP 状态 |
| `VIDEO_MODEL_INVALID` | `multi_video_model` | 接口有响应，但本集模型缺失、格式不符合预期 |
| `CODEC_UNSUPPORTED` | `codec_select` | 本集未提供受支持的 H.264 / HEVC 轨道，例如只有 ByteVC2 |
| `MEDIA_SOURCE_INVALID` | `media_source` | 片源地址无效或不可用 |
| `MEDIA_FORMAT_UNSUPPORTED` | `media_prepare` | 媒体索引、密钥、格式或加密数据无法处理 |
| `MEDIA_STORAGE_FULL` | `media_cache` | 存储空间不足 |
| `PLAYBACK_DNS_FAILED` | `network` | 域名解析失败 |
| `PLAYBACK_TIMEOUT` | `network` | 请求超时 |
| `PLAYBACK_TLS_FAILED` | `network` | TLS 安全连接失败 |
| `PLAYBACK_NETWORK_FAILED` | `network` | 通用的网络请求失败 |
| `MEDIA_PREPARE_IO_FAILED` | `media_prepare` | 读写或媒体准备 I/O 失败 |
| `PLAYBACK_PREPARE_FAILED` | `playback_prepare` | 尚未识别的准备错误，需要测试或后续添加分类 |
| `PLAYER_DECODER_UNSUPPORTED` | `media_decoder` | Media3 播放器解码失败 |
| `PLAYER_PLAYBACK_FAILED` | `media_player` | Media3 播放器播放阶段失败 |

注意：不是所有状态都有 HTTP 状态码、响应字节数或尝试次数；仅在确实由请求层报告时显示。尤其是 `VIDEO_MODEL_INVALID` 不代表“HTTP 200 空正文”。

## 真机验证步骤

1. 使用本 PR 的 **Debug** APK，在安卓手机上播放一集新内容（不要只测试已有完整 MP4 缓存的剧集）。
2. 若出现错误，点击“复制诊断”并附上代码/阶段、实际播放触发方式（首次播放 / 切集 / 预加载）和应用版本。
3. 测试不同剧集、移动网络/Wi-Fi，排查是否与线路或单剧编码相关。
4. 切集、退出播放器、打开另一个剧集，确认旧诊断不会留在新请求上。
5. 测试缓存命中、渐进式播放与完整下载兼容路径。
6. 验证正常播放时“复制诊断”按钮不出现；取消与后台暂停不产生无关错误。

## 安全与隐私

**复制诊断只包括白名单字段**：稳定错误码、阶段、请求次数、HTTP 状态（如有）、响应长度（如有）。不记录/导出 URL、用户登录状态、设备标识符、Cookie、加密参数、签名头或原始服务端响应。

## 后续排查

若真机仍然无法播放，应基于复制出的错误码定位对应环节。对于 `DEVICE_REGISTER_FAILED` 和 `VIDEO_MODEL_EMPTY`，先验证请求及网络情况；对于 `CODEC_UNSUPPORTED`，需要根据实际源视频编码能力处理，**不应该通过延长超时解决**。

本轮没有更改签名生成或模拟设备参数；在缺少实机证据时，直接换协议配置可能让当前已经能取址的环境出现回归。

## 设备注册失败专项分析

设备注册失败表示**还没有进入视频片源接口**，不应归咎于 ByteVC2 或 ExoPlayer。旧版只有 `DEVICE_REGISTER_FAILED`，无法区分 HTTP、DNS 或 JSON 类型。

当前源码将原有泛用异常拆为安全、可复制的具体错误码，保留原有 10 秒失败冷却；考虑跨地区网络时延，注册请求单次超时由 8 秒调整为 12 秒，**不引入无上限重试或身份绕过**。针对 Android 上可能出现的 gzip 正文，只有检测到 gzip 文件头时才执行有大小上限的解压；若网络栈已经解压，则直接按文本解析。

如果更新后仍失败，按新错误码判断：DNS/TLS/timeout 可以比较 Wi-Fi 与蜂窝网络；HTTP 或服务业务拒绝请记录状态码和大致时间（不发送账号和 Cookie），它不是本机视频解码问题；INVALID_IDS/INVALID_JSON 提示上游响应与当前注册协议不匹配，不能通过盲目延长播放器缓冲解决。

这些改动**不能保证**上游注册服务接受第三方请求。切换网络或等待可能帮助排查，但修复最终依赖真实错误码，必要时使用服务提供的官方客户端或正式支持的接口。
