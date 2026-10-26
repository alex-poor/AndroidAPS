package app.aaps.plugins.configuration.maintenance.dialogs

import android.content.Context
import app.aaps.core.ui.extensions.runOnUiThread
import app.aaps.core.ui.extensions.scanForActivity
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.configuration.R
import app.aaps.plugins.configuration.maintenance.cloud.compose.ConfigSheetDialog
import app.aaps.plugins.configuration.maintenance.cloud.compose.ImportDetailsSheet
import app.aaps.plugins.configuration.maintenance.cloud.compose.ImportSummaryRow
import app.aaps.plugins.configuration.maintenance.cloud.compose.ImportSummarySheet
import app.aaps.plugins.configuration.maintenance.cloud.compose.ImportTint
import app.aaps.plugins.configuration.maintenance.data.Prefs
import app.aaps.plugins.configuration.maintenance.data.PrefsStatusImpl
import java.util.LinkedList

object PrefImportSummaryDialog {

    fun showSummary(context: Context, importOk: Boolean, importPossible: Boolean, prefs: Prefs, ok: (() -> Unit)?, cancel: (() -> Unit)? = null) {
        val activity = context.scanForActivity() ?: return

        val messageRes = if (importOk) R.string.check_preferences_before_import else {
            if (importPossible) R.string.check_preferences_dangerous_import else R.string.check_preferences_cannot_import
        }

        val details = LinkedList<String>()
        val rows = prefs.metadata.map { (metaKey, metaEntry) ->
            if (metaEntry.info != null) {
                details.add("<b>${context.getString(metaKey.label)}</b>: ${metaEntry.value}<br/><i style=\"color:silver\">${metaEntry.info}</i>")
            }
            ImportSummaryRow(
                label = metaKey.formatForDisplay(context, metaEntry.value),
                metaIcon = metaKey.icon,
                statusIcon = metaEntry.status.icon,
                tint = when (metaEntry.status) {
                    PrefsStatusImpl.WARN  -> ImportTint.WARN
                    PrefsStatusImpl.ERROR -> ImportTint.ERROR
                    else                  -> ImportTint.NORMAL
                },
                onTap = {
                    val msg = if (metaEntry.info != null) "[${context.getString(metaKey.label)}] ${metaEntry.info}"
                    else context.getString(metaKey.label)
                    when (metaEntry.status) {
                        PrefsStatusImpl.WARN  -> ToastUtils.Long.warnToast(context, msg)
                        PrefsStatusImpl.ERROR -> ToastUtils.Long.errorToast(context, msg)
                        else                  -> ToastUtils.Long.infoToast(context, msg)
                    }
                }
            )
        }

        val html = "<!doctype html><html><head><meta charset=\"utf-8\"><style>body { color: white; }</style></head><body>" +
            details.joinToString("<hr>")

        ConfigSheetDialog.show(activity, "importSummary") { dismiss ->
            ImportSummarySheet(
                title = context.getString(R.string.import_setting),
                message = context.getString(messageRes),
                rows = rows,
                detailsLabel = if (details.isNotEmpty()) context.getString(R.string.check_preferences_details_title) else null,
                onDetails = {
                    ConfigSheetDialog.show(activity, "importDetails") { d ->
                        ImportDetailsSheet(
                            context.getString(R.string.check_preferences_details_title),
                            html,
                            context.getString(android.R.string.ok),
                            d
                        )
                    }
                },
                cancelLabel = context.getString(android.R.string.cancel),
                importLabel = if (importPossible)
                    context.getString(if (importOk) R.string.check_preferences_import_btn else R.string.check_preferences_import_anyway_btn)
                else null,
                onCancel = { dismiss(); if (cancel != null) runOnUiThread { cancel() } },
                onImport = { dismiss(); if (ok != null) runOnUiThread { ok() } },
                onClose = dismiss
            )
        }
    }
}
