// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.api.service;

import com.hongguo.api.util.Http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Anonymous registration transport, isolated from the playback/account HTTP stacks.
 *
 * OkHttp uses the device TLS/proxy setup and negotiates HTTP/2 where supported.
 * A connection-layer failure gets ONE bounded HttpURLConnection fallback to the
 * SAME verified registration host, with the same encrypted request body and headers.
 * HTTP status failures and invalid registration responses never get retried here.
 */
final class RegistrationHttp {
    private RegistrationHttp() {}

    private static final int RESPONSE_LIMIT = 128 * 1024;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build();

    static PlaybackDevice.Reply post(String url, Map<String, String> headers, byte[] body) throws IOException {
        return postWithFallback(RegistrationHttp::okHttpPost, RegistrationHttp::legacyPost,
                url, headers, body);
    }

    /** Package-visible to verify that only connection failures get exactly one fallback. */
    static PlaybackDevice.Reply postWithFallback(
            PlaybackDevice.Transport preferred, PlaybackDevice.Transport secondary,
            String url, Map<String, String> headers, byte[] body) throws IOException {
        try {
            return preferred.post(url, headers, body);
        } catch (IOException primary) {
            if (primary instanceof PlaybackDevice.RegistrationException) throw primary;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("播放请求已取消");
            try {
                return secondary.post(url, headers, body);
            } catch (IOException fallback) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("播放请求已取消");
                String first = PlaybackDevice.registrationTransportCode(primary);
                String second = PlaybackDevice.registrationTransportCode(fallback);
                // Preserve a specific original failure if the legacy stack only says "network error".
                String code = "DEVICE_REGISTER_NETWORK_FAILED".equals(second) ? first : second;
                throw new PlaybackDevice.RegistrationException(code, first, second);
            }
        }
    }

    private static PlaybackDevice.Reply okHttpPost(
            String url, Map<String, String> headers, byte[] body) throws IOException {
        RequestBody requestBody = RequestBody.create(
                MediaType.parse("application/octet-stream;tt-data=a"), body);
        Request.Builder request = new Request.Builder().url(url).post(requestBody);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getValue() != null) request.header(entry.getKey(), entry.getValue());
        }
        // Registration carries NO signed request or account cookies from other APIs.
        try (Response response = CLIENT.newCall(request.build()).execute()) {
            List<String> cookies = new ArrayList<>(response.headers("Set-Cookie"));
            // A non-200 response needs no body: never retain an error page or provider JSON.
            if (response.code() != 200)
                return new PlaybackDevice.Reply(response.code(), "", cookies);
            ResponseBody responseBody = response.body();
            if (responseBody == null) return new PlaybackDevice.Reply(200, "", cookies);
            try (InputStream in = responseBody.byteStream()) {
                byte[] bytes = readBounded(in);
                return new PlaybackDevice.Reply(200,
                        PlaybackDevice.decodeRegistrationResponse(bytes,
                                response.header("Content-Encoding")), cookies);
            }
        }
    }

    private static PlaybackDevice.Reply legacyPost(
            String url, Map<String, String> headers, byte[] body) throws IOException {
        Http.Req request = new Http.Req(url).method("POST").timeout(6_000);
        request.followRedirect = false;
        for (Map.Entry<String, String> entry : headers.entrySet())
            request.header(entry.getKey(), entry.getValue());
        request.body(body);
        Http.Resp response = Http.send(request);
        List<String> cookies = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : response.headers.entrySet()) {
            if ("Set-Cookie".equalsIgnoreCase(entry.getKey())) cookies.addAll(entry.getValue());
        }
        if (response.status != 200)
            return new PlaybackDevice.Reply(response.status, "", cookies);
        return new PlaybackDevice.Reply(response.status,
                PlaybackDevice.decodeRegistrationResponse(response.body,
                        response.header("Content-Encoding")), cookies);
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("播放请求已取消");
            if (buffer.size() > RESPONSE_LIMIT - n)
                throw new PlaybackDevice.RegistrationException("DEVICE_REGISTER_RESPONSE_TOO_LARGE", 200, -1);
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }
}
