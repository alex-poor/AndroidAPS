# YpsoPump driver for AndroidAPS — ⚠️ EXPERIMENTAL / ALPHA

An AndroidAPS pump plugin for the **Ypsomed mylife YpsoPump**, which AndroidAPS officially lists as
*"Not Loopable — Ypso added very heavy 3rd-party encryption."* This is a proof-of-concept that it **can**
be looped, built on the reverse-engineering work of the people credited below.

> ## 🛑 READ THIS FIRST
> - **This driver DELIVERS INSULIN. It can dose incorrectly and hurt you.**
> - **Alpha. Validated by ONE person, on ONE pump, over ONE day, with a handful of commands.** It has had
>   nothing like the testing an insulin-dosing driver needs.
> - **NOT affiliated with or endorsed by** Ypsomed, the Nightscout Foundation, or the AndroidAPS project.
> - It only works with the pump's **per-pairing session key**, which you must **extract yourself from the
>   genuine app (mylife / CamAPS FX) on a rooted device** (e.g. via frida). There is **no clean/supported
>   path** — no key, no function.
> - **Use at your own risk, and only if you fully understand what it's doing.** Do not rely on it as therapy.

---

## Credit — this stands entirely on prior work

This driver would not exist without the reverse-engineering done by:

- **[SandraK82 — `ypsopump-research`](https://github.com/SandraK82/ypsopump-research)** — the comprehensive,
  clean-room RE of the YpsoPump: hardware, the BLE GATT protocol and the full 33-command set, the
  XChaCha20-Poly1305 / Curve25519 crypto stack, the 9-step key exchange, obfuscation analysis, and an
  AndroidAPS driver scaffold. The protocol foundation here is hers.
- **[vicktor — `ypsomed-pump`](https://github.com/vicktor/ypsomed-pump)** — a Kotlin YpsoPump SDK and
  invaluable real-world operational + key-lifecycle experience (the key is renewed from the Ypsomed backend;
  a rooted, online instance of the genuine app effectively acts as a key server). vicktor and SandraK82
  together established the control-characteristic UUIDs and the crypto.

Protocol findings from this project are being contributed **back** to SandraK82's research repo (see the
`docs/` PRs there). This work is meant to **feed the community effort**, not replace it — collaboration with
vicktor's SDK and SandraK82's driver is very welcome.

---

## What works (validated against a real pump, firmware V05.02.03)

- **Read** — connect over the OS BLE bond → `MD5(mac+salt)` auth → XChaCha20-Poly1305 → multi-frame read +
  decrypt of system status, reservoir, battery, event history.
- **Bolus** — a 0.1 U immediate bolus was delivered and confirmed on the pump.
- **TBR** — percent temp basals set and confirmed (duration must be in **15-minute steps**).
- **Loop** — an open-loop enact (`setTempBasalPercent`) validated end-to-end: Dexcom G6 → xDrip+ → AAPS →
  OpenAPS SMB → this driver → pump (an 80 % / 15 min TBR was accepted by the pump).
- **Write counter** — AAPS **owns and persists** the pump's write counter, so it stays in sync across
  reconnects once the genuine app is off (sole controller).

### Not done / caveats
- Bolus/SMB has only been exercised manually, not yet driven by the loop over time.
- Closed loop not attempted (open loop only so far).
- TBR *cancel* uses a 100 % TBR (no dedicated stop command reverse-engineered yet).
- The basal **profile is programmed on the pump**, not written by the driver — your AAPS profile basal
  **must match** the pump's, because TBRs are percentage-relative.
- The captured key is currently a build-time constant (empty in source); a runtime key-load from prefs is a
  TODO. A pump battery change bumps the reboot counter and requires re-seeding.

---

## What this project adds on top of the prior RE

On-hardware validation and specifics needed to actually *write* to the pump:
- **Command encoding** — index/selection commands are bare `value‖~value` complement (no CRC); only the
  **bolus** appends a CRC; **TBR** is `pct‖~pct‖dur‖~dur` (16 bytes, no CRC, 15-min duration steps).
- **The write handshake** — the pump gates control writes on an active NOTIFY subscription to
  `fcbee58b`; without it every write is rejected with ATT `0x8A`.
- **The counter model** — separate read/write counters, the write check is **forward-gap tolerant**, and
  writing a read-region value pollutes the write counter (a real coexistence hazard with the genuine app).
- A working **AndroidAPS `Pump` plugin** wiring `setTempBasal*` / `deliverTreatment` into the loop via
  `pumpSync`, with a canary-gated safe write path (aborts before touching the therapy characteristic if the
  counter is wrong).

See `pump/ypsopump/` for the code; the protocol write-up lives in SandraK82's research repo.

---

## Requirements
- A rooted Android device, the YpsoPump, and the ability to extract the pump session key from the genuine
  app (frida). Build AndroidAPS from this branch, seed the key, enable the plugin.

## License
Inherits the AndroidAPS license (AGPL-3.0). Same terms and the same **no-warranty** — emphatically so for
this experimental, insulin-dosing code.
