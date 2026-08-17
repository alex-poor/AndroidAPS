package app.aaps.ui.dialogs.compose

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.aaps.core.compose.components.HoldToConfirmButton
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Confirmation for an action that will actually move insulin.
 *
 * Deliberately a drop-in for `OKDialog.showConfirmation(activity, title, message, ok, cancel)`: same
 * itemised summary (including the constraint warnings the callers build), but the positive action is a
 * press-and-hold rather than a tap. The Bolus Wizard has always gated delivery behind a hold; this is
 * what lets every OTHER delivery route — manual bolus, insulin, extended bolus, prime/fill — use the
 * same gesture, so "how do I commit insulin" has exactly one answer in this app.
 *
 * Callers should keep using plain `OKDialog` when nothing is delivered (carbs-only, record-only), so
 * the hold stays meaningful rather than becoming a reflex.
 */
object HoldConfirmDialog {

    fun show(activity: FragmentActivity, title: String, message: CharSequence, ok: Runnable?, cancel: Runnable? = null) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(false)

        // A ComposeView in a bare Dialog has no ViewTree owners of its own; borrow the host activity's
        // so composition, saved state and lifecycle behave.
        val view = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setContent {
                AapsTheme {
                    HoldConfirmContent(
                        title = title,
                        message = message.toPlainText(),
                        onConfirm = {
                            dialog.dismiss()
                            ok?.run()
                        },
                        onCancel = {
                            dialog.dismiss()
                            cancel?.run()
                        }
                    )
                }
            }
        }
        dialog.setContentView(view)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    /**
     * Callers hand us HTML built with `formatColor(...)` for the legacy AlertDialog. The colours are
     * theme attributes from the old palette, so we take the text and let the redesign colour it.
     */
    private fun CharSequence.toPlainText(): String =
        HtmlCompat.fromHtml(toString(), HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
}

@Composable
private fun HoldConfirmContent(title: String, message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = AapsTheme.colors
    Box(Modifier.fillMaxWidth().padding(AapsSpacing.screenH)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(AapsShape.hero)
                .background(colors.surface)
                .padding(AapsSpacing.cardPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)
        ) {
            Text(AnnotatedString(title), style = AapsType.title, color = colors.textPrimary, textAlign = TextAlign.Center)
            if (message.isNotBlank())
                Text(
                    message,
                    style = AapsType.body,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                )
            HoldToConfirmButton(
                label = "Hold to confirm",
                onConfirm = onConfirm,
                modifier = Modifier.fillMaxWidth().padding(top = AapsSpacing.rowGapSmall)
            )
            Text(
                "Cancel",
                style = AapsType.label,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AapsShape.button)
                    .clickable(onClick = onCancel)
                    .padding(vertical = 12.dp)
            )
        }
    }
}
