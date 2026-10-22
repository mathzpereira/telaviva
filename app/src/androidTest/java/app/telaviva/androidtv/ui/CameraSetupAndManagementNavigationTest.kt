package app.telaviva.androidtv.ui

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isFocused
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import app.telaviva.androidtv.MainActivity
import app.telaviva.androidtv.domain.CameraConfig
import app.telaviva.androidtv.network.onvif.OnvifEndpoint
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSetupAndManagementNavigationTest {
    @Test
    fun discoveredCamerasAreDpadReachableAndSelectable() {
        val first = OnvifEndpoint(
            InetAddress.getByName("192.168.1.20"),
            URI("http://192.168.1.20/onvif/device_service"),
        )
        val second = OnvifEndpoint(
            InetAddress.getByName("192.168.1.21"),
            URI("http://192.168.1.21/onvif/device_service"),
        )
        val selectedEndpoint = AtomicReference<OnvifEndpoint?>()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = CameraSetupScreen(
                    context = activity,
                    onDiscover = {},
                    onUseEndpoint = selectedEndpoint::set,
                    onSave = { _, _, _, _, _ -> },
                    onCancel = {},
                )
                activity.setContentView(screen.root)
                screen.showCandidates(listOf(first, second))
                screen.requestInitialFocus()
            }

            onView(withText("Buscar câmeras ONVIF")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withText("Usar câmera 192.168.1.20")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            onView(withText("Usar câmera 192.168.1.21")).check(matches(isFocused()))
            press(KeyEvent.KEYCODE_DPAD_CENTER)

            assertEquals(second, selectedEndpoint.get())
        }
    }

    @Test
    fun managementRowsShowTheCameraNameAlongsideRemoveAction() {
        val camera = CameraConfig(
            id = "management-camera",
            name = "Entrada",
            host = "192.168.1.20",
            mainRtspUri = "rtsp://192.168.1.20:554/main",
            subRtspUri = null,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = CameraPickerScreen(
                    context = activity,
                    slotIndex = 0,
                    cameras = listOf(camera),
                    managementMode = true,
                    onSelect = {},
                    onDelete = {},
                    onAddCamera = {},
                    onCancel = {},
                )
                activity.setContentView(screen.root)
            }

            onView(withText("Remover Entrada")).check(matches(isDisplayed()))
        }
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    }
}
