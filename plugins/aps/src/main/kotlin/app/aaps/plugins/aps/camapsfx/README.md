# CamAPS FX replica (CAMR)

A **bit-exact clean-room reimplementation** of the CamAPS FX closed-loop controller, in Kotlin, running as a
selectable APS algorithm inside this fork.

Where [HovorkaMPC](../hovorka/) is an *independent* MPC built on the published Hovorka model and informed by
reverse-engineering CamAPS, this is the opposite exercise: a faithful transcription of the genuine
controller's decision, reproduced closely enough that it returns **the same basal rate as the real binary,
to the bit**, on the same inputs.

> **Not affiliated with or endorsed by CamDiab.** This is a reverse-engineered reimplementation for
> research and personal use. It is **not clinically validated** and is **off by default**.

---

## What "bit-exact" means here, and how it was proven

The genuine CamAPS FX controller ships as a stripped native library. It was decoded with Ghidra (control
flow) and an ARM64 instruction emulator (Unicorn) that runs the real binary on the host and lets any
internal array be captured mid-decision — so a Kotlin function could be checked against the genuine one's
*actual* floating-point output, not just its intent.

Every stage below was validated that way, function by function, until the whole controller agreed with the
binary to floating-point rounding (`max|Δ|` at the ~1e-5 "FP-floor" — the difference between ARM and JVM
evaluation order, which rounds away on the 0.05 U/h delivery grid).

Two end-to-end results anchor it:

- **Synthetic replay: 49/49 ticks bit-exact** over a full simulated day (49 control cycles).
- **Real data: 49/49 ticks bit-exact** driven from the author's own AAPS database (`live.db`) — the same
  record types this plugin reads live — with **no CamAPS binary anywhere in the loop.**

The genuine binary is used **only offline**, as the oracle those comparisons are made against. It is not
in this repository and is not distributed (see *Provenance* below).

---

## The decision, stage by stage

The controller is **stateless per tick**: exactly like the binary's `oneRun`, it carries no estimator state
between cycles. Each tick re-reads the recent history and re-derives everything. Nothing can silently
persist or corrupt.

1. **Input assembly** (`AapsInput`, `InputParser`) — the trailing ~24 h of glucose, insulin (basal schedule
   × active TBRs, plus boluses), and carbs become the binary's T1D-data record set. DB-agnostic: the same
   code serves both the offline validators and the live plugin.
2. **CGM decimation** (`Decimate`) — the binary does **not** estimate on raw sensor cadence. It caps the CGM
   buffer and resamples onto a coarse grid (≈10–15 min) before learning. Feeding raw 1-min data was the
   single largest early divergence; reproducing the decimation fixed it.
3. **Learning horizon** (`Gdfl`, `GdflWalk`) — assembles the ~8 h estimation window by the binary's exact
   event-snapping walk (validated 56/56 steps bit-exact), reading insulin-infusion / CGM / meal per step.
4. **Cold init** (`CamapsColdInit`) — the per-patient plant template (weight-independent defaults), captured
   once, so learning starts from the same state the binary does.
5. **State estimation** (`Imm`, `Submodel`, `Ekf`, `Plant`) — an **8-submodel IMM bank** of Extended Kalman
   Filters. Each submodel carries a 6-dimensional state including a **bioavailability** estimate; the bank
   mixes them by mode probability. Includes the online BIR (basal-insulin-requirement) adaptation and the
   meal-finalisation covariance reset that pins bioavailability once a meal has aged out.
6. **Prior-meal memory** (`MealPriorDb`) — the within-horizon table of learned per-meal bioavailability and
   mode probability that a later meal in the same time-of-day / size bucket reads back.
7. **Control horizon + optimiser** (`HorizonBuilder`, `Optimiser`) — the set-point trajectory and a
   **Gauss-Newton least-squares** solve for a piecewise-constant basal sequence, regularised by a
   **tridiagonal move-penalty** (not a plain λI — that distinction was decisive), with a dynamic penalty
   that doubles near hypo. Enacts the first block.
8. **Output pipeline** (`OutputPipeline`) — the seven safety modifiers in the binary's order: personal-max
   cap, suspend-on-low, trend brake, occlusion ΔBIR guard, exercise suspend, staleness guard, hypo rescue —
   then the min-rate trickle and 0.05 U/h grid rounding.

`Controller.runTick` composes all of this; `CamapsFxEngine.decide` is the single entry point the plugin
calls. `TddAdapt` holds the decoded day-to-day TDD-adaptation formulas (identity for a cold single tick;
accumulates over days).

---

## Running live

`CamapsPlugin` (package `app.aaps.plugins.aps.camaps`, shown as **"CamAPS FX replica"**) is the APS plugin.
Per tick it:

- reads glucose / boluses / carbs / temp-basals from the AAPS persistence layer and the scheduled basal
  from the active profile,
- builds `AapsInput` → `CamapsFxEngine.decide` → a basal rate,
- clamps it to the **AAPS Max Basal** preference and applies a **raw-CGM hypo backstop** (0 U/h on the
  unsmoothed sensor value below the suspend threshold); the Loop additionally enforces **Max IOB**,
- reports the engine's **own model forecast** as `eventualBG` (the predicted glucose at the ~2.9 h horizon
  under the chosen rate), and enacts an absolute temp basal.

It is **basal-only**: the engine's meal handling and TDD adaptation are intrinsic to the decoded model, not
user toggles, so the Algorithm tab shows only body weight and the standard Max Basal / Max IOB. It is not
enabled by default and must be selected deliberately.

---

## Files

| File | Role |
|---|---|
| `CamapsFxEngine.kt` | The one entry point: records → decision. |
| `AapsInput.kt` | DB-agnostic record-lists → the binary's input structure. |
| `InputParser.kt` | Parses the T1D-data format; insulin-infusion / CGM / meal reads. |
| `Decimate.kt` | CGM load-cap + resample-to-grid before learning. |
| `Gdfl.kt`, `GdflWalk.kt` | Learning-horizon assembly + the event-snapping walk. |
| `HorizonBuilder.kt` | Control/prediction horizon (GDFO) and BIR ramps. |
| `CamapsColdInit.kt` | Per-patient cold-init plant template. |
| `Imm.kt` | The 8-submodel IMM bank: learn, mix, rollout. |
| `Submodel.kt`, `Ekf.kt`, `Plant.kt` | One EKF submodel: the plant, its Jacobians, the update. |
| `MealPriorDb.kt` | Within-horizon prior-meal bioavailability / probability table. |
| `Optimiser.kt` | Gauss-Newton LQ solve with the tridiagonal move-penalty. |
| `OutputPipeline.kt` | The seven safety modifiers + grid rounding. |
| `TddAdapt.kt` | Decoded day-to-day TDD-adaptation formulas. |
| `Controller.kt` | Composes a whole tick. |
| `Tick.kt` | Per-tick config / result types. |

---

## Provenance and limits

- **Clean-room and binary-free.** The Kotlin here is an independent reimplementation. The genuine decrypted
  CamAPS FX library and the JNI scaffolding that runs it are **local-only validation tooling**, kept out of
  this repository by `.gitignore` and never distributed. This plugin does not load, link, or require them.
- **Bit-exact ≠ clinically validated.** Reproducing the binary's arithmetic says the *transcription* is
  faithful. It says nothing about whether the controller is right for you. There is no multi-user testing.
- **One patient, offline.** Parity was proven against one person's data and the host oracle. Live behaviour
  is the author's own to monitor.
- **Reproduces the binary's conservatism too.** Faithful includes faithfully cautious — this controller
  tends to run less basal than an aggressive MPC near and above target.
