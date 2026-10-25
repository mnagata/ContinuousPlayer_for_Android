package jp.nagu.continuousplayer

import org.junit.Assert.*
import org.junit.Test

class SwipeDirectionTest {
    private val densities = listOf(0.75f, 1f, 1.3125f, 2f, 3f)

    @Test fun minimumDistanceIsSeventyDpInclusiveAtEveryDensity() {
        for (density in densities) {
            assertNull(SwipeDirection.fromDistance(69f * density, 0f, density))
            assertNull(SwipeDirection.fromDistance(-69f * density, 0f, density))
            assertEquals(SwipeDirection.PREVIOUS, SwipeDirection.fromDistance(70f * density, 0f, density))
            assertEquals(SwipeDirection.NEXT, SwipeDirection.fromDistance(-70f * density, 0f, density))
        }
    }

    @Test fun horizontalToVerticalRatioIncludesExactlyOneAndAHalf() {
        for (density in densities) for (verticalSign in listOf(-1, 1)) {
            assertEquals(SwipeDirection.PREVIOUS,
                SwipeDirection.fromDistance(105f * density, verticalSign * 70f * density, density))
            assertEquals(SwipeDirection.NEXT,
                SwipeDirection.fromDistance(-105f * density, verticalSign * 70f * density, density))
            assertNull(SwipeDirection.fromDistance(104f * density, verticalSign * 70f * density, density))
        }
    }

    @Test fun shortVerticalAndInsufficientlyHorizontalDragsAreIgnored() {
        for ((dx, dy) in listOf(0f to 0f, 20f to 0f, 0f to 200f, 100f to 100f, 100f to 80f, -100f to -80f)) {
            assertNull(SwipeDirection.fromDistance(dx, dy, 1f))
        }
    }

    @Test fun rightGoesToPreviousAndLeftGoesToNext() {
        assertEquals(SwipeDirection.PREVIOUS, SwipeDirection.fromDistance(150f, -20f, 2f))
        assertEquals(SwipeDirection.NEXT, SwipeDirection.fromDistance(-150f, 20f, 2f))
    }
}
