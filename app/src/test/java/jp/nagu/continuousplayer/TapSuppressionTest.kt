package jp.nagu.continuousplayer

import org.junit.Assert.*
import org.junit.Test

class TapSuppressionTest {
    @Test fun firstCompletedTapIsAcceptedWithoutWaiting() {
        assertTrue(TapSuppression(300, 100f).accept(TapRegion.TOGGLE, 50f, 50f, 0, 10))
    }

    @Test fun nearbySecondTapAtThreeHundredMillisecondsIsIgnored() {
        val policy = TapSuppression(300, 100f)
        assertTrue(policy.accept(TapRegion.FORWARD, 50f, 50f, 0, 10))
        assertFalse(policy.accept(TapRegion.FORWARD, 60f, 50f, 310, 340))
    }

    @Test fun tapAfterThreeHundredMillisecondsIsAccepted() {
        val policy = TapSuppression(300, 100f)
        assertTrue(policy.accept(TapRegion.REWIND, 50f, 50f, 0, 10))
        assertTrue(policy.accept(TapRegion.REWIND, 50f, 50f, 311, 340))
    }

    @Test fun adjacentRegionsRemainIndependentEvenWhenPositionsAreClose() {
        val policy = TapSuppression(300, 100f)
        assertTrue(policy.accept(TapRegion.REWIND, 99f, 50f, 0, 10))
        assertTrue(policy.accept(TapRegion.TOGGLE, 101f, 50f, 50, 60))
        assertTrue(policy.accept(TapRegion.FORWARD, 201f, 50f, 100, 110))
    }

    @Test fun proximityBoundaryUsesTwoDimensionalDistance() {
        val policy = TapSuppression(300, 100f)
        assertTrue(policy.accept(TapRegion.TOGGLE, 0f, 0f, 0, 10))
        assertFalse(policy.accept(TapRegion.TOGGLE, 60f, 80f, 50, 60))
        assertTrue(policy.accept(TapRegion.TOGGLE, 80f, 80f, 100, 110))
    }

    @Test fun ignoredTapsDoNotExtendTheFirstTapsWindow() {
        val policy = TapSuppression(300, 100f)
        assertTrue(policy.accept(TapRegion.TOGGLE, 50f, 50f, 0, 10))
        assertFalse(policy.accept(TapRegion.TOGGLE, 50f, 50f, 250, 260))
        assertTrue(policy.accept(TapRegion.TOGGLE, 50f, 50f, 311, 320))
    }

    @Test fun resetAllowsANewTapAfterAnInterruption() {
        val policy = TapSuppression(300, 100f)
        assertTrue(policy.accept(TapRegion.TOGGLE, 50f, 50f, 0, 10))
        policy.reset()
        assertTrue(policy.accept(TapRegion.TOGGLE, 50f, 50f, 50, 60))
    }

    @Test fun shorterSystemTimeoutDoesNotReduceTheCommonWindow() {
        val policy = TapSuppression(200, 100f)
        assertTrue(policy.accept(TapRegion.TOGGLE, 50f, 50f, 0, 10))
        assertFalse(policy.accept(TapRegion.TOGGLE, 50f, 50f, 260, 270))
    }

    @Test fun longerSystemTimeoutIsRespected() {
        val policy = TapSuppression(500, 100f)
        assertTrue(policy.accept(TapRegion.TOGGLE, 50f, 50f, 0, 10))
        assertFalse(policy.accept(TapRegion.TOGGLE, 50f, 50f, 410, 420))
        assertTrue(policy.accept(TapRegion.TOGGLE, 50f, 50f, 511, 520))
    }
}
