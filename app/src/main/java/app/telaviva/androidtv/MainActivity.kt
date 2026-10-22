package app.telaviva.androidtv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(10, 15, 20))
            setPadding(48.dp, 32.dp, 48.dp, 32.dp)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 38f
            gravity = Gravity.CENTER
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 32 },
        )

        val addCamera = Button(this).apply {
            text = getString(R.string.add_camera)
            textSize = 20f
            setTextColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.focusable_action)
            minimumWidth = 260.dp
            minimumHeight = 64.dp
        }
        root.addView(addCamera)
        setContentView(root)
        addCamera.requestFocus()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
