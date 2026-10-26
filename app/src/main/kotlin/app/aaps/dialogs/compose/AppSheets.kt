package app.aaps.dialogs.compose

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SecondaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsTheme

/** Bottom-sheet host for the app module's own sheets. */
class AppSheetDialog : DialogFragment() {
    var sheet: (@Composable (dismiss: () -> Unit) -> Unit)? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val s = sheet ?: run { dismiss(); return View(requireContext()) }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { s { dismiss() } } }
        }
    }

    companion object {
        fun show(activity: FragmentActivity, tag: String, sheet: @Composable (dismiss: () -> Unit) -> Unit) {
            AppSheetDialog().apply { this.sheet = sheet }.show(activity.supportFragmentManager, tag)
        }
    }
}

/**
 * The About sheet. The [message] is already linkified, so it is shown in a
 * TextView (via AndroidView) with a LinkMovementMethod to keep the links
 * clickable — Compose text would flatten them.
 */
@Composable
fun AboutSheet(
    title: String,
    message: CharSequence,
    dontKillLabel: String,
    okLabel: String,
    onDontKill: () -> Unit,
    onClose: () -> Unit
) {
    SheetSurface(title = title, onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val textColor = AapsTheme.colors.textSecondary.toArgb()
            AndroidView(
                factory = { ctx ->
                    TextView(ctx).apply {
                        movementMethod = LinkMovementMethod.getInstance()
                        textSize = 13f
                    }
                },
                update = {
                    it.setTextColor(textColor)
                    it.text = message
                }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(dontKillLabel, onDontKill, Modifier.weight(1f))
                PrimaryButton(okLabel, onClose, Modifier.weight(1f))
            }
        }
    }
}
