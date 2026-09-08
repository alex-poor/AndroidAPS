# HovorkaMPC — in-silico validation harness

Standalone Kotlin (no Android/AAPS) that validates the HovorkaMPC dosing algorithm before it ever
touches hardware. The algorithm files here (`HovorkaModel`, `HovorkaParams`, `HovorkaEkf`,
`HovorkaMpc`, `HovorkaImmBank`, `TddAdapterV2`) are the **same clean-room code** shipped in the AAPS
plugin at `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/hovorka/` (package differs only:
`hovorka.mpc` here vs `app.aaps.plugins.aps.hovorka` there).

**Keeping the two in sync is a manual step, and it has silently lapsed before.** In September 2026 this
copy was found to be months behind: `HovorkaParams` still carried the parameter-ordering bug that shipped
a model 37% too insulin-sensitive (fixed in the plugin on 2026-08-14), and `HovorkaMpc` still optimised a
single basal rate rather than the piecewise-constant sequence. Anyone building on the harness in that
window reproduced a defect that had already been fixed — and separately, this copy's `HovorkaMpc`
reference-trajectory DEFAULTS stayed at the pre-2026-08 values (120/300/10) after the plugin moved to
60/180/13, so any harness run that did not pass them explicitly was not testing the shipped controller.

The two trees are not line-identical by design: the harness adds what only in-silico work needs (cohort
generation, parameter jitter, A/B hooks that are inert at their defaults), so a plain `diff` always shows
something. What matters is the one-directional check — plugin content MISSING here:

```bash
for f in HovorkaModel HovorkaParams HovorkaEkf HovorkaMpc HovorkaImmBank TddAdapterV2; do
  n=$(diff <(sed 's/^package.*//' plugins/aps/src/main/kotlin/app/aaps/plugins/aps/hovorka/$f.kt) \
           <(sed 's/^package.*//' hovorka-mpc/src/main/kotlin/hovorka/mpc/$f.kt) | grep -c '^<')
  [ "$n" = 0 ] || echo "BEHIND: $f ($n plugin lines absent here)"
done
```

Read the hits rather than trusting the count — `open` modifiers and rewrapped comments show up too. Any
DEFAULT VALUE or logic line in that output is real drift and invalidates results from this harness.

The controller is a clean-room Hovorka nonlinear-MPC reimplemented from the published Hovorka 2004
model + our CamAPS FX reverse-engineering — NOT a binary port. See the design + decoded evidence in
`report/algorithm-spec.md` and `report/hovorka-plugin-plan.md` (outside this repo).

## What this harness cannot see

Read in-silico results with this in mind. The harness runs the *algorithm* files only; the plugin's
safety layers live in `HovorkaMpcPlugin` and are **not modelled here** — SITE-GUARD, the IOB-divergence
detector and the hypo suspend all depend on AAPS state (therapy events, persisted records) this harness
has no concept of.

More importantly, the virtual cohort cannot express some of the effects that turned out to matter most.
Its insulin time-to-peak is 40–70 min; a day-one infusion site is nearer 110 min, so **no A/B run here
could have surfaced the site-change effect** that is the largest single signal in the real data. Several
changes that passed this cohort were later rejected when fitted against recorded logs. A pass here is a
necessary check before hardware, not evidence that something helps.

## Run

```bash
./build.sh            # compiles with a local JDK 21 + kotlinc, then runs all self-tests
# TOOLS=/path/to/tools ./build.sh   # if your JDK/kotlinc toolchain lives elsewhere
```

`build.sh` compiles every `src/main/kotlin/**/*.kt` into one jar and runs `hovorka.mpc.DemoKt`, which
prints a suite of PASS/FAIL checks:

- **Model / EKF / closed-loop** sanity (physiology, estimator convergence, matched-model TIR).
- **Robustness** — 20 virtual patients, controller model ≠ patient.
- **2a personalisation** — map profile ISF/IC → model params; cohort TIR vs population baseline.
- **2b temp targets** — the setpoint shifts, the model anchor does not.
- **2c output law** — graduated basal floor + deadband kills the open-loop zero-temp spam.
- **2d adaptive TDD** — multi-day operating-basal gain converges, lifts TIR, stays safe.
- **3a IMM bank** — 8-submodel absorption estimator vs single EKF.

All checks are expected to pass. This harness is the gate: nothing reaches the device (open-loop,
user-approved, TBR-only) until it is green in-silico.
