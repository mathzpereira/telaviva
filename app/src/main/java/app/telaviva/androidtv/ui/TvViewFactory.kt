package app.telaviva.androidtv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import app.telaviva.androidtv.R

internal object TvViewFactory {
    const val BACKGROUND = 0xff0a0f14.toInt()
    const val SURFACE = 0xff18212b.toInt()
    const val FOREGROUND = Color.WHITE
    const val MUTED = 0xffb7c4d1.toInt()
    const val ERROR = 0xffff766f.toInt()

    fun button(context: Context, text: String, description: String = text): Button =
        Button(context).apply {
            id = View.generateViewId()
            this.text = text
            contentDescription = description
            textSize = 20f
            setTextColor(FOREGROUND)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.focusable_action)
            minimumWidth = context.dp(200)
            minimumHeight = context.dp(64)
            setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(12))
        }

    fun heading(context: Context, text: String, sizeSp: Float = 34f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(FOREGROUND)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

    fun body(context: Context, text: String, sizeSp: Float = 20f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(MUTED)
            gravity = Gravity.CENTER_VERTICAL
        }

    fun field(context: Context, hint: String, description: String, inputType: Int): EditText =
        TvFocusableEditText(context).apply {
            id = View.generateViewId()
            this.hint = hint
            contentDescription = description
            textSize = 20f
            setTextColor(FOREGROUND)
            setHintTextColor(MUTED)
            this.inputType = inputType
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.focusable_action)
            minimumHeight = context.dp(64)
            setPadding(context.dp(18), context.dp(8), context.dp(18), context.dp(8))
        }

    fun background(view: View) {
        view.setBackgroundColor(BACKGROUND)
    }

    fun vertical(context: Context): android.widget.LinearLayout =
        android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }

    fun horizontal(context: Context): android.widget.LinearLayout =
        android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BACKGROUND)
        }

    fun marginParams(
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        bottom: Int = 0,
        end: Int = 0,
        top: Int = 0,
        start: Int = 0,
    ) = ViewGroup.MarginLayoutParams(width, height).apply {
        bottomMargin = bottom
        marginEnd = end
        topMargin = top
        marginStart = start
    }
}

private class TvFocusableEditText(context: Context) : EditText(context) {
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            else -> return super.onKeyDown(keyCode, event)
        }
        val next = focusSearch(direction)
        if (next != null && next !== this && next.requestFocus()) return true
        return super.onKeyDown(keyCode, event)
    }
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
