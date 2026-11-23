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
    # Port the verified 2026-11-20 route from upstream PR #17. The old
    # /player/v1 path returns HTTP 200 with zero bytes, not a 404.
    change(protocol, 'PathPlaybackV5      = "/novel/player/multi_video_model/player/v1"',
           'PathPlaybackV5      = "/novel/player/multi_video_model/v1/"\n'
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

    # HTTP 200 with an empty body and Retry-After is an explicit request to
    # back off. Do not launch a GET probe or rotate devices in that situation.
    client = root / "sdk/client.go"
    change(client,
           """\tif len(bytes.TrimSpace(body)) == 0 {
\t\treturn nil, ErrAnonymousLimited
\t}""",
           """\tif len(bytes.TrimSpace(body)) == 0 {
\t\tfor name, value := range resp.Headers {
\t\t\tif strings.EqualFold(name, "Retry-After") && strings.TrimSpace(value) != "" {
\t\t\t\treturn nil, &explicitRetryAfterLimit{}
\t\t\t}
\t\t}
\t\treturn nil, ErrAnonymousLimited
\t}""")
    # Retry-After is an explicit server instruction, not a recoverable
    # transient network error. It must stop the SDK's internal retry loop too.
    change(client,
           "func retryableInLoop(err error) bool { return Retryable(err) }",
           """func retryableInLoop(err error) bool {
\tvar limit *explicitRetryAfterLimit
\treturn !errors.As(err, &limit) && Retryable(err)
}

// explicitRetryAfterLimit still satisfies errors.Is(ErrRateLimited) for
// the Android SDK while preventing immediate replays of a denied request.
type explicitRetryAfterLimit struct{}
func (*explicitRetryAfterLimit) Error() string { return ErrRateLimited.Error() }
func (*explicitRetryAfterLimit) Unwrap() error { return ErrRateLimited }""")
    helper = root / "sdk/playback_video_tab_recovery.go"
    if helper.exists():
        raise SystemExit(f"Unexpected upstream file {helper}")
    helper.write_text(r'''package sdk

import (
    "bytes"
    "context"
    "encoding/json"
    "fmt"
    "strconv"

    "github.com/beilunyang/openhongguo/internal/protocol"
    "github.com/beilunyang/openhongguo/sdk/model"
)

// recoverPlaybackVideoTab is intentionally only invoked after an empty
// V5 model. It uses the SAME registered profile and the SAME V5 signer,
// not another independent device/cookie/signature implementation.
func (c *Client) recoverPlaybackVideoTab(ctx context.Context, p *DeviceProfile, episodeID string) (*model.Playback, error) {
    // The bodyless video_tab GET has a distinct, ordered parameter template.
    // Do not reuse PlaybackV5Pairs (which contains POST-only fields) or the
    // older protocol.VersionCode: the captured 7.4.3.32 endpoint expects 74332.
    // Ported from upstream PR #18's protocol.VideoTabPairs.
    identity := c.identity(p)
    device := identity.Device.OrDefault()
    version := strconv.Itoa(protocol.PlaybackVersionCode)
    pairs := [][2]string{
        {"aid", protocol.AID}, {"app_name", protocol.AppName},
        {"version_code", version}, {"version_name", protocol.PlaybackVersionName},
        {"channel", device.Channel}, {"device_platform", "android"},
        {"device_type", device.Model}, {"os_version", device.OSVersion},
        {"update_version_code", version}, {"iid", identity.InstallID},
        {"cdid", identity.CDID}, {"device_id", identity.DeviceID},
        {"video_id", episodeID}, {"video_id_type", "0"},
        {"device_level", "1"}, {"need_all_video_definition", "1"},
        {"need_mp4_align", "0"}, {"use_os_player", "0"},
        {"video_platform", "1024"}, {"source", "0"}, {"from_video_id", ""},
    }
    ticket := c.clock().UnixMilli()
    query, headers, err := c.playbackSigner.Sign(pairs, ticket, nil, nil)
    if err != nil {
        return nil, err
    }
    headers = anonymousHeaders(c, p, ticket, headers)
    headers["User-Agent"] = protocol.PlaybackUserAgent(c.deviceOf(p))
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
