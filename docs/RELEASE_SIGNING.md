# 红果 Android · Release 签名与构建

本分支应用 ID：`com.yagay.hongguo`，Release 使用 **单独 RSA-4096 / SHA-256 PKCS#12 签名证书**。Android 26+ 可安装。

## 签名证书

- Keystore 类型：`PKCS12`
- Alias：`hongguo-release`
- 签名证书 SHA-256 指纹：

```
35:86:FD:60:C0:24:3B:B2:05:F1:27:B3:47:E8:EE:D7:31:1F:32:D3:BA:14:6D:E9:71:30:22:9C:8C:22:09:75
```

**Keystore 私钥及密码不在本仓库。** 应保存在独立安全备份中。此证书用于后续同包名 APK 升级，丢失后无法以相同签名更新已安装的非 Play 商店应用。绝不可把 .p12 文件、base64 文件和口令提交到仓库。

## GitHub Actions：Release APK

1. 将已交付的私有签名包解压并安全备份。
2. 在仓库 **Settings → Secrets and variables → Actions → New repository secret** 配置四项 Secrets：
   - `HG_RELEASE_KEYSTORE_BASE64`：`hongguo-release.base64.txt` 的完整内容（不要加引号）。
   - `HG_RELEASE_STORE_PASSWORD`：见私有 `PRIVATE-GitHub-Secrets.txt`。
   - `HG_RELEASE_KEY_ALIAS`：`hongguo-release`。
   - `HG_RELEASE_KEY_PASSWORD`：见私有 `PRIVATE-GitHub-Secrets.txt`。
3. `rewrite` 是仓库默认分支。手动签名编译可打开 **Actions → Native rewrite Android build → Run workflow**，选择 `signed_release=true`。
4. 编译运行 Debug 单元测试并构建 Release，校验 APK 签名、证书指纹、包名与 16KB 对齐。
5. 在 workflow 的 Artifacts 下载 `hongguo-signed-release-<run_number>`。

此外，**Daily OpenHongguo update and signed APK** 每天检查上游；验证通过后才以同一证书签名并发布 `YHongguo-update.apk` 到 GitHub Releases。日常推送 `rewrite` 仍只运行 Debug CI。缺少 Secrets、密钥文件、证书不匹配或 APK 未签名都会使自动发布失败，不会向本地 App 推送更新。

## 本地构建

设置以下环境变量再运行：

```bash
export HG_RELEASE_STORE_FILE="/absolute/path/to/hongguo-release.p12"
export HG_RELEASE_STORE_PASSWORD="<PRIVATE_PASSWORD>"
export HG_RELEASE_KEY_ALIAS="hongguo-release"
export HG_RELEASE_KEY_PASSWORD="<PRIVATE_PASSWORD>"
./gradlew :app:testDebugUnitTest :app:assembleRelease --build-cache
```

`<PRIVATE_PASSWORD>` 请从私有签名文件读取，不要写入源码、提交历史或构建日志。产物位于 `app/build/outputs/apk/release/app-release.apk`。

**注意：** 包名 `com.yagay.hongguo` 与旧版 `com.hongguo.rewrite` 不同，会作为新的 Android 应用安装；旧版用户数据不会自动迁移。

## 发布前确认

- Release APK 的证书指纹与上方一致；`apksigner verify --verbose --print-certs` 成功。
- `aapt dump badging` 显示 `com.yagay.hongguo`。
- 使用测试机验证首次安装、升级、播放、登录、通知、系统 PiP 和三键/手势沉浸布局。
- 正式发布前评估 `versionCode`（后续每次更新必须严格递增）和 `versionName`，且保留同一签名密钥。
