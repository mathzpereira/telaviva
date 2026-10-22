package app.telaviva.androidtv.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import app.telaviva.androidtv.domain.CameraConfig

class FullscreenScreen(
    context: Context,
    camera: CameraConfig,
    onBack: () -> Unit,
    onAttachPlayer: (String, PlayerView?) -> Unit,
) {
    val root: View = FrameLayout(context).apply {
        setBackgroundColor(0xff000000.toInt())
        isSaveEnabled = false
    }
    val playerView: PlayerView = PlayerView(context).apply {
        useController = false
        isFocusable = false
        isSaveEnabled = false
    }

    init {
        val frame = root as FrameLayout
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val title = TextView(context).apply {
            text = camera.name
            textSize = 22f
            setTextColor(TvViewFactory.FOREGROUND)
            setPadding(context.dp(18), context.dp(10), context.dp(18), context.dp(10))
            setBackgroundColor(0xb3000000.toInt())
            contentDescription = "Tela cheia: ${camera.name}"
        }
        frame.addView(
            title,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START),
        )
        val back = TvViewFactory.button(context, "Voltar", "Voltar para as câmeras")
        back.setOnClickListener { onBack() }
        frame.addView(
            back,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = context.dp(24)
                bottomMargin = context.dp(24)
            },
        )
        onAttachPlayer(camera.id, playerView)
        back.post { back.requestFocus() }
    }
}
