package app.aaps.plugins.aps.camapsfx

/**
 * The top-level control tick — the clean-room `MPC::GetRate` (0x14540c) orchestration, composing the
 * validated modules end-to-end. This is the COMPOSITION layer: every module it calls is bit-exact vs the
 * binary, and the control-decision sub-chain (horizon → warm-start → setpoint → optimise → rate) is validated
 * as an assembly by [ControllerEnd]. What is NOT yet validated end-to-end is running this whole loop on live
 * data through a [DataAccess] implementation — that needs the AAPS DB wiring + a real-history capture to diff
 * against, which is the remaining integration step. Treat the per-tick numbers as trustworthy only once that
 * diff is green.
 *
 * Order (from `MPC::GetRate`, guide §8):
 *   1. target glucose for this tick (per-tick, e.g. 7.8 in the sanity run — not the 5.8 static floor).
 *   2. **estimation** — `GetDataForLearning` assembles the history horizon; [ImmBank.tickStep] runs the 6-D
 *      EKF/IMM update per step, advancing the persistent estimator [bank] onto the newest CGM.
 *   3. **λ selection** — `lambdaBase`/`lambdaBaseMeal` set dynamically (1.6/1.2 default; 3.2/2.4 when the
 *      offset-mean glucose ≤ target+6.5). (Currently the [Optimiser] constants are the defaults.)
 *   4. **control** — `GetDataForOptimisation` ([HorizonBuilder]) → [Controller.runControlSolve]
 *      (getPreviousAdvice → determineSetPoint → optimise).
 *   5. **safety envelope** — the seven [OutputPipeline] modifiers (no-ops on a benign tick; the trend-brake's
 *      rate-of-change input still needs `GetSlope` + `ProgressModel` to be driven).
 *   6. **finalRate** — round to the pump's 0.05 U/h grid.
 */
object Tick {

    /** Everything the control half needs that isn't the estimator state or the live reads. */
    data class Config(
        val target: Float, val predictLead: Float, val conv: Float,
        val controlStep: Int, val dtInt: Int, val totalMinutes: Int, val nSteps: Int,
        val p1: Int, val p2: Int, val bolus: Int, val mealActive: Int,
        val p9: Float, val w24: Float, val w2c: Float, val optBir: Float, val tau0: Float, val anchor: Float,
    )

    /** The result of one tick: the delivered basal rate + the intermediate solve (for logging / diffing). */
    // NOTE: the standalone project's `controlDecision(data: DataAccess, …)` alternate driver (a thin wrapper over
    // HorizonBuilder + Controller.runControlSolve) is omitted here — the live plugin drives the engine through
    // [Controller.runTick] via [CamapsFxEngine]/[AapsInput], so the `DataAccess` seam is unused inside AAPS.
}
