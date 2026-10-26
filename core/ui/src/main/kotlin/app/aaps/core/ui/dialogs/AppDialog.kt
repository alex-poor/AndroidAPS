package app.aaps.core.ui.dialogs

import android.content.Context
import app.aaps.core.compose.components.AlertAction
import app.aaps.core.compose.components.AlertContent
import app.aaps.core.ui.extensions.runOnUiThread

/**
 * A styled alert with arbitrary buttons — for the confirmations [OKDialog]'s
 * fixed OK / Cancel / Yes / No set does not cover: a custom positive label, a
 * Yes/No with no Cancel, an extra neutral action. Renders the design-system
 * [AlertContent] through the same host OKDialog uses.
 *
 * Every call site runs from an activity, so there is no legacy fallback: if
 * there were somehow no activity to host Compose, nothing is shown rather than
 * a mismatched dialog.
 */
object AppDialog {

    /** One button: a label, whether it is the filled affirmative or the red destructive, and what it does. */
    class Button(
        val label: String,
        val onClick: () -> Unit = {},
        val primary: Boolean = false,
        val destructive: Boolean = false
    )

    fun show(context: Context, title: String, message: String, buttons: List<Button>) {
        var clicked = false
        ComposeDialogHost.show(context) { dismiss ->
            AlertContent(
                title = title,
                message = message,
                actions = buttons.map { b ->
                    AlertAction(b.label, primary = b.primary, destructive = b.destructive, onClick = {
                        if (!clicked) {
                            clicked = true
                            dismiss()
                            runOnUiThread { b.onClick() }
                        }
                    })
                }
            )
        }
    }
}
