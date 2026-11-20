#!/usr/bin/env python3
"""Apply a narrowly-scoped, fail-closed playback recovery patch to official v0.4.0.

The patch lives outside the vendored source: the build script clones a pinned,
auditable upstream commit, then applies this patch before building its AAR.
It does not modify discovery/account APIs, the Android media player or DRM.
"""
from pathlib import Path
import sys


def change(path: Path, old: str, new: str) -> None:
    src = path.read_text(encoding="utf-8")
    if src.count(old) != 1:
        raise SystemExit(f"Upstream changed: refusing ambiguous patch for {path}")
    path.write_text(src.replace(old, new), encoding="utf-8")


def main(root: Path) -> None:
    protocol = root / "internal/protocol/playback_v5.go"
    change(protocol, 'PathPlaybackV5      = "/novel/player/multi_video_model/player/v1"',
           'PathPlaybackV5      = "/novel/player/multi_video_model/player/v1"\n'
           '\tPathPlaybackVideoTab = "/reading/bookapi/video_tab/multi_video_model/v1/"')

    media = root / "sdk/media.go"
    old = """\t\tif err != nil {
\t\t\tif !errors.Is(err, ErrAnonymousLimited) && !playbackRouteUnavailable(err) {
\t\t\t\treturn nil, err
\t\t\t}
\t\t\tv5Err := err
"""
    new = """\t\tif err != nil {
\t\t\t// V5 HTTP 200/empty is not proof of rate limiting. Try one
\t\t\t// differently signed read-only route with the SAME registered
\t\t\t// identity before the SDK considers a device reset.
\t\t\tif errors.Is(err, ErrAnonymousLimited) {
\t\t\t\trecovered, recoveryErr := c.recoverPlaybackVideoTab(ctx, p, episodeID)
\t\t\t\tif recoveryErr == nil {
\t\t\t\t\tif recovered.Protected {
\t\t\t\t\t\treturn recovered, ErrDRMProtected
\t\t\t\t\t}
\t\t\t\t\treturn recovered, nil
\t\t\t\t}
\t\t\t\tif ctxErr := ctx.Err(); ctxErr != nil {
\t\t\t\t\treturn nil, ctxErr
\t\t\t\t}
\t\t\t\t// Explicit refusal/backoff must never be masked or bypassed.
\t\t\t\tif errors.Is(recoveryErr, ErrRateLimited) ||
\t\t\t\t\terrors.Is(recoveryErr, ErrAuthenticationRequired) ||
\t\t\t\t\terrors.Is(recoveryErr, ErrChallengeRequired) ||
\t\t\t\t\terrors.Is(recoveryErr, ErrDeviceRejected) {
\t\t\t\t\treturn nil, recoveryErr
\t\t\t\t}
\t\t\t\t// Invalid/empty/unsupported recovery response: retain the
\t\t\t\t// useful primary error. Do not hammer the obsolete POST.
\t\t\t\treturn nil, err
\t\t\t}
\t\t\tif !playbackRouteUnavailable(err) {
\t\t\t\treturn nil, err
\t\t\t}
\t\t\tv5Err := err
"""
    change(media, old, new)
    helper = root / "sdk/playback_video_tab_recovery.go"
    if helper.exists():
        raise SystemExit(f"Unexpected upstream file {helper}")
    helper.write_text(r'''package sdk

import (
    "bytes"
    "context"
    "encoding/json"
    "fmt"

    "github.com/beilunyang/openhongguo/internal/protocol"
    "github.com/beilunyang/openhongguo/sdk/model"
)

// recoverPlaybackVideoTab is intentionally only invoked after an empty
// V5 model. It uses the SAME registered profile and the SAME V5 signer,
// not another independent device/cookie/signature implementation.
func (c *Client) recoverPlaybackVideoTab(ctx context.Context, p *DeviceProfile, episodeID string) (*model.Playback, error) {
    pairs := protocol.PlaybackV5Pairs(c.identity(p))
    for index := range pairs {
        switch pairs[index][0] {
        case "version_code", "manifest_version_code", "update_version_code", "pv_player":
            pairs[index][1] = protocol.VersionCode
        case "version_name":
            pairs[index][1] = protocol.VersionName
        }
    }
    pairs = append(pairs,
        [2]string{"video_id", episodeID},
        [2]string{"video_id_type", "0"},
        [2]string{"device_level", "1"},
        [2]string{"need_all_video_definition", "1"},
        [2]string{"need_mp4_align", "0"},
        [2]string{"use_os_player", "0"},
        [2]string{"video_platform", "1024"},
        [2]string{"source", "0"},
        [2]string{"from_video_id", ""},
    )
    ticket := c.clock().UnixMilli()
    query, headers, err := c.playbackSigner.Sign(pairs, ticket, nil, nil)
    if err != nil {
        return nil, err
    }
    headers = anonymousHeaders(c, p, ticket, headers)
    headers["User-Agent"] = protocol.UserAgent(c.deviceOf(p))
    headers["Accept"] = "application/json"
    headers["Accept-Language"] = "zh-CN,zh;q=0.9"
    // The TV recovery route does not send signed POST-body or session
    // cookies; do not transfer the stored guest cookie to this GET.
    delete(headers, "Cookie")
    delete(headers, "Content-Type")
    delete(headers, "x-ss-stub")

    origin := c.apiOrigin
    if origin == "https://"+protocol.APIHost {
        origin = "https://api5-normal-sinfonlineb.fqnovel.com"
    }
    raw, _, err := c.doRaw(ctx, "GET", origin+protocol.PathPlaybackVideoTab+"?"+query, headers, nil)
    if err != nil {
        return nil, err
    }
    // video_tab nests models under data.video_model_data[episodeId],
    // unlike V5's data["1004"][episodeId]. Never substitute another
    // episode even when the server returns one.
    var response struct {
        Data struct {
            Models map[string]json.RawMessage `json:"video_model_data"`
        } `json:"data"`
    }
    if err := json.Unmarshal(raw, &response); err != nil {
        return nil, fmt.Errorf("video_tab response malformed: %w", err)
    }
    row, ok := response.Data.Models[episodeID]
    if !ok || len(bytes.TrimSpace(row)) == 0 || bytes.Equal(bytes.TrimSpace(row), []byte("null")) {
        return nil, fmt.Errorf("video_tab did not return the requested episode")
    }
    normalized, err := json.Marshal(map[string]any{
        "code": 0,
        "data": map[string]json.RawMessage{episodeID: row},
    })
    if err != nil {
        return nil, err
    }
    return protocol.ParsePlayback(normalized, episodeID)
}
'''.replace('\\`', '`'), encoding="utf-8")
    print("Applied official OpenHongguo V5 same-session video_tab recovery patch")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply-openhongguo-recovery.py <pinned upstream clone>")
    main(Path(sys.argv[1]).resolve())
