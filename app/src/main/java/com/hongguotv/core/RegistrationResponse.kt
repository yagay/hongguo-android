// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.Response

/** Bounded response parser reused by anonymous registration only.
 * Adapted from hongguoTV CloudAccount.cloudResponse (GPL-3.0-only).
 */
internal fun cloudResponse(response: Response): String {
    if (!response.isSuccessful) throw IOException("设备注册 HTTP " + response.code)
    val body = response.body ?: throw IOException("DEVICE_REGISTER_EMPTY_RESPONSE")
    val limit = 2 * 1024 * 1024L
    if (body.contentLength() > limit) throw IOException("DEVICE_REGISTER_RESPONSE_TOO_LARGE")
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    val stream = body.byteStream()
    while (out.size() <= limit) {
        val n = stream.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    if (out.size() > limit) throw IOException("DEVICE_REGISTER_RESPONSE_TOO_LARGE")
    return out.toString(Charsets.UTF_8.name()).takeIf { it.isNotBlank() }
        ?: throw IOException("DEVICE_REGISTER_EMPTY_RESPONSE")
}
