package app.aaps.plugins.configuration.setupwizard.elements

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.protection.PasswordCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.extensions.scanForActivity
import javax.inject.Inject

class SWFragment @Inject constructor(aapsLogger: AAPSLogger, rh: ResourceHelper, rxBus: RxBus, preferences: Preferences, passwordCheck: PasswordCheck) : SWItem(aapsLogger, rh, rxBus, preferences, passwordCheck) {

    lateinit var fragmentName: String

    fun with(fragmentName: String): SWFragment {
        this.fragmentName = fragmentName
        return this
    }

    override fun generateDialog(layout: LinearLayout) {
        val activity = layout.context.scanForActivity() ?: error("Activity not found")
        val fragment = activity.supportFragmentManager.fragmentFactory.instantiate(
            ClassLoader.getSystemClassLoader(),
            fragmentName
        )
        // Host the fragment in a container with an explicit height rather than adding it straight to the
        // wizard's item column. That column lives inside a ScrollView, which measures its children with an
        // unbounded height -- and a plugin fragment is free to be Compose with a verticalScroll inside it,
        // which throws when measured that way ("Vertically scrollable component was measured with an
        // infinity maximum height"). That crash took out the Local Profile screen and, because the wizard
        // has no way past a screen that dies, every screen after it -- pump selection included.
        //
        // A fixed height gives the fragment a real constraint to measure against, so it scrolls inside
        // itself exactly as it does when the plugin owns the whole screen.
        val host = FrameLayout(layout.context).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (layout.context.resources.displayMetrics.heightPixels * FRAGMENT_HEIGHT_FRACTION).toInt()
            )
        }
        layout.addView(host)
        activity.supportFragmentManager.beginTransaction().replace(host.id, fragment, fragmentName).commitAllowingStateLoss()
    }

    companion object {

        /** Leaves room for the wizard's own title and its previous/next bar. */
        private const val FRAGMENT_HEIGHT_FRACTION = 0.72f
    }
}
