package app.aaps.core.ui.dialogs

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import app.aaps.core.compose.components.AlertAction
import app.aaps.core.compose.components.AlertContent
import app.aaps.core.ui.R
import app.aaps.core.ui.extensions.runOnUiThread
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object TwoMessagesAlertDialog {

    /**
     * Renders in the design system (Compose [AlertContent]): the two messages
     * become two paragraphs under one title, with OK/Cancel. Falls back to the
     * legacy two-message layout only when there is no activity to host Compose.
     * The [icon] is kept for API compatibility; the new alert has no title icon.
     */
    fun showAlert(context: Context, title: String, message: String, secondMessage: String, ok: (() -> Unit)?, cancel: (() -> Unit)? = null, @DrawableRes icon: Int? = null) {
        var clicked = false
        val shown = ComposeDialogHost.show(context) { dismiss ->
            AlertContent(
                title = title,
                message = if (secondMessage.isBlank()) message else "$message\n\n$secondMessage",
                actions = listOf(
                    AlertAction(context.getString(android.R.string.ok), primary = true, onClick = {
                        if (!clicked) { clicked = true; dismiss(); ok?.let { runOnUiThread(it) } }
                    }),
                    AlertAction(context.getString(android.R.string.cancel), onClick = {
                        if (!clicked) { clicked = true; dismiss(); cancel?.let { runOnUiThread(it) } }
                    })
                )
            )
        }
        if (!shown) legacyAlert(context, title, message, secondMessage, ok, cancel, icon)
    }

    @SuppressLint("InflateParams")
    private fun legacyAlert(context: Context, title: String, message: String, secondMessage: String, ok: (() -> Unit)?, cancel: (() -> Unit)?, @DrawableRes icon: Int?) {
        val secondMessageLayout = LayoutInflater.from(context).inflate(R.layout.dialog_alert_two_messages, null)
        (secondMessageLayout.findViewById<View>(R.id.password_prompt_title) as TextView).text = secondMessage

        MaterialAlertDialogBuilder(context, R.style.DialogTheme)
            .setMessage(message)
            .setCustomTitle(AlertDialogHelper.buildCustomTitle(context, title, icon ?: R.drawable.ic_check_white_48dp))
            .setView(secondMessageLayout)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                SystemClock.sleep(100)
                if (ok != null) runOnUiThread { ok() }
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                SystemClock.sleep(100)
                if (cancel != null) runOnUiThread { cancel() }
            }
            .show()
            .setCanceledOnTouchOutside(false)
    }
}
