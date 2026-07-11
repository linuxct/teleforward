package space.linuxct.teleforward.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun higherPatchIsNewer() {
        assertTrue(isNewer("0.2.0", "0.1.0"))
    }

    @Test
    fun leadingVIsStripped() {
        assertTrue(isNewer("v1.0.0", "0.9.9"))
    }

    @Test
    fun currentWithLeadingVAlsoStripped() {
        assertTrue(isNewer("1.0.0", "v0.9.9"))
    }

    @Test
    fun equalVersionsAreNotNewer() {
        assertFalse(isNewer("1.2.3", "1.2.3"))
    }

    @Test
    fun equalWithDifferentLeadingV() {
        assertFalse(isNewer("v0.1.0", "0.1.0"))
    }

    @Test
    fun olderIsNotNewer() {
        assertFalse(isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun differingSegmentCountsAreZeroPadded() {
        // 1.2 == 1.2.0, so neither direction is "newer".
        assertFalse(isNewer("1.2", "1.2.0"))
        assertTrue(isNewer("1.2.1", "1.2"))
        assertTrue(isNewer("1.3", "1.2.9"))
    }

    @Test
    fun preReleaseSuffixIsTolerated() {
        // Suffix is dropped: 1.0.0-rc1 -> 1.0.0, which is newer than 0.9.0.
        assertTrue(isNewer("1.0.0-rc1", "0.9.0"))
        // ...and equal to 1.0.0, so not newer.
        assertFalse(isNewer("1.0.0-rc1", "1.0.0"))
    }

    @Test
    fun buildMetadataSuffixIsTolerated() {
        assertTrue(isNewer("2.0.0+build.7", "1.9.9"))
    }

    @Test
    fun malformedLatestIsNotNewer() {
        assertFalse(isNewer("not-a-version", "1.0.0"))
        assertFalse(isNewer("", "1.0.0"))
        assertFalse(isNewer("1.x.0", "1.0.0"))
    }

    @Test
    fun malformedCurrentIsNotNewer() {
        assertFalse(isNewer("2.0.0", "garbage"))
    }

    @Test
    fun higherMajorBeatsHigherMinor() {
        assertTrue(isNewer("2.0.0", "1.99.99"))
    }
}
