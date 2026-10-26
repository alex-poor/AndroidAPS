package app.aaps.core.ui.dialogs

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import app.aaps.core.compose.components.AlertAction
import app.aaps.core.compose.components.AlertContent
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.ui.R
import app.aaps.core.ui.extensions.runOnUiThread
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// if you need error dialog - duplicate to ErrorDialog and make it and use: AppThemeErrorDialog & R.drawable.ic_header_error instead

object WarningDialog {

    /**
     * Renders in the app's design system (Compose [AlertContent]) with an amber
     * warning rule, falling back to the legacy themed dialog only when there is
     * no activity in the context chain to host Compose — the same bridge
     * [OKDialog] uses.
     */
    fun showWarning(context: Context, title: String, message: String, @StringRes positiveButton: Int = -1, ok: (() -> Unit)? = null, cancel: (() -> Unit)? = null) {
        var clicked = false
        val shown = ComposeDialogHost.show(
            context,
            onDismissed = { if (!clicked) { clicked = true; cancel?.let { runOnUiThread(it) } } }
        ) { dismiss ->
            val actions = buildList {
                if (positiveButton != -1)
                    add(AlertAction(context.getString(positiveButton), primary = true, onClick = {
                        if (!clicked) { clicked = true; dismiss(); ok?.let { runOnUiThread(it) } }
                    }))
                add(AlertAction(context.getString(R.string.dismiss), onClick = {
                    if (!clicked) { clicked = true; dismiss(); cancel?.let { runOnUiThread(it) } }
                }))
            }
            AlertContent(title = title, message = message, actions = actions, tint = AapsTheme.colors.high)
        }
        if (!shown) legacyWarning(context, title, message, positiveButton, ok, cancel)
    }

    @SuppressLint("InflateParams")
    private fun legacyWarning(context: Context, title: String, message: String, @StringRes positiveButton: Int, ok: (() -> Unit)?, cancel: (() -> Unit)?) {
        var okClicked = false
        val builder = MaterialAlertDialogBuilder(context, R.style.AppThemeWarningDialog)
            .setMessage(message)
            .setCustomTitle(AlertDialogHelper.buildCustomTitle(context, title, R.drawable.ic_header_warning, R.style.AppThemeWarningDialog))
            .setNegativeButton(R.string.dismiss) { dialog, _ ->
                if (okClicked) return@setNegativeButton
                okClicked = true
                dialog.dismiss()
                SystemClock.sleep(100)
                if (cancel != null) runOnUiThread { cancel() }
            }
        if (positiveButton != -1) {
            builder.setPositiveButton(positiveButton) { dialog, _ ->
                if (okClicked) return@setPositiveButton
                okClicked = true
                dialog.dismiss()
                SystemClock.sleep(100)
                if (ok != null) runOnUiThread { ok() }
            }
        }
        builder.show().setCanceledOnTouchOutside(true)
    }
}
