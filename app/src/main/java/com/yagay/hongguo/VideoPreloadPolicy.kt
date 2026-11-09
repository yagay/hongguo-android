package com.yagay.hongguo

/** Bounded in-memory warmup for ONE upcoming encrypted video stream. */
internal object VideoPreloadPolicy {
    const val MIB = 1024 * 1024
    const val DEFAULT_MIB = 8
    const val DEFAULT_BYTES = DEFAULT_MIB * MIB
    // A useful 1 MiB is handed off as soon as possible, then warmed in
    // background up to the user's target while the current video plays.
    const val STARTUP_BYTES = MIB
    val OPTIONS_MIB = listOf(1, 2, 4, 8, 16)

    fun normalizedMiB(value: Int): Int =
        if (value in OPTIONS_MIB) value else DEFAULT_MIB

    fun bytes(valueMiB: Int): Int = normalizedMiB(valueMiB) * MIB

    fun validBytes(value: Int): Int =
        if (value in OPTIONS_MIB.map { it * MIB }) value else DEFAULT_BYTES
}
