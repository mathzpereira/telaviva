package app.telaviva.androidtv.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import app.telaviva.androidtv.domain.CameraConfig

class CameraPickerScreen(
    context: Context,
    slotIndex: Int,
    cameras: List<CameraConfig>,
    managementMode: Boolean,
    onSelect: (CameraConfig) -> Unit,
    onDelete: (CameraConfig) -> Unit,
    onAddCamera: () -> Unit,
    onCancel: () -> Unit,
) {
    val root: View
    private val firstAction: View?

    init {
        val content = TvViewFactory.vertical(context).apply {
            setPadding(context.dp(40), context.dp(24), context.dp(40), context.dp(24))
        }
        val title = TvViewFactory.heading(
            context,
            if (managementMode) "Gerenciar câmeras" else "Selecionar câmera para o espaço ${slotIndex + 1}",
        )
        if (!managementMode) title.contentDescription = "Selecionar câmera para o espaço ${slotIndex + 1}"
        content.addView(title, TvViewFactory.marginParams(bottom = context.dp(16)))

        var focusCandidate: View? = null
        if (cameras.isEmpty()) {
            val add = TvViewFactory.button(context, "Adicionar câmera").apply {
                setOnClickListener { onAddCamera() }
            }
            content.addView(add, TvViewFactory.marginParams(bottom = context.dp(10)))
            focusCandidate = add
        } else {
            cameras.forEach { camera ->
                val row = TvViewFactory.horizontal(context)
                if (!managementMode) {
                    val select = TvViewFactory.button(
                        context,
                        camera.name,
                        "Selecionar ${camera.name}",
                    ).apply {
                        setOnClickListener { onSelect(camera) }
                    }
                    row.addView(select, LinearLayout.LayoutParams(0, context.dp(64), 1f))
                    focusCandidate = focusCandidate ?: select
                }
                val remove = TvViewFactory.button(
                    context,
                    "Remover",
                    "Remover ${camera.name}",
                ).apply {
                    setOnClickListener { onDelete(camera) }
                }
                row.addView(remove, TvViewFactory.marginParams(start = context.dp(12)))
                content.addView(row, TvViewFactory.marginParams(bottom = context.dp(8)))
                if (managementMode) focusCandidate = focusCandidate ?: remove
            }
        }
        val cancel = TvViewFactory.button(context, "Voltar", "Voltar").apply {
            setOnClickListener { onCancel() }
        }
        content.addView(cancel, TvViewFactory.marginParams(top = context.dp(12)))
        root = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(TvViewFactory.BACKGROUND)
            addView(content)
        }
        firstAction = focusCandidate ?: cancel
    }

    fun requestInitialFocus() {
        firstAction?.post { firstAction.requestFocus() }
    }
}
