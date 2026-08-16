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
> - **The key expires after 28 days.** Confirmed on real hardware (2026-07-28): at 28 days the pump
>   begins refusing every encrypted read with `NO_SHARED_KEY` (0x8C/140) and the loop stops dead.
>   You must re-extract a key every 28 days, and that requires a valid un-revoked keybox. Plan for
>   this before you rely on it for therapy — see [Key expiry](#key-expiry-28-days).
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
- **Bolus** — delivered and **confirmed by reading back pump status** rather than trusting the write
  acknowledgement, with a working cancel. Under-recording was fixed by decoding pump status properly and
  reconciling against pump history; a separate bug had AAPS discarding correctly-delivered boluses because
  of a one-minute record-freshness gate, which silently under-counted IOB.
- **TBR** — percent temp basals set and confirmed (duration must be in **15-minute steps**). A pump-side
  suspend is reflected back into AAPS as a 0-rate `PUMP_SUSPEND` TBR, so the loop knows delivery stopped.
- **Closed loop** — running continuously since 2026-07-02: Dexcom G6 → xDrip+ → AAPS → HovorkaMPC → this
  driver → pump, enacting real temp basals unattended.
- **Write counter** — AAPS **owns and persists** the pump's write counter, so it stays in sync across
  reconnects once the genuine app is off (sole controller). It self-heals from a dropped write-ack
  (`0x8B` counter-behind) by scanning forward with benign canaries — **TBR only**; a bolus fails closed
  rather than risk a double dose.
- **Session key at runtime** — the key, pump MAC and reboot counter are read from device preferences at
  startup, never compiled in and never committed. A rebuilt APK installed over the top keeps working
  without re-seeding.
- **Connection watchdog** — unwedges stalled BLE operations, which was the cause of recurring
  "pump unreachable" alarms.

### Not done / caveats
- TBR *cancel* uses a 100 % TBR (no dedicated stop command reverse-engineered yet).
- The basal **profile is programmed on the pump**, not written by the driver — your AAPS profile basal
  **must match** the pump's, because TBRs are percentage-relative. Change one and you must hand-mirror the
  other, or every TBR under-delivers and IOB is overcounted, silently.
- The pump enforces a **28-day session-key expiry** (see below), after which every encrypted read fails
  while the connection still looks healthy.
- A pump battery change bumps the reboot counter.

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
- **Failure-mode recovery** for what the pump actually does in service: `0x86` means a TBR is already
  running and must be stopped before a new one is accepted; `0x8B` means the write counter is behind,
  which a dropped ATT write-response causes because the pump advances its counter on accept even when the
  response is lost; `0x8C` means the 28-day key has expired.
- **The key lifetime itself** — that the pump enforces a 28-day expiry at all was not previously
  documented, and it presents as healthy GATT with every read failing.

See `pump/ypsopump/` for the code; the protocol write-up lives in SandraK82's research repo.

---

## Requirements
- A rooted Android device, the YpsoPump, and the ability to extract the pump session key from the genuine
  app (frida). Build AndroidAPS from this branch, seed the key, enable the plugin.

## Key expiry (28 days)

The pump enforces a **28-day expiry** on the shared key. This is not optional and not configurable.

Measured on a real pump:

```
sharedKeyDate (minted)   2026-06-29 17:30:58
last successful read     2026-07-28 00:08:04   key age 28d 6.62h
first NO_SHARED_KEY      2026-07-28 00:17:31   key age 28d 6.78h
```

Reads succeeded every 20 minutes right up to the last one, so this is a hard cutoff rather than a
gradual degradation. The ~6.7 hours past an exact 28 days suggests the pump checks lazily — a timer
or a daily boundary — rather than at the precise instant.

### Recognising it

The failure is easy to misdiagnose, because **the BLE link looks perfectly healthy**:

- GATT connect ✅
- MD5 auth ✅ — this is `MD5(mac + salt)`, a static hash with nothing to do with the shared key, so
  it succeeds even with no key at all
- CTRL_NOTIFY subscription ✅
- **every** encrypted read ❌ — `status=140, got 0 frames`, on every characteristic

It **survives** an app restart, a pump Bluetooth stop/start, and a **pump battery pull**. The key
normally survives a reboot, so a battery pull changing nothing is itself a diagnostic signal. The
counters never move, which rules out `0x8B` counter desync.

If you are chasing this live, AAPS's own persistent log is the place to look —
`/storage/emulated/0/Android/data/info.nightscout.androidaps/files/AndroidAPS.log` plus the rotated
`.zip` archives beside it. `logcat`'s ring buffer will have lost the transition. Note also that
`deviceStatus` records keep appearing after reads have stopped: AAPS re-publishes *cached* pump data,
which will give you a false impression of when contact was lost.

### Error codes

Documented by [SandraK82](https://github.com/SandraK82/ypsopump-research) in
`guides/building-a-driver-app.md` (not in the BLE-protocol doc):

| Code | Meaning |
|------|---------|
| `0x82` (130) | bad parameter |
| `0x85` (133) | GATT / connection |
| `0x86` (134) | `BLE_READ_ERROR_INVALID_SHARED_KEY` — new key exchange needed |
| `0x88` (136) | `KEY_EXCHANGE_ERROR_BLOCKED_OR_BUSY` — wait and retry |
| `0x8A` (138) | `BLE_WRITE_ERROR_ENCRYPTION_FAILED` — key or counter problem |
| `0x8B` (139) | `COUNTER_ERROR` — counter desync, recoverable by resync |
| `0x8C` (140) | **`NO_SHARED_KEY` — key exchange required.** Not recoverable in software. |
| `0x8D` (141) | `FRAGMENTATION_ERROR` — multiframe reassembly failure |

## License
Inherits the AndroidAPS license (AGPL-3.0). Same terms and the same **no-warranty** — emphatically so for
this experimental, insulin-dosing code.
