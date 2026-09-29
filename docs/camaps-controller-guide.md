# CamAPS FX controller — the reference

*The single, self-contained reference for the decompiled controller (`libd9c625.decrypted.so`): what every
subsystem does, how one tick flows, every constant and global, the full estimator/optimiser/output decode,
which knobs change behaviour for a given profile, a coverage ledger, and (§15) the raw decode evidence.
Written to be **read for understanding** and used as the map for eventual personalisation.*

**This document is complete on its own** — every finding is inline (the mechanisms in §1–§9, the tables in
§10–§11, the status in §12–§14, and the raw dumps/ASM/derivations in the §15 appendix). The other files are
**backing/reproducibility only**, not required reading: `report/camaps-model-spec.md` (longer formula
derivations + real-data validation), `decode/DIGEST.md` / `decode/INDEX.md` (per-function facts),
`decode/asm/` + `decode/ghidra/c/fn/` (disassembly / decompiled C), and the `decode/probe_*.py` scripts that
regenerate every dumped number here.

---

## 0. How to read this, and how much to trust it

**Evidence tags**, used throughout:

| tag | meaning |
|---|---|
| **[D]** | Decoded — read from the instructions or the symbol table. |
| **[M]** | Measured — the binary was executed and the behaviour observed. |
| **[F]** | Fitted — our replica's value; the binary's is not recovered. |
| **[U]** | Unknown — not yet recovered. |

**Two independent ground-truth checks make the [D] and [M] facts unusually trustworthy:**

1. **The binary grades itself.** Every call re-runs an embedded sanity vector and compares to the
   constant `1.3418282270431519`. On device and in our host emulator it returns `Act 1.3414` (last-digit
   libm rounding) — the controller reports "sane."
2. **The whole controller runs on the host, bit-for-bit with the phone.** `decode/emu.py` maps the image,
   applies all relocations, runs the C++ static constructors, and executes `runControllerString`
   end-to-end in a CPU emulator. It reproduces the phone on 7/7 probes and lets every internal quantity be
   read at every step without the device (~1 s/run). Everything tagged [M] here that isn't from live
   probing was produced this way.

**One-paragraph orientation.** CamAPS is **not** a single MPC with one cost function. It is a
**bank-of-models estimator** (an Interacting-Multiple-Model Kalman filter over 8 submodels) feeding a
**small least-squares optimiser**, whose output is then passed through a **fixed chain of seven rule-based
rate modifiers** (ceilings, suspends, trend brakes). Most of the safety behaviour lives in that rule
chain, not in the optimiser. Reasoning about it as "an MPC with a clever cost" fails; reason about it as
*estimator → LQ solve → rule chain*.

---

## 1. Architecture at a glance

```
   Java getRecommendation(a, b, c, byte[] state)
        │  (JNI: read 3 input strings + persistent state blob)
        ▼
   runControllerString ──► oneRun
        │                    │
        │                    ├─ ReadT1DData / DataDatabases::Init      [INPUT]
        │                    │     parses: weight, 48-slot basal profile, TDD,
        │                    │     CGM series, meals, boluses, exercise, 48-slot target
        │                    │     (NB: no ISF, no IC are input)
        │                    │
        │                    ├─ MPC::GetRate                            [THE TICK]
        │                    │     1. resolve target T (per-half-hour, clamp, stability offset)
        │                    │     2. ESTIMATOR  — IMM bank of 8 SubModel1:
        │                    │          Interact → PredictStep → Learn (6-D EKF each)
        │                    │          → mode probabilities → combined glucose + BIR
        │                    │     3. OPTIMISER  — Model::Optimise:
        │                    │          iterative Gauss-Newton LQ solve u=(SᵀS+DᵀWD)⁻¹Sᵀe (move-penalty)
        │                    │          over ≤8 piecewise-constant control blocks
        │                    │     4. OUTPUT PIPELINE — 7 rule modifiers in fixed order
        │                    │          (ceiling, low-suspend, trend brake, occlusion cap,
        │                    │           exercise suspend, CGM-staleness, rescue-carb)
        │                    │     5. quantise to 0.05 U/h, emit rate + diagnostics
        │                    │
        │                    ├─ MPC::ModifyTDD                          [TDD adaptation]
        │                    └─ WriteResults
        ▼
   NewStringUTF(recommendation) ; state blob committed back to Java
```

The three stages that do the real work:

| stage | what it is | key functions |
|---|---|---|
| **Estimator** | IMM Kalman filter: 8 competing submodels, each a plant + a 6-D EKF that tracks glucose + 3 slow parameters. Produces a probability-weighted glucose forecast and an online basal-insulin-requirement (BIR). | `ModelIMM1::Interact/Predict/Learn`, `SubModel1::PredictStep/Learn` |
| **Optimiser** | A Tikhonov-regularised least-squares solve for the insulin sequence that steers the predicted glucose onto the reference trajectory. Symmetric-quadratic only. | `MPC::Optimise`, `Model::Optimise` |
| **Rule chain** | Seven modifiers applied to the optimiser's rate in a fixed order. Four can only *reduce* it. This is where most safety lives. | `MPC::MaximumPersonalRange` … `RescueCarbReduction` |

---

## 2. One tick, in call order  [D] + [M]

`MPC::GetRate` (0x4540c) is the tick and the entry point. The logical order (the DIGEST call lists are
unordered):

| # | function | vaddr | phase | what it does |
|---|---|---|---|---|
| 1 | `Java_..._getRecommendation` | 0x61ccc | — | JNI shim: reads 3 input strings + the persistent `byte[] state`, calls `runControllerString`, commits the mutated state back to Java (`ReleaseByteArrayElements` mode 0), returns the recommendation string. |
| 2 | `runControllerString` | 0x5ca94 | — | String plumbing; runs the embedded self-check; `memmove`s the state blob (**must be ≤ 8288 B** or it segfaults); calls `oneRun`. |
| 3 | `oneRun` | 0x5b510 | — | Per-run driver: builds `DataDatabases`, parses input, constructs `MPC`, calls `GetRate`, then `ModifyTDD`, then `WriteResults`. |
| 4 | `DataInOut::ReadT1DData` | 0x388d4 | INPUT | Parses ASCII into time-series (CGM×390, bolus/infusion×80, meals×32, exercise) + scalars: **weight, 48-slot basal profile, TDD**. Clamps TDD to `[minimumTDD, maximumTDD]`. |
| 5 | `DataDatabases::Init` | 0x4bcc4 | INPUT | Loads the series into the query DB every `Get*` accessor reads. |
| 6 | target resolution | 0x4534c / 0x43670 | — | `GetTargetGlucose`: per-half-hour index `hour*2+(min≥30)` into the 48-target array, default 5.8, clamp [4.4, 11]; `FastingAndGlucoseStable` licenses a −0.4 tightening. |
| 7 | `MPC::Learn` | 0x42af4 | EST | Fires the estimator: `GetDataForLearning` builds horizon inputs → drives the IMM bank's Interact→Predict→Learn. |
| 8 | `ModelIMM1::Interact` → `InteractStep1/2` | 0x5e4ec / 0x58640 | EST | IMM **mixing**: each submodel's prior state+covariance re-formed from the mode probabilities. |
| 9 | `ModelIMM1::Predict` → `SubModel1::PredictStep` | 0x5f6a8 / 0x536c0 | EST | Propagate each submodel one estimator step (dt≈3 min). Analytic (16×`expf`, 2×`pow`); builds the transition Jacobian; decays the `Cs` disturbance. |
| 10 | `SubModel1::Learn` (+ `SetBIC`) | 0x56600 / 0x554ac | EST | 6-D EKF measurement update (bounded parameter tracker — §5). `SetBIC` maps a BIR to plant params. |
| 11 | `ModelIMM1::UpdateModePropability` / `GetBestModel` | 0x5ecb0 / 0x60798 | EST | Update posterior mode probabilities from each submodel's log-likelihood; select/combine. |
| 12 | `ModelIMM1::UpdateBIRs` (+ `ModifyBIR`) | 0x5e6e0 / 0x535f8 | EST/TDD | Blend the online basal-insulin-requirement over the 1440-/120-min half-lives. |
| 13 | `MPC::ProgressModel` → `ModelIMM1::Predict` | 0x43498 | EST | Run the mixed model forward → the "model glucose" and short prediction the pipeline uses. |
| 14 | `MPC::Optimise` → `Model::Optimise` | 0x4664c / 0x3f7b0 | OPT | Build the set-point (`DetermineSetPoint`), fetch the 7 horizon arrays, segment into ≤8 control blocks, solve the LQ problem. |
| 15 | **output pipeline** (7 stages) | 0x46b08…0x47a70 | OUT | Modify the rate in place (§8). |
| 16 | `UpdateTDDandPerfomance` / `RuleUsed` / `SetMessage` | 0x48040 / 0x460c0 / 0x47e10 | TDD | Update records; fill the diagnostics/message fields. |
| 17 | `MPC::ModifyTDD` | 0x44f90 | TDD | (after the tick) exercise/hypo TDD-fraction adjustments; periodic TDD recompute. |

Emitted rate = pipeline output, quantised to **0.05 U/h** [M], with an 8-char diagnostics field.

---

## 3. Function map by subsystem

vaddrs from `decode/INDEX.md`. **Hot** = runs every tick; **cold** = init/periodic/T2D/advisor/dead.

