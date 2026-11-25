# YHongguo · 自动检查上游与应用版本提醒

## 每天自动检查

默认分支 rewrite 中的 [Daily OpenHongguo update and signed APK](../.github/workflows/daily-openhongguo.yml)
工作流每天 **04:23 UTC** 检查 beilunyang/openhongguo 的 main 分支。GitHub 计划任务可能延后，
并且开源项目的上游版本本身并不保证播放永远可用。

上游 SHA 或本地 SDK 集成摘要（构建脚本、恢复补丁、Go 回归测试）变化，或还没有发布对应签名升级包时才构建；
每天同时在 Actions 摘要列出待审核的上游播放相关 PR，不自动执行或合并未审核 PR。
也可以从 Actions 手动运行，并选择 rebuild_current。

更新流程：

1. 查上游 main 的完整 SHA，生成一个精确锁定的候选源码版本。
2. 在 **无仓库写权限、无正式签名 Secrets 的构建任务**中，
   运行 OpenHongguo 的签名测试、播放恢复测试、Android Debug 单元测试，
   并构建、校验 arm64/16KB APK。
3. 用临时 CI 证书生成候选 Release APK（该证书不对用户发布）。
4. 所有测试成功后，单独的受保护发布任务下载候选 APK，使用仓库现有
   HG_RELEASE_* Secrets **重新签名**，验证证书指纹、包名和 16KB 对齐。
5. 只有这些检查全部通过，才将新的上游 SHA 推送至 rewrite，并发布
   GitHub Releases 预发布版本，附件名称固定为 YHongguo-update.apk。
6. 如果构建、测试、签名、校验或 Git 推送失败，不发布升级通知；
   当前可以播放的 rewrite 版本保持不变。

此流程不自动更新手机上的 App、不绕过安装确认，也不自动合并不兼容的上游 API。
失败日志可从 GitHub Actions 查阅并修复，下一次运行将继续检查。

## Android 客户端

打开 App 时检查公开的 GitHub Releases，最多每 12 小时联网一次。
仅处理标记为 upstream-auto-v<versionCode>-<SHA12>、版本号更高并且有
YHongguo-update.apk 附件的发布。设置 → 应用更新 → 立即检查应用更新
可以强制检查。发现更新会提示版本信息，点击后跳转到对应 GitHub Release 页面；
用户自行下载安装，或选择以后再说。

**签名限制**：固定 Release 签名可覆盖此前使用同一证书的正式版。
GitHub Actions 临时 Debug 签名或其他开发版不能直接覆盖安装签名不同的正式版。
首次切换正式版前请备份本地数据；不提供静默安装。

## 前置配置

必须事先在仓库 Settings → Secrets and variables → Actions 配置：

- HG_RELEASE_KEYSTORE_BASE64
- HG_RELEASE_STORE_PASSWORD
- HG_RELEASE_KEY_ALIAS
- HG_RELEASE_KEY_PASSWORD

签名证书必须与现有 Release 证书指纹匹配；密钥不能进入 Git 仓库。
若 GitHub Actions 的默认令牌不能推送 rewrite 或创建 Release，
需在仓库 Actions 权限中启用 **Read and write permissions**（或让
有写权限的 GitHub App Token 执行发布步骤）。

构建通过只代表源代码兼容，不能证明官方服务端始终接受匿名播放签名。

## 2026-11-25 兼容性修订

- 保留 v0.4.1 固定版本及 V5 主路径。对现有旧版接口采用 PR #25 的有限兼容修订并校验回归断言，不增加播放路径。
- 签名拒绝、验证要求、保护内容和签名组件不可用分别报告，明确拒绝不得继续自动重试或回退。
- CI 不使用真实账号、播放地址或绕过访问限制来验证在线服务；线上故障仍需设备日志与有权访问内容的实际播放验证。

## 多项目审查与官网播放入口

每天在候选构建前记录七个相关项目近期播放修复及待合并 PR。只读取元数据，不执行其他项目的脚本，也不自动将 Fork 推入正式构建。开心果 v0.4.1-kx.4 的提交 4c0e09faed45ba2aa1df849660dd4a3d6a85de73 相对于当前主线主要包含 Video Tab 回退与 PR #25；V8 已覆盖相同方向，但实现不同，未取得独立实机播放证据，故不盲目替换 SDK。

播放失败页新增“在红果官网观看”，用户主动打开官方剧集页面，并自行选择剧集、完成登录或授权。成功打开网页后停止本次周期重试；不改变正常 Media3 播放或自动下载网站媒体。网页是否允许观看由官网决定；本入口不是原生播放修复。

受保护模型缺少有效授权信息时，不再把缺少内容密钥当成通用网络故障并自动回退。

## TV 播放兼容性（2026-11-25）

参考 N3urda/hongguoTV 的 e2f9ef97 兼容档位处理思路，仅在已有 SDK 返回的媒体列表内选取可播放的标准编码。过滤 ByteVC2/BVC2/VVC/H.266，保留用户画质上限及当前单文件 MP4 播放器。无兼容档位时明确报告编码错误，不反复请求新地址。模型为空、模型非空但无有效地址、容器不兼容和编码不兼容分开诊断。未移植设备身份、签名或额外取址接口。该改动不保证恢复 HTTP 200/0B，必须以实际合法播放验收为准。
