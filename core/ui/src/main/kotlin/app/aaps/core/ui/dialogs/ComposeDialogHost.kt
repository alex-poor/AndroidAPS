package app.aaps.core.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.aaps.core.compose.theme.AapsTheme

/**
 * Hosts redesigned (Compose) dialog content in a plain [Dialog].
 *
 * A bare `Dialog` gives a `ComposeView` no ViewTree owners of its own, so we borrow the host
 * activity's — the same trick `HoldConfirmDialog` uses. When the caller's context can't be unwrapped
 * to an activity (a service/application context) there is nothing to borrow, [show] returns `false`
 * and the caller falls back to the legacy AlertDialog. That path is unreachable in practice — a
 * plain application context can't show a dialog at all — but failing over beats crashing.
 */
internal object ComposeDialogHost {

    /**
     * @param content receives a `dismiss` lambda; call it from every action so the dialog closes
     *                exactly once regardless of which button was pressed.
     * @param onDismissed invoked after the dialog goes away for ANY reason (button or back press).
     * @return false when the context has no activity to borrow lifecycle/state owners from.
     */
    fun show(
        context: Context,
        cancelable: Boolean = true,
        onDismissed: () -> Unit = {},
        content: @Composable (dismiss: () -> Unit) -> Unit
    ): Boolean {
        val owner = context.findOwner() ?: return false

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(cancelable)
        // Matches the legacy OKDialog: a therapy confirmation must not be dismissed by a stray tap
        // outside it. Back still works (setCancelable), as it did before.
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { onDismissed() }

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner as? LifecycleOwner)
            setViewTreeViewModelStoreOwner(owner as? ViewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(owner as? SavedStateRegistryOwner)
            setContent { AapsTheme { content { dialog.dismiss() } } }
        }
        dialog.setContentView(view)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
        return true
    }

    /** Unwrap [ContextWrapper]s (themed contexts, ContextThemeWrapper) down to the hosting activity. */
    private fun Context.findOwner(): Context? {
        var c: Context? = this
        while (c != null) {
            if (c is LifecycleOwner && c is SavedStateRegistryOwner) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }
}