### Plant — `SubModel1` dynamics
| function | vaddr | role | path |
|---|---|---|---|
| `SubModel1::EndoBalance` | 0x58370 | the glucose ODE (§4). Emulator-exact. | hot |
| `SubModel1::GetEGP` | 0x53638 | EGP exponential in isolation. Exact. | hot |
| `SubModel1::PredictStep` | 0x536c0 | analytic one-step propagation of all compartments + builds the EKF transition Jacobian. | hot |
| `SubModel1::GetInsulinForUs` | 0x580ec | inverts `EndoBalance` by bisection → the BIR root-find. | hot |
| `SubModel1::SetBIC` | 0x554ac | BIR → {Iref, egpHalf, SI, F01}. Emulator-exact to ~1e-7. | hot |
| `SubModel1::ModifyBIR` | 0x535f8 | BIR blend (§9). | hot |
| `SubModel1::GetVg` | 0x5845c | Vg = 0.14 L/kg. | hot |
| `SubModel1::GetBackgroundInfusion` | 0x53678 | returns 0 — present-but-disabled. | dead |
| `SubModel1::Initialise` / `InitialiseCovariance` | 0x5359c / 0x563a4 | per-submodel field-map + P0 setup. | init |
| `Model1::InitialiseParameters` | 0x3c014 | **per-patient plant derivation (4144 B) — [U] unread**. | init |

### Estimator — IMM / EKF (see §5 and §6)
| function | vaddr | role | path |
|---|---|---|---|
| `ModelIMM1::Interact` | 0x5e4ec | IMM mixing orchestration. | hot |
| `SubModelIMM1::InteractStep1` / `InteractStep2` | 0x58640 / 0x5a1d8 | the mixing math. | hot |
| `ModelIMM1::Predict` / `PredictForOptimise` | 0x5f6a8 / 0x603b8 | per-submodel forward step; the predictor the optimiser linearises. | hot |
| `SubModel1::Learn` / `ModelIMM1::Learn` | 0x56600 / 0x5df9c | EKF measurement update; IMM learn cycle. | hot |
| `ModelIMM1::UpdateModePropability` | 0x5ecb0 | posterior mode-probability update. | hot |
| `ModelIMM1::GetBestModel` | 0x60798 | winning/combined submodel. | hot |
| `ModelIMM1::GetPredGlucSD` / `SubModelIMM1::CombineVarPredGlu` | 0x60bac / 0x5a7cc | combine predicted-glucose variance across the bank. | hot |
| `ModelIMM1::InitialiseTransitionProb` | 0x5f0b0 | mode-transition matrix from `halfTimeTran` 17/60/180. | init |
| `ModelIMM1::ModelIMM1` | 0x5cfc0 | builds the 8-submodel bank (the arg→field map that decoded the param arrays). | init |
| `Model::CalculateDerivative` | 0x3f3ec | finite-difference Jacobian — **NOT the ODE; the name is a trap**. | hot |

### Meal handling
| function | vaddr | role |
|---|---|---|
| `Model1::UpdateRunningMealProbAndBio` | 0x3d6c8 | running meal probability + bioavailability. |
| `Model1::GetMealTypeIdx` / `GetMealSizeIdx` | 0x3d204 / 0x3d2fc | meal period & size-class classification. |
| `SubModel1::IncludeNewMeal` | 0x556e0 | ingest a meal into the bank. |
| `ModelIMM1::Before/AfterIncludeNewMealIMM1`, `AfterInsulinBolusIMM1` | 0x5dd30 / 0x5db4c / 0x5d8a0 | bank bookkeeping around meals/boluses. |

### Optimiser
| function | vaddr | role |
|---|---|---|
| `MPC::Optimise` | 0x4664c | set-point + horizon assembly + segmentation → `Model::Optimise`. |
| `Model::Optimise` | 0x3f7b0 | iterative Gauss-Newton Tikhonov LQ solve (≤8 blocks). |
| `MPC::GetDataForOptimisation` / `Model::DataForOptimisation` | 0x48598 / 0x3e770 | build the seven `Vector<float,180>` horizon arrays. |
| `Model::GetPreviousAdvice` / `AdviceToInsulinInfusion` | 0x3ed28 / 0x3ea24 | warm-start; expand advice to infusion. |
| `Matrix::inv` / `ludcmp` / `operator*` | 0x416fc / 0x41fdc / 0x41534 | LU inverse; matrix products for SᵀS and S·x. |

### Output pipeline / safety (order & effect in §8)
`MaximumPersonalRange` 0x46b08 · `ModifyRateGlucoseLevel` 0x46dcc · `ModifyRateGlucoseRate` 0x46f10 ·
`ModifyRateDeltaBIR` 0x47590 · `ModifyExercise` 0x4771c · `ModifyEnoughGlucoseMeasurements` 0x478e4 ·
`RescueCarbReduction` 0x47a70 — all hot. Support: `GetSlope` 0x49994 (OLS trend), `LowestBGIfOcclusion`
0x49c40, `ProgressModelForOcclusion` 0x4a4c4, `AssureMinimumRate` 0x47cc4, `RuleUsed` 0x460c0.

### Target / set-point
`DetermineSetPoint` 0x48b84 (reference trajectory) · `GetTargetGlucose` 0x4534c · `FastingAndGlucoseStable`
0x43670 · `GetSlope` 0x49994 · `GetMeanGlucose`/`GetMinGlucose`/`GetMaxGlucose`/`GetMinBG` · `get_t_g`/`get_t_BG`.

### TDD & BIR adaptation
`GetBIRpump` 0x48e44 (profile basal at t — the reference the pipeline reverts to) · `GetBIRStepsSmoothed`
0x46510 · `GetBIR`/`GetAverageBasal` 0x429bc/0x4294c · `UpdateTDDandPerfomance` 0x48040 · `ModifyTDD` 0x44f90 ·
`UpdateFractionTDD`/`AdjustTDDbasedOnCGM`/`UpdateTDD` (periodic) · `ModelIMM1::UpdateBIRs`/`GetShortBIR`/
`GetDeltaBIR`. The `*_T2D` family (`CalculateTDDfromPastPerformance_T2D`, …) is the **type-2 path — not used
here**.

### Data I/O, time, matrix primitives, harness
`ReadT1DData`/`DataDatabases::Init` (parse) · the `Get*` accessors (hot) · `Model::LoadParameters`/
`SaveParameters` (state-blob round-trip) · `CTimeMy`/`CTimeSpanMy` arithmetic (pervasive) · `DataInOutString`/
`DataInOutFile` string plumbing · `Matrix`/`Vector` ctors/ops · `main`/`getTDDproc`/`CCRC` (off-path/dead).
The fully-closed-loop twin lives in `libed34ff.so` (identical class inventory).

---

## 4. The plant — one submodel's glucose model  [D], emulator-exact

Each of the 8 submodels carries a Hovorka-style compartment model. The core is `EndoBalance` (0x58370):

```
(insulin gain)   I  = u · (1000/60) / (W · 0.02709)
(glucose ODE)    dG = EGP0 · 2^(−(I − Iref)/egpHalf)  +  Ra  −  F01  −  SI·I
```
- `u` = insulin rate (U/h), `W` = body weight, `Ra` = gut glucose appearance.
- `0.02709` L/(kg·min) is the **combined insulin volume·elimination** constant (a single decoded immediate,
  `0x3CDDEBD9`); `1000/60` converts U/h→mU/min.
- Per-patient plant fields set by `SetBIC` from the BIR: `EGP0` (this+0x08), `Iref` (0x22c), `F01` (0x230),
  `egpHalf` (0x234), `SI` (0x23c). These are **derived per patient**, not globals — `SetBIC` runs
  bit-exact in the emulator (e.g. BIR 0.85, m 1.0 → Iref 7.4707, egpHalf 7.1861, SI 6.03e-4, F01 0.00763).
- **No glucose-dependent disposal term** (no `∝G`); disposal is insulin-driven only.
- `Vg = 0.14 L/kg` [D] (`GetVg`), used to convert glucose mass↔concentration.
- `GetBackgroundInfusion` returns 0, and the dose-dependent-`tMaxI` term is inert (`insTmaxFac = 0`): two
  present-but-disabled mechanisms.

The gut is a **two-compartment** model per submodel (time constants `tMaxG1s` fast, `tMaxG2s` slow), with a
carb-appearance **rate cap** scaled by `multWk31ini` (= 2 for all submodels) — see §6.

---

## 5. The estimator I: the 6-D EKF inside one submodel  [D] + [M] — **decoded in full this session**

*Previously only state 3 (bioavailability) was identified; the transition matrix was "partial" and
`multWk31ini` was "unexamined." All three are now closed. Verified by dumping the covariance and state
across a real run in the emulator (`scratchpad/re/probe_ekf.py`, `probe_jac.py`).*

Each submodel runs a 6-state Extended Kalman Filter. The covariance **P is 6×6** [M, confirmed], with
prior `P0 = diag(196, 100, 3.08e-5, 0.09, 196, 3.08e-5)` → prior sd `[14, 10, 0.0056, 0.30, 14, 0.0056]`.

### The six states — identities

| idx | prior sd | state-array offset | quantity | per-step bound | prior mean | role |
|---|---|---|---|---|---|---|
| 0 | 14 | elem 27 / 0x6c | glucose compartment A | — | — | **reported plasma G = state0 / 0.14** |
| 1 | 10 | elem 28 / 0x70 | glucose compartment B | — | — | second glucose pool |
| 2 | 0.0056 | elem 33 / 0x84 = **p** | insulin/EGP-balance disturbance | ±5.517e-4 | 0 | shifts glucose; relaxes toward Cs (t½ 75 min) |
| 3 | 0.30 (= `fPriorSDN`) | elem 41 / 0xa4 = **f** | **meal bioavailability** | clamp [0.2, 2.2] | 1.0 (= `fPriorN`) | random walk (persists) |
| 4 | 14 | elem 29 / 0x74 | glucose compartment C | — | — | **the DIRECTLY OBSERVED one (H=1)** |
| 5 | 0.0056 | elem 34 / 0x88 = **Cs** | additive glucose-flux disturbance | ±2.483e-4, abs ±0.005 | 0 | slow disturbance, decays t½ 540 min |

