package app.telaviva.androidtv.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import app.telaviva.androidtv.domain.CameraCredentials
import app.telaviva.androidtv.R
import app.telaviva.androidtv.network.onvif.OnvifEndpoint

class CameraSetupScreen(
    context: Context,
    private val onDiscover: () -> Unit,
    private val onUseEndpoint: (OnvifEndpoint) -> Unit,
    private val onSave: (String, String, String, String?, CameraCredentials) -> Unit,
    private val onCancel: () -> Unit,
) {
    val root: View
    val discoveryButton = TvViewFactory.button(context, "Buscar câmeras ONVIF")
    val nameField = TvViewFactory.field(
        context,
        "Nome exibido na TV",
        "Nome da câmera",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
    )
    val hostField = TvViewFactory.field(
        context,
        "Ex.: 192.168.1.50",
        "IP da câmera",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
    )
    val streamField = TvViewFactory.field(
        context,
        "rtsp://192.168.1.50:554/stream",
        "Endereço RTSP principal",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
    )
    val usernameField = TvViewFactory.field(
        context,
        "Usuário da câmera",
        "Usuário da câmera",
        InputType.TYPE_CLASS_TEXT,
    )
    val passwordField = TvViewFactory.field(
        context,
        "Senha da câmera",
        "Senha da câmera",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
    ).apply { isSaveEnabled = false }
    private val status = TvViewFactory.body(context, "")
    private val candidates = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var discoveredSecondaryUri: String? = null
    private val saveButton = TvViewFactory.button(context, "Salvar câmera")
    private val cancelButton = TvViewFactory.button(context, "Cancelar")

    init {
        val content = TvViewFactory.vertical(context).apply {
            setPadding(context.dp(40), context.dp(18), context.dp(40), context.dp(24))
            isSaveEnabled = false
        }
        content.addView(TvViewFactory.heading(context, "Adicionar câmera"), TvViewFactory.marginParams(bottom = context.dp(8)))
        content.addView(discoveryButton, TvViewFactory.marginParams(bottom = context.dp(6)))
        content.addView(status, TvViewFactory.marginParams(bottom = context.dp(6)))
        content.addView(candidates)
        listOf(nameField, hostField, streamField, usernameField, passwordField).forEach { field ->
            content.addView(
                field,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(54)).apply {
                    bottomMargin = context.dp(5)
                },
            )
        }
        val footer = TvViewFactory.horizontal(context)
        footer.addView(saveButton, TvViewFactory.marginParams(end = context.dp(12)))
        footer.addView(cancelButton)
        content.addView(footer)
        root = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(TvViewFactory.BACKGROUND)
            addView(content)
        }

        discoveryButton.setOnClickListener { onDiscover() }
        saveButton.setOnClickListener {
            onSave(
                nameField.text.toString().trim(),
                hostField.text.toString().trim(),
                streamField.text.toString().trim(),
                discoveredSecondaryUri,
                CameraCredentials(usernameField.text.toString(), passwordField.text.toString()),
            )
        }
        cancelButton.setOnClickListener { onCancel() }

        val verticalActions = listOf(
            discoveryButton,
            nameField,
            hostField,
            streamField,
            usernameField,
            passwordField,
            saveButton,
            cancelButton,
        )
        verticalActions.zipWithNext().forEach { (top, bottom) ->
            top.nextFocusDownId = bottom.id
            bottom.nextFocusUpId = top.id
        }
        saveButton.nextFocusRightId = cancelButton.id
        cancelButton.nextFocusLeftId = saveButton.id
    }

    fun requestInitialFocus() {
        discoveryButton.post { discoveryButton.requestFocus() }
    }

    fun showStatus(message: String) {
        status.text = message
    }

    fun showCandidates(endpoints: List<OnvifEndpoint>) {
        candidates.removeAllViews()
        val buttons = mutableListOf<View>()
        endpoints.forEachIndexed { index, endpoint ->
            val address = endpoint.deviceAddress.hostAddress ?: "Câmera ${index + 1}"
            val button = TvViewFactory.button(context = candidates.context, text = "Usar câmera $address")
            button.setOnClickListener { onUseEndpoint(endpoint) }
            candidates.addView(button, TvViewFactory.marginParams(bottom = candidates.context.dp(5)))
            buttons += button
        }

        val firstCandidate = buttons.firstOrNull()
        val lastCandidate = buttons.lastOrNull()
        discoveryButton.nextFocusDownId = firstCandidate?.id ?: nameField.id
        nameField.nextFocusUpId = lastCandidate?.id ?: discoveryButton.id
        firstCandidate?.nextFocusUpId = discoveryButton.id
        lastCandidate?.nextFocusDownId = nameField.id
        buttons.zipWithNext().forEach { (top, bottom) ->
            top.nextFocusDownId = bottom.id
            bottom.nextFocusUpId = top.id
        }
    }

    fun fillDiscoveredCamera(host: String, streamUri: String, secondaryUri: String?) {
        discoveredSecondaryUri = secondaryUri
        hostField.setText(host)
        streamField.setText(streamUri)
        status.text = if (secondaryUri == null) {
            "Câmera encontrada. Preencha usuário e senha e salve."
        } else {
            "Câmera e dois perfis H.264 encontrados. Preencha usuário e senha e salve."
        }
        if (nameField.text.isNullOrBlank()) {
            nameField.setText(nameField.context.getString(R.string.camera_name_template, host))
        }
    }

    fun clearDiscoveredSecondary() {
        discoveredSecondaryUri = null
    }

    fun clearPassword() {
        passwordField.text?.clear()
    }
}
