package app.telaviva.androidtv.domain

import java.util.ArrayList
import java.util.Collections

class CameraGridState(layout: CameraLayout, slots: List<String?>) {
    val layout: CameraLayout = layout
    val slots: List<String?> = Collections.unmodifiableList(ArrayList(slots))

    init {
        require(slots.size == SLOT_COUNT) { "Camera grid state must contain exactly four slots" }
    }

    fun withLayout(layout: CameraLayout): CameraGridState = CameraGridState(layout, slots)

    companion object {
        const val SLOT_COUNT = 4
    }
}
