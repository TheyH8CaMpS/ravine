package dev.veil.launcher.data

import dev.veil.launcher.alerts.PendingScoreAlerts
import dev.veil.launcher.alerts.ScoreAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingScoreAlertsTest {
    @Test fun disablingCancelsDelayedAndPostedAlertsEvenAfterReenabling() {
        val pending = PendingScoreAlerts()
        val generation = pending.generation
        val ready = ScoreAlerts.Alert("score", "game1", true, "RUN", "1–0", 0L, null)
        val delayed = ready.copy(key = "later", gameId = "game2")
        pending.enqueue(generation, 0, listOf(ready))
        pending.enqueue(generation, 90, listOf(delayed))
        assertEquals(listOf(ready), pending.takeDue(0))
        assertFalse(pending.canDeliver(generation, enabled = false))

        pending.clear()
        pending.enqueue(generation, 90, listOf(delayed)) // poll that started before switching off
        assertTrue(pending.takeDue(90).isEmpty())
        assertFalse(pending.canDeliver(generation, enabled = true)) // already posted before switching off

        val nextGeneration = pending.generation
        pending.enqueue(nextGeneration, 100, listOf(delayed))
        assertEquals(listOf(delayed), pending.takeDue(100))
        assertTrue(pending.canDeliver(nextGeneration, enabled = true))
    }
}
