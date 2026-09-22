# Native FreeStyle Libre 3 / 3 Plus CGM source for AndroidAPS

This module lets AAPS talk to a Libre 3 / Libre 3 Plus sensor **directly over BLE** — no Juggluco,
no xDrip, nothing else in the glucose path. AAPS performs the sensor authorization handshake,
receives the 1‑minute glucose stream, backfills gaps from the sensor's own retained history, and
exposes sensor lifecycle (warm‑up / active / expiring / failed) in the UI.

> **Private, single‑device build.** This is a research/personal‑use module. Do not distribute an APK
> built from it. See [Security & credentials](#security--credentials).

---

## Credit — this stands on Juggluco

**None of this would exist without [Juggluco](https://github.com/j-kaltes/Juggluco) by
Jaap Korthals Altes (`j-kaltes`), GPL‑3.0‑or‑later.**

Juggluco is the reference implementation of the Libre 3 BLE protocol and the sensor authorization
engine. Two distinct debts:

1. **Vendored native core** — the entire sensor‑authorization / challenge‑cipher core in
   [`src/main/cpp/libre3/process/`](src/main/cpp/libre3/process/) and
   [`src/main/cpp/bcrypt/`](src/main/cpp/bcrypt/) is Juggluco's C, copied **verbatim** with its
   GPL‑3.0 headers intact. It is not a reimplementation. Provenance, exact paths, byte counts and
   build profile are in [`src/main/cpp/VENDOR.md`](src/main/cpp/VENDOR.md). Every file there is
   byte‑identical to `j-kaltes/Juggluco` branch `primary`.

2. **Protocol knowledge** — every Kotlin file in this module that decodes a wire format
   (`Libre3Framing`, `Libre3GlucoseRecord`, `Libre3History`, `Libre3PatchInfo`, `Libre3Nfc`,
   `Libre3SecuritySession`, `Libre3Gatt`) transcribes struct layouts, GATT UUIDs, framing rules and
   CRC parameters that Juggluco worked out first. Where a format was taken from Juggluco the source
   file says so in its header. These are clean‑room Kotlin, but the *knowledge* is Juggluco's.

AAPS is AGPL‑3.0; GPL‑3.0 combines upward into it. If you build on this, keep the GPL headers and
credit Juggluco.

---

## Why native? (and why upstream doesn't ship this)

xDrip and AAPS cannot **start** a Libre 3 sensor, so today users run **Juggluco** to activate the
sensor and stream glucose, then bridge into AAPS. That works, but:

- **It decimates the data.** Juggluco reads every minute, but the xDrip/broadcast bridge into AAPS
  forwards roughly one reading per 5 minutes. Measured on a live sensor: 9 readings into xDrip → 1
  forwarded to AAPS. The HovorkaMPC EKF steps once a minute and reads **raw** BG; feeding it 1‑of‑5
  throws away most of the signal.
- **Backfill is lost.** The sensor retains history and Juggluco *does* backfill its own store, but
  those recovered readings don't reach AAPS through the bridge — an out‑of‑range gap stays a hole in
  the loop's record.
- **Three apps, three failure points.** Sensor → Juggluco → xDrip → AAPS is three BLE/IPC hops that
  can each wedge. Native collapses it to one.

The reason first‑party support doesn't exist upstream is **not** technical — it's that the
authorization core is derived from Abbott's protected code, and the Nightscout Foundation can't ship
that in a distributed binary. A private single‑device build doesn't have that constraint, which is
the whole premise here.

---

## Architecture

```
 Libre 3 sensor
      │  BLE (GATT)
      ▼
 Libre3BleClient ──────────────── transport: op queue, CCCD chain, reconnect, watchdog
      │
      ├─ Libre3SecuritySession ── handshake state machine (Crypto iface for JVM testing)
      │        │
      │        ▼
      │   Libre3Native (JNI) ──── liblibre3.so
      │        │                    ├─ libl3core.a   (VENDORED: authorization engine, P-256)
      │        │                    ├─ libl3ccm.a    (VENDORED: AES-CCM)
      │        │                    └─ jni/libre3_jni.cpp  (our shim + AES-128-CCM session cipher)
      │        ▼
      ├─ Libre3Framing ────────── de/reassembly (two different directions, see below)
      ├─ Libre3GlucoseRecord ──── 29-byte per-minute struct → mg/dL + trend
      ├─ Libre3History ────────── retained 5-min history → gap backfill
      └─ Libre3PatchInfo / -Status  sensor identity + lifecycle
      │
      ▼
 Libre3SourcePlugin (plugins:source, @IntKey 401)
      │  range/rate sanity gate, dedupe on lifeCount, backfill timestamps
      ▼
 AAPS glucose DB ──► HovorkaMPC / overview / everything downstream
```

UI lives in `plugins/source/.../compose/` (`Libre3SensorScreen`, `Libre3ScanScreen`) hosted by
`Libre3SensorFragment`; sensor lifecycle is **derived** from the activation time, not stored, so it
survives restarts with nothing to drift. (Full UI rationale lives in the private RE workspace,
`report/libre3-ui-plan.md`, not committed here.)

### Native build profile

`liblibre3.so` is built from the vendored core with `-DL3_EXTERNAL_ENTROPY_ONLY=1`, which removes
the **only** OpenSSL dependency (Juggluco uses libcrypto solely for `RAND_bytes`) and routes all
entropy through the JNI layer from Java `SecureRandom`/`getrandom(2)`. Net result: no `dlopen` of
system libcrypto, one fewer runtime dependency, and the archive's only unresolved externals are
libc. `-fvisibility=hidden` on all three targets keeps ~350 internal `l3_*` symbols out of
`.dynsym`. Details and a reproduce recipe are in [`src/main/cpp/VENDOR.md`](src/main/cpp/VENDOR.md).

---

## Protocol notes (the parts that cost time)

These are documented so the next person doesn't re‑derive them. All verified against a real sensor.

- **CCCD chain.** The sensor rejects the first control write with ATT `0xFD` ("CCCD Improperly
  Configured") unless **all seven** data characteristics have notifications enabled first, in order:
  `PATCH_CONTROL, EVENT_LOG, HISTORIC_DATA, CLINICAL_DATA, FACTORY_DATA, GLUCOSE_DATA, PATCH_STATUS`.
  Enabling only the ones you read is not enough.
- **Handshake framing is asymmetric** (`Libre3Framing`):
  - sensor → app: `sequence(1) ‖ payload`, sequence starts at 0 and increments.
  - app → sensor: `offsetLE16(2) ‖ 18 bytes`, **always** 20 bytes zero‑padded; the header is a
    byte offset, not a sequence number.
- **Handshake plaintext order** (`Libre3SecuritySession`): app sends `r1 ‖ r2 ‖ blePin` (36 B);
  sensor replies `r2 ‖ r1 ‖ kEnc ‖ ivEnc` (56 B) — **r2 first** in the reply.
- **The JNI success code is `1`, not `0`.** `L3_SECURITY_OK == 0` is an internal enum; the
  `int`‑returning handshake calls return 1 on success.
- **Glucose lag.** The per‑minute record carries both `readingMgDl` (real‑time, offset 2) and
  `historicalReading` (lagged, offset 12). They diverge mid‑excursion — a captured falling record
  showed 5.1 vs 6.4 mmol/L. We feed the loop the **real‑time** field.
- **History request** is a 7‑byte control command `{1,0} ‖ arg ‖ int32LE from`, `from` floored at 5,
  issued from `onPatchStatus` (not `onAuthorized`).
- **NFC activation CRC** (`Libre3Nfc`): CRC‑16 poly `0x1021`, init `0xFFFF`, **refin=true
  refout=false**; verified against Juggluco's vectors `0x313E / 0xCCBF / 0x2063`.

---

## Dense-data handling in core AAPS

Libre 3 delivers ~5× more readings than AAPS was built around. Two core changes make that a benefit
rather than a jagged mess:

- `AutosensDataStoreObject.isDenseData()` / `createBucketedDataAveraged()` — when readings are dense
  (median interval < 150s), 1‑minute samples are **averaged** into 5‑minute buckets instead of
  point‑sampled, so `GlucoseStatus` and everything reading bucketed data see a clean series. The
  EKF still reads the raw 1‑minute values directly — bucketing is only for the legacy consumers.
- The overview chart draws the raw 1‑minute scatter under the bucketed trace, so nothing is hidden
  and the smoothing is honest rather than interpolated.
- Sensor life / warm‑up are now prefs (`OverviewSensorLifeDays`, `OverviewSensorWarmupMinutes`),
  not the old hardcoded 10 days.

---

## Build

```sh
# machine default JDK is too new for this Gradle; use the pinned 21
JAVA_HOME=<workspace>/tools/jdk-21.0.5+11 ./gradlew :libre3:compileFullDebugKotlin
JAVA_HOME=<workspace>/tools/jdk-21.0.5+11 ./gradlew :libre3:testFullDebugUnitTest
# full APK:
<workspace>/sdk/build-loop-apk.sh
```

Gotchas:

- **JDK.** JDK 26 fails with `Unsupported class file major version 70`. Pin JDK 21.
- **NDK.** AGP will silently substitute its own NDK; `ndkVersion` is pinned to the validated
  `28.2.13676358`. Don't let it drift.
- **Dagger wiring.** `:app` needs a direct `implementation(project(":libre3"))` — an `api(":libre3")`
  from `plugins:source` alone does not let KSP resolve `Libre3SourcePlugin`.
- **`Sources.Libre3` is stored by name** in the DB enum, so appending it needs no migration — but it
  touches four exhaustive `when` blocks (`SourcesExtension`, `UserEntryPresentationHelperImpl`,
  `TranslatorImpl`, and the enum itself). All four must be updated or the build breaks.

---

## Tests

Pure‑JVM, no device:

- `src/test/kotlin/.../Libre3FramingTest`, `-GlucoseRecordTest`, `-HistoryTest`, `-NfcTest`,
  `-PatchInfoTest`, `-ScanFlowTest` — wire‑format decoders against known vectors.
- `plugins/source/.../Libre3LifecycleTest`, `-SensorStateTest` — lifecycle boundaries.
- `src/test/l3_replay.py` — conformance harness. Builds the vendored core for the host
  (`clang -shared ... -DL3_EXTERNAL_ENTROPY_ONLY=1`) and replays a captured JNI trace through it,
  comparing byte‑for‑byte. If green, our handshake is bit‑identical to Juggluco's. The RESUME‑path
  (saved authorization) is fully byte‑comparable; fresh‑pairing draws entropy internally so it's
  reported INFO. **The trace and `Libre3SecuritySessionTest.kt` are not in this repo** — they embed
  a real sensor's secrets (see below).

---

## Security & credentials

- **Per‑sensor credentials** (BLE PIN + `kAuth` authorization) are the keys to a specific sensor.
  They live in `filesDir/libre3-credentials.json` (loaded by `Libre3CredentialStore`) and in the
  workspace's `realdata/` — **both outside this repo**, both `.gitignore`d. They are per‑sensor,
  replaced wholesale at each sensor change, and deliberately a file (not a preference) so they can't
  end up in a shared settings export.
- **`Libre3SecuritySessionTest.kt` is intentionally excluded from git** — it hard‑codes a real
  sensor's PIN and derived session keys as its test vectors. Keep it local.
- **The vendored native code is public GPL** (it's in Juggluco), so it *is* committed — see
  `VENDOR.md` rule 3. The line to hold is: never commit **credentials**, and don't distribute a
  built **APK**.

---

## Status

- ✅ Authorization, 1‑minute streaming, backfill, reconnect — **live on hardware**.
- ✅ Dense‑aware bucketing, lifecycle UI, source plugin wired end‑to‑end.
- ✅ **Sensor start from AAPS over NFC — built end to end.** `Libre3NfcActivation` (the read +
  activate exchange) over `Libre3NfcV` (reader‑mode NfcV I/O), the `Libre3Nfc2` response parser, a
  credentials write, and the existing BLE fresh‑pairing that mints the kAuth. Reader‑mode is armed
  from the sensor screen behind a confirmation; `readInfo` (safe) and `activate` (the irreversible
  `02 A0` write) are separate methods.
- ⏳ **One live validation outstanding.** Activating a fresh sensor is irreversible, so the NFC path
  is so far proven only off‑device — unit tests against a real 2026‑09‑22 capture (`nfc2` MAC and
  activation time parse byte‑exact) and against the transcribed Juggluco command sequence. The first
  real activation, and pinning the account id (`LIBRE3_ACCOUNT_ID`), wait for the next sensor change.
  Until then the Juggluco import path stays as the fallback. Full plan lives in the private RE
  workspace, `report/libre3-native-plan.md` (not committed here).
