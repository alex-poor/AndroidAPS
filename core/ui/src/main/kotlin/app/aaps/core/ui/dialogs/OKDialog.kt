package app.aaps.core.ui.dialogs

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.os.SystemClock
import android.text.Spanned
import androidx.core.text.HtmlCompat
import androidx.fragment.app.FragmentActivity
import app.aaps.core.compose.components.AlertAction
import app.aaps.core.compose.components.AlertContent
import app.aaps.core.ui.R
import app.aaps.core.ui.extensions.runOnUiThread
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The app's shared message / confirmation dialogs.
 *
 * These now render the redesign's [AlertContent] (Compose, `core:compose` tokens) instead of a
 * MaterialAlertDialog, so every one of the ~46 call sites — loop mode changes, treatment removals,
 * profile switches, maintenance actions — picks up the new design language without changing a line.
 * The API, the run-once guards and the "not cancelable by touching outside" behaviour are unchanged;
 * only the surface is new.
 *
 * If the caller's context can't host Compose (no activity to borrow ViewTree owners from) each entry
 * point falls back to the previous MaterialAlertDialog rather than failing.
 *
 * Callers that actually move insulin should use `HoldConfirmDialog` instead — same content, but the
 * affirmative is a press-and-hold.
 */
object OKDialog {

    // region ---- message (single OK) ----

    @SuppressLint("InflateParams")
    fun show(context: Context, title: String, message: String, runOnDismiss: Boolean = false, runnable: Runnable? = null) {
        val notEmptyTitle = title.ifEmpty { context.getString(R.string.message) }
        var okClicked = false
        val shown = ComposeDialogHost.show(
            context,
            onDismissed = { if (runOnDismiss && !okClicked) runOnUiThread(runnable) }
        ) { dismiss ->
            AlertContent(
                title = notEmptyTitle,
                message = message,
                actions = listOf(
                    AlertAction(context.getString(R.string.ok), primary = true, onClick = {
                        if (!okClicked) {
                            okClicked = true
                            dismiss()
                            runOnUiThread(runnable)
                        }
                    })
                )
            )
        }
        if (!shown) legacyShow(context, notEmptyTitle, message, runOnDismiss, runnable)
    }

    fun show(context: Context, title: String, message: Spanned, runOnDismiss: Boolean = false, runnable: Runnable? = null) =
        show(context, title, message.toDisplayText(), runOnDismiss, runnable)

    fun show(activity: FragmentActivity, title: String, message: Spanned, runOnDismiss: Boolean = false, runnable: Runnable? = null) =
        show(activity as Context, title, message.toDisplayText(), runOnDismiss, runnable)

    // endregion

    // region ---- confirmation (OK / Cancel) ----

    fun showConfirmation(activity: FragmentActivity, message: String, ok: Runnable?) =
        showConfirmation(activity as Context, activity.getString(R.string.confirmation), message, ok, null)

    fun showConfirmation(activity: FragmentActivity, message: Spanned, ok: Runnable?) =
        showConfirmation(activity as Context, activity.getString(R.string.confirmation), message.toDisplayText(), ok, null)

    fun showConfirmation(activity: FragmentActivity, title: String, message: Spanned, ok: Runnable?, cancel: Runnable? = null) =
        showConfirmation(activity as Context, title, message.toDisplayText(), ok, cancel)

    fun showConfirmation(activity: FragmentActivity, title: String, message: String, ok: Runnable?, cancel: Runnable? = null) =
        showConfirmation(activity as Context, title, message, ok, cancel)

    fun showConfirmation(context: Context, message: Spanned, ok: Runnable?, cancel: Runnable? = null) =
        showConfirmation(context, context.getString(R.string.confirmation), message.toDisplayText(), ok, cancel)

    fun showConfirmation(context: Context, title: String, message: Spanned, ok: Runnable?, cancel: Runnable? = null) =
        showConfirmation(context, title, message.toDisplayText(), ok, cancel)

    fun showConfirmation(context: Context, message: String, ok: Runnable?, cancel: Runnable? = null) =
        showConfirmation(context, context.getString(R.string.confirmation), message, ok, cancel)

