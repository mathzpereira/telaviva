package app.telaviva.androidtv

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.telaviva.androidtv.data.CameraRepository
import app.telaviva.androidtv.data.LayoutPreferences
import app.telaviva.androidtv.domain.CameraConfig
import app.telaviva.androidtv.domain.CameraCredentials
import app.telaviva.androidtv.domain.CameraGridState
import app.telaviva.androidtv.domain.CameraLayout
import app.telaviva.androidtv.network.LanAction
import app.telaviva.androidtv.network.LocalAddressPolicy
import app.telaviva.androidtv.network.LocalNetworkPermissionGate
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
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class Screen { HOME, SETUP, PICKER, FULLSCREEN }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private val localNetworkPermissionGate = LocalNetworkPermissionGate()
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private lateinit var ioExecutor: ExecutorService
    private lateinit var storageExecutor: ExecutorService
    private lateinit var cameraRepository: CameraRepository
    private lateinit var layoutPreferences: LayoutPreferences
    private lateinit var playbackCoordinator: CameraPlaybackCoordinator
    private var currentScreen = Screen.HOME
    private var screenGeneration = 0L
    private var catalogLoaded = false
    private var cameraCatalog: List<CameraConfig> = emptyList()
    private var currentGrid = CameraGridState(CameraLayout.ONE, List(4) { null })
    private var homeScreen: HomeScreen? = null
    private var setupScreen: CameraSetupScreen? = null
    private var fullscreenScreen: FullscreenScreen? = null
    private var organizing = false
    private var managementMode = false
    private var selectedSlot = 0
    private var selectedFullscreenCameraId: String? = null
    private val retryableErrors = mutableMapOf<String, String>()
    private var lastFocusedSlot: Int? = null
    private var lastFocusedLayout: CameraLayout? = CameraLayout.ONE
    private var wasBackgrounded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraRepository = CameraRepository(this)
        layoutPreferences = LayoutPreferences(this)
        ioExecutor = Executors.newFixedThreadPool(IO_THREADS)
        storageExecutor = Executors.newSingleThreadExecutor()
        playbackCoordinator = CameraPlaybackCoordinator(
            playerFactory = Media3CameraPlayerFactory(this),
            credentialProvider = cameraRepository::credentials,
            ioExecutor = ioExecutor,
            mainExecutor = mainExecutor,
            onTileError = ::showPlaybackError,
        )
        if (Build.VERSION.SDK_INT >= API_TIRAMISU) {
            backInvokedCallback = OnBackInvokedCallback(::handleBack)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                checkNotNull(backInvokedCallback),
            )
        }
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
        screenGeneration++
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wasBackgrounded = true
        super.onStop()
    }

    @Deprecated("Deprecated in Android, retained for Android TV permission result compatibility")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (!LocalNetworkPermissionGate.onRequestPermissionsResult(this, requestCode, permissions, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onDestroy() {
        if (::ioExecutor.isInitialized) ioExecutor.shutdownNow()
        if (::storageExecutor.isInitialized) storageExecutor.shutdownNow()
        if (Build.VERSION.SDK_INT >= API_TIRAMISU) {
            backInvokedCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            handleBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack() {
        when (currentScreen) {
            Screen.HOME -> finish()
            Screen.SETUP, Screen.PICKER -> showHome(selectedSlot, currentGrid.layout)
            Screen.FULLSCREEN -> closeFullscreen()
        }
    }

    private fun showHome(focusSlot: Int? = null, focusLayout: CameraLayout? = null) {
        homeScreen?.detachPlayers()
        setupScreen = null
        fullscreenScreen = null
        currentScreen = Screen.HOME
        val generation = ++screenGeneration
        if (!catalogLoaded) {
            storageExecutor.execute {
                val loaded = runCatching {
                    val cameras = cameraRepository.list()
                    val saved = layoutPreferences.load()
                    val ids = cameras.mapTo(mutableSetOf(), CameraConfig::id)
                    val normalizedSlots = saved.slots.map { id -> id?.takeIf(ids::contains) }
                    val grid = CameraGridState(saved.layout, normalizedSlots)
                    if (grid.slots != saved.slots) layoutPreferences.save(grid)
                    cameras to grid
                }
                mainHandler.post {
                    if (currentScreen != Screen.HOME || screenGeneration != generation) return@post
                    loaded.onSuccess { (cameras, grid) ->
                        cameraCatalog = cameras
                        currentGrid = grid
                    }.onFailure {
                        cameraCatalog = emptyList()
                        currentGrid = emptyGrid()
                    }
                    catalogLoaded = true
                    renderHome(focusSlot, focusLayout)
                    if (loaded.isFailure) {
                        homeScreen?.showStatus("Não foi possível carregar as câmeras salvas.")
                    }
                }
            }
            return
        }
        renderHome(focusSlot, focusLayout)
    }

    private fun renderHome(focusSlot: Int? = null, focusLayout: CameraLayout? = null) {
        val cameraList = cameraCatalog
        val screen = HomeScreen(
            context = this,
            state = currentGrid,
            cameras = cameraList,
            organizing = organizing,
            onLayoutSelected = { layout ->
                currentGrid = currentGrid.withLayout(layout)
                persistLayout(currentGrid)
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
            onRetryCamera = ::retryGridCamera,
            onAttachPlayer = playbackCoordinator::attach,
        )
        homeScreen = screen
        setContentView(screen.root)
        screen.requestInitialFocus(focusSlot, focusLayout)
        retryableErrors.forEach(screen::setTileError)
        val camerasById = cameraList.associateBy(CameraConfig::id)
        val visibleCameras = currentGrid.slots
            .take(currentGrid.layout.slotCount)
            .filterNotNull()
            .distinct()
            .mapNotNull(camerasById::get)
        setKeepScreenOn(visibleCameras.isNotEmpty())
        if (visibleCameras.isEmpty()) {
            screen.showStatus(null)
            playbackCoordinator.showGrid(currentGrid, camerasById)
        } else {
            screen.showStatus("O Tela Viva precisa acessar a rede local para reproduzir as câmeras.")
            localNetworkPermissionGate.ensureGranted(this, LanAction.CAMERA_CONNECTION) { granted ->
                if (currentScreen != Screen.HOME || homeScreen !== screen) return@ensureGranted
                if (granted) {
                    screen.showStatus(null)
                    playbackCoordinator.showGrid(currentGrid, camerasById)
                } else {
                    playbackCoordinator.onAppBackgrounded()
                    visibleCameras.forEach { camera ->
                        setRetryableError(camera.id, "Acesso à rede local negado. Selecione para tentar novamente.")
                    }
                    screen.showStatus("Permita o acesso à rede local para conectar às câmeras.")
                }
            }
        }
    }

    private fun openSetup() {
        screenGeneration++
        setKeepScreenOn(false)
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
        screen.showStatus("O Tela Viva precisa acessar a rede local para buscar câmeras ONVIF.")
        localNetworkPermissionGate.ensureGranted(this, LanAction.DISCOVERY) { granted ->
            if (currentScreen != Screen.SETUP || setupScreen !== screen) return@ensureGranted
            if (!granted) {
                screen.showStatus("Acesso à rede local negado. Você ainda pode configurar o RTSP manualmente.")
                return@ensureGranted
            }
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
    }

    private fun loadOnvifCamera(endpoint: OnvifEndpoint) {
        val screen = setupScreen ?: return
        val username = screen.usernameField.text.toString()
        val password = screen.passwordField.text.toString()
        val credentials = CameraCredentials(username, password)
        screen.showStatus("O Tela Viva precisa acessar a rede local para consultar os perfis da câmera.")
        localNetworkPermissionGate.ensureGranted(this, LanAction.CAMERA_CONNECTION) { granted ->
            if (currentScreen != Screen.SETUP || setupScreen !== screen) return@ensureGranted
            if (!granted) {
                screen.showStatus("Acesso à rede local negado. Confira a permissão e tente novamente.")
                return@ensureGranted
            }
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
    }

    private fun saveCamera(
        name: String,
        host: String,
        mainUri: String,
        secondaryUri: String?,
        credentials: CameraCredentials,
    ) {
        val screen = setupScreen ?: return
        if (name.isBlank()) {
            screen.showStatus("Informe um nome para a câmera.")
            return
        }
        val camera = try {
            validateCamera(name, host, mainUri, secondaryUri)
        } catch (error: CameraInputException) {
            screen.showStatus(error.message ?: "Confira os dados da câmera.")
            return
        }

        screen.showStatus("Salvando câmera com segurança…")
        storageExecutor.execute {
            val saved = runCatching {
                cameraRepository.save(camera, credentials)
                cameraRepository.list()
            }
            mainHandler.post {
                saved.onSuccess { cameras ->
                    cameraCatalog = cameras
                    catalogLoaded = true
                    screen.clearPassword()
                    val slots = currentGrid.slots.toMutableList()
                    val availableSlot = slots.take(currentGrid.layout.slotCount).indexOfFirst { it == null }
                        .takeIf { it >= 0 }
                    if (availableSlot != null) {
                        slots[availableSlot] = camera.id
                        currentGrid = CameraGridState(currentGrid.layout, slots)
                        persistLayout(currentGrid)
                    }
                    if (currentScreen == Screen.SETUP && setupScreen === screen) {
                        showHome(
                            focusSlot = availableSlot,
                            focusLayout = if (availableSlot == null) currentGrid.layout else null,
                        )
                    } else if (currentScreen == Screen.HOME) {
                        showHome(focusSlot = availableSlot ?: lastFocusedSlot, focusLayout = currentGrid.layout)
                    }
                }.onFailure {
                    if (setupScreen === screen) {
                        screen.showStatus("Não foi possível salvar os dados da câmera.")
                    }
                }
            }
        }
    }

    private fun validateCamera(
        name: String,
        host: String,
        mainUri: String,
        secondaryUri: String?,
    ): CameraConfig {
        val address = localAddressLiteral(host)
            ?: throw CameraInputException("Informe o IP local da câmera, sem domínio DNS.")
        if (!LocalAddressPolicy.isAllowed(address)) {
            throw CameraInputException("O endereço precisa pertencer à rede local.")
        }
        val main = manualRtspUri(mainUri)
        val mainAddress = localAddressLiteral(main.host.orEmpty())
        if (mainAddress != address) {
            throw CameraInputException("O IP e o endereço RTSP precisam apontar para a mesma câmera.")
        }
        val secondary = secondaryUri?.takeIf(String::isNotBlank)?.let { value ->
            val validated = manualRtspUri(value)
            if (localAddressLiteral(validated.host.orEmpty()) != address) {
                throw CameraInputException("O perfil secundário precisa apontar para a mesma câmera.")
            }
            validated.toASCIIString()
        }
        return CameraConfig(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            host = host.trim(),
            mainRtspUri = main.toASCIIString(),
            subRtspUri = secondary,
        )
    }

    private fun manualRtspUri(value: String): URI {
        val parsed = try {
            URI(value.trim()).normalize()
        } catch (_: Exception) {
            throw CameraInputException("Informe um endereço RTSP local válido.")
        }
        if (!parsed.scheme.equals("rtsp", ignoreCase = true) || parsed.rawUserInfo != null ||
            parsed.host.isNullOrBlank() || parsed.port != -1 && parsed.port !in 1..MAX_PORT) {
            throw CameraInputException("Informe um endereço RTSP local válido, sem usuário ou senha embutidos.")
        }
        val address = localAddressLiteral(parsed.host.orEmpty())
            ?: throw CameraInputException("O endereço RTSP precisa usar o IP da câmera, sem domínio DNS.")
        if (!LocalAddressPolicy.isAllowed(address)) {
            throw CameraInputException("O endereço RTSP precisa pertencer à rede local.")
        }
        return parsed
    }

    private fun localAddressLiteral(value: String): InetAddress? {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        if (':' in host) return runCatching { InetAddress.getByName(host) }.getOrNull()

        val octets = host.split('.')
        if (octets.size != IPV4_OCTETS) return null
        val bytes = ByteArray(IPV4_OCTETS)
        octets.forEachIndexed { index, octet ->
            val value = octet.toIntOrNull()?.takeIf { it in 0..MAX_IPV4_OCTET } ?: return null
            bytes[index] = value.toByte()
        }
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }

    private fun openPicker(slot: Int, management: Boolean) {
        screenGeneration++
        setKeepScreenOn(false)
        homeScreen?.detachPlayers()
        homeScreen = null
        currentScreen = Screen.PICKER
        selectedSlot = slot
        managementMode = management
        playbackCoordinator.showGrid(emptyGrid(), emptyMap())
        val screen = CameraPickerScreen(
            context = this,
            slotIndex = slot,
            cameras = cameraCatalog,
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
                persistLayout(currentGrid)
                organizing = false
                managementMode = false
                lastFocusedSlot = selectedSlot
                showHome(focusSlot = selectedSlot)
            },
            onDelete = { camera ->
                storageExecutor.execute {
                    val deleted = runCatching {
                        cameraRepository.delete(camera.id)
                        cameraRepository.list()
                    }
                    mainHandler.post {
                        deleted.onSuccess { cameras ->
                            cameraCatalog = cameras
                            retryableErrors.remove(camera.id)
                            val slots = currentGrid.slots.map { id -> if (id == camera.id) null else id }
                            currentGrid = CameraGridState(currentGrid.layout, slots)
                            persistLayout(currentGrid)
                            when (currentScreen) {
                                Screen.PICKER -> openPicker(selectedSlot, managementMode)
                                Screen.HOME -> showHome(selectedSlot, currentGrid.layout)
                                Screen.SETUP, Screen.FULLSCREEN -> Unit
                            }
                        }.onFailure {
                            android.widget.Toast.makeText(
                                this,
                                "Não foi possível remover a câmera.",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
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
        screenGeneration++
        setKeepScreenOn(true)
        homeScreen?.detachPlayers()
        homeScreen = null
        currentScreen = Screen.FULLSCREEN
        selectedFullscreenCameraId = camera.id
        val screen = FullscreenScreen(
            context = this,
            camera = camera,
            onBack = ::closeFullscreen,
            onRetry = { requestFullscreenPlayback(camera) },
            onAttachPlayer = playbackCoordinator::attach,
        )
        fullscreenScreen = screen
        setContentView(screen.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        playbackCoordinator.onAppBackgrounded()
        requestFullscreenPlayback(camera)
    }

    private fun requestFullscreenPlayback(camera: CameraConfig) {
        val screen = fullscreenScreen ?: return
        screen.showStatus("O Tela Viva precisa acessar a rede local para reproduzir esta câmera.")
        localNetworkPermissionGate.ensureGranted(this, LanAction.CAMERA_CONNECTION) { granted ->
            if (currentScreen != Screen.FULLSCREEN || fullscreenScreen !== screen) return@ensureGranted
            if (granted) {
                retryableErrors.remove(camera.id)
                screen.clearError()
                playbackCoordinator.openFullscreen(camera)
            } else {
                playbackCoordinator.onAppBackgrounded()
                setRetryableError(camera.id, "Acesso à rede local negado. Selecione Tentar novamente.")
                screen.showError(retryableErrors.getValue(camera.id))
            }
        }
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
        val camera = cameraCatalog.firstOrNull { it.id == cameraId }
        if (camera == null) {
            currentScreen = Screen.HOME
            showHome(selectedSlot, currentGrid.layout)
        } else {
            openFullscreen(camera)
        }
    }

    private fun showPlaybackError(cameraId: String, error: CameraTileError) {
        val message = when (error) {
            CameraTileError.UNAVAILABLE -> "Sem sinal. Verifique a rede."
            CameraTileError.AUTHENTICATION_FAILED -> "Usuário ou senha recusados."
            CameraTileError.UNSUPPORTED_STREAM -> "Formato de vídeo não compatível."
        }
        setRetryableError(cameraId, message)
        if (currentScreen == Screen.FULLSCREEN && selectedFullscreenCameraId == cameraId) {
            fullscreenScreen?.showError(message)
        }
    }

    private fun retryGridCamera(camera: CameraConfig) {
        val screen = homeScreen ?: return
        retryableErrors.remove(camera.id)
        screen.setTileError(camera.id, null)
        screen.showStatus("Tentando conectar a ${camera.name}…")
        localNetworkPermissionGate.ensureGranted(this, LanAction.CAMERA_CONNECTION) { granted ->
            if (currentScreen != Screen.HOME || homeScreen !== screen) return@ensureGranted
            if (granted) {
                screen.showStatus(null)
                playbackCoordinator.showGrid(currentGrid, cameraCatalog.associateBy(CameraConfig::id))
            } else {
                playbackCoordinator.onAppBackgrounded()
                setRetryableError(camera.id, "Acesso à rede local negado. Selecione para tentar novamente.")
                screen.showStatus("Permita o acesso à rede local para conectar às câmeras.")
            }
        }
    }

    private fun setRetryableError(cameraId: String, message: String) {
        retryableErrors[cameraId] = message
        homeScreen?.setTileError(cameraId, message)
    }

    private fun setKeepScreenOn(keepScreenOn: Boolean) {
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun persistLayout(state: CameraGridState) {
        storageExecutor.execute { layoutPreferences.save(state) }
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
        private const val IPV4_OCTETS = 4
        private const val MAX_IPV4_OCTET = 255
        private const val MAX_PORT = 65535
        private const val API_TIRAMISU = 33
    }
}
