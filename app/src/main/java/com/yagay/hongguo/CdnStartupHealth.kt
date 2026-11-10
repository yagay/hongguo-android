package com.yagay.hongguo

import java.net.URI
import java.util.Locale

/**
 * Conservative, process-local CDN preference. Uses only the hostname:
 * signed paths, tokens, query strings, keys and episode IDs are never stored.
 */
internal class CdnStartupHealth(private val now: () -> Long = System::currentTimeMillis) {
    private data class Health(
        var lastSuccess: Long = 0,
        var smoothMs: Long = 0,
        var failedUntil: Long = 0,
    )
    private val hosts = linkedMapOf<String, Health>()
    private fun host(url: String): String? = runCatching {
        URI(url).takeIf { it.scheme.equals("https", true) }
            ?.host?.lowercase(Locale.ROOT)
    }.getOrNull()

    @Synchronized fun score(url: String): Int {
        val value = hosts[host(url)] ?: return 0
        val time = now()
        if (value.failedUntil > time) return 50_000
        if (value.lastSuccess <= 0L || time < value.lastSuccess ||
            time - value.lastSuccess > 15 * 60_000L) return 0
        return -10_000 + (value.smoothMs / 5).coerceIn(0, 5_000).toInt()
    }

    @Synchronized fun success(url: String, elapsedMs: Long) {
        val name = host(url) ?: return
        val value = hosts.getOrPut(name) { Health() }
        val latency = elapsedMs.coerceIn(1, 60_000)
        value.smoothMs = if (value.smoothMs <= 0L) latency
            else (value.smoothMs * 3 + latency) / 4
        value.lastSuccess = now()
        value.failedUntil = 0
        trim()
    }

    @Synchronized fun failure(url: String) {
        val name = host(url) ?: return
        val value = hosts.getOrPut(name) { Health() }
        value.failedUntil = now() + 30_000L
        trim()
    }

    private fun trim() {
        while (hosts.size > 32) hosts.remove(hosts.keys.first())
    }
}
