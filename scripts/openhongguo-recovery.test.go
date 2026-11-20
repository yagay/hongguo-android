package sdk_test

// This file is copied into a pinned upstream source tree by the build script.
// All requests are served by an in-process fake server; no live credentials,
// signed media URLs or upstream network are used during tests.
import (
    "context"
    "encoding/json"
    "errors"
    "net/http"
    "testing"

    "github.com/beilunyang/openhongguo/internal/protocol"
    "github.com/beilunyang/openhongguo/sdk"
)

const recoveryEpisode = "7100000000000201"
const otherEpisode = "7100000000000202"

func tabFixture(t *testing.T, episode string) []byte {
    t.Helper()
    var original struct {
        Data map[string]json.RawMessage `json:"data"`
    }
    if err := json.Unmarshal(fixture(t, "clear-playback.json"), &original); err != nil {
        t.Fatal(err)
    }
    b, err := json.Marshal(map[string]any{
        "code": 0,
        "data": map[string]any{
            "video_model_data": map[string]any{
                episode: map[string]json.RawMessage{
                    "video_model": original.Data["video_model"],
                },
            },
        },
    })
    if err != nil {
        t.Fatal(err)
    }
    return b
}

func TestV5EmptyRecoversWithSignedSameIdentityGet(t *testing.T) {
    var v5Requests, tabRequests, registerRequests, legacyRequests int
    var postedDevice string
    handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("Content-Type", "application/json")
        switch r.URL.Path {
        case protocol.PathRegister:
            registerRequests++
            w.Header().Set("Set-Cookie", "ttreq=fixture-ticket; Path=/")
            _, _ = w.Write(fixture(t, "register-response.json"))
        case protocol.PathPlaybackV5:
            v5Requests++
            postedDevice = r.URL.Query().Get("device_id")
            if r.Method != "POST" {
                t.Error("V5 path must be POST")
            }
            // 200 + empty body: the intermittent real-world symptom.
        case protocol.PathPlaybackVideoTab:
            tabRequests++
            if r.Method != "GET" || r.URL.Query().Get("video_id") != recoveryEpisode ||
                r.URL.Query().Get("device_id") != postedDevice || postedDevice == "" {
                t.Errorf("recovery must use the same episode and registered identity")
            }
            if r.Header.Get("x-medusa") == "" || r.Header.Get("x-helios") == "" {
                t.Error("GET recovery was not V5-signed")
            }
            if r.Header.Get("x-ss-stub") != "" || r.Header.Get("Content-Type") != "" ||
                r.Header.Get("Cookie") != "" {
                t.Error("GET recovery must not reuse a signed POST body or guest cookies")
            }
            _, _ = w.Write(tabFixture(t, recoveryEpisode))
        case protocol.PathPlayback:
            legacyRequests++
        default:
            http.NotFound(w, r)
        }
    })
    client, _ := resetClient(t, handler, sdk.Int(0))
    playback, err := client.ResolvePlayback(context.Background(), recoveryEpisode, sdk.PlaybackOptions{})
    if err != nil || playback == nil || len(playback.Streams) != 2 {
        t.Fatalf("recovery model = %#v, err = %v", playback, err)
    }
    if registerRequests != 1 || v5Requests != 2 || tabRequests != 1 || legacyRequests != 0 {
        t.Fatalf("registration=%d v5=%d tab=%d legacy=%d", registerRequests, v5Requests, tabRequests, legacyRequests)
    }
}

func TestV5RecoveryNeverAcceptsAnotherEpisode(t *testing.T) {
    var tabRequests, legacyRequests int
    handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        switch r.URL.Path {
        case protocol.PathRegister:
            w.Header().Set("Set-Cookie", "ttreq=fixture-ticket; Path=/")
            _, _ = w.Write(fixture(t, "register-response.json"))
        case protocol.PathPlaybackV5:
            // V5 response deliberately empty.
        case protocol.PathPlaybackVideoTab:
            tabRequests++
            _, _ = w.Write(tabFixture(t, otherEpisode))
        case protocol.PathPlayback:
            legacyRequests++
        default:
            http.NotFound(w, r)
        }
    })
    client, _ := resetClient(t, handler, sdk.Int(0))
    pb, err := client.ResolvePlayback(context.Background(), recoveryEpisode, sdk.PlaybackOptions{})
    if pb != nil || !errors.Is(err, sdk.ErrAnonymousLimited) || tabRequests != 1 || legacyRequests != 0 {
        t.Fatalf("cross-episode recovery must fail closed: model=%#v err=%v tab=%d legacy=%d",
            pb, err, tabRequests, legacyRequests)
    }
}

func TestV5RecoveryHonoursExplicitRateLimit(t *testing.T) {
    var tabRequests, legacyRequests int
    handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        switch r.URL.Path {
        case protocol.PathRegister:
            w.Header().Set("Set-Cookie", "ttreq=fixture-ticket; Path=/")
            _, _ = w.Write(fixture(t, "register-response.json"))
        case protocol.PathPlaybackV5:
        case protocol.PathPlaybackVideoTab:
            tabRequests++
            w.WriteHeader(http.StatusTooManyRequests)
        case protocol.PathPlayback:
            legacyRequests++
        default:
            http.NotFound(w, r)
        }
    })
    client, _ := resetClient(t, handler, sdk.Int(0))
    pb, err := client.ResolvePlayback(context.Background(), recoveryEpisode, sdk.PlaybackOptions{})
    if pb != nil || !errors.Is(err, sdk.ErrRateLimited) || tabRequests != 1 || legacyRequests != 0 {
        t.Fatalf("rate limit must stop fallback: model=%#v err=%v tab=%d legacy=%d",
            pb, err, tabRequests, legacyRequests)
    }
}

func TestV5EmptyWithRetryAfterDoesNotProbeOrRotate(t *testing.T) {
    var tabRequests, registerRequests, legacyRequests int
    handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        switch r.URL.Path {
        case protocol.PathRegister:
            registerRequests++
            w.Header().Set("Set-Cookie", "ttreq=fixture-ticket; Path=/")
            _, _ = w.Write(fixture(t, "register-response.json"))
        case protocol.PathPlaybackV5:
            w.Header().Set("Retry-After", "120")
            // HTTP 200 with empty body but an explicit backoff hint.
        case protocol.PathPlaybackVideoTab:
            tabRequests++
        case protocol.PathPlayback:
            legacyRequests++
        default:
            http.NotFound(w, r)
        }
    })
    client, _ := resetClient(t, handler, nil)
    playback, err := client.ResolvePlayback(context.Background(), recoveryEpisode, sdk.PlaybackOptions{})
    if playback != nil || !errors.Is(err, sdk.ErrRateLimited) ||
        tabRequests != 0 || legacyRequests != 0 || registerRequests != 1 {
        t.Fatalf("Retry-After must stop all recovery: model=%#v err=%v tab=%d legacy=%d regs=%d",
            playback, err, tabRequests, legacyRequests, registerRequests)
    }
}
