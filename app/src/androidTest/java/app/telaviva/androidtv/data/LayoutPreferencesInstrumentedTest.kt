package app.telaviva.androidtv.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.telaviva.androidtv.domain.CameraGridState
import app.telaviva.androidtv.domain.CameraLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutPreferencesInstrumentedTest {
    @Test
    fun saveLoadRetainsAssignmentsHiddenBySingleCameraLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = LayoutPreferences(context)
        val assignments = listOf("camera-1", "camera-2", "camera-3", "camera-4")

        preferences.save(CameraGridState(CameraLayout.ONE, assignments))

        val restored = preferences.load()
        assertEquals(CameraLayout.ONE, restored.layout)
        assertEquals(assignments, restored.slots)
    }
}
