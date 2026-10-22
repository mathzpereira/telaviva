package app.telaviva.androidtv.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.GridLayout
import android.widget.LinearLayout
import app.telaviva.androidtv.domain.CameraConfig
import app.telaviva.androidtv.domain.CameraGridState
import app.telaviva.androidtv.domain.CameraLayout
import androidx.media3.ui.PlayerView

class HomeScreen(
    context: Context,
    private val state: CameraGridState,
    cameras: List<CameraConfig>,
    organizing: Boolean,
    private val onLayoutSelected: (CameraLayout) -> Unit,
    private val onOrganizeChanged: (Boolean) -> Unit,
    private val onAddCamera: () -> Unit,
    private val onManageCameras: () -> Unit,
    private val onTileSelected: (Int, CameraConfig?) -> Unit,
    private val onRetryCamera: (CameraConfig) -> Unit,
    private val onAttachPlayer: (String, PlayerView?) -> Unit,
) {
    val root: LinearLayout = TvViewFactory.vertical(context).apply {
        setPadding(context.dp(32), context.dp(18), context.dp(32), context.dp(20))
    }
    private val camerasById = cameras.associateBy(CameraConfig::id)
    private val tiles = mutableListOf<CameraTileView>()
    private val layoutButtons = mutableMapOf<CameraLayout, View>()
    private val actions = mutableListOf<View>()
    private val grid: GridLayout
    private val status = TvViewFactory.body(context, "").apply { visibility = View.GONE }

    init {
        val header = TvViewFactory.horizontal(context)
        header.addView(
            TvViewFactory.heading(context, "Tela Viva", 30f),
            LinearLayout.LayoutParams(0, context.dp(54), 1f),
        )
        val layoutRow = TvViewFactory.horizontal(context)
        CameraLayout.values().forEach { layout ->
            val label = when (layout) {
                CameraLayout.ONE -> "1 câmera"
                CameraLayout.TWO -> "2 câmeras"
                CameraLayout.FOUR -> "4 câmeras"
            }
            val button = TvViewFactory.button(context, label, "Layout $label").apply {
                isSelected = state.layout == layout
                setOnClickListener { onLayoutSelected(layout) }
            }
            layoutButtons[layout] = button
            layoutRow.addView(button, TvViewFactory.marginParams(end = context.dp(12)))
        }
        header.addView(layoutRow)
        root.addView(header)

        val actionRow = TvViewFactory.horizontal(context)
        val organizeButton = TvViewFactory.button(
            context,
            if (organizing) "Concluir organização" else "Organizar",
            "Organizar câmeras",
        ).apply {
            isSelected = organizing
            setOnClickListener { onOrganizeChanged(!organizing) }
        }
        val addButton = TvViewFactory.button(context, "Adicionar câmera").apply {
            setOnClickListener { onAddCamera() }
        }
        val manageButton = TvViewFactory.button(context, "Gerenciar câmeras").apply {
            setOnClickListener { onManageCameras() }
        }
        actions.addAll(listOf(organizeButton, addButton, manageButton))
        listOf(organizeButton, addButton, manageButton).forEach { button ->
            actionRow.addView(button, TvViewFactory.marginParams(end = context.dp(12)))
        }
        root.addView(actionRow, TvViewFactory.marginParams(bottom = context.dp(12)))
        root.addView(status, TvViewFactory.marginParams(bottom = context.dp(8)))

        grid = GridLayout(context).apply {
            columnCount = if (state.layout.slotCount == 1) 1 else 2
            rowCount = if (state.layout.slotCount == 1) 1 else state.layout.slotCount / 2
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setBackgroundColor(TvViewFactory.BACKGROUND)
        }
        repeat(state.layout.slotCount) { slot ->
            val camera = state.slots[slot]?.let(camerasById::get)
            val tile = CameraTileView(
                context = context,
                slotIndex = slot,
                camera = camera,
                onSelected = { selected -> onTileSelected(selected.slotIndex, selected.camera) },
                onRetry = onRetryCamera,
                onAttachPlayer = onAttachPlayer,
            )
            tiles += tile
            val row = if (state.layout.slotCount == 1) 0 else slot / 2
            val column = if (state.layout.slotCount == 1) 0 else slot % 2
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row, 1f),
                GridLayout.spec(column, 1f),
            ).apply {
                width = 0
                height = 0
                setMargins(context.dp(5), context.dp(5), context.dp(5), context.dp(5))
            }
            grid.addView(tile, params)
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val hasCameras = cameras.isNotEmpty()
        val focusTarget = tiles.firstOrNull { it.camera != null } ?: tiles.firstOrNull()
        focusTarget?.let { tile ->
            layoutButtons[state.layout]?.nextFocusDownId = organizeButton.id
            organizeButton.nextFocusDownId = if (hasCameras) tile.id else addButton.id
            tile.nextFocusUpId = organizeButton.id
        }
        if (!hasCameras) {
            root.contentDescription = "Nenhuma câmera adicionada"
        }
        listOf(
            Pair(layoutButtons.getValue(CameraLayout.ONE), layoutButtons.getValue(CameraLayout.TWO)),
            Pair(layoutButtons.getValue(CameraLayout.TWO), layoutButtons.getValue(CameraLayout.FOUR)),
        ).forEach { (left, right) ->
            left.nextFocusRightId = right.id
            right.nextFocusLeftId = left.id
        }
        actions.zipWithNext().forEach { (left, right) ->
            left.nextFocusRightId = right.id
            right.nextFocusLeftId = left.id
        }
    }

    fun requestInitialFocus(slot: Int? = null, layout: CameraLayout? = null) {
        val target = slot?.let(tiles::getOrNull)
            ?: layout?.let(layoutButtons::get)
            ?: tiles.firstOrNull { it.camera != null }
            ?: layoutButtons[state.layout]
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!root.isAttachedToWindow) return
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                target?.requestFocus()
            }
        })
    }

    fun setTileError(cameraId: String, message: String?) {
        tiles.firstOrNull { it.camera?.id == cameraId }?.setError(message)
    }

    fun showStatus(message: String?) {
        status.text = message.orEmpty()
        status.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun detachPlayers() {
        tiles.forEach { it.detachPlayer(onAttachPlayer) }
    }
}
