package app.aaps.core.interfaces.lifecycle

/**
 * Whether any AAPS screen is currently in front of the user.
 *
 * Used to skip work that only exists to draw the overview. The calculation chain that runs on every
 * CGM tick is 16 workers, and 14 of them build graph series (bucketed points, treatment markers,
 * basal steps, temp-target and running-mode bands, IOB/autosens curves, predictions) by querying
 * Room. None of that is worth doing with the phone in a pocket, and it measured as the single
 * largest CPU consumer in the app.
 *
 * Dosing must never depend on this.
 */
interface AppLifecycle {

    /** True between the first activity's onStart and the last activity's onStop. */
    val uiVisible: Boolean
}