    @SuppressLint("InflateParams")
    fun showConfirmation(context: Context, title: String, message: String, ok: Runnable?, cancel: Runnable? = null) {
        var clicked = false
        val shown = ComposeDialogHost.show(context) { dismiss ->
            AlertContent(
                title = title,
                message = message,
                actions = listOf(
                    AlertAction(context.getString(android.R.string.ok), primary = true, onClick = {
                        if (!clicked) { clicked = true; dismiss(); runOnUiThread(ok) }
                    }),
                    AlertAction(context.getString(android.R.string.cancel), onClick = {
                        if (!clicked) { clicked = true; dismiss(); runOnUiThread(cancel) }
                    })
                )
            )
        }
        if (!shown) legacyConfirmation(context, title, message, ok, cancel)
    }

    @SuppressLint("InflateParams")
    fun showConfirmation(context: Context, title: String, message: String, ok: DialogInterface.OnClickListener?, cancel: DialogInterface.OnClickListener? = null) =
        showConfirmation(
            context, title, message,
            ok?.let { Runnable { it.onClick(null, DialogInterface.BUTTON_POSITIVE) } },
            cancel?.let { Runnable { it.onClick(null, DialogInterface.BUTTON_NEGATIVE) } }
        )

    // endregion

    // region ---- yes / no / cancel ----

    @SuppressLint("InflateParams")
    fun showYesNoCancel(context: Context, title: String, message: String, yes: Runnable?, no: Runnable? = null) {
        var clicked = false
        val shown = ComposeDialogHost.show(context) { dismiss ->
            AlertContent(
                title = title,
                message = message,
                actions = listOf(
                    AlertAction(context.getString(R.string.yes), primary = true, onClick = {
                        if (!clicked) { clicked = true; dismiss(); runOnUiThread(yes) }
                    }),
                    AlertAction(context.getString(R.string.no), onClick = {
                        if (!clicked) { clicked = true; dismiss(); runOnUiThread(no) }
                    }),
                    AlertAction(context.getString(R.string.cancel), onClick = {
                        if (!clicked) { clicked = true; dismiss() }
                    })
                )
            )
        }
        if (!shown) legacyYesNoCancel(context, title, message, yes, no)
    }

    // endregion

    /**
     * Call sites build their messages as HTML (`formatColor(...)` etc.) for the old AlertDialog. The
     * colours are attributes from the retired palette, so keep the text and let the redesign colour it.
     */
    private fun Spanned.toDisplayText(): String =
        HtmlCompat.fromHtml(toString(), HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

    // region ---- legacy fallbacks (no activity in the context chain) ----

    private fun legacyShow(context: Context, title: String, message: String, runOnDismiss: Boolean, runnable: Runnable?) {
        var okClicked = false
        MaterialAlertDialogBuilder(context, R.style.DialogTheme)
            .setCustomTitle(AlertDialogHelper.buildCustomTitle(context, title))
            .setMessage(message)
            .setPositiveButton(context.getString(R.string.ok)) { dialog, _ ->
                if (!okClicked) {
                    okClicked = true
                    dialog.dismiss()
                    SystemClock.sleep(100)
                    runOnUiThread(runnable)
                }
            }
            .setOnDismissListener { if (runOnDismiss && !okClicked) runOnUiThread(runnable) }
            .show()
            .setCanceledOnTouchOutside(false)
    }

    private fun legacyConfirmation(context: Context, title: String, message: String, ok: Runnable?, cancel: Runnable?) {
        var clicked = false
        MaterialAlertDialogBuilder(context, R.style.DialogTheme)
            .setCustomTitle(AlertDialogHelper.buildCustomTitle(context, title))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                if (!clicked) { clicked = true; dialog.dismiss(); SystemClock.sleep(100); runOnUiThread(ok) }
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                if (!clicked) { clicked = true; dialog.dismiss(); SystemClock.sleep(100); runOnUiThread(cancel) }
            }
            .show()
            .setCanceledOnTouchOutside(false)
    }

    private fun legacyYesNoCancel(context: Context, title: String, message: String, yes: Runnable?, no: Runnable?) {
        var clicked = false
        MaterialAlertDialogBuilder(context, R.style.DialogTheme)
            .setCustomTitle(AlertDialogHelper.buildCustomTitle(context, title))
            .setMessage(message)
            .setPositiveButton(R.string.yes) { dialog, _ ->
                if (!clicked) { clicked = true; dialog.dismiss(); SystemClock.sleep(100); runOnUiThread(yes) }
            }
            .setNegativeButton(R.string.no) { dialog, _ ->
                if (!clicked) { clicked = true; dialog.dismiss(); SystemClock.sleep(100); runOnUiThread(no) }
            }
            .setNeutralButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
            .setCanceledOnTouchOutside(false)
    }

    // endregion
}
