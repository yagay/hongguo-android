package com.hongguo.api.service;

import com.hongguo.api.core.PureSign;
import com.hongguo.api.util.Config;
import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Json;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

/** 2026-10-27 播放取链恢复：匿名设备注册 + 无签名播放请求 + 内联片源模型。 */
public class PlaybackSourceTest {

    /** 19 位、超出 double 精度的设备号（合成值，非真实身份）。 */
    private static final String DEVICE_ID = "7432109876543210987";
    private static final String INSTALL_ID = "7432109876543210123";

    /** Spade 自检真值之一，解出合法 content key。 */
    private static final String VALID_SPADE = Base64.getEncoder().encodeToString(Crypto.unhex(
            "93bc1df253ba1bf7618b19c448b806f64d810afc45b715fe5eb925f26cbf12f541ba098282"));

    @BeforeClass public static void initialize() {
        Config.initialize("{\"upstream\":{\"api_host\":\"example.invalid\"}}",
                new File(System.getProperty("java.io.tmpdir"), "hongguo-playback-test").getAbsolutePath(),
                "1234567890123456");
    }

    @Before public void reset() {
        PlaybackDevice.resetForTest(null);
        AppSource.setPlaybackPostForTest(null);
        // 默认让 video_tab 不可用，覆盖旧接口回退路径；需要新接口的用例自行注入。
        AppSource.setPlaybackTabForTest((vid, identity) -> null);
    }

    @After public void restore() {
        AppSource.setPlaybackFallbackForTest(null);
        PlaybackDevice.resetForTest(null);
        AppSource.setPlaybackPostForTest(null);
        AppSource.setPlaybackTabForTest(null);
    }

    // ==================== ttEncrypt 注册请求体 ====================

