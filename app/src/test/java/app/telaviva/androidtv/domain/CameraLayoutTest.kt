package app.telaviva.androidtv.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraLayoutTest {
    @Test
    fun oneTwoFourLayoutsHaveExactSlotCounts() {
        assertEquals(1, CameraLayout.ONE.slotCount)
        assertEquals(2, CameraLayout.TWO.slotCount)
        assertEquals(4, CameraLayout.FOUR.slotCount)
    }

    @Test
    fun gridStateAlwaysHasFourAssignments() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraGridState(CameraLayout.ONE, listOf("camera-1"))
        }

        val assignments = mutableListOf<String?>("camera-1", null, "camera-3", null)
        val state = CameraGridState(layout = CameraLayout.ONE, slots = assignments)
        assignments.clear()

        assertEquals(4, state.slots.size)
        assertEquals(listOf("camera-1", null, "camera-3", null), state.slots)
    }

    @Test
    fun layoutChangesPreserveHiddenCameraAssignments() {
        val assignments = listOf("camera-1", "camera-2", "camera-3", "camera-4")
        val state = CameraGridState(CameraLayout.FOUR, assignments)

        val restored = state.withLayout(CameraLayout.ONE).withLayout(CameraLayout.FOUR)

        assertEquals(assignments, restored.slots)
    }
}
