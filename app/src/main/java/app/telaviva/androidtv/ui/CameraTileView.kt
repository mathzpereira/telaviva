package app.telaviva.androidtv.ui

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import app.telaviva.androidtv.domain.CameraConfig

@SuppressLint("ViewConstructor")
class CameraTileView(
    context: Context,
    val slotIndex: Int,
    camera: CameraConfig?,
    onSelected: (CameraTileView) -> Unit,
    onRetry: (CameraConfig) -> Unit,
    onAttachPlayer: (String, PlayerView?) -> Unit,
) : FrameLayout(context) {
    val playerView = PlayerView(context).apply {
        useController = false
        isFocusable = false
        isFocusableInTouchMode = false
        isSaveEnabled = false
    }

    private val cameraName = TextView(context).apply {
        textSize = 18f
        setTextColor(Color.WHITE)
        setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
        setBackgroundColor(0xb3000000.toInt())
    }
    private val errorMessage = TextView(context).apply {
        textSize = 17f
        setTextColor(TvViewFactory.ERROR)
        setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
        setBackgroundColor(0xd9000000.toInt())
        visibility = View.GONE
    }
    private val focusOutline = View(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(context.dp(4), 0xff42e8bd.toInt())
            cornerRadius = context.dp(10).toFloat()
        }
        visibility = View.GONE
        isClickable = false
    }
    private val retryButton = TvViewFactory.button(context, "Tentar novamente").apply {
        visibility = View.GONE
    }

    var camera: CameraConfig? = camera
        private set

    init {
        id = View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isSaveEnabled = false
        setBackgroundColor(TvViewFactory.SURFACE)
        contentDescription = slotDescription(slotIndex, camera)
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            cameraName,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.START),
        )
        addView(
            errorMessage,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM).apply {
                bottomMargin = context.dp(74)
            },
        )
        addView(
            retryButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.END),
        )
        addView(focusOutline, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        updateCameraLabel()
        setOnClickListener { onSelected(this) }
        retryButton.contentDescription = camera?.let { "Tentar novamente: ${it.name}" }
        retryButton.setOnClickListener { camera?.let(onRetry) }
        onFocusChangeListener = OnFocusChangeListener { _, focused ->
            focusOutline.visibility = if (focused) View.VISIBLE else View.GONE
        }
        camera?.let { onAttachPlayer(it.id, playerView) }
    }

    fun setError(message: String?) {
        errorMessage.text = if (message.isNullOrBlank()) {
            ""
        } else {
            "$message\nOK para tentar novamente"
        }
        errorMessage.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        retryButton.visibility = if (message.isNullOrBlank() || camera == null) View.GONE else View.VISIBLE
        nextFocusDownId = if (retryButton.visibility == View.VISIBLE) retryButton.id else View.NO_ID
    }

    fun showCamera(selected: CameraConfig?) {
        camera = selected
        updateCameraLabel()
        retryButton.contentDescription = selected?.let { "Tentar novamente: ${it.name}" }
        setError(null)
    }

    fun detachPlayer(onAttachPlayer: (String, PlayerView?) -> Unit) {
        camera?.let { onAttachPlayer(it.id, null) }
    }

    private fun updateCameraLabel() {
        val selected = camera
        cameraName.text = selected?.name ?: "Espaço ${slotIndex + 1} vazio"
        contentDescription = slotDescription(slotIndex, selected)
    }

    companion object {
        fun slotDescription(slotIndex: Int, camera: CameraConfig?): String =
            camera?.let { "Espaço ${slotIndex + 1}: ${it.name}" } ?: "Espaço ${slotIndex + 1} vazio"
    }
}
