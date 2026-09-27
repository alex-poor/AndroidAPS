package app.aaps.plugins.aps.hovorka

import kotlin.math.min

/**
 * The last word on what HovorkaMPC hands to the loop. Applied after every other stage, and it only
 * ever REMOVES insulin, so it composes with the descent guard, the hypo suspends and the budget.
 *
 * Two holes it closes, both invisible while everything is normal:
 *
 *  - **maxIOB ≤ 0 had no effect on the temp basal.** AAPS implements low-glucose-suspend mode
 *    (user-chosen or forced by Objectives) and the "doubled BG values" fallback purely as
 *    maxIOB = 0, which oref then applies to high temps. Hovorka used maxIOB only as SMB headroom,
 *    so in LGS the MPC — and the HIGH-CORR floor — could still run a high temp. Here maxIOB ≤ 0
 *    means: nothing above the neutral basal, and no SMB (with negative IOB the SMB headroom
 *    `maxIob - iob` would otherwise be positive even at maxIOB 0).
 *
 *  - **A non-finite rate or SMB passed straight through.** Kotlin's min/max propagate NaN, the
 *    descent guard is skipped (`NaN > 0` is false), and the first thing to notice was
 *    Round.roundTo in SafetyPlugin, which throws — leaving whatever temp was running (possibly
 *    max basal) to run out, then failing the same way every tick. A non-finite value becomes the
 *    neutral basal and no SMB.
 *
 * Pure, so it is unit-tested directly ([HovorkaSafetyGateTest]).
 */
internal object HovorkaSafetyGate {

    data class Result(val rateUhr: Double, val smbU: Double, val note: String)

    /**
     * @param maxIobU the effective maxIOB (constraint ∩ pref). ≤ 0 in LGS / doubled-BG fallback.
     * @param neutralUhr the basal to fall back to — the lower of profile and operating basal.
     */
    fun apply(rateUhr: Double, smbU: Double, maxIobU: Double, neutralUhr: Double): Result {
        val neutral = if (neutralUhr.isFinite() && neutralUhr > 0.0) neutralUhr else 0.0
        if (!rateUhr.isFinite() || !smbU.isFinite())
            return Result(neutral, 0.0, " | NON-FINITE rate=$rateUhr smb=$smbU → neutral %.2f U/hr".format(neutral))

        var rate = rateUhr
        var smb = smbU
        var note = ""
        if (maxIobU <= 0.0) {
            if (rate > neutral) {
                note += " | MAXIOB-0 %.2f→%.2f U/hr (LGS / BG-quality fallback)".format(rate, neutral)
                rate = neutral
            }
            if (smb > 0.0) {
                note += " | MAXIOB-0-SMB %.2f→0".format(smb)
                smb = 0.0
            }
        }
        return Result(rate.coerceAtLeast(0.0), smb.coerceAtLeast(0.0), note)
    }

    /** The basal a failed or neutralised tick falls back to: never more than either basal the user runs. */
    fun neutralBasal(profileBasalUhr: Double, operatingBasalUhr: Double): Double =
        min(profileBasalUhr, operatingBasalUhr).takeIf { it.isFinite() && it > 0.0 } ?: profileBasalUhr.coerceAtLeast(0.0)
}
