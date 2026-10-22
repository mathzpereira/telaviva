package app.telaviva.androidtv.ui

import android.content.Context
import android.os.StrictMode
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import app.telaviva.androidtv.MainActivity
import app.telaviva.androidtv.data.CameraRepository
import app.telaviva.androidtv.data.LayoutPreferences
import app.telaviva.androidtv.domain.CameraConfig
import app.telaviva.androidtv.domain.CameraCredentials
import app.telaviva.androidtv.domain.CameraGridState
import app.telaviva.androidtv.domain.CameraLayout
import java.util.Collections
import java.util.concurrent.Executor
import junit.framework.AssertionFailedError
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MainActivityStorageThreadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cameraRepository = CameraRepository(context)
    private val layoutPreferences = LayoutPreferences(context)

    @Before
    fun createCameraFixture() {
        cameraRepository.delete(CAMERA_ID)
        cameraRepository.save(
            CameraConfig(
                id = CAMERA_ID,
                name = "Câmera A",
                host = "192.168.1.20",
                mainRtspUri = "rtsp://192.168.1.20:554/main",
                subRtspUri = null,
            ),
            CameraCredentials("test-user", "test-password"),
        )
        layoutPreferences.save(CameraGridState(CameraLayout.FOUR, listOf(CAMERA_ID, null, null, null)))
    }

    @After
    fun removeCameraFixture() {
        cameraRepository.delete(CAMERA_ID)
        layoutPreferences.save(CameraGridState(CameraLayout.ONE, List(4) { null }))
    }

    @Test
    fun repositoryOperationsDoNotRunOnMainThread() {
        val violations = Collections.synchronizedList(mutableListOf<String>())
        lateinit var previousPolicy: StrictMode.ThreadPolicy

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForDisplayed(withContentDescription("Espaço 1: Câmera A"))
            scenario.onActivity { activity ->
                previousPolicy = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .penaltyListener(Executor { command -> command.run() }) { violation ->
                            violations += violation.javaClass.simpleName
                        }
                        .build(),
                )
            }

            press(KeyEvent.KEYCODE_DPAD_UP)
            onView(withContentDescription("Organizar câmeras")).check(matches(isDisplayed()))
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitForDisplayed(withText("Gerenciar câmeras"))
            waitForDisplayed(withText("Remover Câmera A"))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitForDisplayed(withText("Adicionar câmera"))

            scenario.onActivity { activity -> StrictMode.setThreadPolicy(previousPolicy) }
            assertTrue("Disk I/O ran on the UI thread: $violations", violations.isEmpty())
        }
    }

    private fun waitForDisplayed(matcher: Matcher<View>, timeoutMs: Long = 5_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(matcher).check(matches(isDisplayed()))
                return
            } catch (_: NoMatchingViewException) {
            } catch (_: AssertionFailedError) {
            }
            SystemClock.sleep(50)
        }
        onView(matcher).check(matches(isDisplayed()))
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    }

    private companion object {
        const val CAMERA_ID = "main-thread-storage-camera"
    }
}
