package app.telaviva.androidtv.network

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import java.util.WeakHashMap

enum class LanAction {
    DISCOVERY,
    CAMERA_CONNECTION,
}

interface LocalNetworkPermissionPlatform {
    val sdkInt: Int
    fun isGranted(activity: Activity): Boolean
    fun requestPermission(activity: Activity, action: LanAction, onResult: (Boolean) -> Unit)
}

class LocalNetworkPermissionGate(
    private val platform: LocalNetworkPermissionPlatform = AndroidLocalNetworkPermissionPlatform,
) {
    fun ensureGranted(activity: Activity, action: LanAction, onResult: (Boolean) -> Unit) {
        if (platform.sdkInt < ANDROID_17_API || platform.isGranted(activity)) {
            onResult(true)
            return
        }
        platform.requestPermission(activity, action, onResult)
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 0x475

        fun onRequestPermissionsResult(
            activity: Activity,
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray,
        ): Boolean = AndroidLocalNetworkPermissionPlatform.onRequestPermissionsResult(
            activity,
            requestCode,
            permissions,
            grantResults,
        )
    }
}

private object AndroidLocalNetworkPermissionPlatform : LocalNetworkPermissionPlatform {
    private val pendingCallbacks = WeakHashMap<Activity, MutableList<(Boolean) -> Unit>>()

    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun isGranted(activity: Activity): Boolean =
        activity.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override fun requestPermission(
        activity: Activity,
        action: LanAction,
        onResult: (Boolean) -> Unit,
    ) {
        val shouldRequest = synchronized(pendingCallbacks) {
            val callbacks = pendingCallbacks.getOrPut(activity) { mutableListOf() }
            callbacks += onResult
            callbacks.size == 1
        }
        if (!shouldRequest) return

        try {
            activity.requestPermissions(
                arrayOf(LOCAL_NETWORK_PERMISSION),
                LocalNetworkPermissionGate.PERMISSION_REQUEST_CODE,
            )
        } catch (_: RuntimeException) {
            val callbacks = synchronized(pendingCallbacks) { pendingCallbacks.remove(activity).orEmpty() }
            callbacks.forEach { it(false) }
        }
    }

    fun onRequestPermissionsResult(
        activity: Activity,
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != LocalNetworkPermissionGate.PERMISSION_REQUEST_CODE) return false

        val granted = permissions.indices.any { index ->
            permissions[index] == LOCAL_NETWORK_PERMISSION &&
                grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
        }
        val callbacks = synchronized(pendingCallbacks) { pendingCallbacks.remove(activity).orEmpty() }
        callbacks.forEach { it(granted) }
        return true
    }

    private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
}

private const val ANDROID_17_API = 37
