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

Forked from `nightscout/AndroidAPS` at `43cc754` (2026-06-04). Five workstreams:

| # | Area | What it is | Status |
|---|------|-----------|--------|
| 1 | **[YpsoPump driver](#1-ypsopump-pump-driver)** | Loops a pump AAPS lists as "Not Loopable" | Alpha, dosing-capable |
| 2 | **[HovorkaMPC](#2-hovorkampc--a-model-predictive-controller)** | A nonlinear-MPC APS algorithm alongside oref1 | Experimental, runs live |
| 3 | **[Infusion-site handling](#3-infusion-site-handling)** | Treats a fresh cannula as a distinct physiological state, across the algorithm, wizard and careportal | Running |
| 4 | **[Compose UI redesign](#4-compose-ui-redesign)** | Full-app Material 3 rewrite of the interface | Running |
| 5 | **[Slim loop build](#5-slim-loop-build)** | Strips the app to the one pump and one algorithm it runs, and lets Android AOT-compile it | Running |

Plus a number of [smaller changes](#smaller-changes) — Nightscout over a private network, wizard fields
the redesign had dropped, and pump-driver reliability fixes.

All of it lives on **`main`** — the workstreams are separable as ideas, not as code (the fresh-site
work is algorithm *and* wizard; the slim build is gradle config *and* workers *and* UI), so a single
branch is the honest representation.

### Branches

| Branch | What it is |
|---|---|
| **`main`** | Everything above. This is the build that runs on hardware. |
| **`master`** | An untouched mirror of `nightscout/AndroidAPS` at the fork point, kept so upstream can be merged and diffed cleanly. No work of this fork's is on it. |
| *others* | Short-lived feature branches, merged and deleted. |

An earlier layout split the algorithm and the UI across two long-lived branches. It was retired in
August 2026 after the loop branch drifted to 0 commits ahead and 74 behind, six commit subjects were
applied twice, and a repair commit had to be written to carry algorithm fixes back across. Its tip
survives as the tag `loop-only-3.4.2.3`.

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
- **SITE-GUARD** — a fresh cannula suppresses SMB and stands the divergence detector down; see
  [section 3](#3-infusion-site-handling)

**Validated in-silico only**, plus replay against recorded logs. Not clinically validated. Note that
several changes which passed the virtual-patient cohort were later rejected when fitted against real
data — where that happened, the code comments record what was tried and why it was dropped.

---

## 3. Infusion-site handling

**`plugins/aps/.../hovorka/` (SITE-GUARD) · `ui/.../dialogs/` (wizard advisory, back-dated recording)**

The largest single effect found in this user's own data, and it is not an algorithm parameter — it is the
cannula. Across 17 days: for roughly six hours after a site change, glucose averaged **13.5 mmol/L against
7.6 in the same clock hours on non-change days, while taking four times the bolus insulin** (2.15 vs
0.51 U/h). Six changes out of six, paired sign test p=0.016. The cleanest case involved zero carbs, ~13 U
of bolus over six hours, and still averaged 11.8.

The important part is what that implies. A normal loop response there is not merely ineffective, it is
**actively harmful**: the insulin is not absorbed, it accumulates, and when the site opens it all arrives
at once. The change where the loop pushed hardest ended at 2.2 mmol/L.

So a fresh site is handled as its own state, in three places:

- **SITE-GUARD** — for the first 24 hours (configurable), SMB is suppressed (irreversible dosing has no
  place in a window where the high is caused by insulin *not arriving*), and the IOB-divergence detector
  is stood down. That detector fires on exactly this signature — glucose rising while booked IOB says it
  should fall — and responds by discounting IOB and adding correction. It was written for a site that has
  **failed**; a fresh site is **late**, and the two need opposite responses. An earlier version also
  capped basal; that was removed as the wrong instrument on the wrong timescale. The guard **fails open**:
  with no cannula change ever recorded it stays off rather than clamping forever on an empty history.
- **Fresh-site bolus advisory** — insulin peaks about twice as slowly at a day-1 site (time-to-peak
  110 min vs 56 min, Hildebrandt 1991), and a large single bolus compounds it. Splitting a dose is the
  user's call and the loop cannot do it for them, so the wizard carries the advisory: amber, informational,
  and only for boluses of 1.5 U or more, since a basal trickle is handled fine.
- **Back-dated site recording** — a cannula change is routinely made away from the phone. Prime/Fill used
  to stamp the current time with no way to correct it, so changes went unrecorded and were invisible to
  both the analysis and the guard. There is now a "changed N minutes ago" field with quick chips. It
  back-dates the **therapy event only** — the prime bolus is still delivered now, because insulin cannot
  be given retroactively and a fictional delivery time would corrupt IOB.

Armed on `CANNULA_CHANGE`, so a tubing- or reservoir-only fill does not trigger it.

---

## 4. Compose UI redesign

**`core/compose/` (design system) + Compose screens throughout `plugins/main/`**

A full-app rewrite of the interface in Jetpack Compose and Material 3, on a shared design-system module.
It reuses the existing dosing logic, constraints and protection paths underneath — every delivery still
goes through `BolusWizard.confirmAndExecute` and the same constraint chain.

Rewritten: Home (hero card, graph, actions), Bolus/Carb wizard, Loop control, Temp target, Profile switch,
Actions & Careportal, Statistics, History timeline, Profile view and editor, Config Builder, preference
screens, YpsoPump status, and around a dozen legacy dialogs.

**Not purely cosmetic, despite the above.** Some inputs that affect dosing changed:

- **Pre-bolus (carb time)** — restored after the first pass of the redesign dropped it
- **Extended carbs** — an absorption-duration field, with a `carbDurationHours` parameter threaded
  through the wizard calculation
- **Record-only insulin entry** — logs a bolus delivered by pump or pen into IOB *without* re-delivering
  it, reachable from the Home "+" menu
- **Fresh-site advisory** — see [section 3](#3-infusion-site-handling)
- The second confirmation popup was removed; the hold-to-deliver control is the confirmation

### What it looks like

Captured on the phone that runs the loop, so every number is live data rather than a mock-up.

| | | |
|:---:|:---:|:---:|
| <img src="docs/screenshots/home.png" width="240" alt="Home screen"> | <img src="docs/screenshots/home-details.png" width="240" alt="Details sheet"> | <img src="docs/screenshots/bolus-wizard.png" width="240" alt="Bolus wizard"> |
| **Home** — hero card, supplies, glucose graph | **Details** — supplies, loop & sensitivity | **Bolus wizard** — carbs, pre-bolus, absorption |
| <img src="docs/screenshots/loop-control.png" width="240" alt="Loop control sheet"> | <img src="docs/screenshots/temp-target.png" width="240" alt="Temp target sheet"> | <img src="docs/screenshots/actions.png" width="240" alt="Actions screen"> |
| **Loop control** — mode, suspend, disconnect | **Temp target** — intent presets, then adjust | **Actions** — therapy, event log, tools |
| <img src="docs/screenshots/algorithm-hovorkampc.png" width="240" alt="HovorkaMPC screen"> | <img src="docs/screenshots/statistics.png" width="240" alt="Statistics screen"> | <img src="docs/screenshots/history.png" width="240" alt="History timeline"> |
| **HovorkaMPC** — model response and its switches | **Statistics** — TIR, GMI, CV, TDD | **History** — unified, day-grouped timeline |
| <img src="docs/screenshots/profile.png" width="240" alt="Profile screen"> | <img src="docs/screenshots/config-builder.png" width="240" alt="Config Builder screen"> | |
| **Profile** — DIA, basal curve, ISF/IC/target | **Config Builder** — active loop, plugins, settings | |

The dark theme is the only theme; the design is mmol/L-first, and the accent colour is reserved for
"this is tappable" while greens, ambers and reds mean glucose or loop state and nothing else.

---

## 5. Slim loop build

**`loop` build type in `buildSrc/`, plus removals across the module tree**

Upstream ships every pump driver, every algorithm and every integration, because upstream serves everyone.
This fork serves one pump and one algorithm, so it carries what it uses. Measured on device before and after:

| | Before | After |
|---|---|---|
| APK on disk | 189 MB | 67 MB |
| Dex files | 34 | 7 |
| `resources.arsc` | 10.2 MB | 1.2 MB |
| Native ABIs | 7 | 1 (`arm64-v8a`) |
| Dexopt state | `run-from-apk` | `speed` |
| Idle CPU, screen off | 4.72%* | 2.59% of a core |

\* The before figure is a 56-hour mixed foreground/background average rather than a matched screen-off
window, so treat that one pair as indicative. The protocol-matched measurement is 3.04% → 2.59% across the
legacy-overview change alone, over identical 15-minute screen-off windows.

### The `loop` build type

The important part is not the size. **Android refuses to ahead-of-time compile a `DEBUGGABLE` package** —
`cmd package compile -m speed` reports success and silently downgrades to `verify`, so a debug build runs
`status=run-from-apk` and JIT-compiles every dex file, forever.

The `loop` build type is the debug build with `isDebuggable = false`, **signed with the same debug
keystore**. That signing choice is deliberate and load-bearing: the signature still matches what is
installed, so `adb install -r` remains an *update* rather than a reinstall, `/data` survives, and the pump
driver keeps its session key. Give it a different `signingConfig` and every install becomes an uninstall.

```bash
./gradlew :app:assembleFullLoop
adb install -r app/build/outputs/apk/full/loop/app-full-loop.apk
adb shell cmd package compile -m speed -f info.nightscout.androidaps   # not automatic after install
adb shell cmd package dump info.nightscout.androidaps | grep -A3 'Dexopt state'   # want status=speed
```

Build `assembleFullDebug` instead when you need a debugger, and rebuild `loop` afterwards.

### What was removed

Twelve unused pump drivers and the RileyLink radio only Medtronic/Omnipod need (YpsoPump plus
Medtrum, virtual and `pump:common` are kept); Firebase Analytics,
Crashlytics and Remote Config (`FabricPrivacy` now logs to the AAPS log instead, so its call sites are
unchanged); LeakCanary; Wear support and fourteen bundled watchfaces; all locales but `en`; all ABIs but
`arm64-v8a`. `material-icons-extended` was 19.7 MB of dex to draw 33 icons — the 25 that only exist there
are vendored into `core/compose` as path data extracted from the library itself, so they render identically.

### Runtime changes

Only two touch behaviour, and neither is on the dosing path:

- **Graph work is gated on UI visibility.** Every CGM tick ran a 16-worker chain, of which 14 built
  overview graph series by querying the database — with the screen off, and with the dosing decision
  queued thirteenth. The chain now runs IOB/COB and the loop first, and prepares graph series only while
  a screen is visible; `OverviewFragment` rebuilds them on resume. Hidden cycles run 5 workers in under a
  second against 17 taking several.
- **The hidden legacy overview no longer runs or draws.** The Compose home is an opaque overlay over the
  original layout, which was still being fed ~120 values per refresh and redrawing its own graphs.

Plus: Nightscout upload/download failures back off and log one stack per distinct error rather than one per
record, and `deviceStatus` — write-only rows that existed to be uploaded — is kept for 7 days instead of 186.

---

## Smaller changes

Things that do not warrant a section but are still differences from upstream.

**Nightscout over a private network.** NSClient v3 is HTTPS-only at four separate gates — the URL
validator, the plugin's scheme handling, the `nssdk` request builder, and Android's own
`network_security_config`. The last is the quiet one: it fails with `UnknownServiceException: CLEARTEXT
... not permitted` and only in AAPS's internal log. All four are patched so a Nightscout on a Tailscale
or LAN address works. **For private networks only** — do not point this at anything reachable from the
internet over plain HTTP.

**Nightscout failure handling.** Upload and download failures back off (30 s doubling to 30 min) and log
one stack per distinct error instead of one per record. With the mesh down, the previous behaviour was
416 failures in three hours and 55% of every line the app wrote.

**Pump-driver reliability.** A BLE watchdog that unwedges stalled operations (the recurring "pump
unreachable"), a fix for delivered boluses being discarded by an AAPS one-minute freshness gate, bolus
under-recording corrected by decoding pump status and reconciling against pump history, and the pump's
own 28-day session-key expiry identified and surfaced (`0x8C NO_SHARED_KEY`) rather than presenting as a
generic connection failure.

---

## Building

Two variants matter:

```bash
./gradlew :app:assembleFullLoop     # the build that drives the pump — non-debuggable, AOT-eligible
./gradlew :app:assembleFullDebug    # debuggable, for attaching a debugger
```

Both are signed with the debug keystore, so `install -r` between them preserves app data.

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

> ℹ️ **Fork change — NSClientv3 accepts `http://` Nightscout URLs.** Upstream AAPS is HTTPS-only for
> Nightscout sync, enforced at four independent layers: the URL input validator, the NSClientv3 plugin's
> scheme handling, the `nssdk` network builder, and the app's Android network-security config
> (`app/src/main/res/xml/network_security_config.xml`). This fork loosens all four so a plain-HTTP
> Nightscout can be used. **This is intended specifically for VPN / mesh setups (e.g. Tailscale,
> WireGuard, ZeroTier) where the transport is already end-to-end encrypted and TLS on Nightscout is
> redundant.** Cleartext is permitted only for the explicitly listed hosts in `network_security_config.xml`
> — everything else stays HTTPS-only. **Do not point NSClientv3 at a plain-HTTP endpoint over the public
> internet or an untrusted LAN**: your API token and health data would travel unencrypted. Add or remove
> allowed hosts by editing the `<domain>` entries in that file.

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
