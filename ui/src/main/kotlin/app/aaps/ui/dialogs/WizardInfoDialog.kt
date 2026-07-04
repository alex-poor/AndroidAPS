package app.aaps.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.BCR
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.WizardInfoRow
import app.aaps.ui.dialogs.compose.WizardInfoSheet
import app.aaps.ui.dialogs.compose.WizardInfoState
import com.google.gson.Gson
import dagger.android.support.DaggerDialogFragment
import javax.inject.Inject

/**
 * Read-only breakdown of a bolus-wizard calculation. UI is Compose ([WizardInfoSheet]); this
 * fragment only maps the stored [BCR] into a [WizardInfoState] using the SAME formatters/resources
 * the legacy XML used. Public API (`arguments["data"]` JSON of [BCR]) is unchanged so all callers
 * keep working.
 */
class WizardInfoDialog : DaggerDialogFragment() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var dateUtil: DateUtil

    private lateinit var data: BCR

    fun setData(bolusCalculatorResult: BCR) {
        this.data = bolusCalculatorResult
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setGravity(Gravity.BOTTOM)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        (savedInstanceState ?: arguments)?.let {
            it.getString("data")?.let { str ->
                data = Gson().fromJson(str, BCR::class.java)
            }
        }
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(false)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { WizardInfoSheet(state = buildState(), onClose = { dismiss() }) } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("data", Gson().toJson(data).toString())
    }

    /** Maps the stored [BCR] into the presentation state, formatted exactly as the legacy dialog. */
    private fun buildState(): WizardInfoState {
        fun insulin(value: Double) = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, value)

        val bgString = profileUtil.fromMgdlToStringInUnits(data.glucoseValue)
        val isf = profileUtil.fromMgdlToUnits(data.isf)
        val trend = profileUtil.fromMgdlToStringInUnits(data.glucoseTrend * 3)

        val inputs = listOf(
            // BG
            WizardInfoRow(
                label = rh.gs(app.aaps.core.ui.R.string.bg_label),
                value = insulin(data.glucoseInsulin),
                detail = rh.gs(R.string.format_bg_isf, bgString, isf),
                used = data.wasGlucoseUsed
            ),
            // Temp target (affects the BG target; shown as a used-flag row, no insulin of its own)
            WizardInfoRow(
                label = rh.gs(app.aaps.core.ui.R.string.tt_label),
                value = "",
                used = data.wasTempTargetUsed
            ),
            // Trend
            WizardInfoRow(
                label = rh.gs(app.aaps.core.ui.R.string.bg_trend_label),
                value = insulin(data.trendInsulin),
                detail = trend,
                used = data.wasTrendUsed
            ),
            // COB
            WizardInfoRow(
                label = rh.gs(app.aaps.core.ui.R.string.treatments_wizard_cob_label),
                value = insulin(data.cobInsulin),
                detail = rh.gs(R.string.format_cob_ic, data.cob, data.ic),
                used = data.wasCOBUsed
            ),
            // Bolus IOB
            WizardInfoRow(
                label = rh.gs(app.aaps.core.ui.R.string.bolus_iob_label),
                value = insulin(-data.bolusIOB),
                used = data.wasBolusIOBUsed
            ),
            // Basal IOB
            WizardInfoRow(
                label = rh.gs(app.aaps.core.ui.R.string.treatments_wizard_basaliob_label),
                value = insulin(-data.basalIOB),
                used = data.wasBasalIOBUsed
            ),
            // Superbolus
            WizardInfoRow(
                label = rh.gs(app.aaps.core.ui.R.string.superbolus),
                value = insulin(data.superbolusInsulin),
                used = data.wasSuperbolusUsed
            ),
            // Carbs
            WizardInfoRow(
                label = rh.gs(app.aaps.core.ui.R.string.carbs),
                value = insulin(data.carbsInsulin),
                detail = rh.gs(R.string.format_carbs_ic, data.carbs, data.ic)
            ),
            // Correction
            WizardInfoRow(
                label = rh.gs(R.string.treatments_wizard_correction_label),
                value = insulin(data.otherCorrection)
            )
        )

        val result = listOf(
            WizardInfoRow(
                label = rh.gs(app.aaps.core.ui.R.string.percent),
                value = rh.gs(app.aaps.core.ui.R.string.format_percent, data.percentageCorrection)
            ),
            WizardInfoRow(
                label = rh.gs(R.string.treatments_wizard_total_label),
                value = insulin(data.totalInsulin)
            )
        )

        return WizardInfoState(
            inputs = inputs,
            result = result,
            profileName = data.profileName,
            notes = data.note
        )
    }
}
