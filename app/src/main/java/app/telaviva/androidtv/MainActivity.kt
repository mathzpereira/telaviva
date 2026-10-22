package app.telaviva.androidtv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import app.telaviva.androidtv.data.CameraRepository
import app.telaviva.androidtv.data.LayoutPreferences
import app.telaviva.androidtv.domain.CameraConfig
import app.telaviva.androidtv.domain.CameraCredentials
import app.telaviva.androidtv.domain.CameraGridState
import app.telaviva.androidtv.domain.CameraLayout
import app.telaviva.androidtv.network.CameraAddressValidator
import app.telaviva.androidtv.network.LocalAddressPolicy
import app.telaviva.androidtv.network.onvif.OnvifEndpoint
import app.telaviva.androidtv.network.onvif.OnvifException
import app.telaviva.androidtv.network.onvif.OnvifFailure
import app.telaviva.androidtv.network.onvif.OnvifProfileSelector
import app.telaviva.androidtv.network.onvif.OnvifSoapClient
import app.telaviva.androidtv.network.onvif.ProfileSelectionResult
import app.telaviva.androidtv.network.onvif.WsDiscoveryClient
import app.telaviva.androidtv.playback.CameraPlaybackCoordinator
import app.telaviva.androidtv.playback.CameraTileError
import app.telaviva.androidtv.playback.Media3CameraPlayerFactory
import app.telaviva.androidtv.ui.CameraPickerScreen
import app.telaviva.androidtv.ui.CameraSetupScreen
import app.telaviva.androidtv.ui.FullscreenScreen
import app.telaviva.androidtv.ui.HomeScreen
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class Screen { HOME, SETUP, PICKER, FULLSCREEN }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private lateinit var ioExecutor: ExecutorService
    private lateinit var cameraRepository: CameraRepository
    private lateinit var layoutPreferences: LayoutPreferences
    private lateinit var playbackCoordinator: CameraPlaybackCoordinator
    private var currentScreen = Screen.HOME
    private var currentGrid = CameraGridState(CameraLayout.ONE, List(4) { null })
    private var homeScreen: HomeScreen? = null
    private var setupScreen: CameraSetupScreen? = null
    private var fullscreenScreen: FullscreenScreen? = null
    private var organizing = false
    private var managementMode = false
    private var selectedSlot = 0
    private var selectedFullscreenCameraId: String? = null
    private var lastFocusedSlot: Int? = null
    private var lastFocusedLayout: CameraLayout? = CameraLayout.ONE
    private var wasBackgrounded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraRepository = CameraRepository(this)
        layoutPreferences = LayoutPreferences(this)
        ioExecutor = Executors.newFixedThreadPool(IO_THREADS)
        playbackCoordinator = CameraPlaybackCoordinator(
            playerFactory = Media3CameraPlayerFactory(this),
            credentialProvider = cameraRepository::credentials,
            ioExecutor = ioExecutor,
            mainExecutor = mainExecutor,
            onTileError = ::showPlaybackError,
        )
        showHome()
    }

    override fun onStart() {
        super.onStart()
        if (!wasBackgrounded) return
        wasBackgrounded = false
        when (currentScreen) {
            Screen.HOME -> showHome(lastFocusedSlot, lastFocusedLayout)
            Screen.FULLSCREEN -> selectedFullscreenCameraId?.let(::resumeFullscreen)
            Screen.SETUP, Screen.PICKER -> Unit
        }
    }

    override fun onStop() {
        if (::playbackCoordinator.isInitialized) playbackCoordinator.onAppBackgrounded()
        wasBackgrounded = true
        super.onStop()
    }

    override fun onDestroy() {
        if (::ioExecutor.isInitialized) ioExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android, retained for Android TV Back compatibility")
    override fun onBackPressed() {
        when (currentScreen) {
            Screen.HOME -> super.onBackPressed()
            Screen.SETUP, Screen.PICKER -> showHome(selectedSlot, currentGrid.layout)
            Screen.FULLSCREEN -> closeFullscreen()
        }
    }

    private fun showHome(focusSlot: Int? = null, focusLayout: CameraLayout? = null) {
        homeScreen?.detachPlayers()
        setupScreen = null
        fullscreenScreen = null
        currentScreen = Screen.HOME
        val cameraList = cameraRepository.list()
        val ids = cameraList.mapTo(mutableSetOf(), CameraConfig::id)
        val saved = layoutPreferences.load()
        val normalizedSlots = saved.slots.map { id -> id?.takeIf(ids::contains) }
        currentGrid = CameraGridState(saved.layout, normalizedSlots)
        if (currentGrid.slots != saved.slots) layoutPreferences.save(currentGrid)

        val screen = HomeScreen(
            context = this,
            state = currentGrid,
            cameras = cameraList,
            organizing = organizing,
            onLayoutSelected = { layout ->
                currentGrid = currentGrid.withLayout(layout)
                layoutPreferences.save(currentGrid)
                lastFocusedLayout = layout
                lastFocusedSlot = null
                showHome(focusLayout = layout)
            },
            onOrganizeChanged = { value ->
                organizing = value
                lastFocusedSlot = if (value) selectedSlot else null
                showHome(focusSlot = if (value) selectedSlot else null, focusLayout = currentGrid.layout)
            },
            onAddCamera = { openSetup() },
            onManageCameras = {
                managementMode = true
                openPicker(selectedSlot, management = true)
            },
            onTileSelected = { slot, camera ->
                selectedSlot = slot
                lastFocusedSlot = slot
                if (organizing) {
                    managementMode = false
                    openPicker(slot, management = false)
                } else if (camera != null) {
                    openFullscreen(camera)
                } else if (cameraList.isEmpty()) {
                    openSetup()
                } else {
                    managementMode = false
                    openPicker(slot, management = false)
                }
            },
            onAttachPlayer = playbackCoordinator::attach,
        )
        homeScreen = screen
        setContentView(screen.root)
        screen.requestInitialFocus(focusSlot, focusLayout)
        playbackCoordinator.showGrid(currentGrid, cameraList.associateBy(CameraConfig::id))
    }

    private fun openSetup() {
        homeScreen?.detachPlayers()
        homeScreen = null
        fullscreenScreen = null
        currentScreen = Screen.SETUP
        playbackCoordinator.showGrid(emptyGrid(), emptyMap())
        val screen = CameraSetupScreen(
            context = this,
            onDiscover = { discoverCameras() },
            onUseEndpoint = ::loadOnvifCamera,
            onSave = ::saveCamera,
            onCancel = { showHome(selectedSlot, currentGrid.layout) },
        )
        setupScreen = screen
        setContentView(screen.root)
        screen.requestInitialFocus()
    }

    private fun discoverCameras() {
        val screen = setupScreen ?: return
        screen.showStatus("Buscando câmeras ONVIF na rede local…")
        ioExecutor.execute {
            val endpoints = WsDiscoveryClient(applicationContext).discover()
            mainHandler.post {
                if (currentScreen != Screen.SETUP || setupScreen !== screen) return@post
                screen.showCandidates(endpoints)
                screen.showStatus(
                    if (endpoints.isEmpty()) "Nenhuma câmera encontrada. Você ainda pode configurar o RTSP manualmente."
                    else "Encontradas ${endpoints.size} respostas ONVIF. Selecione uma câmera para carregar os perfis."
                )
            }
        }
    }

    private fun loadOnvifCamera(endpoint: OnvifEndpoint) {
        val screen = setupScreen ?: return
        val username = screen.usernameField.text.toString()
        val password = screen.passwordField.text.toString()
        val credentials = CameraCredentials(username, password)
        screen.showStatus("Carregando perfis H.264 da câmera…")
        ioExecutor.execute {
            val loaded = runCatching {
                val soap = OnvifSoapClient()
                val profiles = soap.getProfiles(endpoint, credentials)
                when (val selection = OnvifProfileSelector.select(profiles)) {
                    ProfileSelectionResult.UnsupportedCodec -> throw UnsupportedCameraCodecException()
                    is ProfileSelectionResult.SelectedProfiles -> {
                        val main = soap.getStreamUri(endpoint, selection.main.token, credentials)
                        val secondary = selection.secondary?.let {
                            soap.getStreamUri(endpoint, it.token, credentials)
                        }
                        Triple(main.toASCIIString(), secondary?.toASCIIString(), endpoint.deviceAddress.hostAddress)
                    }
                }
            }
            mainHandler.post {
                if (currentScreen != Screen.SETUP || setupScreen !== screen) return@post
                loaded.onSuccess { (main, secondary, host) ->
                    screen.fillDiscoveredCamera(host.orEmpty(), main, secondary)
                }.onFailure { error ->
                    screen.showStatus(safeOnvifMessage(error))
                }
            }
        }
    }

    private fun saveCamera(
        name: String,
        host: String,
        mainUri: String,
        secondaryUri: String?,
        credentials: CameraCredentials,
    ) {
        if (name.isBlank()) {
            setupScreen?.showStatus("Informe um nome para a câmera.")
            return
        }
        val camera = try {
            validateCamera(name, host, mainUri, secondaryUri)
        } catch (error: CameraInputException) {
            setupScreen?.showStatus(error.message ?: "Confira os dados da câmera.")
            return
        }

        try {
            cameraRepository.save(camera, credentials)
        } catch (_: Exception) {
            setupScreen?.showStatus("Não foi possível salvar os dados da câmera.")
            return
        }
        setupScreen?.clearPassword()
        val slots = currentGrid.slots.toMutableList()
        val availableSlot = slots.take(currentGrid.layout.slotCount).indexOfFirst { it == null }
            .takeIf { it >= 0 }
        if (availableSlot != null) {
            slots[availableSlot] = camera.id
            currentGrid = CameraGridState(currentGrid.layout, slots)
            layoutPreferences.save(currentGrid)
        }
        showHome(focusSlot = availableSlot, focusLayout = if (availableSlot == null) currentGrid.layout else null)
    }

    private fun validateCamera(
        name: String,
        host: String,
        mainUri: String,
        secondaryUri: String?,
    ): CameraConfig {
        val addresses = try {
            InetAddress.getAllByName(host.trim()).toList()
        } catch (_: Exception) {
            throw CameraInputException("Informe o IP local da câmera.")
        }
        if (addresses.isEmpty() || addresses.any { !LocalAddressPolicy.isAllowed(it) }) {
            throw CameraInputException("O endereço precisa pertencer à rede local.")
        }
        val main = try {
            CameraAddressValidator.validate(mainUri)
        } catch (_: Exception) {
            throw CameraInputException("Informe um endereço RTSP local válido, sem usuário ou senha embutidos.")
        }
        if (addresses.none { it == main.resolvedAddress }) {
            throw CameraInputException("O IP e o endereço RTSP precisam apontar para a mesma câmera.")
        }
        val secondary = secondaryUri?.takeIf(String::isNotBlank)?.let { value ->
            val validated = try {
                CameraAddressValidator.validate(value)
            } catch (_: Exception) {
                throw CameraInputException("O perfil secundário RTSP não é válido para a rede local.")
            }
            if (addresses.none { it == validated.resolvedAddress }) {
                throw CameraInputException("O perfil secundário precisa apontar para a mesma câmera.")
            }
            validated.uriWithoutUserInfo.toASCIIString()
        }
        return CameraConfig(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            host = host.trim(),
            mainRtspUri = main.uriWithoutUserInfo.toASCIIString(),
            subRtspUri = secondary,
        )
    }

    private fun openPicker(slot: Int, management: Boolean) {
        homeScreen?.detachPlayers()
        homeScreen = null
        currentScreen = Screen.PICKER
        selectedSlot = slot
        managementMode = management
        playbackCoordinator.showGrid(emptyGrid(), emptyMap())
        val screen = CameraPickerScreen(
            context = this,
            slotIndex = slot,
            cameras = cameraRepository.list(),
            managementMode = management,
            onSelect = { camera ->
                val slots = currentGrid.slots.mapIndexed { index, id ->
                    when {
                        index == selectedSlot -> camera.id
                        id == camera.id -> null
                        else -> id
                    }
                }
                currentGrid = CameraGridState(currentGrid.layout, slots)
                layoutPreferences.save(currentGrid)
                organizing = false
                managementMode = false
                lastFocusedSlot = selectedSlot
                showHome(focusSlot = selectedSlot)
            },
            onDelete = { camera ->
                cameraRepository.delete(camera.id)
                val slots = currentGrid.slots.map { id -> if (id == camera.id) null else id }
                currentGrid = CameraGridState(currentGrid.layout, slots)
                layoutPreferences.save(currentGrid)
                openPicker(selectedSlot, management = true)
            },
            onAddCamera = { openSetup() },
            onCancel = {
                managementMode = false
                showHome(selectedSlot, currentGrid.layout)
            },
        )
        setContentView(screen.root)
        screen.requestInitialFocus()
    }

    private fun openFullscreen(camera: CameraConfig) {
        homeScreen?.detachPlayers()
        homeScreen = null
        currentScreen = Screen.FULLSCREEN
        selectedFullscreenCameraId = camera.id
        val screen = FullscreenScreen(
            context = this,
            camera = camera,
            onBack = ::closeFullscreen,
            onAttachPlayer = playbackCoordinator::attach,
        )
        fullscreenScreen = screen
        setContentView(screen.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        playbackCoordinator.openFullscreen(camera)
    }

    private fun closeFullscreen() {
        fullscreenScreen?.let { screen ->
            selectedFullscreenCameraId?.let { playbackCoordinator.attach(it, null) }
        }
        fullscreenScreen = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        playbackCoordinator.closeFullscreen()
        showHome(focusSlot = selectedSlot)
    }

    private fun resumeFullscreen(cameraId: String) {
        val camera = cameraRepository.list().firstOrNull { it.id == cameraId }
        if (camera == null) {
            currentScreen = Screen.HOME
            showHome(selectedSlot, currentGrid.layout)
        } else {
            openFullscreen(camera)
        }
    }

    private fun showPlaybackError(cameraId: String, error: CameraTileError) {
        val message = when (error) {
            CameraTileError.UNAVAILABLE -> "Sem sinal. Verifique a rede e tente novamente."
            CameraTileError.AUTHENTICATION_FAILED -> "Usuário ou senha recusados."
            CameraTileError.UNSUPPORTED_STREAM -> "Formato de vídeo não compatível."
        }
        homeScreen?.setTileError(cameraId, message)
    }

    private fun safeOnvifMessage(error: Throwable): String = when (error) {
        is UnsupportedCameraCodecException -> "A câmera não oferece vídeo H.264 compatível."
        is OnvifException -> when (error.reason) {
            OnvifFailure.AUTHENTICATION_FAILED -> "Usuário ou senha recusados pela câmera."
            OnvifFailure.NON_LOCAL_ADDRESS -> "O endereço informado não pertence à rede local."
            OnvifFailure.DEVICE_UNAVAILABLE -> "A câmera não respondeu. Verifique a rede e tente novamente."
            OnvifFailure.INVALID_RESPONSE -> "A câmera retornou uma resposta ONVIF inválida."
            OnvifFailure.UNSUPPORTED_AUTHENTICATION -> "O método de autenticação da câmera não é compatível."
        }
        else -> "Não foi possível carregar os perfis da câmera. Confira usuário e senha."
    }

    private fun emptyGrid() = CameraGridState(CameraLayout.ONE, List(4) { null })

    private class CameraInputException(message: String) : IllegalArgumentException(message)

    private class UnsupportedCameraCodecException : IllegalStateException()

    companion object {
        private const val IO_THREADS = 2
    }
}
