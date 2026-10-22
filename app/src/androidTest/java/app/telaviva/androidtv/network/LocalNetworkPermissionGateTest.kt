package app.telaviva.androidtv.network

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import app.telaviva.androidtv.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionGateTest {
    @Test
    fun api28DoesNotRequestRuntimePermission() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val platform = FakePermissionPlatform(sdkInt = 28)
                var result: Boolean? = null

                LocalNetworkPermissionGate(platform).ensureGranted(activity, LanAction.DISCOVERY) {
                    result = it
                }

                assertEquals(0, platform.requestCount)
                assertTrue(result == true)
            }
        }
    }

    @Test
    fun api37RequestsBeforeAnyLanOperation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val platform = FakePermissionPlatform(sdkInt = 37)
                var result: Boolean? = null

                LocalNetworkPermissionGate(platform).ensureGranted(activity, LanAction.CAMERA_CONNECTION) {
                    result = it
                }

                assertEquals(1, platform.requestCount)
                assertNull(result)
                platform.completeRequest(granted = false)
                assertEquals(false, result)
            }
        }
    }
}

private class FakePermissionPlatform(override val sdkInt: Int) : LocalNetworkPermissionPlatform {
    var requestCount = 0
        private set
    private var pendingResult: ((Boolean) -> Unit)? = null

    override fun isGranted(activity: Activity): Boolean = false

    override fun requestPermission(activity: Activity, action: LanAction, onResult: (Boolean) -> Unit) {
        requestCount++
        pendingResult = onResult
    }

    fun completeRequest(granted: Boolean) {
        pendingResult?.invoke(granted)
        pendingResult = null
    }
}
