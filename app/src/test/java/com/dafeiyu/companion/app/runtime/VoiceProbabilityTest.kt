package com.dafeiyu.companion.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProbabilityTest {

    @Test
    fun baseRates() {
        assertEquals(1.0, VoiceProbability.base(true, false), 0.0001)
        assertEquals(1.0, VoiceProbability.base(true, true), 0.0001)
        assertEquals(0.99, VoiceProbability.base(false, true), 0.0001)
        assertEquals(0.10, VoiceProbability.base(false, false), 0.0001)
    }

    @Test
    fun actualIsProductOfBaseAndWillingness() {
        assertEquals(0.495, VoiceProbability.actual(0.99, 0.5), 0.0001)
        assertEquals(0.05, VoiceProbability.actual(0.10, 0.5), 0.0001)
        assertEquals(0.8, VoiceProbability.actual(1.0, 0.8), 0.0001)
    }

    @Test
    fun decisionUsesRoll() {
        // p=0.5，roll=0.4 → 发；roll=0.6 → 不发。
        assertTrue(VoiceProbability.decide(1.0, 0.5, 0.4))
        assertFalse(VoiceProbability.decide(1.0, 0.5, 0.6))
    }

    @Test
    fun zeroWillingnessNeverSends() {
        assertFalse(VoiceProbability.decide(1.0, 0.0, 0.0))
    }
}