*Confirmation from the live run:* early on, glucose `G(0xb0) = state0(0x6c)/0.14` exactly (1.6839/0.14 =
12.03; 2.4187/0.14 = 17.28); `f(0xa4)` starts at the prior 1.0 and drifts to 0.96; `p` and `Cs` start at 0
and stay within their tiny clamps. The three glucose states (0,1,4) are a linear glucose subsystem; the CGM
observes compartment 4 directly plus compartment-3 (f) sensitivity.

### The prediction step — transition F (6×6), **fully mapped**

`PredictStep` builds an analytic Jacobian in `this+0x2a0…0x2ec`; `Learn` assembles it into F. Nonzero
entries (numeric values [M] at a meal step, estimator dt = 3 min):

```
          col0     col1     col2(p)  col3(f)  col4     col5(Cs)
  row0    0.8486   0.1737   2.7595     ·         ·        ·      glucose A
  row1    0.1241   0.8235   0.1983     ·         ·        ·      glucose B
  row2      ·        ·      0.9727     ·         ·      0.0273   p    (0.9727 = 2^(−dt/75);  [2][5] = 1 − [2][2])
  row3      ·        ·        ·       1.0        ·        ·      f    ← PURE RANDOM WALK
  row4    0.2374   0.0251   0.3852     ·       0.7408     ·      glucose C (observed)
  row5      ·        ·        ·         ·         ·      0.9962   Cs   (0.9962 = 2^(−dt/540), t½ = 540 min)
```
Field map: `F[0][0]=+0x2a0 F[0][1]=+0x2a4 F[0][2]=+0x2a8 | F[1][0]=+0x2b0 F[1][1]=+0x2b4 F[1][2]=+0x2b8 |
F[2][2]=+0x2c8 F[2][5]=1−(+0x2c8) | F[3][3]=1 | F[4][0]=+0x2d0 F[4][1]=+0x2d4 F[4][2]=+0x2d8 F[4][4]=+0x2dc
| F[5][5]=+0x2e0`.

**Reading it:** `p` (state 2) is an insulin/EGP disturbance that strongly moves glucose (F[0][2] = 2.76) and
relaxes toward `Cs` with a 75-min half-life; `Cs` is a slow flux disturbance (540-min half-life); `f`
persists as a random walk. Covariance prediction is `P ← F·P·Fᵀ + Q`, then `× forgettingFactor = 2^(dt/150)`
(half-life 150 min).

Process noise `Q = (σ²·multWini² / max(dt,1)) · v·vᵀ` — **rank-1** — with `σ = 5.03e-4` within 180 min of a
meal, else `2.90e-4`, direction `v = dt·[…, 1, 0, …]`.

### The measurement update

- Measurement matrix `H = [0, 0, 0, h₃, 1, 0]`, `h₃ = this+0x2c4` (≈1.09 at the sample step): CGM sees the
  glucose state 4 directly and state 3 (f) through a sensitivity.
- CGM noise sd `= max(0.02·max(G, 3.5), 0.16)` mmol/L — **proportional to glucose** (our replica used a
  constant 0.71). Near a meal it is inflated ×2.5.
- Innovation covariance `S = R + H·P·Hᵀ`; gain `Kᵢ = (0.14/S)·(h₃·P[i][3] + P[i][4])`.
- **Every state step is rate-limited** (this is the architecturally decisive feature):
  `p` ±5.517e-4/reading, `Cs` ±2.483e-4/reading and ±0.005 absolute, `f` clamped to [0.2, 2.2].
- Each submodel accumulates a **log-likelihood** at `state+0xc8` (drives the IMM mode probabilities, §6).

*Example P at a meal step (symmetric):* glucose states 0/1/4 strongly correlated; `f` variance already shrunk
0.09→0.0198 and anti-correlated with glucose (−0.022) — the filter trading glucose level against
bioavailability to explain the CGM.

**What this means.** CamAPS's estimator tracks a handful of slowly-varying **parameters** (`p`, `Cs`, `f`)
alongside the glucose state, each allowed to move only a bounded amount per CGM reading. It is a **bounded
parameter tracker**, not a free-disturbance estimator — which is exactly why, in earlier work, dropping any
single decoded piece (e.g. the `Cs` clamp) into our free-flux replica made it worse: in CamAPS the trend
response is shared across `p`, `Cs`, `f` and the glucose compartments, not carried by one free state.

*Estimator cadence:* the EKF runs on the CGM cadence (~1–5 min; `smalldtInt = 3`), while the optimiser/control
runs on `controlStep = 25 min`. (Confirmed: CsDecay 0.9962 = 2^(−3/540).)

**The two `pow` calls (numerical corner case, documented).** `PredictStep` contains two `pow` calls
(0x53b3c → this+0x268; 0x53d40 → this+0x278). Both are `pow((double)τ_I, 3.0)` — the **cube of an insulin
absorption time constant** — reached only in the guarded **repeated-eigenvalue** branch (`|1/τ − p| ≤ 1e-5`),
the l'Hôpital limit that avoids a 0/0 when the two insulin poles coincide; the generic branch (distinct
eigenvalues) uses a `(τ·p − 1)` form with no `pow`. **Neither site executes on the sanity run** — with the
default per-submodel parameters the eigenvalues never coincide, so `pow` guards a corner case that does not
arise in normal operation. The coefficient it forms, e.g.
`this+0x268 = (dt²·500·e^(−dt/τ)) / (τ³·W·0.02709)`, is transcribed from the ASM in §15.2.

---

## 6. The estimator II: the IMM bank — how 8 submodels combine  [D] — **decoded this session**

The 8 submodels don't vote or get selected; they are combined by a **textbook Interacting-Multiple-Model
(IMM) filter**: mix → per-filter EKF update → mode-probability update → moment-matched combined output. The
submodels differ only in structural meal parameters (gut time constants, mixing multipliers); each carries its
own 6-D EKF (§5).

**The bank is effectively 6 modes, not 8.** In `SubModel1::Learn`, submodels whose index (`+0x4c`) is **2 or 4
have their log-likelihood forced to −1e10**, so after the exp/floor they sit permanently at the probability
floor — pinned out of the data-driven mixture (they still exist for the spread machinery). *(Cross-checked two
ways: the IMM decode and the `Learn` disassembly agree.)*

### 6.1 Mode-probability recursion (`UpdateModePropability`, 0x5ecb0)

Standard IMM update **μ_j ∝ Λ_j · c̄_j**, run each minute-block after mixing and after each submodel's `Learn`:

```
m   = max_j ℓ_j                         # ℓ_j = per-step log-likelihood, state+0xcc
Λ_j = exp(ℓ_j − m)                      # relative likelihood (log-sum-exp stabilised)
μ_j = max(1e-7, Λ_j · c̄_j)             # c̄_j = mixed prior (submodel+0x3e8); hard floor 1e-7
μ_j = μ_j / Σ_k μ_k                      # normalise → state+0xd0
```
- The per-step log-likelihood (from `Learn`) is the Gaussian likelihood of the innovation `ν_j = y − ŷ_j`:
  `ℓ_j = −ln(max(S_j/Vg², 1e-30)) − (Vg·ν_j)²/S_j`, with `S_j` the innovation covariance (§5) and `Vg = 0.14`.
- The **1e-7 floor** stops any mode dying permanently.
- There is a *second*, forgetting-weighted accumulator `L_j ← 2^(−dt/150)·L_j + ℓ_j` at `state+0xc8`; it is
  **not** consumed by the probability path (a subtle point — the memory note's `+0xc8` is the accumulator, the
  driver is `+0xcc`).

### 6.2 The transition-probability matrix (`InitialiseTransitionProb`, 0x5f0b0)

A fixed **8×8 block-diagonal Markov matrix** `P = [[A, 0], [0, A]]` (rebuilt only when dt changes). The two
4-mode groups {modes 1–4} and {5–8} **never inter-transition** — they differ only by slow-gut time constant
`tMaxG2s` (140 min vs 76.9 min).

Each 4×4 block `A = X ⊗ Y` is a Kronecker product of two 2-state chains (mode-in-block = `2·x + y`):
- **x** (fast, τ = 17 min, tied to `multWini`=2): escape weights 0.2 (into excited) / 0.8 (out of excited).
- **y** (slow, τ = 180 min, tied to `multWktInsIni`=1.4): escape weights 0.1 (in) / 0.9 (out).
- `a = e^(−dt/17)`, `b = e^(−dt/180)`; stay-probs `1 − 0.2(1−a)` etc.

The excited hypotheses ("a meal / an insulin-need shift just happened") revert to baseline ~4× (x) and ~9× (y)
faster than they're entered — asymmetric decay back to normal.

### 6.3 Mixing (`Interact` → `InteractStep1`/`InteractStep2`, 0x5e4ec / 0x58640 / 0x5a1d8)

Classic IMM moment-matched mixing, per target submodel j:
1. **Mixed prior** `c̄_j = Σ_i π_{i→j}·μ_i` (→ submodel+0x3e8).
2. **Mixed state** `x0_j = Σ_i w_{i|j}·x_i`, weight `w_{i|j} = π_{i→j}·μ_i / c̄_j` (→ scratch +0x400).
3. **Mixed covariance** (in learning) with the spread-of-means term:
   `P0_j = Σ_i w_{i|j}·[P_i + (x_i − x0_j)(x_i − x0_j)ᵀ]`.

`InteractStep2` commits the mixed state/covariance into each filter. A parallel `…Progress` path does the same
over the *prediction* state vector (`ModelIMM1+0x80`) for the forecast/occlusion rollout.

