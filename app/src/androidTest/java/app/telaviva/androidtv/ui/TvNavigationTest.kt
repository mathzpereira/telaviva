package app.telaviva.androidtv.ui

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isFocused
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import app.telaviva.androidtv.MainActivity
import app.telaviva.androidtv.data.CameraRepository
import app.telaviva.androidtv.data.LayoutPreferences
import app.telaviva.androidtv.domain.CameraConfig
import app.telaviva.androidtv.domain.CameraCredentials
import app.telaviva.androidtv.domain.CameraGridState
import app.telaviva.androidtv.domain.CameraLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TvNavigationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cameraRepository = CameraRepository(context)
    private val layoutPreferences = LayoutPreferences(context)

    @Before
    fun clearCameraFixtures() {
        deleteCameraFixtures()
        layoutPreferences.save(emptyGrid())
    }

    @After
    fun restoreEmptyCameraCatalog() {
        deleteCameraFixtures()
        layoutPreferences.save(emptyGrid())
    }

    @Test
    fun allSetupAndLayoutActionsAreDpadReachable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Layout 1 câmera")).check(matches(isFocused()))

            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            onView(withContentDescription("Layout 2 câmeras")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(CameraLayout.TWO, layoutPreferences.load().layout)

            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            onView(withContentDescription("Layout 4 câmeras")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(CameraLayout.FOUR, layoutPreferences.load().layout)

            press(KeyEvent.KEYCODE_DPAD_LEFT)
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(CameraLayout.ONE, layoutPreferences.load().layout)

            press(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withContentDescription("Organizar câmeras")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withContentDescription("Adicionar câmera")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_CENTER)

            onView(withContentDescription("Buscar câmeras ONVIF")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withContentDescription("Nome da câmera")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withContentDescription("IP da câmera")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withContentDescription("Endereço RTSP principal")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withContentDescription("Usuário da câmera")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withContentDescription("Senha da câmera")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withContentDescription("Salvar câmera")).check(matches(isFocused()))
            onView(withContentDescription("Cancelar")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun emptyTileOpensCameraPicker() {
        saveCamera(CAMERA_A_ID, "Câmera A")
        layoutPreferences.save(
            CameraGridState(CameraLayout.FOUR, listOf(CAMERA_A_ID, null, null, null)),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Espaço 1: Câmera A")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            onView(withContentDescription("Espaço 2 vazio")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_CENTER)

            onView(withContentDescription("Selecionar câmera para o espaço 2"))
                .check(matches(isDisplayed()))
            onView(withContentDescription("Selecionar Câmera A")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun organizeModeAssignsCameraToFocusedSlot() {
        saveCamera(CAMERA_A_ID, "Câmera A")
        saveCamera(CAMERA_B_ID, "Câmera B")
        layoutPreferences.save(
            CameraGridState(CameraLayout.FOUR, listOf(CAMERA_A_ID, null, null, null)),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            press(KeyEvent.KEYCODE_DPAD_UP)
            onView(withContentDescription("Organizar câmeras")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            onView(withContentDescription("Espaço 2 vazio")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_CENTER)

            onView(withContentDescription("Selecionar câmera para o espaço 2"))
                .check(matches(isDisplayed()))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withContentDescription("Selecionar Câmera B")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_CENTER)

            assertEquals(CAMERA_B_ID, layoutPreferences.load().slots[1])
            onView(withContentDescription("Espaço 2: Câmera B")).check(matches(isFocused()))
        }
    }

    @Test
    fun fullscreenBackRestoresGridAndFocus() {
        saveCamera(CAMERA_A_ID, "Câmera A")
        layoutPreferences.save(
            CameraGridState(CameraLayout.TWO, listOf(CAMERA_A_ID, null, null, null)),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Espaço 1: Câmera A")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            onView(withContentDescription("Tela cheia: Câmera A")).check(matches(isDisplayed()))

            press(KeyEvent.KEYCODE_BACK)

            assertEquals(CameraLayout.TWO, layoutPreferences.load().layout)
            assertEquals(CAMERA_A_ID, layoutPreferences.load().slots[0])
            onView(withContentDescription("Espaço 1: Câmera A")).check(matches(isFocused()))
        }
    }

    private fun saveCamera(id: String, name: String) {
        cameraRepository.save(
            CameraConfig(
                id = id,
                name = name,
                host = "127.0.0.1",
                mainRtspUri = "rtsp://127.0.0.1:554/$id/main",
                subRtspUri = "rtsp://127.0.0.1:554/$id/sub",
            ),
            CameraCredentials("test-user", "test-password"),
        )
    }

    private fun deleteCameraFixtures() {
        cameraRepository.delete(CAMERA_A_ID)
        cameraRepository.delete(CAMERA_B_ID)
    }

    private fun emptyGrid() = CameraGridState(
        CameraLayout.ONE,
        listOf(null, null, null, null),
    )

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    }

    private companion object {
        const val CAMERA_A_ID = "tv-navigation-camera-a"
        const val CAMERA_B_ID = "tv-navigation-camera-b"
    }
}
