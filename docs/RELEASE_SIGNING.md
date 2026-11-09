# 红果 Android · Release 签名与构建

本分支应用 ID：`com.yagay.hongguo`，Release 使用 **单独 RSA-4096 / SHA-256 PKCS#12 签名证书**。Android 26+ 可安装。

## 签名证书

- Keystore 类型：`PKCS12`
- Alias：`hongguo-release`
- 签名证书 SHA-256 指纹：

```
6B:DA:27:91:0D:85:36:0F:D9:30:4D:32:EE:CB:AC:A6:24:DA:8B:4F:61:7F:EF:B3:08:59:5E:CA:1B:BC:68:CF
```

**Keystore 私钥及密码不在本仓库。** 应保存在独立安全备份中。此证书用于后续同包名 APK 升级，丢失后无法以相同签名更新已安装的非 Play 商店应用。绝不可把 .p12 文件、base64 文件和口令提交到仓库。

## GitHub Actions：Release APK

1. 将已交付的私有签名包解压并安全备份。
2. 在仓库 **Settings → Secrets and variables → Actions → New repository secret** 配置四项 Secrets：
   - `HG_RELEASE_KEYSTORE_BASE64`：`hongguo-release.base64.txt` 的完整内容（不要加引号）。
   - `HG_RELEASE_STORE_PASSWORD`：见私有 `PRIVATE-GitHub-Secrets.txt`。
   - `HG_RELEASE_KEY_ALIAS`：`hongguo-release`。
   - `HG_RELEASE_KEY_PASSWORD`：见私有 `PRIVATE-GitHub-Secrets.txt`。
3. 打开 **Actions → Native rewrite Android build → Run workflow**，选择 `rewrite-web-native`，勾选 **Build signed Release APK**。
4. 运行时先执行 Debug 单元测试，再编译 Release、校验 APK 签名、证书指纹和包名。
5. 在 workflow 的 **Artifacts** 下载 `hongguo-signed-release-<run_number>`。

日常 push 仍只运行 Debug CI；手动触发但不勾选时也只构建 Debug。缺少 Secrets、密钥文件、证书不匹配或 APK 未签名将使 Release 工作流失败，不会发布未签名安装包。

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
