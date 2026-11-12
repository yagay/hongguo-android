# Third-party code and implementation references

- Native Hongguo content, signature, playback, MP4 decryption, and read-only
  comment protocols: [tsukiyo-zero/hongguoTV](https://github.com/tsukiyo-zero/hongguoTV),
  GPL-3.0-only. Imported code remains in `com.hongguotv.core`.
- `CommentSignature.kt` states its Argus/Simon design was adapted upstream from
  [tr4cex/TikTok-Encryption](https://github.com/tr4cex/TikTok-Encryption)
  (MIT license); see the source project's attribution.
- Vertical pager and media lifecycle architecture referenced from
  [manjees/compose-reels](https://github.com/manjees/compose-reels)
  (Apache-2.0) and [clown6613/ComposeReels](https://github.com/clown6613/ComposeReels);
  no third-party vertical-feed library is vendored.

No write/comment-post or account-mutation APIs are included in the comment reader.

- [kadaliao/kaixinguo-android](https://github.com/kadaliao/kaixinguo-android)
  (MIT License; Copyright (c) 2026 Kada Liao): the range-based CENC decoder
  and streaming transport adapted into `com.yagay.hongguo.kxg`.
  The required MIT license notice and copyright are retained here; the source
  files were ported only for package/adapter integration. See original LICENSE.
- [kadaliao/openhongguo](https://github.com/kadaliao/openhongguo)
  Android AAR `0.2.1-kx.1`, used by Kaixinguo for its Android signing fix;
  original openhongguo SDK is Apache-2.0 licensed.
