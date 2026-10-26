package app.aaps.plugins.configuration.maintenance.cloud.compose

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import app.aaps.core.compose.components.ListRow
import app.aaps.core.compose.components.SecondaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsTheme

/**
 * One bottom-sheet host for the configuration module's sheets — window pinned to
 * the bottom with a transparent background, the content composable handed in via
 * [show]. Mirrors the app's own action sheets (ProfileSwitchDialog) and the
 * swarm plugin's SwarmSheetDialog.
 */
class ConfigSheetDialog : DialogFragment() {
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
            ConfigSheetDialog().apply { this.sheet = sheet }.show(activity.supportFragmentManager, tag)
        }
    }
}

/** Pick where backups go: the one provider row, its authorisation state, and clear/cancel. */
@Composable
fun CloudDirectorySheet(
    title: String,
    driveTitle: String,
    driveSub: String,
    selected: Boolean,
    authLine: String?,
    authWarning: Boolean,
    cloudPath: String?,
    clearLabel: String,
    cancelLabel: String,
    onDrive: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    SheetSurface(title = title, onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ListRow(
                title = driveTitle,
                sub = driveSub,
                onClick = onDrive,
                trailing = { if (selected) Text("●", color = AapsTheme.colors.accent) }
            )
            if (authLine != null) {
                Text(authLine, style = AapsTheme.type.body, color = if (authWarning) AapsTheme.colors.high else AapsTheme.colors.textSecondary)
                if (cloudPath != null) Text(cloudPath, style = AapsTheme.type.caption, color = AapsTheme.colors.textTertiary)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(clearLabel, onClear, Modifier.weight(1f))
                SecondaryButton(cancelLabel, onClose, Modifier.weight(1f))
            }
        }
    }
}

/** Local vs cloud: one tappable row each. */
@Composable
fun ImportSourceSheet(
    title: String,
    localTitle: String,
    localSub: String?,
    cloudTitle: String,
    cloudSub: String?,
    onLocal: () -> Unit,
    onCloud: () -> Unit,
    onClose: () -> Unit
) {
    SheetSurface(title = title, onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ListRow(title = localTitle, sub = localSub, onClick = onLocal)
            ListRow(title = cloudTitle, sub = cloudSub, onClick = onCloud)
        }
    }
}

/** A non-dismissable spinner sheet, dismissed programmatically via [ProgressSheetController]. */
@Composable
fun ProgressSheet(title: String, message: String) {
    SheetSurface(title = title) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp), color = AapsTheme.colors.accent)
            Text(message, style = AapsTheme.type.body, color = AapsTheme.colors.textSecondary)
        }
    }
}

/**
 * A progress sheet with the same `setMessage` / `dismiss` surface the legacy
 * AlertDialog gave, so a long flow that updates its message as it works keeps
 * working with a one-line creation change. The message is Compose state, so
 * updates recompose the sheet.
 */
class ProgressSheetController(
    private val activity: FragmentActivity,
    private val title: String,
    initialMessage: String
) {
    private val message = mutableStateOf(initialMessage)
    private val fragment = ConfigSheetDialog().apply {
        isCancelable = false
        sheet = { _ -> ProgressSheet(title, message.value) }
    }

    fun show(tag: String = "progress") = fragment.show(activity.supportFragmentManager, tag)
    fun setMessage(m: String) { message.value = m }
    fun dismiss() {
        if (fragment.isAdded) fragment.dismissAllowingStateLoss()
    }
}
