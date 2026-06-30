package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * START_STOP_BOLUS (index 27) — deliver or cancel a bolus. Written to CHAR_BOLUS_START_STOP
 * (…fcbee18b…); the caller appends CRC16 before encryption.
 *
 * Request payload — 13 bytes LE (validated against mylife / firmware V05.02.03; vicktor + SandraK82
 * test app agree — NOT yet confirmed on our pump, gated behind capture-verify):
 *   total(u32, units×100) || duration(u32, minutes) || immediate(u32, units×100) || type(u8)
 *   type: 1 = immediate/standard (duration==0), 2 = extended/combo (duration>0)
 *   total is clamped to 1..2500 (0.01..25.00 U).
 *
 * To CANCEL, write an all-zero payload with only [12]=type (1=immediate, 2=extended): see [cancelPayload].
 *
 * [decode] parses the CHAR_BOLUS_STATUS read (CRC already stripped): the "fast" (immediate) block:
 *   fastStatus(u8) | fastSeq(u32) | fastInjected(u32 /100) | fastTotal(u32 /100).
 */
class BolusCommand(
    private val totalUnits: Double,
    private val durationMinutes: Int = 0,
    private val immediateUnits: Double = 0.0
) : YpsoCommand(YpsoCommandCodes.START_STOP_BOLUS) {

    // Immediate ("fast") block.
    var deliveredUnits: Double = 0.0; private set
    var totalProgrammedUnits: Double = 0.0; private set
    var bolusStatusCode: Int = 0; private set
    // Extended ("slow") block — populated for extended/combo boluses.
    var extendedStatusCode: Int = 0; private set
    var extendedDeliveredUnits: Double = 0.0; private set
    var extendedTotalUnits: Double = 0.0; private set
    var extendedMinutesElapsed: Int = 0; private set
    var extendedMinutesTotal: Int = 0; private set

    val isImmediate: Boolean get() = durationMinutes == 0
    val isDelivering: Boolean get() = bolusStatusCode != 0 || extendedStatusCode != 0

    override fun encode(): ByteArray {
        val totalScaled = (totalUnits * 100).roundToInt().coerceIn(1, MAX_BOLUS_X100)
        val immediateScaled = (immediateUnits * 100).roundToInt().coerceIn(0, totalScaled)
        val type: Byte = if (isImmediate) TYPE_IMMEDIATE else TYPE_EXTENDED
        return ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(totalScaled).putInt(durationMinutes).putInt(immediateScaled).put(type)
            .array()
    }

    override fun decode(data: ByteArray) {
        if (data.size >= 13) {
            // Immediate ("fast") block: status u8 @0 | seq u32 @1 | injected u32/100 @5 | total u32/100 @9
            bolusStatusCode = data[0].toInt() and 0xFF
            deliveredUnits = data.getUInt32(5) / 100.0
            totalProgrammedUnits = data.getUInt32(9) / 100.0
            // Extended ("slow") block (validated vs a real 2h/13U extended bolus):
            // status u8 @13 | seq u32 @14 | injected u32/100 @18 | total u32/100 @22 |
            // (fast-within-combo inj @26 / tot @30) | minutesElapsed u32 @34 | minutesTotal u32 @38
            if (data.size >= 42) {
                extendedStatusCode = data[13].toInt() and 0xFF
                extendedDeliveredUnits = data.getUInt32(18) / 100.0
                extendedTotalUnits = data.getUInt32(22) / 100.0
                extendedMinutesElapsed = data.getUInt32(34).toInt()
                extendedMinutesTotal = data.getUInt32(38).toInt()
            }
            success = true
        } else {
            errorCode = if (data.isNotEmpty()) data[0].toInt() and 0xFF else -1
            success = false
        }
    }

    companion object {
        const val MAX_BOLUS_X100 = 2500
        const val TYPE_IMMEDIATE: Byte = 1
        const val TYPE_EXTENDED: Byte = 2

        /** All-zero 13-byte payload with the type byte set — cancels the running fast/extended bolus. */
        fun cancelPayload(extended: Boolean): ByteArray =
            ByteArray(13).also { it[12] = if (extended) TYPE_EXTENDED else TYPE_IMMEDIATE }
    }
}