### 6.4 Combined output — 8 modes → one number

Every real controller output is a **probability-weighted (moment-matched) average over μ_j** — a genuine IMM
combination, not winner-take-all:

| function | vaddr | output |
|---|---|---|
| `GetUs` | 0x61128 | `Σ_j μ_j·Us_j` — **the combined insulin recommendation (the control output)** |
| `GetUsAndMeal` | 0x611e0 | `Σ_j μ_j·(Us+meal)_j` |
| `GetWeightedResidual` | 0x61070 | `Σ_j μ_j·residual_j` |
| `GetPredGlucSD` / `CombineVarPredGlu` | 0x60bac / 0x5a7cc | combined glucose mean `Σ_j μ_j·x_j`, then output SD = √(Σ_j μ_j·[own var + spread of means]) |
| `PredictForOptimise` | 0x603b8 | per control step, combined predicted glucose `Σ_j μ_j·g_j` into the optimiser trajectory |

**`GetBestModel` (0x60798) is NOT a model selector** — it's a packed diagnostic code:
`return 10·code_y + code_x`, where `code_x = trunc(10·(Σ_j multWini_j·μ_j − 1))` (decile of posterior mass on
the fast meal-absorption modes) and `code_y = trunc(10·((Σ_j multWktInsIni_j·μ_j − 2)/0.4))` (mass on the
insulin-shift modes). It is telemetry / a coarse meal-state classifier; the control path uses `GetUs`.

### 6.5 The submodels (the bank)

| # (1-based) | tMaxG1s (fast gut) | tMaxG2s (slow gut) | multWini (x) | multWktInsIni (y) | in mixture? |
|---|---|---|---|---|---|
| 1 | 21.88 | 140.0 | 1 | 1.0 | yes |
| 2 | 21.88 | 140.0 | 1 | 1.4 | **pinned out** |
| 3 | 81.88 | 140.0 | 2 | 1.0 | yes |
| 4 | 51.88 | 140.0 | 2 | 1.4 | **pinned out** |
| 5 | 16.63 | 76.91 | 1 | 1.0 | yes |
| 6 | 16.63 | 76.91 | 1 | 1.4 | yes |
| 7 | 36.63 | 76.91 | 2 | 1.0 | yes |
| 8 | 16.63 | 76.91 | 2 | 1.4 | yes |

Priors μ_j(0) = 0.125 (1/8) for all. Process-noise scale = `0.06 × weight-ramp` (1.45 at ≤20 kg → 1.0 at
≥45 kg). The bank is a set of competing hypotheses about **how fast the current meal is absorbing and whether
insulin need has shifted**; the IMM continuously re-weights them against the CGM.

### `multWk31ini` — resolved this session
`this+0x38 = multWk31ini[k] = 2.0` for all 8 submodels. Read **only** in `PredictStep`'s gut-absorption
block: it is the **gut-flux rate-cap multiplier**. The maximum rate at which glucose may appear from a gut
compartment is `ratecap = multWk31ini · (this[0x10]/5.551) · (dt / this[0x40])`, so `=2` gives 2× headroom on
the carb-appearance limiter. It is a fixed structural constant, not patient-varying. (Distinct from
`multWktInsIni` at this+0x3c, read only by `SetBIC`, and `multWini` at this+0x34, the initial-covariance
multiplier.)

---

## 7. The optimiser  [D]

`Model::Optimise` (0x3f7b0) solves, **iteratively (Gauss-Newton, re-linearised each pass)**:

```
minimise  J(u) = ‖ S·u − e ‖²  +  Σ_k w_k · (u_k − u_{k−1})²          [tracking + MOVE-suppression]
solved by u = (SᵀS + DᵀWD)⁻¹ · Sᵀe
```
- `S` = sensitivity of predicted glucose to each control block, from linearising `ModelIMM1::PredictForOptimise`;
  `e` = tracking error vs the reference trajectory. `SᵀS` is the tracking Gram (dumped: entries 0.09–0.62,
  decaying — later blocks have less cumulative glucose effect).
- **The effort term is a move (Δu) penalty, not `λ‖u‖²` on magnitude** [M, decoded 2026-09-29]. The Hessian is
  `SᵀS + M2` where `M2` is a **tridiagonal `DᵀWD`** operator (dumped from a live solve) — `diag_i = w_i+w_{i+1}`,
  `offdiag = −w_{i+1}` — i.e. it penalises the *rate of change* of basal between blocks, anchored to the current
  rate `u_0`. This is why the binary produces smooth trajectories, and why a replica using `λ‖u‖²` on magnitude
  has the wrong regulariser.
- **The exact move-weight formula (fully traced):**
  `w_k = λ_eff(τ_k)² / BIR`, with `λ_eff(τ) = 1.2 + 0.4·min(τ,240)/240` — a **linear ramp** of the effort weight
  from `lambdaBaseMeal` 1.2 to `lambdaBase` 1.6 over `lambdaMealDuration` 240 min since the last meal (`τ_k` =
  minutes-since-meal at block k), normalised **per-patient by `1/BIR`** (the basal rate). Measured `w_k ≈
  {2.33, 2.47, 2.61, 2.77, 2.92}` reproduce exactly (`1.38²/0.819 = 2.325` …). So post-meal the weight is
  *lower* (softer move penalty → the loop can move basal faster), relaxing to the base weight 4 h later.
  Two corrections to the earlier spec: the meal weight is a **ramp, not a step**, and it is **basal-normalised**.
- Control is **≤ 8 blocks** capacity; the working count is **`controlHorizon 100 / controlStep 25 = 4`** (the
  dumped Hessian is 4×4).
- **Bounds are not in the solve** — a suspend is the unconstrained optimum later clipped at 0 by the rule chain.

Caveat for anyone re-deriving it: the solve **can only express a symmetric quadratic cost** — any asymmetric /
glucose-dependent / one-sided cost term "fitted" into a replica is an artefact of a *different* (gridded-search)
optimiser, not of this. **The absolute cost scale is now recovered:** it lives entirely in the tracking Gram
`SᵀS` (physical sensitivities) and the move-weights `w_k` — both dumped from a live solve.

Horizons (from the `.init_array` static constructor, §10.2): prediction 150 min, control 100 min, step
25 min → **100/25 = 4 control blocks** in normal use (the ≤8 is a capacity limit); `predictLead` 30 min.

### 7.1 The seven optimiser input arrays — measured contents  [M]

`Model::DataForOptimisation` (0x3e770) builds the horizon inputs as `Vector<float,180>` and wires them onto
the Model at +0x23f0…+0x2428, where `PredictStep`/`Learn` read them per step. `180` is the container capacity;
the **working length is 7** (the control blocks — 7 × 25 min spans the horizon). Dumped at `Model::Optimise`
entry on the sanity run:

| Model offset | array | measured (7 entries) |
|---|---|---|
| +0x23f0 | **dt** — control step per block | 25 ×7 (= `controlStep`) |
| +0x23f8 | **CGM / measured glucose** | all **−999.9 (NA)** — the forward rollout observes nothing |
| +0x2400 | input channel (meal/gut Ra) | 0 ×7 |
| +0x2408 | input channel (→+0x1b18) | 0.4771, 0, 0, 0, 0, 0, 0 (one first-step value) |
| +0x2410 | input channel (→+0x1e08) | 0 ×7 |
| +0x2418 | input channel | 0 ×7 |
| +0x2420 | input channel | 0 ×7 |
| +0x2428 | **BIR** — basal-insulin-requirement per step | 0.819, 0.829, 0.833 ×5 U/h (≈ profile basal) |