    @Test public void registrationBodyDecryptsToGzipPayloadWithDigest() throws Exception {
        byte[] random = new byte[32];
        for (int i = 0; i < random.length; i++) random[i] = (byte) i;
        long now = 1_793_000_000_000L;
        byte[] body = PlaybackDevice.buildBody(now, "cdid-fixed", random);

        assertArrayEquals(Crypto.unhex("746305100000"), Arrays.copyOfRange(body, 0, 6));
        assertArrayEquals(random, Arrays.copyOfRange(body, 6, 38));

        byte[] derived = PlaybackDevice.deriveKeyIv(random);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, 0, 16, "AES"),
                new IvParameterSpec(derived, 16, 16));
        byte[] plain = c.doFinal(body, 38, body.length - 38);
        byte[] digest = Arrays.copyOfRange(plain, 0, 64);
        byte[] compressed = Arrays.copyOfRange(plain, 64, plain.length);
        assertArrayEquals(MessageDigest.getInstance("SHA-512").digest(compressed), digest);

        Map<String, Object> payload = Json.parseObject(new String(gunzip(compressed), StandardCharsets.UTF_8));
        assertEquals("ss_app_log", payload.get("magic_tag"));
        assertEquals(now, Json.optLong(payload.get("_gen_time"), 0));
        Map<String, Object> header = Json.optObj(payload.get("header"));
        assertEquals("cdid-fixed", header.get("cdid"));
        assertEquals(8662L, Json.optLong(header.get("aid"), 0));
        assertEquals("com.phoenix.read", header.get("package"));
        assertFalse("注册请求不得携带账号或 Cookie 字段",
                header.containsKey("cookie") || header.containsKey("user_id") || header.containsKey("sessionid"));
    }

    @Test public void registrationKeyMatchesDoubleSha512OverSalt() throws Exception {
        byte[] random = new byte[32];
        Arrays.fill(random, (byte) 7);
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        byte[] inner = sha.digest(random);
        sha.update(inner);
        sha.update(Crypto.unhex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524"
                + "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"));
        assertArrayEquals(Arrays.copyOf(sha.digest(), 32), PlaybackDevice.deriveKeyIv(random));
    }

    @Test public void registrationHeadersCarryStubAndNoSignature() throws Exception {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = PlaybackDevice.headers(body, 123L);
        assertEquals(Crypto.md5HexUpper(body), h.get("x-ss-stub"));
        assertEquals("application/octet-stream;tt-data=a", h.get("Content-Type"));
        assertEquals("123", h.get("x-ss-req-ticket"));
        for (String k : h.keySet()) assertFalse(k, isSignatureHeader(k));
    }

    // ==================== 注册响应解析 ====================

    @Test public void responseIdsKeepNineteenDigitsExactly() throws Exception {
        Map<String, Object> j = Json.parseObject("{\"device_id\":" + DEVICE_ID
                + ",\"install_id\":" + INSTALL_ID + ",\"code\":0}");
        PlaybackDevice.Identity id = PlaybackDevice.parse(j, "c", "");
        assertEquals(DEVICE_ID, id.deviceId);
        assertEquals(INSTALL_ID, id.iid);
        assertEquals("c", id.cdid);
        assertFalse(id.toString().contains(DEVICE_ID));
    }

    @Test public void responseIdsPreferStringFields() {
        Map<String, Object> j = Json.obj();
        j.put("device_id", 1.0e18);           // 已丢精度的浮点，单独出现时必须拒绝
        j.put("device_id_str", DEVICE_ID);
        assertEquals(DEVICE_ID, PlaybackDevice.responseId(j, "device_id"));
    }

    @Test public void responseIdsRejectZeroFloatsAndJunk() {
        Object[] bad = {0L, "0", "000", 1.0e18, -5L, "-5", "12a", "", " ", "1".repeat(31), true};
        for (Object v : bad) {
            Map<String, Object> j = Json.obj();
            j.put("install_id", v);
            assertEquals(String.valueOf(v), "", PlaybackDevice.responseId(j, "install_id"));
        }
        Map<String, Object> big = Json.obj();
        big.put("install_id", new BigInteger("98765432109876543210"));
        assertEquals("98765432109876543210", PlaybackDevice.responseId(big, "install_id"));
    }

    @Test(expected = IOException.class)
    public void registrationRejectedByBusinessCode() throws Exception {
        PlaybackDevice.parse(Json.parseObject("{\"code\":1,\"device_id_str\":\"" + DEVICE_ID
                + "\",\"install_id_str\":\"" + INSTALL_ID + "\"}"), "c", "");
    }

    // ==================== 进程内共享与失败冷却 ====================

    @Test public void everyCallRegistersItsOwnIdentity() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> {
            calls.incrementAndGet();
            assertEquals(PlaybackDevice.REGISTER_URL, url);
            return okReply();
        });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<PlaybackDevice.Identity>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) results.add(pool.submit(PlaybackDevice::fresh));
            for (Future<PlaybackDevice.Identity> f : results) assertEquals(DEVICE_ID, f.get(5, TimeUnit.SECONDS).deviceId);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(4, calls.get());
    }

    @Test public void consecutiveEpisodesDoNotReuseAnIdentity() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> {
            registrations.incrementAndGet();
            return okReply();
        });
        List<PlaybackDevice.Identity> used = new ArrayList<>();
        AppSource.setPlaybackTabForTest((vid, identity) -> {
            used.add(identity);
            throw new IllegalStateException("reached model request");
        });
        for (int i = 0; i < 2; i++) {
            try {
                AppSource.fetch("7000000000000000001", "auto");
                fail();
            } catch (IllegalStateException expected) {
                assertEquals("reached model request", expected.getMessage());
            }
        }
        assertEquals(2, registrations.get());
        assertNotSame(used.get(0), used.get(1));
    }

    @Test public void failureIsReusedOnlyDuringCooldown() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        boolean[] healthy = {false};
        PlaybackDevice.resetForTest((url, headers, body) -> {
            calls.incrementAndGet();
            if (!healthy[0]) throw new IOException("offline");
            return okReply();
        });
        IOException first = expectRegistrationFailure();
        IOException second = expectRegistrationFailure();
        assertSame("冷却期内复用同一失败，不重复注册", first, second);
        assertEquals(1, calls.get());
        assertTrue(first.getMessage().contains("匿名播放设备注册失败"));
        assertFalse("失败消息不得带出底层细节", first.getMessage().contains("offline"));

        healthy[0] = true;
        PlaybackDevice.advanceClockForTest(PlaybackDevice.FAILURE_COOLDOWN_MS + 1);
        assertEquals(DEVICE_ID, PlaybackDevice.fresh().deviceId);
        assertEquals(2, calls.get());
    }

    @Test public void emptyOrNonJsonRegistrationIsFailure() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> new PlaybackDevice.Reply(200, ""));
        expectRegistrationFailure();
        PlaybackDevice.resetForTest((url, headers, body) -> new PlaybackDevice.Reply(503, "{}"));
        expectRegistrationFailure();
    }

    // ==================== 故障阶段诊断（不能泄露服务器内容或标识） ====================

    @Test public void registrationFailuresExposeSafeCodesWithoutResponseBody() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> new PlaybackDevice.Reply(403, "Bearer secret-token"));
        try {
            PlaybackDevice.fresh();
            fail("403 must fail");
        } catch (PlaybackDevice.RegistrationException failure) {
            assertEquals("DEVICE_REGISTER_HTTP", failure.getCode());
            assertEquals(403, failure.getHttpStatus());
            assertFalse(failure.getMessage().contains("secret-token"));
            assertFalse(failure.getMessage().contains(REGISTER_SECRET_FOR_TEST));
        }

        PlaybackDevice.resetForTest((url, headers, body) -> new PlaybackDevice.Reply(200, ""));
        assertEquals("DEVICE_REGISTER_EMPTY", registrationCode());

        PlaybackDevice.resetForTest((url, headers, body) ->
                new PlaybackDevice.Reply(200, "not-json?token=secret-token"));
        assertEquals("DEVICE_REGISTER_INVALID_JSON", registrationCode());

        PlaybackDevice.resetForTest((url, headers, body) ->
                new PlaybackDevice.Reply(200, "{\"code\":0,\"device_id\":0,\"install_id\":0}"));
        assertEquals("DEVICE_REGISTER_INVALID_IDS", registrationCode());

        PlaybackDevice.resetForTest((url, headers, body) ->
                new PlaybackDevice.Reply(200, "{\"code\":101,\"device_id\":0}"));
        assertEquals("DEVICE_REGISTER_REJECTED", registrationCode());
    }

    private static final String REGISTER_SECRET_FOR_TEST = "https://private.example.invalid/path?cookie=xyz";

    private static String registrationCode() throws IOException {
        try {
            PlaybackDevice.fresh();
            fail("Expected typed registration failure");
            return "";
        } catch (PlaybackDevice.RegistrationException failure) {
            assertFalse(failure.getMessage().contains("token="));
            assertFalse(failure.getMessage().contains("cookie="));
            return failure.getCode();
        }
    }

    @Test public void registrationTransportFailuresRemainClassified() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> {
            throw new java.net.UnknownHostException("token.private.invalid");
        });
        assertEquals("DEVICE_REGISTER_DNS_FAILED", registrationCode());

        PlaybackDevice.resetForTest((url, headers, body) -> {
            throw new java.net.SocketTimeoutException("private.example");
        });
        assertEquals("DEVICE_REGISTER_TIMEOUT", registrationCode());

        PlaybackDevice.resetForTest((url, headers, body) -> {
            throw new javax.net.ssl.SSLException("certificate https://private.example");
        });
        assertEquals("DEVICE_REGISTER_TLS_FAILED", registrationCode());
    }

    @Test public void registrationDetailsReachAndroidDiagnosticWithoutLeakingSecrets() {
        PlaybackDevice.RegistrationException error =
                new PlaybackDevice.RegistrationException("DEVICE_REGISTER_HTTP", 429, -1);
        com.hongguo.android.PlaybackDiagnostic mapped =
                com.hongguo.android.PlaybackDiagnostic.Companion.fromPreparationFailure(error);
        assertEquals("DEVICE_REGISTER_HTTP", mapped.getCode());
        assertEquals("device_register", mapped.getStage());
        assertEquals(Integer.valueOf(429), mapped.getHttpStatus());
        assertTrue(mapped.safeReport().contains("http_status=429"));
        assertFalse(mapped.safeReport().contains("private.example"));
    }

    @Test public void registrationDecodesCompressedOrAlreadyDecodedResponses() throws Exception {
        String json = "{\"code\":0,\"device_id_str\":\"" + DEVICE_ID
                + "\",\"install_id_str\":\"" + INSTALL_ID + "\"}";
        byte[] original = json.getBytes(StandardCharsets.UTF_8);
        assertEquals(json, PlaybackDevice.decodeRegistrationResponse(original, "gzip"));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(buffer)) {
            gzip.write(original);
        }
        assertEquals(json, PlaybackDevice.decodeRegistrationResponse(buffer.toByteArray(), "gzip"));
    }

    @Test public void registrationRejectsOversizedDecompressedResponses() throws Exception {
        byte[] oversized = new byte[129 * 1024];
        Arrays.fill(oversized, (byte) 'x');
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(buffer)) {
            gzip.write(oversized);
        }
        try {
            PlaybackDevice.decodeRegistrationResponse(buffer.toByteArray(), "gzip");
            fail("Decompression must be bounded");
        } catch (PlaybackDevice.RegistrationException expected) {
            assertEquals("DEVICE_REGISTER_RESPONSE_TOO_LARGE", expected.getCode());
        }
    }

    @Test public void registrationOkHttpActuallySendsBinaryBodyAndHeaders() throws Exception {
        okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        try {
            server.enqueue(new okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "install_id=test123; Path=/")
                    .setBody(okReply().text));
            server.start();
            byte[] payload = new byte[]{0x74, 0x63, 0x05, 0x10, 0x00, 0x00, (byte) 0xff, 0x01};
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            headers.put("Content-Type", "application/octet-stream;tt-data=a");
            headers.put("x-ss-stub", "ABCD1234");
            PlaybackDevice.Reply response =
                    RegistrationHttp.okHttpPost(server.url("/service/2/device_register/").toString(), headers, payload);
            assertEquals(200, response.status);
            assertTrue(response.text.contains(DEVICE_ID));
            assertEquals(1, response.setCookies.size());
            okhttp3.mockwebserver.RecordedRequest request =
                    server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertEquals("application/octet-stream;tt-data=a", request.getHeader("Content-Type"));
            assertEquals("ABCD1234", request.getHeader("x-ss-stub"));
            assertArrayEquals(payload, request.getBody().readByteArray());
        } finally {
            server.shutdown();
        }
    }

    @Test public void registrationHttpResponseNeverTriggersSecondTransport() throws Exception {
        okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        try {
            server.enqueue(new okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(403).setBody("Do not export server errors: Bearer secret"));
            server.start();
            AtomicInteger fallbackCalls = new AtomicInteger();
            PlaybackDevice.Reply response = RegistrationHttp.postWithFallback(
                    RegistrationHttp::okHttpPost,
                    (url, headers, body) -> {
                        fallbackCalls.incrementAndGet();
                        throw new AssertionError("HTTP error is not a transport failure");
                    },
                    server.url("/service/2/device_register/").toString(),
                    java.util.Collections.emptyMap(), new byte[]{3});
            assertEquals(403, response.status);
            assertEquals("", response.text);
            assertEquals(0, fallbackCalls.get());
        } finally {
            server.shutdown();
        }
    }

    @Test public void alternateOfficialRegistrationUsesJsonAndNoAccountCredentials() throws Exception {
        okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        try {
            server.enqueue(new okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "install_id=abc; Path=/")
                    .setBody(okReply().text));
            server.start();
            long now = 1_793_000_000_123L;
            String cdid = "synthetic-cdid";
            PlaybackDevice.Reply reply = RegistrationHttp.alternateJsonRegisterAt(
                    server.url("/service/2/device_register/").toString(), now, cdid, "test/1");
            assertEquals(200, reply.status);
            assertTrue(reply.text.contains(DEVICE_ID));
            assertEquals(1, reply.setCookies.size());
            okhttp3.mockwebserver.RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"));
            assertEquals("test/1", request.getHeader("User-Agent"));
            assertNull(request.getHeader("Cookie"));
            assertNull(request.getHeader("x-ss-stub"));
            assertNull(request.getHeader("Authorization"));
            assertEquals("8662", request.getRequestUrl().queryParameter("aid"));
            assertEquals("73932", request.getRequestUrl().queryParameter("version_code"));
            assertEquals(String.valueOf(now), request.getRequestUrl().queryParameter("_rticket"));
            assertNull(request.getRequestUrl().queryParameter("iid"));
            assertNull(request.getRequestUrl().queryParameter("device_id"));
            Map<String, Object> payload = Json.parseObject(request.getBody().readUtf8());
            assertEquals(now, Json.optLong(payload.get("_gen_time"), 0L));
            Map<String, Object> header = Json.optObj(payload.get("header"));
            assertEquals(cdid, header.get("cdid"));
            assertEquals("com.phoenix.read", header.get("package"));
            assertFalse(header.containsKey("account_cookie"));
            assertFalse(header.containsKey("device_id"));
        } finally {
            server.shutdown();
        }
    }

    @Test public void alternateRegistrationReportsSafeHttpErrorWithoutLeakingBody() throws Exception {
        okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        try {
            server.enqueue(new okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(429)
                    .setBody("private_server_response?token=secret"));
            server.start();
            PlaybackDevice.Reply reply = RegistrationHttp.alternateJsonRegisterAt(
                    server.url("/service/2/device_register/").toString(),
                    1_793_000_000_123L, "synthetic-cdid", "test/1");
            assertEquals(429, reply.status);
            assertEquals("", reply.text);
        } finally {
            server.shutdown();
        }
    }

    @Test public void registrationPreferredTransportSuccessDoesNotRetry() throws Exception {
        AtomicInteger primary = new AtomicInteger(), fallback = new AtomicInteger();
        PlaybackDevice.Reply reply = RegistrationHttp.postWithFallback(
                (url, headers, body) -> {
                    primary.incrementAndGet();
                    return okReply();
                },
                (url, headers, body) -> {
                    fallback.incrementAndGet();
                    throw new AssertionError("No fallback after HTTP response");
                },
                PlaybackDevice.REGISTER_URL, java.util.Collections.emptyMap(), new byte[]{1, 2, 3});
        assertEquals(200, reply.status);
        assertEquals(1, primary.get());
        assertEquals(0, fallback.get());
    }

    @Test public void registrationFallbackPreservesExactBodyHeadersAndHost() throws Exception {
        AtomicInteger preferredCount = new AtomicInteger(), fallbackCount = new AtomicInteger();
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/octet-stream;tt-data=a");
        headers.put("x-ss-stub", "DEADBEEF");
        byte[] body = new byte[]{1, 2, 3};
        PlaybackDevice.Reply reply = RegistrationHttp.postWithFallback(
                (url, h, b) -> {
                    preferredCount.incrementAndGet();
                    assertEquals(PlaybackDevice.REGISTER_URL, url);
                    assertSame(headers, h);
                    assertSame(body, b);
                    throw new java.net.ConnectException("private-token.secret");
                },
                (url, h, b) -> {
                    fallbackCount.incrementAndGet();
                    assertEquals(PlaybackDevice.REGISTER_URL, url);
                    assertSame(headers, h);
                    assertSame(body, b);
                    return okReply();
                }, PlaybackDevice.REGISTER_URL, headers, body);
        assertEquals(200, reply.status);
        assertEquals(1, preferredCount.get());
        assertEquals(1, fallbackCount.get());
    }

    @Test public void registrationDoubleTransportFailureHasRedactedIndependentReasons() throws Exception {
        try {
            RegistrationHttp.postWithFallback(
                    (url, h, body) -> {
                        throw new java.net.ConnectException("https://secret.primary/?token=abc");
                    },
                    (url, h, body) -> {
                        throw new java.io.EOFException("Authorization: Bearer secret-secondary");
                    }, PlaybackDevice.REGISTER_URL, java.util.Collections.emptyMap(), new byte[]{8});
            fail("Expected both transports to fail");
        } catch (PlaybackDevice.RegistrationException failure) {
            assertEquals("DEVICE_REGISTER_EOF", failure.getCode());
            assertEquals("DEVICE_REGISTER_CONNECT_FAILED", failure.getPrimaryTransportFailure());
            assertEquals("DEVICE_REGISTER_EOF", failure.getFallbackTransportFailure());
            String report = com.hongguo.android.PlaybackDiagnostic.Companion
                    .fromPreparationFailure(failure).safeReport();
            assertTrue(report.contains("primary_transport=DEVICE_REGISTER_CONNECT_FAILED"));
            assertTrue(report.contains("fallback_transport=DEVICE_REGISTER_EOF"));
            assertFalse(report.contains("secret.primary"));
            assertFalse(report.contains("secret-secondary"));
        }
    }

    @Test public void registrationProtocolResponseFailureDoesNotRetryTransport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try {
            RegistrationHttp.postWithFallback(
                    (url, h, body) -> {
                        throw new PlaybackDevice.RegistrationException("DEVICE_REGISTER_INVALID_JSON", 200, -1);
                    },
                    (url, h, body) -> {
                        calls.incrementAndGet();
                        return okReply();
                    }, PlaybackDevice.REGISTER_URL, java.util.Collections.emptyMap(), new byte[]{8});
            fail("Invalid JSON must remain terminal");
        } catch (PlaybackDevice.RegistrationException failure) {
            assertEquals("DEVICE_REGISTER_INVALID_JSON", failure.getCode());
        }
        assertEquals(0, calls.get());
    }

    // ==================== 签名播放请求 ====================

    @Test public void playbackRequestIsSignedWithTheRegisteredIdentityAndCookie() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> new PlaybackDevice.Reply(200, okReply().text,
                Arrays.asList("install_id=11; Path=/; Domain=fqnovel.com", "ttreq=1$abc; Path=/", "other=secret; Path=/")));
        PlaybackDevice.Identity id = PlaybackDevice.fresh();
        assertEquals("install_id=11; ttreq=1$abc", id.cookie);
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String base = "https://" + AppSource.RETRY_HOST + "/novel/player/multi_video_model/v1/";
        AppSource.SignedPlayback post = AppSource.signPlayback(base, id, new java.util.LinkedHashMap<>(), body, 1_793_000_000_123L);

        String query = post.url.substring(post.url.indexOf('?') + 1);
        assertTrue(query.startsWith("iid=" + INSTALL_ID + "&device_id=" + DEVICE_ID + "&"));
        assertTrue(query.contains("&cdid=" + id.cdid + "&_rticket="));
        assertTrue(query.contains("version_code=73932") && query.contains("version_name=7.3.9.32") && query.contains("pv_player=73932"));
        assertFalse(query.contains("71332"));
        assertTrue("resolution 的 * 不编码", query.contains("resolution=1280*2772"));
        assertFalse(query.contains("&ts="));
        assertEquals(1, count(query, "device_id="));
        assertEquals("只接受分支 0", 0, PureSign.branchOf(post.url, body, 1_793_000_000L));
        assertEquals(post.headers.get("x-ss-req-ticket"), query.substring(query.indexOf("_rticket=") + 9));
        for (String name : new String[]{"x-gorgon", "x-khronos", "x-ladon", "x-argus", "x-helios", "x-medusa"}) {
            assertFalse(name, post.headers.getOrDefault(name, "").isEmpty());
        }
        assertEquals(AppSource.PLAYBACK_UA, post.headers.get("User-Agent"));
        assertEquals("install_id=11; ttreq=1$abc", post.headers.get("Cookie"));
        assertEquals("application/json; charset=utf-8", post.headers.get("Content-Type"));
        assertEquals(Crypto.md5HexUpper(body), post.headers.get("x-ss-stub"));

        AppSource.SignedPlayback get = AppSource.signPlayback(base, id, AppSource.tabQuery("7600000000000000001"), null, 1_793_000_000_123L);
        assertFalse(get.headers.containsKey("x-ss-stub"));
        assertFalse(get.headers.containsKey("Content-Type"));
        assertEquals("install_id=11; ttreq=1$abc", get.headers.get("Cookie"));
        assertFalse(AppSource.deviceBase().containsKey("iid"));
        assertFalse(AppSource.deviceBase().containsKey("device_id"));
    }

    @Test public void registrationWithoutCookiesStillSignsWithoutCookieHeader() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        PlaybackDevice.Identity id = PlaybackDevice.fresh();
        assertEquals("", id.cookie);
        assertFalse(AppSource.signPlayback("https://h/p", id, new java.util.LinkedHashMap<>(), null, 1L).headers.containsKey("Cookie"));
    }

    @Test public void httpRejectionMovesToTheNextRouteAndIsReportedLast() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        List<String> hosts = new ArrayList<>();
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            hosts.add(host);
            throw new AppSource.PlaybackHttpException(403);
        });
        try {
            AppSource.fetch("7600000000000000001", "auto");
            fail();
        } catch (AppSource.PlaybackHttpException e) {
            assertEquals("App 接口 HTTP 403", e.getMessage());
        }
        assertEquals(Arrays.asList(AppSource.VIDEO_API_HOST, AppSource.RETRY_HOST), hosts);
    }

    @Test public void renewedIdentityRetriesTheOfficialRouteBeforeTheAlternateHost() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> registrations.incrementAndGet() == 1
                ? okReply() : reply("7432109876543219999", "7432109876543218888"));
        List<String> tabIdentities = new ArrayList<>();
        Map<String, Object> model = Json.obj();
        List<Object> list = Json.arr();
        list.add(item("bytevc1", 1080, 1920, "https://v1.qznovelvod.com/1080.mp4", VALID_SPADE));
        model.put("video_list", list);
        AppSource.setPlaybackTabForTest((vid, identity) -> {
            tabIdentities.add(identity.deviceId);
            return tabIdentities.size() == 1 ? null : response(vid, model);
        });
        AppSource.setPlaybackPostForTest((body, identity, host) -> { throw new AppSource.EmptyPlaybackResponseException(); });
        assertEquals("https://v1.qznovelvod.com/1080.mp4", AppSource.fetch("7600000000000000001", "auto").url);
        assertEquals(Arrays.asList(DEVICE_ID, "7432109876543219999"), tabIdentities);
    }

    @Test public void emptyPlaybackResponseIsReportedAsIdentityRejection() {
        try {
            AppSource.parsePlaybackResponse("  ");
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("空响应"));
        }
        try {
            AppSource.parsePlaybackResponse("{\"code\":0}");
            fail();
        } catch (IllegalStateException e) {
            assertEquals("App 接口未返回播放数据", e.getMessage());
        }
    }

    @Test public void emptyPlaybackResponseRetriesOnceWithRenewedIdentity() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> registrations.incrementAndGet() == 1
                ? okReply() : reply("7432109876543219999", "7432109876543218888"));
        List<PlaybackDevice.Identity> used = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        List<String> hosts = new ArrayList<>();
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            used.add(identity);
            hosts.add(host);
            bodies.add(new String(body, StandardCharsets.UTF_8));
            if (used.size() == 1) throw new AppSource.EmptyPlaybackResponseException();
            throw new IllegalStateException("reached retry");
        });
        try {
            AppSource.fetch("7000000000000000001", "auto");
            fail();
        } catch (IllegalStateException e) {
            assertEquals("reached retry", e.getMessage());
        }
        assertEquals(2, used.size());
        assertEquals(2, registrations.get());
        assertEquals(DEVICE_ID, used.get(0).deviceId);
        assertEquals("7432109876543219999", used.get(1).deviceId);
        assertTrue(bodies.get(0).contains("\"device_level\":1"));
        assertEquals(Arrays.asList(AppSource.VIDEO_API_HOST, AppSource.RETRY_HOST), hosts);
    }

    @Test public void secondEmptyPlaybackResponseIsNotRetriedAgain() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> {
            registrations.incrementAndGet();
            return okReply();
        });
        AtomicInteger posts = new AtomicInteger();
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            posts.incrementAndGet();
            throw new AppSource.EmptyPlaybackResponseException();
        });
        try {
            AppSource.fetch("7000000000000000001", "auto");
            fail();
        } catch (AppSource.EmptyPlaybackResponseException expected) {
            // 只换一次身份
        }
        assertEquals(2, posts.get());
        assertEquals(2, registrations.get());
    }

    @Test public void failedRenewalStillRetriesAlternateHost() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> {
            if (registrations.incrementAndGet() > 1) throw new IOException("offline");
            return okReply();
        });
        AtomicInteger posts = new AtomicInteger();
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            posts.incrementAndGet();
            throw new AppSource.EmptyPlaybackResponseException();
        });
        try {
            AppSource.fetch("7000000000000000001", "auto");
            fail();
        } catch (AppSource.EmptyPlaybackResponseException e) {
            assertEquals(1, e.getSuppressed().length);
        }
        assertEquals("换身份失败仍以原身份试一次备用域名", 2, posts.get());
        assertEquals(2, registrations.get());
    }

    // ==================== video_tab 播放模型 ====================

    @Test public void exhaustedEmptyPlaybackRequestsExposeSafeAttemptCount() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        AppSource.setPlaybackTabForTest((vid, identity) -> {
            throw new AppSource.EmptyPlaybackResponseException();
        });
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            throw new AppSource.EmptyPlaybackResponseException();
        });
        try {
            AppSource.fetch("7000000000000000001", "auto");
            fail("Expected an empty model response");
        } catch (AppSource.EmptyPlaybackResponseException expected) {
            assertEquals(AppSource.PLAYBACK_ROUTES.length, expected.getAttempts());
            assertFalse(expected.getMessage().contains(DEVICE_ID));
        }
    }

    @Test public void malformedFirstRouteContinuesToPostInsteadOfFailingImmediately() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        AppSource.setPlaybackTabForTest((vid, identity) -> {
            // Server returned an incomplete envelope rather than a playable model.
            return new java.util.LinkedHashMap<String, Object>();
        });
        java.util.concurrent.atomic.AtomicInteger posts = new java.util.concurrent.atomic.AtomicInteger();
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            posts.incrementAndGet();
            throw new IllegalStateException("post route reached");
        });
        try {
            AppSource.fetch("7000000000000000001", "auto");
            fail("Expected test POST to be reached");
        } catch (IllegalStateException expected) {
            assertEquals("post route reached", expected.getMessage());
        }
        assertEquals(1, posts.get());
    }

    @Test public void nonEmptyButUnavailableModelsHaveDistinctFailure() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        AppSource.setPlaybackTabForTest((vid, identity) -> null);
        AppSource.setPlaybackPostForTest((body, identity, host) -> null);
        try {
            AppSource.fetch("7000000000000000001", "auto");
            fail("Expected a missing model failure");
        } catch (AppSource.PlaybackModelUnavailableException expected) {
            assertEquals(AppSource.PLAYBACK_ROUTES.length, expected.getAttempts());
            assertFalse(expected.getMessage().contains(DEVICE_ID));
        }
    }

    @Test public void tabRequestCarriesEveryVideoModelField() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        PlaybackDevice.Identity id = PlaybackDevice.fresh();
        String url = AppSource.signPlayback("https://" + AppSource.VIDEO_API_HOST + "/reading/bookapi/video_tab/multi_video_model/v1/",
                id, AppSource.tabQuery("7600000000000000001"), null, 1_793_000_000_123L).url;
        String query = url.substring(url.indexOf('?') + 1);
        assertTrue(query.contains("&cdid=" + id.cdid + "&video_id=7600000000000000001&video_id_type=0"
                + "&device_level=1&need_all_video_definition=1&need_mp4_align=0&use_os_player=0"
                + "&video_platform=1024&source=0&from_video_id=&_rticket="));
        assertEquals(1, count(query, "device_id="));
    }

    @Test public void tabResponseIsNormalizedOrRejected() throws Exception {
        String vid = "7600000000000000001";
        Map<String, Object> j = AppSource.parseTabResponse(vid, "{\"code\":0,\"data\":{\"video_model_data\":{\""
                + vid + "\":{\"video_model\":\"{\\\"video_list\\\":[{\\\"codec_type\\\":\\\"bytevc1\\\"}]}\"}}}}");
        assertNotNull(j);
        assertEquals(1, Json.optArr(Json.optObj(Json.optObj(Json.optObj(j.get("data")).get(vid)).get("video_model"))
                .get("video_list")).size());
        assertNull(AppSource.parseTabResponse(vid, "{\"code\":100103,\"message\":\"PARAM_INVALID\"}"));
        assertNull(AppSource.parseTabResponse(vid, ""));
        assertNull(AppSource.parseTabResponse(vid, "{\"data\":{}}"));
        assertNull(AppSource.parseTabResponse(vid, "{\"code\":0,\"data\":{\"video_model_data\":{\"7600000000000000999\":"
                + "{\"video_model\":{\"video_list\":[{}]}}}}}"));
        assertNull(AppSource.parseTabResponse(vid, "{\"code\":0,\"data\":{\"video_model_data\":{\"" + vid
                + "\":{\"video_model\":{\"video_list\":[]}}}}}"));
    }

    @Test public void tabModelIsPlayedWithoutOldEndpoint() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        List<Object> list = Json.arr();
        list.add(item("bytevc2", 720, 1280, "https://v1.qznovelvod.com/vvc.mp4", VALID_SPADE));
        list.add(item("bytevc1", 1080, 1920, "https://v1.qznovelvod.com/1080.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        AppSource.setPlaybackTabForTest((vid, identity) -> response(vid, model));
        AppSource.setPlaybackPostForTest((body, identity, host) -> { throw new AssertionError("旧接口不应被请求"); });
        Stream.VideoTrack t = AppSource.fetch("7600000000000000001", "auto");
        assertEquals("bytevc2 被过滤", "https://v1.qznovelvod.com/1080.mp4", t.url);
    }

    @Test public void unavailableTabFallsBackToOldEndpoint() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        AtomicInteger tabs = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        AppSource.setPlaybackTabForTest((vid, identity) -> { tabs.incrementAndGet(); return null; });
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            posts.incrementAndGet();
            throw new IllegalStateException("reached old endpoint");
        });
        try {
            AppSource.fetch("7600000000000000001", "auto");
            fail();
        } catch (IllegalStateException e) {
            assertEquals("业务或格式错误不再继续试", "reached old endpoint", e.getMessage());
        }
        assertEquals(1, tabs.get());
        assertEquals(1, posts.get());
    }

    // ==================== 内联片源模型 ====================

    @Test public void inlineVideoListIsUsedWithoutFallbackRequest() throws Exception {
        List<Object> list = Json.arr();
        list.add(item("bytevc1", 1080, 1920, "https://v1.qznovelvod.com/hevc.mp4", VALID_SPADE));
        list.add(item("h264", 720, 1280, "https://v1.qznovelvod.com/avc.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        // 若误走 fallback，会请求这个不受信任地址并抛出异常
        model.put("fallback_api", "https://evil.invalid/fallback");

        Stream.VideoTrack t = AppSource.parseModel("7600000000000000001", response("7600000000000000001", model), "auto");
        assertEquals("自动模式 H.264 优先", "https://v1.qznovelvod.com/avc.mp4", t.url);
        assertTrue(t.encrypt);
        assertEquals(VALID_SPADE, t.spadeA);
        assertEquals("720p", t.definition);
    }

    @Test public void inlineModelMayBeJsonStringAndHonoursRequestedQuality() throws Exception {
        List<Object> list = Json.arr();
        list.add(item("h264", 540, 960, "https://v1.qznovelvod.com/540.mp4", VALID_SPADE));
        list.add(item("bytevc1", 1080, 1920, "https://v1.qznovelvod.com/1080.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        Stream.VideoTrack t = AppSource.parseModel("7600000000000000002",
                responseRaw("7600000000000000002", Json.stringify(model)), "1080p");
        assertEquals("https://v1.qznovelvod.com/1080.mp4", t.url);
    }

    /** 内联 1080P 缺加密信息；备用接口返回带密钥的片源。 */
    private static Map<String, Object> incompleteInlineModel() {
        List<Object> list = Json.arr();
        list.add(item("h264", 1080, 1920, "https://v1.qznovelvod.com/nokey.mp4", null));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        model.put("video_id", "v0abc");
        model.put("video_duration", 60.0);
        model.put("fallback_api", "https://vod.snssdk.com/video/fallback");
        return model;
    }

    private static Map<String, Object> fallbackReply(String id, double duration, int shortSide) {
        Map<String, Object> row = item("h264", shortSide, shortSide * 16 / 9, "https://v1.qznovelvod.com/keyed.mp4", VALID_SPADE);
        Map<String, Object> rows = Json.obj();
        rows.put("video_1", row);
        Map<String, Object> data = Json.obj();
        data.put("video_id", id);
        data.put("video_duration", duration);
        data.put("video_list", rows);
        Map<String, Object> info = Json.obj();
        info.put("data", data);
        Map<String, Object> outer = Json.obj();
        outer.put("video_info", info);
        return outer;
    }

    @Test public void inlineWithoutEncryptionInfoUsesVerifiedFallback() throws Exception {
        String vid = "7600000000000000011";
        AppSource.setPlaybackFallbackForTest(url -> fallbackReply("v0abc", 60.2, 1080));
        Stream.VideoTrack t = AppSource.parseModel(vid, response(vid, incompleteInlineModel()), "auto");
        assertEquals("https://v1.qznovelvod.com/keyed.mp4", t.url);
        assertTrue(t.encrypt);
    }

    @Test public void fallbackMustMatchIdentityDurationAndQuality() {
        String vid = "7600000000000000012";
        Map<String, Map<String, Object>> replies = new java.util.LinkedHashMap<>();
        replies.put("其他视频", fallbackReply("v0other", 60.0, 1080));
        replies.put("时长不同", fallbackReply("v0abc", 30.0, 1080));
        replies.put("清晰度更低", fallbackReply("v0abc", 60.0, 720));
        replies.forEach((name, reply) -> {
            AppSource.setPlaybackFallbackForTest(url -> reply);
            try {
                AppSource.parseModel(vid, response(vid, incompleteInlineModel()), "auto");
                fail(name + "的备用片源不得使用");
            } catch (IllegalStateException expected) {
                assertFalse(expected.getMessage().contains("keyed"));
            } catch (Exception other) {
                throw new AssertionError(other);
            }
        });
    }

    @Test public void declaredEncryptedInlineItemDoesNotTryFallback() throws Exception {
        String vid = "7600000000000000013";
        Map<String, Object> model = incompleteInlineModel();
        Map<String, Object> info = Json.obj();
        info.put("encrypt", true);
        ((Map<String, Object>) ((List<Object>) model.get("video_list")).get(0)).put("encrypt_info", info);
        AppSource.setPlaybackFallbackForTest(url -> { throw new AssertionError("不应请求备用接口"); });
        try {
            AppSource.parseModel(vid, response(vid, model), "auto");
            fail();
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().contains("备用"));
        }
    }

    @Test public void byteVc2OnlyEpisodeSaysSoInsteadOfGenericFailure() throws Exception {
        String vid = "7600000000000000014";
        List<Object> list = Json.arr();
        list.add(item("bytevc2", 1080, 1920, "https://v1.qznovelvod.com/vc2.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        try {
            AppSource.parseModel(vid, response(vid, model), "auto");
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("ByteVC2"));
        }
    }

    @Test public void inlineEncryptedItemWithoutSpadeIsNotPlayedAsPlaintext() throws Exception {
        List<Object> list = Json.arr();
        list.add(item("h264", 720, 1280, "https://v1.qznovelvod.com/nokey.mp4", null));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        try {
            AppSource.parseModel("7600000000000000003", response("7600000000000000003", model), "auto");
            fail("缺少解密信息的内联片源不得当作明文播放");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().contains("nokey"));
        }

        Map<String, Object> plain = item("h264", 720, 1280, "https://v1.qznovelvod.com/plain.mp4", null);
        Map<String, Object> info = Json.obj();
        info.put("encrypt", false);
        plain.put("encrypt_info", info);
        list.set(0, plain);
        Stream.VideoTrack t = AppSource.parseModel("7600000000000000003", response("7600000000000000003", model), "auto");
        assertEquals("https://v1.qznovelvod.com/plain.mp4", t.url);
        assertFalse(t.encrypt);
    }

    @Test public void inlineMediaOutsideCdnAllowListIsRejected() throws Exception {
        List<Object> list = Json.arr();
        list.add(item("h264", 720, 1280, "https://evil.invalid/x.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        try {
            AppSource.parseModel("7600000000000000004", response("7600000000000000004", model), "auto");
            fail();
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().contains("evil"));
        }
    }

    @Test public void onlyTheRequestedEpisodeModelIsUsed() throws Exception {
        List<Object> list = Json.arr();
        list.add(item("h264", 720, 1280, "https://v1.qznovelvod.com/other.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        try {
            AppSource.parseModel("7600000000000000005", response("7600000000000000999", model), "auto");
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("App 接口未返回所选分集", expected.getMessage());
        }
    }

    // ==================== 工具 ====================

    private static PlaybackDevice.Reply okReply() {
        return reply(DEVICE_ID, INSTALL_ID);
    }

    private static PlaybackDevice.Reply reply(String deviceId, String installId) {
        return new PlaybackDevice.Reply(200, "{\"server_time\":1,\"device_id\":" + deviceId + ","
                + "\"install_id\":" + installId + ",\"device_id_str\":\"" + deviceId
                + "\",\"install_id_str\":\"" + installId + "\",\"new_user\":1}");
    }

    private static IOException expectRegistrationFailure() {
        try {
            PlaybackDevice.fresh();
            fail("应注册失败");
            return null;
        } catch (IOException e) {
            assertTrue(e instanceof PlaybackDevice.RegistrationException);
            return e;
        }
    }

    private static Map<String, Object> item(String codec, int w, int h, String url, String spade) {
        Map<String, Object> m = Json.obj();
        m.put("codec_type", codec);
        m.put("definition", Math.min(w, h) + "p");
        m.put("vwidth", (long) w);
        m.put("vheight", (long) h);
        m.put("main_url", url);
        if (spade != null) {
            Map<String, Object> enc = Json.obj();
            enc.put("spade_a", spade);
            enc.put("encrypt", true);
            m.put("encrypt_info", enc);
        }
        return m;
    }

    private static Map<String, Object> response(String vid, Map<String, Object> model) {
        return responseRaw(vid, model);
    }

    private static Map<String, Object> responseRaw(String vid, Object model) {
        Map<String, Object> entry = Json.obj();
        entry.put("video_model", model);
        Map<String, Object> data = Json.obj();
        data.put(vid, entry);
        Map<String, Object> j = Json.obj();
        j.put("code", 0L);
        j.put("data", data);
        return j;
    }

    private static boolean isSignatureHeader(String name) {
        String k = name.toLowerCase();
        return k.equals("x-gorgon") || k.equals("x-khronos") || k.equals("x-argus") || k.equals("x-ladon")
                || k.equals("x-helios") || k.equals("x-medusa") || k.equals("x-tt-dt");
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
