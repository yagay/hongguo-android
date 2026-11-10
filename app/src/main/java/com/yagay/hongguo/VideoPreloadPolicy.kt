package com.yagay.hongguo

/** Bounded in-memory warmup: N+1 has the user budget; N+2 a smaller fixed budget. */
internal object VideoPreloadPolicy {
    const val MIB = 1024 * 1024
    const val DEFAULT_MIB = 8
    const val DEFAULT_BYTES = DEFAULT_MIB * MIB
    // A useful 1 MiB is handed off as soon as possible, then warmed in
    // background up to the user's target while the current video plays.
    const val STARTUP_BYTES = MIB
    // Keep the second-neighbour warmup small so the active stream and N+1 win bandwidth.
    const val LATER_BYTES = 2 * MIB

    enum class Slot { NEXT_EPISODE, NEXT_DRAMA, LATER }
    /** The immediate next episode gets the user's full setting; a distant
     * reel/episode cannot consume the same byte budget. */
    fun bytesFor(slot: Slot, requestedMiB: Int): Int = when (slot) {
        Slot.NEXT_EPISODE -> bytes(requestedMiB)
        Slot.NEXT_DRAMA -> minOf(bytes(requestedMiB), 8 * MIB)
        Slot.LATER -> minOf(bytes(requestedMiB), LATER_BYTES)
    }

    val OPTIONS_MIB = listOf(1, 2, 4, 8, 16)

    fun normalizedMiB(value: Int): Int =
        if (value in OPTIONS_MIB) value else DEFAULT_MIB

    fun bytes(valueMiB: Int): Int = normalizedMiB(valueMiB) * MIB

    fun validBytes(value: Int): Int =
        if (value in OPTIONS_MIB.map { it * MIB }) value else DEFAULT_BYTES
}
