# 2026-11-10 播放接口 / CDN / 预加载改造

分支：`rewrite-web-native`。保留 `hongguoTV` 的签名、CENC 和 Android Media3 单播放器；没有移植 Go 服务或第二个播放器。

## 接口选择（依据其他开源项目源码）

- **首选**：签名 `GET /reading/bookapi/video_tab/multi_video_model/v1/`，仍沿用已有的健康线路记忆。
- **备用**：已有签名 `POST /novel/player/multi_video_model/v1/`。
- **最后兜底**：参考 `Xanerix/guoapp` 的 `POST /novel/player/video_model/v1/`；仅在前两类路径全部返回空模型、当前工作尚未取消、绝对截止时间还有超过 1.2 秒时使用。与现有播放会话共享匿名注册身份，不触发单独的循环。解析对象、JSON 字符串和有界 base64 模型；拒绝业务失败、无轨道和超大响应。该备用路径的实际成功率尚未在用户设备上验证。
- **参考验证**：`jackotom/hongguo-drama` 的 2026-11-10 取源诊断说明旧 POST 空正文不等于所有新 GET 路径也失效。当前 App 已优先采用新 GET，不倒退到旧接口。

## CDN 和内存交接

- 首次 MP4 索引建立成功后，对**域名**记录经平滑处理的耗时；网络错误令该域名短暂降权。只在**相同质量和编码档位**内调整 CDN 候选顺序，不记录完整签名地址或播放密钥，健康结果 15 分钟后失效，最多 32 个主机。
- 背景模型解析后，内存短暂保留可**领取一次**的 `StreamLadder`，TTL 12 秒、最多 5 条。用户在 CDN 开头字节还未准备完成时快速切集，可减少一次签名播放模型请求。它不共享已经打开的 `RemoteVideo`，避免预加载取消影响前台正在读取的文件。
- N+1、N+2 和跨剧准备共享一个后台播放**模型请求** `Mutex`，减少低优先级签名取源竞争；前台播放永不等待这个锁。取得模型后立即释放，MP4 索引和后续预热由现有每个任务的取消与超时机制处理，避免慢 CDN 把之后的 N+1 卡在锁中。
- `VideoPreloadPolicy.Slot` 根据与当前视频的距离分配预算：下一集采用设置值；下一部最多 8 MiB；更远最多 2 MiB。后台失败不应令当前解码器失败。
- Media3 `SimpleCache`、解密后缓存键与旧数据目录保持原样，避免升级清空用户缓存。上游 URL、解密信息并未落到额外的磁盘文件。

## 官方推荐失败恢复

- 以前刷新异常会将**整条推荐队列**标为失效，调用 `closePlayer` 后重新 `startFeed`；这可能使原本可播放的剧集也被排除，并导致屏幕和 Media3 状态切换。
- 现在仅暂时排除**当前失败的剧集**（上限 24 条），保留当前剧集作为界面锚点，取消旧的跨剧预加载，创建新官方推荐分页会话；请求成功后自动前往新推荐。模型失败不等于其他推荐都过期。
- 推荐/播放路径已有 `session_id`、`cell/change` 游标与签名上下文隔离，不引入第二套 Go 的分页协议，避免两套游标竞争。

## 真机验收及限制

诊断关注：`MODEL_READY`、`INFLIGHT_MODEL_HANDOFF`、`SPECULATIVE_LANE_WAIT`、`RANGE_PREPARED`、`CDN_CANDIDATE_*_READY/FAILED`、`PLAYBACK SWITCH_TO_FIRST_FRAME`、`FAILED_QUEUE_REFRESH_WITHOUT_PLAYER_RESET`。应分别测试 Wi-Fi / 移动数据、快速连续切集、N+1 未准备完成、明确 HTTP 403、所有推荐为空、用户中途退出、下一集自动播放和关闭预加载设置。

以上只是优化后的**源码行为**与离线测试范围，不代表已在真实红果上游/设备完成成功率 A/B 测试。切换前需对比冷加载、缓存命中、业务拒绝和 CDN 302 等条件的诊断，尤其不能把任何 GET/POST 路径当作永久稳定 API。
