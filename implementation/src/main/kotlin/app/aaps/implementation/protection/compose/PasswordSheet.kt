package app.aaps.implementation.protection.compose

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/**
 * Host for the password sheet. Unlike the other sheet hosts it resizes for the
 * keyboard and asks for it up front — the whole point is text entry — and it is
 * not cancelable, matching the legacy `setCancelable(false)`.
 */
class PasswordSheetDialog : DialogFragment() {
    var sheet: (@Composable (dismiss: () -> Unit) -> Unit)? = null

    init {
        isCancelable = false
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
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
            PasswordSheetDialog().apply { this.sheet = sheet }.show(activity.supportFragmentManager, tag)
        }
    }
}

/**
 * The design-system shell around the existing `passwordprompt` view — the view
 * is hosted verbatim via [AndroidView], so the EditTexts keep their input type,
 * autofill and IME behaviour; only the surround (sheet, buttons) changes.
 */
@Composable
fun PasswordSheet(
    title: String,
    message: String?,
    promptView: View,
    okLabel: String,
    cancelLabel: String,
    onOk: () -> Unit,
    onCancel: () -> Unit
) {
    SheetSurface(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (message != null) Text(message, style = AapsTheme.type.body, color = AapsTheme.colors.textSecondary)
            AndroidView(factory = { promptView }, modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(cancelLabel, onCancel, Modifier.weight(1f))
                PrimaryButton(okLabel, onOk, Modifier.weight(1f))
            }
        }
    }
}