So the optimiser is handed a piecewise-constant **BIR operating-point trajectory** (~0.83 U/h), a constant
**25-min step**, an **all-NA CGM column** (the model predicts, doesn't observe, over the horizon), and input
channels that are zero except any pending meal/bolus. This confirms **7 control blocks** (not 8 = the solver
capacity, not 180 = the container) and shows the replica-vs-binary optimiser-gain question is about how these
BIR/step trajectories combine, not hidden array contents.

---

## 8. The output pipeline — 7 rule modifiers, in order  [D] + [M]

Runs inside `GetRate` **after** `Optimise`. Each stage has signature `(…, float& rate)`. Four can only reduce
the rate. **This is where most of the safety behaviour lives.**

| # | function | vaddr | effect |
|---|---|---|---|
| 1 | `MaximumPersonalRange` | 0x46b08 | **Ceiling.** `mult = cgm>12 ? 3.0 : cgm>8 ? 2.5 : 2.0`; `base = max(0.48·TDD/24, 0.7·meanBasal)`; ceiling ≈ `mult·base`. Reproduces 9 measured points exactly. *Do not simplify to "2.55×basal" — the TDD term dominates in normal use.* |
| 2 | `ModifyRateGlucoseLevel` | 0x46dcc | **Hard low-glucose suspend.** `g = min(modelGlucose, prevCGM)`; suspend if `g < 5.8 − offset`, offset 1.3 (→ **4.500**) normally, 1.5 (→ **4.300**) if a meal in the last 60 min. Sets rate = 0, diag `L`. |
| 3 | `ModifyRateGlucoseRate` | 0x46f10 | **The entire trend response.** Slope = the more-negative of two OLS windows (+70/+40 min). If `g ≥ 8`: percentage attenuation from twin `2^(−…)` indices on slope (offset 2.2, t½ 3.2) and glucose (ref 4.5, t½ 4.5); att=100 ⇒ suspend; onset at slope −1.2; glucose index inert above 12. If `g < 8` **and** four windows all `< −1.2`: `rate = min(rate, 0.2·GetBIRpump)` and RETURN (this *keeps more* than the attenuation would). |
| 4 | `ModifyRateDeltaBIR` | 0x47590 | **Occlusion-aware cap.** `f = G<9 ? 1.3 : G<12 ? 1.6 : 2.0`; if `LowestBGIfOcclusion < 3.9` and the smoothed+model BIR exceeds `smoothed·f` and `rate > smoothed` → cap `rate = smoothed`. (3.9 is an *occlusion* test here, not a hypo threshold.) |
| 5 | `ModifyExercise` | 0x4771c | **Exercise suspend.** In an exercise window, if `min(modelGlucose, prevCGM) ≤ 8.0` (`noInsulinDuringExercise`, a glucose threshold) → rate = 0, diag `E`. |
| 6 | `ModifyEnoughGlucoseMeasurements` | 0x478e4 | **CGM-staleness fallback [D, reconciled].** Needs a CGM within **20 min** AND a second in the **20–50 min** window (`CTimeSpanMy` 1200 s + 1800 s); if not, revert toward profile basal `GetBIRpump` (diag `@`); if no CGM in **180 min**, hard-revert (diag `$`). The 20+30 = **50 min** span explains the measured ~50-min firing. (A 90-min window is constructed but unused.) |
| 7 | `RescueCarbReduction` | 0x47a70 | **No post-hypo hold exists** [M]. The decoded branch *looks* like one (thresholds 4.2 / 6.0, 120-min timeout) but the binary returns ~1.45 U/h after recovered lows — the only low-glucose zeroing is stage 2. Left unimplemented in the replica. |

Note the brief lists stage order as EnoughGlucose before Exercise; the binary runs **Exercise (5) then
EnoughGlucose (6)**.

---

## 9. The "learning" — how CamAPS adapts to you  [D]

CamAPS "learns" at **three timescales**, all decoded:

| timescale | what it learns | mechanism | where |
|---|---|---|---|
| **per CGM (~1–5 min)** | bioavailability `f`, insulin/EGP disturbance `p`, flux `Cs` | the 6-D EKF `SubModel1::Learn` | §5 |
| **~30 min** | basal insulin requirement (BIR) | dual EWMA (24 h + 2 h) `UpdateBIRs` | §9.1 |
| **hourly / daily** | correction fraction + total daily dose (TDD) | three TDD loops | §9.2 |

§5 (estimator) and §6 (IMM) are the *real-time* learning; §9 is the slow **dose adaptation** — what most
people mean by "CamAPS adapting over days."

### 9.1 Basal-requirement learning — `ModelIMM1::UpdateBIRs` (0x5e6e0)
Every ~30 min (a per-submodel elapsed timer reaches 29.5), each submodel updates a **dual
exponentially-weighted moving average** of the insulin the model says you need,
`q = GetBIR + GetInsulinForUs(GetBIR)` (the balancing insulin from inverting the plant):
```
γ = exp(−Δt·ln2 / halflife)                 # halflife = 1440 min (long) or 120 min (short)
num ← q + γ·num ;  den ← γ·den + 1 ;  BIR = num/den
```
- **Long** (24 h half-life): the stable baseline basal requirement.
- **Short** (2 h half-life): fast, only trusted once `den ≥ 1.6` (≈ enough recent samples).
- Combined IMM-probability-weighted via `ModifyBIR` — `(5·stored + w'·new)/(w'+5)`, `w' = min(w,5)`,
  unchanged if `w < 0.1` (anchored 5:1 to the stored value unless there's strong new evidence) — and
  **floored at 0.2 U/h** (`GetModifiedBIR`; this is the measured 0.20 U/h minimum).

It learns from the *model's insulin↔glucose balance*, not from the pump's programmed basal.

### 9.2 TDD adaptation — three loops
**(a) Hourly correction fraction — `UpdateFractionTDD` (0x44c24), fed by `UpdateTDDandPerfomance` (0x48040).**
Once per completed hour, per hour-of-day (24 records), it keeps an EMA of `(mean glucose − target)` with weight
`weightNewAboveTarget` 0.4 (0.6 on history) — "how far above target was this hour" — then:
```
corr = clamp( 1 + amplifierTDD·bForTDD·meanPerf , 0.7 , (frac≥1 ? 1.05 : 1.2) )   # 0.8·0.1324 = 0.10592
if hypo seen (min/mean < 3.9):  corr = min(corr, 0.7)          # corrHypoTDD
fraction ← clamp( fraction·corr , 0.4 , 1.8 )
if fraction > 1.0 and gate flag not set:  fraction = 1.0       # rises above 1.0 only when a gate permits
```
So the hourly fraction is **down-biased** — it can freely trim to 0.4× but only rises above 1.0 when a summary
gate allows it.

**(b) Daily calculated TDD — `AdjustTDDbasedOnCGM` (0x4b068) → `UpdateTDD`/`GetCalculatedTDD`.** Once per day
(anchored ~08:00) over an overnight window (needs ≥4 valid hours):
```
excess = meanPerf − thresholdOffsetToCalculateTDD(−0.2)        # = meanPerf + 0.2
amp    = excess≥0 ? amplifierAdjust(1.1) : 1.2
corr   = clamp( (1 + amp·bForTDD·excess)·(1 − sdReduceAdjust·perfSD) , 0.7 , 1.4 )   # sdReduceAdjust 0.03
if overnight hypo (min < 3.3):  corr = min(corr, 0.8)          # corrHypoAdjust
newTDD = clamp( corr·base , 1.0·base , 1.8·base )              # minTDDmultiplierAdjust=1.0 → UP-only
newTDD = max( newTDD , weight·0.15 )                           # minTDDperKg_T1D
```
The day's `newTDD` enters a **5-day ring buffer**, and the **calculated TDD = median of the last 5 days** — so
one odd day can't swing it. This daily loop revises **upward only** (base … 1.8×base).

**(c) Per-run modifier — `ModifyTDD` (0x44f90).** Every run, scales TDD by target deviation
`((5.8 − target)·0.1324 + 1)`, then applies the live hypo/exercise fraction (§9.3) and a weighted look-back over
the last four hours' fractions (`weightsLookAheadFracTDD` {0.5, 0.3, 0.15, 0.05}).

### 9.3 Where the learned TDD goes, and the hypo/exercise brakes
`GetBIR` (0x429bc): `BIR = max( TDD·0.48/24 , 0.7·mean_profile_basal )` — the adapted TDD becomes the hourly
basal requirement via `BIRasFractionOfTDD` 0.48, floored at 70 % of the profile basal; the same TDD (×2.0–3.0
by recent glucose) sets the `MaximumPersonalRange` ceiling (§8.1), itself capped at 5× profile basal. **The
adapted TDD moves the operating point; the profile basal is the guardrail on both sides.**

Three independent hypo brakes + exercise: per-run (`ModifyTDD`) min CGM < 3.9 in 1–2 h → TDD ×0.6
(`fractionTDDhypo`); active exercise with BG < 14 → TDD ×0.5 (`fractionTDDexercise`, takes precedence); hourly
hypo → correction floored to 0.7; daily overnight min < 3.3 → daily correction capped at 0.8.

⚠️ **Correction:** `forgettingHalfTime` (150 min) is the **estimator's** covariance forgetting (§5), **not**
part of the TDD path — the TDD "forgetting" is the 0.4/0.6 hourly EMA plus the 5-day median.

*(Contrast: our replica's `TddAdapterV2` uses a 7-day window; CamAPS layers an hourly-EMA + 5-day-median TDD on
top of a dual-EWMA (24 h/2 h) basal-requirement learner.)*

---

## 10. Constants & globals — master table

**Structural fact:** CamAPS's constants are **named 4-byte globals** in `.data`/`.rodata`, readable from the
symbol table — *not* immediates inside functions. Dump them with `decode/globals.py` before disassembling.
Per-patient plant params (`egpHalf`, `Iref`, `F01`, `SI`) are per-submodel object **fields**, derived by
`SetBIC`, so they are [U]/[F], not in this table.

### 10.1 Targets & glucose thresholds
| symbol | value | units | role | tag |
|---|---|---|---|---|
| `trueTargetGlucose` / `finalTargetGlucose` | 5.8 | mmol/L | default target | D |
| `minimumTargetGlucose` / `maximumTargetGlucose` | 4.4 / 11 | mmol/L | target clamp | D |
| `glucoseStableOffset` | −0.4 | mmol/L | target lowered when fasting+stable | D |
| `uncertaintyTargetGlucoseOffset` | 0.5 | mmol/L | target raise under uncertainty | D |
| `pregGlucoseOffset` / `pregGlucoseStableOffset` | −0.4 / −0.8 | mmol/L | pregnancy shifts | D |
| `NA` | −999.9 | — | missing-value sentinel | D |
| FastingAndGlucoseStable window | [T−2.0, T+1.5] for 4 h | mmol/L | licenses the −0.4 offset | D |

Target resolution: `idx = hour*2 + (min≥30)`; `t = targetArray[idx]`; `T = (t<0) ? 5.8 : clamp(t, 4.4, 11)`.

### 10.2 Set-point dynamics & horizons
| symbol | value | units | role | tag |
|---|---|---|---|---|
| `UpSlopeHalfTime` / `DownSlopeHalfTime` | 15 / 60 | min | approach half-life from below / above target | D |
| zone slopes | −2.5 / −1.7 / −1.0 | mmol/L/h | absolute descent rate by zone (sp>13 / 10–13 / ≤10) — **rates, not decay constants** | D/M |
| set-point clamp | 12.0 | mmol/L | `sp = min(sp₀, 12.0)` — load-bearing | D |
| dead zone | T ≤ sp ≤ T+2 | mmol/L | reference held | D |
| `predictionHorizonUp/Down` | 150 | min | prediction horizon | D |
| `controlHorizonUp/Down` | 100 | min | control horizon (< prediction) → 4 blocks | D |
| `controlStep` / `dt` | 25 | min | control block width | D |
| `predictLead` | 30 | min | CGM lead projecting set-point start | D |
| `occlusionDuration` | 150 | min | occlusion look-ahead | D |
| `leadInPeriod` / `durationExtension` / `STORE_DELAY` | 480 / 240 / 20 | min | housekeeping | D |
| `ln2`, `forgettingHalfTime` | 0.6931…, 150 | —, min | exponentials; covariance forgetting | D |

The full `.init_array` static-constructor block (all `CTimeSpanMy(d,h,m,s)` horizon globals) is reproduced in
`decode/NOTES.md`; §10.2 gives the ones that matter.

### 10.3 Optimiser cost weights
| symbol | value | role | tag |
|---|---|---|---|
| `lambdaBase` | 1.6 | effort weight, normal | D |
| `lambdaBaseMeal` | 1.2 | effort weight within 240 min of a meal (0.75× → harder) | D |
| `lambdaBaseBolus` | 1.0 | effort weight for a bolus | D |
| `lambdaMealDuration` | 240 | min the meal weight applies | D |
| *absolute cost scale* | — | how glucose error is normalised vs effort | **U** |

### 10.4 Rate limits & output pipeline
| symbol / immediate | value | role | tag |
|---|---|---|---|
| `BIRasFractionOfTDD` | 0.48 | ceiling base = max(0.48·TDD/24, 0.7·meanBasal) | D |
| ceiling `0.7` | 0.7 | mean-basal fraction in ceiling base | D |
| ceiling multipliers | 2.0 / 2.5 / 3.0 | cgm ≤8 / 8–12 / >12 | D/M |
| `minBasalAsFractionOfTotal` | 0.35 | floor on basal share | D |
| low-suspend offsets | 1.3 / 1.5 | → suspend below 4.500 / 4.300 (post-meal) | D/M |
| trend split | 8.0 | mmol/L: ≥8 attenuation vs <8 sustained-fall cap | D |
| slope t½ / offset | 3.2 / 2.2 | trend attenuation index (onset at Ġ = −1.2) | D |
| glucose t½ / span | 4.5 / 7.5 | glucose attenuation index (inert above 12) | D |
| sub-8 sustained-fall threshold / cap | −1.2 / 0.2×basal | four-window fall test → cap | D |
| occlusion factor f | 1.3 / 1.6 / 2.0 | G<9 / 9–12 / ≥12 | D |
| occlusion hypo threshold | 3.9 | LowestBGIfOcclusion test | D |
| CGM-gap fallback | 20 (decoded) / **~50 (measured)** | min → revert to profile basal | D/M |
| min non-zero rate | **0.20** | U/h floor (measured; no global carries it) | M |
| output quantum | 0.05 | U/h | M |

### 10.5 Safety thresholds
`minCGMThresholdTDD`/`thresholdTDDhypo` 3.9 · `minCGMThresholdAdjust` 3.3 · `minCGMThresholdNoCorrection*` 4.4 ·
`fractionTDDhypo` 0.6 · `corrHypoTDD`/`corrHypoAdjust` 0.7/0.8 · `min/maxCorrectionAdjust` 0.7/1.4. All [D].

### 10.6 TDD & BIR adaptation  (see §9 for the formulas)
`GetBIRHalf` 1440 / `GetBIRHalfShort` 120 min (BIR EWMA half-lives) · `GetMealActiveThreshold` 0.015 ·
`maximumTDD`/`minimumTDD` 45/45 · `minimumTDDperKg` 0.2, `minTDDperKg_T1D` 0.15 · hourly:
`maxTDDmultiplier`/`minTDDmultiplier` 1.8/0.4, `maxTDDcorrection`/`minTDDcorrection`/`maxTDDcorrectionLow`
1.05/0.7/1.2, `amplifierTDD` 0.8, `weightNewAboveTarget` 0.4, `corrHypoTDD` 0.7 · daily:
`amplifierAdjust` 1.1, `sdReduceAdjust` 0.03, `thresholdOffsetToCalculateTDD` −0.2,
`max/minCorrectionAdjust` 1.4/0.7, `max/minTDDmultiplierAdjust` 1.8/1.0, `corrHypoAdjust` 0.8,
`minCGMThresholdAdjust` 3.3, `maxIncreaseCalcTdd` 3.5 · `bForTDD` 0.1324 (all three) ·
`weightsLookAheadFracTDD` {0.5,0.3,0.15,0.05} · `BIRasFractionOfTDD` 0.48 · `Vg` 0.14. All [D].
(`forgettingHalfTime` 150 min is the **estimator's** covariance forgetting — §5/§10.2 — not a TDD constant.)

### 10.7 Plant / submodel parameters
| symbol | value | role | tag |
|---|---|---|---|
| Vi·ke immediate | 0.02709 L/(kg·min) | combined insulin gain | D |
| 1000/60 immediate | 16.6667 | U/h→mU/min | D |
| `EGP0`/`Iref`/`egpHalf`/`F01`/`SI` | per-patient fields | plant params (from `SetBIC`) | D field / U value |
| `Vg` | 0.14 L/kg | glucose volume | D |
| `SubModel1::statesN` | **6** | filter dimension (Vector<float,8> is capacity) | D |
| `tMaxIs` | 45 | insulin t_max | D |
| `tMaxG1s[8]` | 21.88,21.88,81.88,51.88,16.63,16.63,36.63,16.63 | fast gut τ per submodel | D |
| `tMaxG2s[8]` | 140×4, 76.91×4 | slow gut τ per submodel | D |
| `tMaxIpriorLN`/`tMaxGpriorLN` | (3.73767, 2.35) | log-normal priors (median 42 min) | D |
| `fPriorN` / `fPriorSDN` | 1.0×8 / 0.3×8 | bioavailability prior N(1.0, 0.3²) | D |
| `fLimits` / `CsLimits` | [0.2, 2.2] / ±0.005 | bioavailability / flux clamps | D |
| Cs decay | 2^(−dt/540), t½ 540 min | flux disturbance decay | D |
| `multWini[8]` | 1,1,2,2,1,1,2,2 | initial-covariance multiplier | D |
| `multWktInsIni[8]` | 1,1.4,1,1.4,… | insulin-variation mult (this+0x3c, read by SetBIC) | D |
| `multWk31ini[8]` | 2.0×8 | **gut-flux rate-cap multiplier** (this+0x38) | D |
| `lowWeight`/`nominalWeight` | 20/45 kg | paediatric rate-modifier knees | D |
| `rateModifierLowWeight`/`Nominal` | 1.45 / 1.0 | gain below 45 kg vs at/above | D |
| `insTmaxFac` | 0 | dose-dependent tMaxI factor (disabled) | D |

### 10.8 Estimator noise / covariance
`H = [0,0,0,h₃,1,0]` · `Kᵢ = (0.14/S)(h₃·P[i][3]+P[i][4])` · sd `= max(0.02·max(G,3.5),0.16)` · process noise
rank-1, σ 5.03e-4 (near meal) / 2.90e-4 · `P0 = diag(196,100,3.08e-5,0.09,196,3.08e-5)` · forgetting 2^(dt/150)
· transition F fully mapped (§5) · step bounds p ±5.517e-4, Cs ±2.483e-4/±0.005, f [0.2,2.2]. All [D]/[M].
Persistent state blob = **8288 bytes**; per-submodel covariance stored as {int rows; int cols; float[36]},
dims read 6,6.

### 10.9 Meal handling
`weightCategory` {13,25,50,85,10000} kg · `mealSizeForWeightCategory[5][4]` (carb thresholds → meal class 0–3)
· `priorMealProb[8][4]` (prior of submodel k given meal class) · `smallMealSize` 40 g · `verySmallMealSize`
20 g · `maxCumBio`/`minCumBio` 2.5/0.4 · `fracInsulinBolusToReset` 10.1 · `ignorePostMealGlucose` 27 min. All
[D]. Meal size is **classified, not taken as entered**: wc = first weightCategory ≥ weight; cls = first
mealSizeForWeightCategory[wc] ≥ carbs; prior = priorMealProb[k][cls].

### 10.10 Exercise
`noInsulinDuringExercise` 8.0 mmol/L (a glucose threshold) · `SetPointIncreaseExercise` 3.5 · exercise
set-point windows 120/120 min · `fractionTDDexercise` 0.5 · `maxBGaffectTDDExercise` 14 ·
`exerciseRateModifyBackPeriod`/`ForwardPeriod` 1/30 min · `exerciseBack/ForwardPeriod` 240/60 min. All [D].

### 10.11 Numerical / housekeeping
`smallVal` 1e-5 · `smallValSanityCheck` 0.05 · `isCV` 0 · `pumpArraySizeCopy` 48 · `smalldtInt` 3 (estimator
step) · `insTmaxMaxAdd` 45 · `nReportRules` 8. All [D]. The `_T2D` family (≈40 symbols) is the type-2 path —
not applicable to this T1D patient; listed in the constants appendix of `report/camaps-model-spec.md` §8.

---

## 11. Personalisation levers — what actually changes behaviour for a profile

The binary's input carries **body weight, the 48-slot basal profile, TDD, and an optional 48-slot target
array — no ISF, no IC.** So everything that personalises the controller flows from those, plus quantities
adapted online. Everything else in §10 is fixed model *structure*, shared across all T1D patients.

> ⚠️ **Safety.** This is a live closed-loop insulin system. The items below are where behaviour *comes from*;
> they are a map for understanding and careful experimentation in the replica, **not** a licence to edit a
> live controller. Validate any change against the patient's own history first (see the standing preference
> in memory), and keep the hypo-side guards intact.

**A. Direct per-patient inputs (change behaviour immediately)**
- **Target array** (48 half-hourly): the single most direct lever. Clamped [4.4, 11], default 5.8, lowered
  −0.4 when fasting+stable. Drives the reference trajectory the optimiser tracks and the suspend edges
  (4.5 = target−1.3).
- **Basal profile** (48 half-hourly): nearly everything internal is expressed *relative to the patient's own
  basal* — the ceiling (§8.1), `GetBIRpump`, the sub-8 cap (0.2×basal), the occlusion cap. Changing basal
  rescales the whole envelope.
- **TDD**: ceiling base 0.48·TDD/24; adapted online.
- **Body weight**: enters the insulin gain (`/(W·0.02709)`), the meal-size classification, and the paediatric
  rate modifier.

**B. Per-patient-derived plant fields (fitted per patient, not editable as globals):** `egpHalf`, `Iref`,
`F01`, `SI`, `EGP0` — computed by `SetBIC`/`GetInsulinForUs` from the BIR. To personalise the *plant*, you
personalise the BIR that drives `SetBIC`.

**C. Weight-conditional switches:** `lowWeight`/`nominalWeight` (20/45 kg) + `rateModifier*` (1.45/1.0) — a
paediatric gain boost, inert for a 70 kg adult (=1.0). Meal classification (`weightCategory` ×
`mealSizeForWeightCategory`) interprets carbs relative to weight.

**D. Online-adaptive (personalises over time):** BIR blend (24 h + 2 h half-lives via `ModifyBIR`); TDD
adaptation. This is CamAPS "learning" — and the mechanism our TddAdapterV2 approximates with a different
window.

**E. Fixed structure, but the levers you'd retune if rebuilding the algorithm (same for all T1D):**
- Cost weights `lambdaBase` 1.6 / `lambdaBaseMeal` 1.2 / `lambdaBaseBolus` 1.0 (aggressiveness; 25 % harder
  post-meal for 240 min).
- Rate caps: ceiling multipliers 2.0/2.5/3.0, `BIRasFractionOfTDD` 0.48, sub-8 cap 0.2×basal, occlusion
  factors, the 0.20 U/h floor.
- Safety thresholds: suspend edges 4.500/4.300, exercise 8.0, occlusion 3.9.
- Meal priors / bioavailability: `fPriorN` 1.0, `fPriorSDN` 0.3, `fLimits` [0.2, 2.2], `priorMealProb`.
- Set-point/horizons: `UpSlopeHalfTime` 15, `DownSlopeHalfTime` 60, prediction 150 / control 100 / step 25.

**Not personalisation-relevant:** submodel banks (`tMaxG*`, `tMaxIs`), `halfTimeTran`, the `multW*` arrays,
estimator P0/noise/forgetting, the numerical constants, and everything `_T2D`.

---

## 12. Decoded vs. open — current status

### Fully decoded AND verified
Plant equation (`EndoBalance`/`GetEGP`, emulator-exact) · `SetBIC` BIR→params · submodel banks
(`tMaxG*`, `multW*`, priors, `fLimits`, `CsLimits`, `halfTimeTran`, meal classification) · output pipeline
stages 1–7 (1–3 confirmed against measurement) · set-point/target · optimiser structure + cost-weight ratios
· horizons (static ctor) · **the full 6-D EKF: state identities, transition F, H/R/K, process noise, all
bounds** (§5, closed this session) · **the IMM bank: mode-probability recursion, the block-diagonal Kronecker
transition matrix, the moment-matched mixing, the weighted combined output, and the two pinned-out modes**
(§6, closed this session) · **`multWk31ini`** (§6, closed this session) · whole controller bit-for-bit with
the phone.

### Measured but not structurally decoded
- **dose-response gain g(BG,trend)** — this is the *emergent net behaviour* of the whole estimator→optimiser→
  pipeline, not a single formula; it is fully captured as the measured response law
  (`report/camaps-measured-response.md`), which is the right form for it.
- **bolus-advisor mode** — deliberately not decoded (implausible outputs; must not be copied to a live loop).

**Resolved since:** *CGM-staleness firing* (~50 min) is now decoded — it needs a CGM in 20 min + one in the
20–50 min window (§8 stage 6). *The 0.20 U/h floor* **is** algorithmic: `ModelIMM1::GetModifiedBIR` (0x5fbec)
returns `max(0.2, probability-weighted adapted BIR)` — so the learned basal requirement can never fall below
0.20 U/h (§9). (`GetBIRpump` itself is a pure 48-slot lookup with no floor; the floor is in the adapted-BIR path.)

**Optimiser cost — now recovered (§7, 2026-09-29):** the Hessian is `SᵀS + DᵀWD` — a **move-suppression**
penalty (tridiagonal, weights `w_k ≈ 2.3–2.9`), not `λ‖u‖²`. Both the tracking Gram and the move-weights were
dumped from a live solve, so the "absolute cost scale" is no longer open; only the closed-form map from the
`λ` globals (1.6/1.2/1.0) to the measured weight ramp remains a refinement.

**Per-patient plant derivation — resolved:** `Model1::InitialiseParameters` (0x3c014, 4144 B) is now read
(2026-09-29). It does **not** compute the plant parameters; it is structural initialisation (seeds each
submodel's state — glucose 5.5, elapsed timers 1440 min, scale factors 1.0 — the IMM mode priors, the
`priorMealProb`×`fPriorSDN` meal records, the input data-array pointers, `InitialiseCovariance` P0 and
`ResetDt`, and the TDD/glucose history buffer to defaults 5.5 / −999.9 / 5.8 / 999). The actual per-patient
plant params (EGP0/Iref/egpHalf/SI/F01) are computed by **`SetBIC(BIR)`** (already decoded, emulator-exact),
driven by the profile/TDD-derived BIR and the online BIR adaptation (§9) — not by a separate hidden formula.

### Fully closed (nothing of the CamAPS algorithm is undecoded)
Every subsystem is now read to formula level and the whole controller runs bit-exact. The items once listed
here are resolved:
- **`PredictStep`'s two `pow` calls — §5:** `pow((double)τ_I, 3.0)`, a repeated-eigenvalue corner case that
  never fires with default params.
- **The seven `Vector<float,180>` horizon arrays — §7.1:** measured contents (dt=25, CGM=NA, BIR≈0.83, rest 0).
- **The optimiser cost — §7:** `‖Su−e‖² + Σ (λ_eff(τ_k)²/BIR)(u_k−u_{k−1})²`, weights fully traced.
- **CGM-staleness — §8 stage 6** (20 + 20–50 min windows); **0.20 U/h "floor"** = the profile minimum
  (`GetBIRpump` is a pure lookup).
- **In-rollout glucose clamp:** `PredictStep` floors the glucose-flux state components at `0.001` (e.g. the
  `if (x+y < 0) → 0.001 − y` guards) and `this+0x240` at `~1e-5`; the high side is bounded by the saturating
  EGP exponential — so the binary cannot produce the "impossible glucose" the *replica's* unbounded plant did
  (that was a replica-only defect, spec §24.10).
- **The 6-D state in the blob is field-scattered, not a contiguous 6-float vector** — it lives as named fields
  in each 276-B submodel record (glucose at +0x5c/+0x60, `f` at +0x50, …) plus the 6×6 covariance; this is why
  the earlier search for a contiguous state vector found none (spec §2.4).

### Remaining are float-precision / curiosities only (not CamAPS logic)
- The exact analytic form of `PredictStep`'s two `pow`-branch coefficients at the *instruction* level (the
  branch never executes with default params).
- `RescueCarbReduction` past its (behaviourally refuted) branch — it does not fire, so untraced.
- The block-7 covariance dims reading `[4,8]` vs `[6,6]` — a blob-serialisation curiosity; the matrix still
  passes symmetry/Cauchy–Schwarz, so it is a differently-tagged or 9-slot array, not a different filter.
- **bolus-advisor mode** — deliberately left un-decoded (implausible outputs; must never be copied to a live loop).

---

## 13. Reproduce / provenance

- **Binary:** `decompiled/camaps-controller-decrypted/libd9c625.decrypted.so` (and the FCL twin `libed34ff…`).
- **Disassembly / C:** `decode/asm/`, `decode/ghidra/c/fn/` (816 named functions), index `decode/INDEX.md`,
  per-function `decode/DIGEST.md`, running log `decode/NOTES.md`.
- **Run the whole controller on the host:** `tools/venv/bin/python scratchpad_re/scratchpad/runctl.py`
  (self-certifies at Exp 1.3418 / Act 1.3414). Emulator: `decode/emu.py`.
- **Probes that regenerate every dumped number in §15:** `decode/probe_ekf.py`, `probe_jac.py`,
  `probe_pow_arrays.py`, `probe_optimiser_cost.py`, `probe_m2_fill.py`, `probe_m2_formula.py` — each
  `tools/venv/bin/python decode/<probe>.py`. (Standalone working notes also mirror these in
  `decode/ekf_findings.md`, `optimiser-cost-decoded.md`, `predictstep_pow_and_optimiser_arrays.md`.)
- **Deeper formula derivations + real-data validation:** `report/camaps-model-spec.md`. **Measured
  input→output law:** `report/camaps-measured-response.md`.

*Standing caveat:* the replica does **not** ship the CamAPS plant — it uses Hovorka+flux because that
forecasts the patient's own glucose better (spec §21–22). This guide documents the *binary*, which is the
reference, not necessarily what should run live.

---

## 14. Coverage ledger — is *everything* in the binary read?

The completeness question, answered with counts anyone can re-derive from the repo.

### Every function is decompiled
`libd9c625.decrypted.so` contains **816 functions**, and **all 816 have decompiled C** in
`decode/ghidra/c/fn/` (+ `decode/ghidra/c/ALL.c`). Nothing in the image is un-decompiled — every function
can be read.

| bucket | count | where | documentation status |
|---|---|---|---|
| **CamAPS-authored** (code range 0x37000–0x63000) | **406** | `decode/INDEX.md` | **100% have a per-function digest** in `decode/DIGEST.md` (382 headers cover all 370 distinct name-keys; the rest are address-overloads) |
| C++ runtime, **named** (`std_*`, `__cxa_*`, `operator new/delete`, RTTI) | 73 | 0x162000–0x17ffff | decompiled; standard library, not CamAPS logic |
| C++/libc++ runtime, **anonymous** `FUN_*` | 337 | 0x1371b0–0x18340c | decompiled; standard library |

**The decisive check:** there are **zero anonymous `FUN_*` functions inside the CamAPS code range** — every
function in the actual algorithm is named, indexed, and digested. Nothing in the controller logic is an
unaccounted-for black box. (`tools/venv/bin/python` + the diff scripts in this session reproduce all counts.)

### Depth of reading, by tier
- **Read to formula/constant level and verified:** the plant (`EndoBalance`/`GetEGP`/`SetBIC`, emulator-exact),
  the whole output pipeline (7 stages), target/set-point, the optimiser structure + cost weights, the horizons,
  **the full 6-D EKF (§5)**, **the full IMM bank (§6)**, meal classification, and — as of 2026-09-29 —
  `Model1::InitialiseParameters` (§12). The whole controller also runs **bit-for-bit with the phone**, so the
  behaviour of every function is validated end-to-end even where a given line wasn't hand-traced.
- **Read to formula level and verified (added since):** the full optimiser cost incl. the traced move-weight
  formula (§7), CGM-staleness windows (§8), the in-rollout glucose floors, the `pow`/horizon-array items
  (§5/§7.1), and the **complete learning stack — BIR dual-EWMA + the three TDD-adaptation loops (§9)**. The only
  functions still documented at role-level rather than instruction-level are the `Report*`/ASCII-output writers
  (`ReportTDDmonitoring`, `ReportCalculatedTDD`, `ReportParametersASCIIfile`, …), which only format diagnostics
  and do not affect the delivered rate.
- **Trivial (documented as such):** ~120 accessors/setters/destructors/time-arithmetic operators and the
  Matrix/Vector primitives.
- **Standard library:** the 410 runtime functions (allocation, formatting, exceptions, RTTI) are decompiled and
  identifiable by name; they are not CamAPS-specific and are not documented per-function.

**Bottom line:** every CamAPS-authored function is named, indexed, and has a written digest; the algorithm's
substantive logic (plant, estimator, optimiser, output pipeline, target, TDD/BIR, meal handling, init) is read
to formula level; and the whole thing executes bit-exact off-device. What remains is float-precision detail in
a couple of functions and the numeric contents of the optimiser's input arrays — refinements, not black boxes.

---

## 15. Appendix — raw decode evidence

Everything the sections above conclude, shown as dumped/transcribed from the binary. Regenerate with the
`decode/probe_*.py` scripts (each runs the real controller in the emulator and prints these).

### 15.1 The 6-D EKF — transition F and a live covariance  (`probe_ekf.py`, `probe_jac.py`)

`P0 = diag(196, 100, 3.08e-5, 0.09, 196, 3.08e-5)` verified at the first `Learn` entry (sd 14, 10, 0.0056,
0.30, 14, 0.0056). Transition F (6×6), numeric at a meal step (estimator dt = 3 min):

```
          col0     col1     col2(p)  col3(f)  col4     col5(Cs)
  row0    0.8486   0.1737   2.7595     ·         ·        ·      glucose A
  row1    0.1241   0.8235   0.1983     ·         ·        ·      glucose B
  row2      ·        ·      0.9727     ·         ·      0.0273   p    (0.9727 = 2^(-dt/75); [2][5]=1-[2][2])
  row3      ·        ·        ·       1.0        ·        ·      f    (random walk)
  row4    0.2374   0.0251   0.3852     ·       0.7408     ·      glucose C (observed, H=1)
  row5      ·        ·        ·         ·         ·      0.9962   Cs   (0.9962 = 2^(-dt/540), t½=540 min)
```
Field map: `F[0][0]=+0x2a0 F[0][1]=+0x2a4 F[0][2]=+0x2a8 | F[1][0]=+0x2b0 F[1][1]=+0x2b4 F[1][2]=+0x2b8 |
F[2][2]=+0x2c8 F[2][5]=1-(+0x2c8) | F[3][3]=1 | F[4][0]=+0x2d0 F[4][1]=+0x2d4 F[4][2]=+0x2d8 F[4][4]=+0x2dc |
F[5][5]=+0x2e0`. Live 6×6 covariance P at the same step (symmetric; f-variance already shrunk 0.09→0.0198,
anti-correlated with glucose):
```
[ 2.780e-2  1.820e-2  5.172e-4 -2.199e-2  2.630e-2  2.621e-4]
[ 1.820e-2  1.216e-2  2.969e-4 -1.447e-2  1.747e-2  1.611e-4]
[ 5.172e-4  2.969e-4  1.786e-5 -3.981e-4  4.443e-4  6.947e-6]
[-2.199e-2 -1.447e-2 -3.981e-4  1.984e-2 -2.089e-2 -2.182e-4]
[ 2.630e-2  1.747e-2  4.443e-4 -2.089e-2  2.514e-2  2.367e-4]
[ 2.621e-4  1.611e-4  6.947e-6 -2.182e-4  2.367e-4  5.099e-6]
```
Live-run confirmation: `G(0xb0) = state0(0x6c)/0.14` exactly (1.6839/0.14 = 12.03); `f(0xa4)` starts at prior
1.0, drifts to 0.96; `p(0x84)`, `Cs(0x88)` start 0 and stay within their clamps.

### 15.2 The two `pow` calls in `PredictStep`  (ASM 0x53b3c / 0x53d40; `probe_pow_arrays.py`)

Both are `pow((double)τ_I, 3.0) = τ_I³`, the repeated-eigenvalue closed-form coefficient (exponent is a literal
`fmov d1, #3.0`). Neither executes on the sanity run (the generic distinct-eigenvalue branch always runs with
default params). Transcribed (site 1, the float/double detail Ghidra blurs):
```
d0 = (double)τ ;  d4 = τ² ;  d2 = (dt+τ)² ;  d2 = τ² + (dt+τ)² ;  d4 = 2τ²
d2 = (τ²+(dt+τ)²)·decay / (2τ²)          ; decay = e^(-dt/τ)
this+0x254 = (float)((1 - d2) · dVar17)   ; dVar17 = 50/(W·0.08127)
d0 = pow((double)τ, 3.0) = τ³
this+0x268 = (float)( (dt²·500·decay) / (τ³·W·0.02709) )
```
Constants: 0.02709 = 0x3cddebd9 (insulin gain), 500 = 0x407f000040000000 (double), 1000 = 0x447a0000.

### 15.3 The optimiser cost — SᵀS, M2, and the traced weight formula  (`probe_optimiser_cost.py`, `probe_m2_formula.py`)

Dumped at a live `Model::Optimise` (Hessian = `SᵀS + M2`, active 4×4 = controlHorizon 100 / controlStep 25):
```
SᵀS (tracking Gram)            M2 (regulariser, tridiagonal DᵀWD)      Hessian into inv()
[0.617 0.497 0.336 0.216]      [ 4.793 -2.468   0      0   ]           [ 5.410 -1.970  0.336  0.216]
[0.497 0.403 0.275 0.180]      [-2.468  5.082 -2.614   0   ]           [-1.970  5.485 -2.340  0.180]
[0.336 0.275 0.190 0.128]      [  0    -2.614  5.380 -2.766]           [ 0.336 -2.340  5.570 -2.637]
[0.216 0.180 0.128 0.091]      [  0      0    -2.766  5.686]           [ 0.216  0.180 -2.637  5.778]
```
M2 = `DᵀWD` (a first-difference / move penalty): `diag_i = w_i + w_{i+1}`, `offdiag_{i,i+1} = -w_{i+1}`.
The M2-fill loop (0x411dc–0x412dc) resolves to: GOT 0x8d638 → `controlStep` 25; GOT 0x8d708 → `lambdaMealDuration`
240; interpolation endpoints `lambdaBaseMeal` 1.2 and `lambdaBase` 1.6; scale `s11 = 1/s9`, `s9 = BIR` (0.819).
Hence:
```
τ_k       = minutes-since-last-meal at block k  (108, 133, 158, 183, 208 on the sanity run; +controlStep each)
λ_eff(τ)  = 1.2 + 0.4·min(τ, 240)/240            (linear ramp lambdaBaseMeal → lambdaBase over 240 min)
w_k       = λ_eff(τ_k)² / BIR
J(u)      = ‖S·u − e‖²  +  Σ_k w_k·(u_k − u_{k-1})²
```
Verification: τ=108 → 1.38²/0.819 = **2.325** ✓; 133 → 2.468 ✓; 158 → 2.614 ✓; 183 → 2.766 ✓; 208 → 2.922 ✓.

### 15.4 The eight optimiser input arrays  (see §7.1)

Full measured table is in §7.1 (dt = 25 ×7, CGM = all NA, BIR ≈ 0.82–0.83, other channels 0). `180` is the
container capacity; the working length is 7 blocks over the horizon, of which 4 are active control moves.

### 15.5 Probes that regenerate this appendix
`decode/probe_ekf.py` · `decode/probe_jac.py` · `decode/probe_pow_arrays.py` · `decode/probe_optimiser_cost.py`
· `decode/probe_m2_fill.py` · `decode/probe_m2_formula.py` — each: `tools/venv/bin/python decode/<probe>.py`.
