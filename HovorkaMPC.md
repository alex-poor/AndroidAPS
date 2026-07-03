# HovorkaMPC — community-fork release notes

> **EXPERIMENTAL. IN-SILICO VALIDATED ONLY. NOT A MEDICAL DEVICE. NOT FOR THERAPY.**
> This is research software distributed as **source you build yourself** (the standard AndroidAPS model).
> There is no prebuilt APK and no support. If you run it, you do so at your own risk, with full
> understanding that it dictates insulin and has **not** been clinically validated. Do not run it
> closed-loop unattended. When in doubt, use mainline AndroidAPS with oref.

## What this is

A build of AndroidAPS that adds **HovorkaMPC** — an additional, optional APS (dosing) algorithm you can
select instead of OpenAPS SMB/AMA or AutoISF. It is a clean-room **nonlinear model-predictive controller**
built on the published **Hovorka** glucose–insulin model (Hovorka et al., Physiol. Meas. 2004): it
estimates your physiological state from CGM history with a Kalman filter, then plans insulin over a
receding horizon to steer glucose to target. It modulates the **temp basal only** by default.

It exists to explore a genuinely different control approach (physiological MPC) alongside oref's rule set,
and to gather **open-loop** feedback from experienced users. It is not trying to replace anything.

## Safety model (read this)

- **Off by default.** Only visible/selectable in **engineering + developer mode** (like AutoISF/Autotune).
- **Temp-basal only** unless you explicitly opt into microbolus (below).
- **Hard hypo suspends:** forced 0 U/hr at/below 3.9 mmol/L, with a graduated basal floor below 5.0.
- All existing AndroidAPS safety limits still apply — **maxBasal, maxIOB, objectives**. HovorkaMPC only
  *proposes* a rate; the Loop and your pump driver enforce constraints exactly as with oref.
- **Body weight** is a new setting (the model is weight-parameterised).

## Opt-in features (each OFF by default)

| Setting | What it does | Risk |
|---|---|---|
| **Adaptive TDD** | Slowly nudges the operating basal from the last week of enacted insulin + outcomes (bounded, asymmetric — cuts faster than it raises). | Low (basal only, clamped) |
| **IMM Kalman bank** | Runs 8 models spanning fast↔slow carb absorption and picks the best fit each cycle. | Low (estimation only) |
| **Microbolus (SMB)** | When a temp basal can't lower a post-meal peak fast enough, delivers a small correction bolus (bounded by maxIOB/maxBolus, forced to zero on any low). | **Highest — delivers insulin** |
| **Unannounced-meal detection** | Infers carbs you didn't enter from the CGM rise and reacts earlier (best paired with SMB; biased hard against false positives). | Medium (feeds SMB) |

## Validation status (honest)

**In-silico.** Validated against a cohort of 20 virtual patients where the controller's model is
deliberately *not* the patient (SI/EGP/absorption/weight all perturbed, plus ±15% carb-count error). The
build ships these as unit tests (`plugins/aps/src/test/.../hovorka/`). Actual figures from the harness:

| Scenario (n=20 virtual patients, 24 h) | Time-in-range (3.9–10) | Time-below-range | Worst-case min | Severe hypo (<3.0) |
|---|---|---|---|---|
| **TBR-only, model mismatch** | **78%** | 0.5% | 3.7 mmol/L | **0 patients** |
| **Personalised from profile ISF/IC** | **83%** (was 73%, +11 pp) | 0.0% | 4.0 mmol/L | **0 patients** |

Opt-in features (in-silico, asserted by the committed tests): **SMB** reduces the mean post-meal peak and
lifts TIR without adding hypo, and is forced to zero at a low; **adaptive-TDD** recovers a profile that
starts 30% underdosed (lifts last-3-day TIR, converges without windup, cuts the gain after a hypo day);
**meal detection** reacts to unannounced carbs without hypo and invents no phantom carbs from CGM noise.
All 26 in-silico checks pass; the full suite is reproducible from source.

**No clinical data. No human trials. No regulatory clearance.** "It behaved well in simulation" is not
"it is safe for you."

## Real-world use (n = 1, the author)

The author — a developer, not a clinician — is currently running HovorkaMPC **closed-loop on their own
YpsoPump**. This is a **single-person self-experiment by the person who wrote it**, run with eyes on it and
full understanding of the risk. It is **not** evidence that the algorithm is safe for anyone else, it is
**not** a clinical result, and it is **not** a recommendation that you do the same. One developer choosing
to take that risk on themselves says nothing about your safety.

## How to build and run (self-build only)

1. Clone this fork and check out the `hovorka-plugin` branch. Build the `full` variant yourself in Android
   Studio (there is intentionally no distributed APK).
2. Enable **developer/engineering mode** (Preferences → enable engineering mode; use a dev build).
3. Config Builder → APS → select **HovorkaMPC**. Set your **body weight**.
4. Start in **open loop** — review every suggestion against what oref would do. Do not enable closed loop
   until you've watched it behave sanely for days on your own data.
5. Only then, and only if you accept the risk, progress through the objectives as with any AAPS loop.
   Enable SMB last, if at all, with conservative maxIOB/maxBolus.

## Feedback

Please report open-loop observations (screenshots of suggestions vs. oref, any surprising doses) via the
fork's issue tracker. Do **not** file these on `nightscout/AndroidAPS` — this is an independent fork.

## Provenance & licence

Same licence as AndroidAPS (AGPL-3.0). The Hovorka model and MPC/IMM techniques are from the published
literature (Hovorka 2004; Blom & Bar-Shalom 1988). This is an independent community fork and is not
endorsed by the AndroidAPS project, Nightscout, or any device manufacturer.
