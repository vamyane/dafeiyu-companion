package com.dafeiyu.companion.app.runtime

import com.dafeiyu.companion.model.Activity
import com.dafeiyu.companion.model.CompanionState
import com.dafeiyu.companion.model.ContactMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LifeStateTest {

    private fun state(
        mood: Int = 10,
        missing: Int = 0,
        energy: Int = 70,
        activity: Activity = Activity.IDLE,
        contactMode: ContactMode = ContactMode.NORMAL
    ): CompanionState = CompanionState(
        companionId = "test",
        mood = mood,
        missing = missing,
        energy = energy,
        activity = activity,
        contactMode = contactMode,
        lastTickAt = Instant.EPOCH
    )

    @Test
    fun highMissingProducesMissingThought() {
        val life = LifeState.from(state(missing = 80))
        assertEquals(80, life.missing)
        assertTrue(life.thought.contains("很想你"))
    }

    @Test
    fun quietModeProducesGentleThought() {
        val life = LifeState.from(state(contactMode = ContactMode.QUIET, missing = 90))
        assertTrue(life.thought.contains("安静"))
    }

    @Test
    fun lowEnergyProducesRestThought() {
        val life = LifeState.from(state(energy = 20))
        assertTrue(life.thought.contains("歇一会儿"))
    }

    @Test
    fun sleepingActivityIsSurfaced() {
        val life = LifeState.from(state(activity = Activity.SLEEPING))
        assertEquals("在睡觉", life.activityLabel)
    }

    @Test
    fun tiredUserMessageChangesThought() {
        val life = LifeState.from(state(), "今天工作好累，不想动了。")
        assertTrue(life.thought.contains("担心"))
    }
}
