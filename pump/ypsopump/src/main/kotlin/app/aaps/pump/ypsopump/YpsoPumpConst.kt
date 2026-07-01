package app.aaps.pump.ypsopump

import java.util.UUID

/**
 * YpsoPump BLE constants: UUIDs, command indices, error codes.
 * Derived from reverse engineering of CamAPS FX v1.4(190).111.
 */
object YpsoPumpConst {

    // -- BLE Service UUIDs --
    val SCAN_FILTER_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-ffffffffffff")
    val GENERAL_SERVICE_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-eeeeeeeeeeee")
    val DATA_CHAR_A_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff0000000a")
    val DATA_CHAR_B_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff0000000b")
    val CONTROL_CHAR_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
    val PUMP_SPECIFIC_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb0147bc5")
    val SECONDARY_SCAN_UUID: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000adde0000")
    val SECONDARY_DATA_UUID: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0002")

    // Standard BLE CCCD for notifications
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // -- Device Name Pattern --
    const val DEVICE_NAME_PREFIX = "mylife YpsoPump"

    // -- Crypto Constants --
    const val KEY_SIZE = 32           // Curve25519 / XChaCha20 key size
    const val NONCE_SIZE = 24         // XChaCha20 extended nonce
    const val TAG_SIZE = 16           // Poly1305 auth tag
    const val COUNTER_DATA_SIZE = 12  // rebootCounter(4) + writeCounter(8)
    const val KEY_EXCHANGE_READ_SIZE = 64   // challenge(32) + pumpPubKey(32)
    const val KEY_EXCHANGE_WRITE_SIZE = 116 // encrypted payload from backend
    const val KEY_EXPIRY_DAYS = 28

    // -- Connection Timeouts --
    const val CONNECT_TIMEOUT_MS = 30_000L
    const val COMMAND_TIMEOUT_MS = 10_000L
    const val SCAN_TIMEOUT_MS = 30_000L

    // -- SharedPreferences Keys --
    const val PREF_SHARED_KEY = "ypso_shared_key"
    const val PREF_PRIVATE_KEY = "ypso_private_key"
    const val PREF_PUMP_PUBLIC_KEY = "ypso_pump_public_key"
    const val PREF_WRITE_COUNTER = "ypso_write_counter"
    const val PREF_READ_COUNTER = "ypso_read_counter"
    const val PREF_REBOOT_COUNTER = "ypso_reboot_counter"
    const val PREF_KEY_DATE = "ypso_key_date"
    const val PREF_PUMP_SERIAL = "ypso_pump_serial"

    // -- Key source (Model 1: key is always captured from the genuine app; see memory/model3-keyexchange-backend.md) --
    // PREFERRED: put the captured key in prefs at runtime — NO rebuild needed, key stays out of the APK:
    //   adb: add  <string name="ypso_shared_key">AEC10ED9...</string>  to
    //        /data/data/info.nightscout.androidaps/shared_prefs/ypso_ble_state.xml  (plain MODE_PRIVATE prefs)
    //   (and optional <int name="ypso_reboot_counter" value="8"/> — only changes on a battery pull).
    // resolveSharedKey() takes prefs over this const. CAPTURED_KEY_HEX below is a build-time FALLBACK only
    // (leave "" for shared/published builds; the key is sensitive and must NOT be committed/pushed).
    const val PUMP_MAC = "REDACTED"
    const val CAPTURED_KEY_HEX = ""   // fallback only — prefer the ypso_shared_key pref (see above)

    // -- Write path (counters). Needed only for WRITEs (history index, dosing); reads need none.
    // Seed CAPTURED_WRITE_COUNTER with mylife's CURRENT numericWriteAppCounter (frida ml-readprefs,
    // captured while mylife is IDLE so it's stable); the cryptor uses seed+1 and auto-syncs on err 138.
    // REBOOT_COUNTER must match the pump's (mylife's stored rebootCounter, currently 8).
    const val CAPTURED_WRITE_COUNTER = -1L   // <0 = writes disabled (read-only)
    const val CAPTURED_REBOOT_COUNTER = 8

    // -- Test flag: run the ZERO-THERAPY write-transport validation (history index write + entry read)
    // once after connect, instead of a status read. Set false for normal status reads. Never dosing.
    const val RUN_WRITE_VALIDATION = false

    // -- READ-ONLY test flag: read SYSTEM_STATUS + BOLUS_STATUS once and log them (no writes). Safe to
    // run while a bolus is being delivered — validates the bolus-status decoder against live data.
    const val RUN_READ_BOLUS_STATUS = false

    // -- SAFETY-CRITICAL test flag: deliver ONE real bolus of TEST_BOLUS_UNITS once after connect.
    // This DELIVERS INSULIN. Only ever set true with explicit user consent + after capture-verify,
    // with the pump observed. Leave false otherwise.
    const val RUN_TEST_BOLUS = false
    const val TEST_BOLUS_UNITS = 0.1

    // -- Test flag: set ONE temporary basal rate once after connect (canary-gated, like the bolus).
    // TEST_TBR_PERCENT 0 = SUSPEND basal (REDUCES insulin — the safe first TBR test); 100 = cancel.
    // The pump auto-reverts after TEST_TBR_DURATION_MIN. Writes the pump (advances the counter →
    // recover mylife after). Leave false otherwise.
    const val RUN_TEST_TBR = false
    const val TEST_TBR_PERCENT = 0
    const val TEST_TBR_DURATION_MIN = 15
}
