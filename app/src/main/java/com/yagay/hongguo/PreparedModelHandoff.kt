package com.yagay.hongguo

/**
 * Shares a freshly resolved model between background preparation and a
 * foreground swipe. The caller must still create its own RemoteVideo, so
 * cancellation/close of the prefetch cannot invalidate active playback.
 *
 * No URLs, identities or credentials are persisted; memory entries expire
 * after 12 seconds and are transferred to only one foreground reader.
 */
internal class PreparedModelHandoff<T>(
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 12_000L,
) {
    private data class Entry<T>(val value: T, val at: Long)
    private val entries = linkedMapOf<String, Entry<T>>()

    @Synchronized fun offer(key: String, value: T) {
        prune()
        entries[key] = Entry(value, now())
        while (entries.size > 5) entries.remove(entries.keys.first())
    }

    @Synchronized fun take(key: String): T? {
        prune()
        return entries.remove(key)?.value
    }

    @Synchronized fun invalidate(key: String) {
        entries.remove(key)
    }

    @Synchronized fun clear() {
        entries.clear()
    }

    private fun prune() {
        val time = now()
        entries.entries.removeAll {
            time < it.value.at || time - it.value.at > ttlMs
        }
    }
}
