# HovorkaMPC — in-silico validation harness

Standalone Kotlin (no Android/AAPS) that validates the HovorkaMPC dosing algorithm before it ever
touches hardware. The algorithm files here (`HovorkaModel`, `HovorkaParams`, `HovorkaEkf`,
`HovorkaMpc`, `HovorkaImmBank`, `TddAdapter`) are the **same clean-room code** shipped in the AAPS
plugin at `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/hovorka/` (package differs only:
`hovorka.mpc` here vs `app.aaps.plugins.aps.hovorka` there). Keep the two in sync.

The controller is a clean-room Hovorka nonlinear-MPC reimplemented from the published Hovorka 2004
model + our CamAPS FX reverse-engineering — NOT a binary port. See the design + decoded evidence in
`report/algorithm-spec.md` and `report/hovorka-plugin-plan.md` (outside this repo).

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
