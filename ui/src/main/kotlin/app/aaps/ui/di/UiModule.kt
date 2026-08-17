package app.aaps.ui.di

import dagger.Module
import dagger.android.ContributesAndroidInjector
import app.aaps.ui.activities.BolusProgressHelperActivity
import app.aaps.ui.activities.ErrorHelperActivity
import app.aaps.ui.activities.ProfileHelperActivity
import app.aaps.ui.activities.StatsActivity
import app.aaps.ui.activities.TreatmentsActivity
import app.aaps.ui.alertDialogs.ErrorDialog
import app.aaps.ui.dialogs.BolusProgressDialog
import app.aaps.ui.dialogs.CalibrationDialog
import app.aaps.ui.dialogs.CarbsDialog
import app.aaps.ui.dialogs.CareDialog
import app.aaps.ui.dialogs.ExtendedBolusDialog
import app.aaps.ui.dialogs.FillDialog
import app.aaps.ui.dialogs.InsulinDialog
import app.aaps.ui.dialogs.LoopDialog
import app.aaps.ui.dialogs.ProfileSwitchDialog
import app.aaps.ui.dialogs.ProfileViewerDialog
import app.aaps.ui.dialogs.TempBasalDialog
import app.aaps.ui.dialogs.TempTargetDialog
import app.aaps.ui.dialogs.TreatmentDialog
import app.aaps.ui.dialogs.WizardDialog
import app.aaps.ui.dialogs.WizardInfoDialog
import app.aaps.ui.services.AlarmSoundService
import app.aaps.ui.widget.Widget
import app.aaps.ui.widget.WidgetConfigureActivity

@Module
@Suppress("unused")
abstract class UiModule {

    @ContributesAndroidInjector abstract fun contributesAlarmSoundService(): AlarmSoundService

    @ContributesAndroidInjector abstract fun contributesWidget(): Widget
    @ContributesAndroidInjector abstract fun contributesWidgetConfigureActivity(): WidgetConfigureActivity

    @ContributesAndroidInjector abstract fun contributesWizardDialog(): WizardDialog
    @ContributesAndroidInjector abstract fun contributesCalibrationDialog(): CalibrationDialog
    @ContributesAndroidInjector abstract fun contributesCarbsDialog(): CarbsDialog
    @ContributesAndroidInjector abstract fun contributesCareDialog(): CareDialog
    @ContributesAndroidInjector abstract fun contributesWizardInfoDialog(): WizardInfoDialog
    @ContributesAndroidInjector abstract fun contributesProfileViewerDialog(): ProfileViewerDialog
    @ContributesAndroidInjector abstract fun contributesExtendedBolusDialog(): ExtendedBolusDialog
    @ContributesAndroidInjector abstract fun contributesFillDialog(): FillDialog
    @ContributesAndroidInjector abstract fun contributesInsulinDialog(): InsulinDialog
    @ContributesAndroidInjector abstract fun contributesTreatmentDialog(): TreatmentDialog
    @ContributesAndroidInjector abstract fun contributesProfileSwitchDialog(): ProfileSwitchDialog
    @ContributesAndroidInjector abstract fun contributesTempBasalDialog(): TempBasalDialog
    @ContributesAndroidInjector abstract fun contributesTempTargetDialog(): TempTargetDialog
    @ContributesAndroidInjector abstract fun contributesLoopDialog(): LoopDialog
    @ContributesAndroidInjector abstract fun contributesBolusProgressDialog(): BolusProgressDialog
    @ContributesAndroidInjector abstract fun contributesErrorDialog(): ErrorDialog

    @ContributesAndroidInjector abstract fun contributeBolusProgressHelperActivity(): BolusProgressHelperActivity
    @ContributesAndroidInjector abstract fun contributeErrorHelperActivity(): ErrorHelperActivity
    @ContributesAndroidInjector abstract fun contributesStatsActivity(): StatsActivity
    @ContributesAndroidInjector abstract fun contributesTreatmentsActivity(): TreatmentsActivity
    @ContributesAndroidInjector abstract fun contributesProfileHelperActivityActivity(): ProfileHelperActivity

}