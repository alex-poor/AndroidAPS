# AndroidAPS — experimental fork

> ## 🛑 READ THIS FIRST
>
> This fork **delivers insulin**. It contains a pump driver and a dosing algorithm that are **not part of
> upstream AndroidAPS**, have **not been through the AAPS review process**, and are **not clinically
> validated**. They were built and tested by one person, on one pump — not with anything like the
> multi-user testing an insulin-dosing system needs.
>
> **Not affiliated with or endorsed by** the AndroidAPS project, the Nightscout Foundation, Ypsomed, or
> CamDiab.
>
> If you want a loop that works and is supported, use
> **[upstream AndroidAPS](https://github.com/nightscout/AndroidAPS)**. This fork exists to explore three
> things upstream does not do, and is shared in case the work is useful to someone — not as a product.

---

## What this fork adds

Forked from `nightscout/AndroidAPS` at `43cc754` (2026-06-04). Three independent workstreams:

| # | Area | What it is | Branch | Status |
|---|------|-----------|--------|--------|
| 1 | **[YpsoPump driver](#1-ypsopump-pump-driver)** | Loops a pump AAPS lists as "Not Loopable" | `ypsopump-integration` | Alpha, dosing-capable |
| 2 | **[HovorkaMPC](#2-hovorkampc--a-model-predictive-controller)** | A nonlinear-MPC APS algorithm alongside oref1 | `ypsopump-integration` | Experimental, runs live |
| 3 | **[Compose UI redesign](#3-compose-ui-redesign)** | Full-app Material 3 rewrite of the interface | `ui-redesign` | Presentation-only |

`ui-redesign` contains everything — the algorithm/pump branch is merged into it. Use
`ypsopump-integration` if you want the loop without the UI rewrite.

---

## 1. YpsoPump pump driver

**`pump/ypsopump/` — [full README: protocol, safety notes, credits](pump/ypsopump/README.md)**

AndroidAPS officially lists the Ypsomed mylife YpsoPump as *"Not Loopable — Ypso added very heavy
3rd-party encryption."* This driver is a proof that it can be.

- Full BLE transport: XChaCha20-Poly1305, multiframe assembly, persistent connection model
- Read path (status, basal, boluses, events) and write path (TBR, bolus, cancel)
- Separate read/write counter model, with self-healing resync after a dropped write-ack
- Recovery for the pump's real failure modes: `0x82` bad param, `0x86` TBR-already-active,
  `0x8A` malformed, `0x8B` counter-behind
- Confirm-by-read bolus delivery, and a pump-side suspend reflected as a 0-rate TBR

**This stands entirely on other people's reverse-engineering**, principally
**[SandraK82 / ypsopump-research](https://github.com/SandraK82/ypsopump-research)** and
**[vicktor / ypsomed-pump](https://github.com/vicktor/ypsomed-pump)**. Proper credit is in the driver README.

**Hard requirement:** the pump's per-pairing session key, which you must extract yourself from the genuine
app on a rooted device. There is no supported path to obtain it — no key, no function. Pairing a pump from
scratch is **not possible** without the vendor backend, as the pairing challenge is validated server-side.

---

## 2. HovorkaMPC — a model predictive controller

**`plugins/aps/src/main/kotlin/app/aaps/plugins/aps/hovorka/`** ·
**validation harness: [`hovorka-mpc/`](hovorka-mpc/README.md)**

An alternative APS algorithm, selectable alongside oref1. Where oref1 is a rule-based system refined over
years of community use, this is a **receding-horizon nonlinear model-predictive controller** built on the
published Hovorka 2004 glucose-insulin model.

**Clean-room** — reimplemented from the published model plus independent reverse-engineering of CamAPS FX.
Not a port of anyone's binary.

### How it works

1. **State estimation** — an Extended Kalman Filter replays the last 6 hours of glucose, insulin and carbs
   every tick to estimate the current physiological state. Stateless by design: there is no persisted
   filter state that can silently corrupt.
2. **Control** — optimises a piecewise-constant basal *sequence* over a 3-hour horizon against an
   exponential reference trajectory, and enacts its first step.
3. **Output law** — graduated basal floor, current-glucose safety damper, deadband, hard hypo suspend.

### Optional layers — all default OFF

| Feature | What it does |
|---|---|
| Adaptive TDD | Walks the operating basal day to day from enacted basal + glucose outcomes |
| IMM Kalman bank | Parallel submodels identifying the current carb-absorption regime |
| SMB microbolus | Part of the short-horizon correction as a bolus, behind five independent gates |
| Meal detection | Bayesian inference of unannounced carbs from the innovation sequence |
| Re-identification | Daily re-fit of insulin sensitivity / EGP / absorption from your own logs |
| Time-of-day carb absorption | Scales absorption by meal time. **Built, then rejected when fitted against real data — left in place, off, and not recommended** |

### Safety machinery

Each of these exists because the failure it prevents happened first:

- **Hard hypo suspend** — 0 U/hr at or below 3.9 mmol/L on the *raw* sensor value, overriding the model
- **Descent guard** — tapers basal toward zero on the *stricter* of two arms, a mass-balance projection
  and a raw-CGM taper, so no forecast can license dosing into an observed fall
- **IOB divergence detector** — when glucose rises while the model insists it is falling, booked IOB is
  discounted, so a failed infusion site cannot silence the correction path at a high
- **High-glucose correction floor** — ramps basal while the mass-balance eventual stays above target
- **SMB gates** — fed-state, rising, post-hypo lockout, rolling stack cap, maxIOB/maxBolus ceilings

**Validated in-silico only**, plus replay against recorded logs. Not clinically validated. Note that
several changes which passed the virtual-patient cohort were later rejected when fitted against real
data — where that happened, the code comments record what was tried and why it was dropped.

---

## 3. Compose UI redesign

**`core/compose/` (design system) + Compose screens throughout `plugins/main/`**

A full-app rewrite of the interface in Jetpack Compose and Material 3, on a shared design-system module.
**Presentation-only**: it reuses the existing logic, constraints and protection paths underneath.

Rewritten: Home (hero card, graph, actions), Bolus/Carb wizard, Loop control, Temp target, Profile switch,
Actions & Careportal, Statistics, History timeline, Profile view and editor, Config Builder, preference
screens, YpsoPump status, and around a dozen legacy dialogs.

---

## Building

Standard AAPS build: `./gradlew assembleFullDebug`.

If the build fails inside KSP with `PROCESSING_ERROR`, run `./gradlew :app:clean` first — the annotation
processor caches stale generated types across large refactors.

The YpsoPump driver reads its session key from device preferences at runtime. It is **never** committed to
this repository.

---

## Issues

Please **do not raise issues from this fork against upstream AndroidAPS**. None of the above is their work
and none of it has had their review. Upstream bugs belong at
[nightscout/AndroidAPS](https://github.com/nightscout/AndroidAPS/issues).

---

## Licence

AGPL-3.0, same as upstream AndroidAPS. See [LICENSE.txt](LICENSE.txt).

---

<sub>Everything below is the upstream AndroidAPS README, unchanged.</sub>

# AAPS
* Check the wiki: https://wiki.aaps.app
*  Everyone who’s been looping with AAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)

[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/master.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/master)
[![Crowdin](https://d322cqt584bo4o.cloudfront.net/androidaps/localized.svg)](https://translations.aaps.app/project/androidaps)
[![Documentation Status](https://readthedocs.org/projects/androidaps/badge/?version=latest)](https://wiki.aaps.app/en/latest/?badge=latest)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/master/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS)

DEV: 
[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/dev/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS/tree/dev)

<img src="https://cdn.iconscout.com/icon/free/png-256/bitcoin-384-920569.png" srcset="https://cdn.iconscout.com/icon/free/png-512/bitcoin-384-920569.png 2x" alt="Bitcoin Icon" width="100">

3KawK8aQe48478s6fxJ8Ms6VTWkwjgr9f2
