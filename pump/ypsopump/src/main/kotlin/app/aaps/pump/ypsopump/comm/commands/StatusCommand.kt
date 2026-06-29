package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.DeliveryMode
import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes

/**
 * GET_SYSTEM_STATUS (index 30) — reads delivery state, reservoir level and battery.
 *
 * Decrypted body is 18 bytes (CRC trailer already stripped by the caller). VERIFIED against a
 * real pump: the battery value matched the device's indicator and the reservoir tracked live as the
 * pump delivered. Offsets:
 *   @1  u32 LE  insulin remaining (centi-units)          [confirmed]
 *   @6  u8      battery percent                          [confirmed]
 *   @10 u32 LE  active TBR / basal percent (100 = normal)
 *   @5  u8      delivery mode (best guess — @0 is the alternative; not yet confirmed)
 *
 * Note: a previous revision used a 6-byte layout with battery@5 (from vicktor/ypsomed-pump's SDK).
 * Reading a real pump showed the body is 18 bytes and battery is @6 (byte 5 was a constant 0x02),
 * so that layout was wrong. Example body: 0a 1f010000 02 1e000000 64000000 000000.
 */
class StatusCommand : YpsoCommand(YpsoCommandCodes.GET_SYSTEM_STATUS) {

    var deliveryMode: Int = 0; private set
    var deliveryModeName: String = ""; private set
    var reservoirUnits: Double = 0.0; private set   // insulin remaining
    var batteryPercent: Int = 0; private set
    var activeTbrPercent: Int = 100; private set
    var isSuspended: Boolean = false; private set

    override fun encode(): ByteArray = byteArrayOf(0x00) // simple read request

    override fun decode(data: ByteArray) {
        if (data.size >= 18) {
            reservoirUnits = data.getUInt32(1).toDouble() / 100.0
            batteryPercent = data[6].toInt() and 0xFF
            activeTbrPercent = data.getUInt32(10).toInt()
            deliveryMode = data[5].toInt() and 0xFF
            deliveryModeName = DeliveryMode.name(deliveryMode)
            isSuspended = DeliveryMode.isSuspended(deliveryMode)
            success = true
        } else {
            errorCode = if (data.isNotEmpty()) data[0].toInt() and 0xFF else -1
            success = false
        }
    }
}
