package com.yagay.hongguo.kxg

import org.junit.Assert.*
import org.junit.Test

class EpisodeDirectoryPolicyTest {
    @Test fun rejectsInvalidIdsAndMissingOrRepeatedEpisodeNumbers() {
        assertTrue(EpisodeDirectoryPolicy.validId("7491234567890123456"))
        assertFalse(EpisodeDirectoryPolicy.validId("preview-id"))
        assertFalse(EpisodeDirectoryPolicy.validId("000"))
        assertFalse(EpisodeDirectoryPolicy.contiguous(emptyList()))
        assertFalse(EpisodeDirectoryPolicy.contiguous(listOf(1, 3)))
        assertFalse(EpisodeDirectoryPolicy.contiguous(listOf(1, 1)))
        assertTrue(EpisodeDirectoryPolicy.contiguous(listOf(1, 2, 3)))
    }
}
