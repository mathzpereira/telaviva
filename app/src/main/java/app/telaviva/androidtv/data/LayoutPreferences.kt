package app.telaviva.androidtv.data

import android.content.Context
import app.telaviva.androidtv.domain.CameraGridState
import app.telaviva.androidtv.domain.CameraLayout

class LayoutPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): CameraGridState {
        val layout = preferences.getString(KEY_LAYOUT, null)
            ?.let { saved -> CameraLayout.values().firstOrNull { it.name == saved } }
            ?: CameraLayout.ONE
        val slots = (0 until CameraGridState.SLOT_COUNT).map { slot ->
            preferences.getString(slotKey(slot), null)
        }
        return CameraGridState(layout, slots)
    }

    fun save(state: CameraGridState) {
        preferences.edit().apply {
            putString(KEY_LAYOUT, state.layout.name)
            state.slots.forEachIndexed { index, cameraId ->
                if (cameraId == null) remove(slotKey(index)) else putString(slotKey(index), cameraId)
            }
        }.apply()
    }

    private fun slotKey(index: Int) = "slot_$index"

    companion object {
        private const val PREFERENCES_NAME = "camera_layout"
        private const val KEY_LAYOUT = "layout"
    }
}
