package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * START_STOP_TBR (index 29) — set or cancel a Temporary Basal Rate. Written to CHAR_TBR_START_STOP
 * (…fcbee38b…).
 *
 * Request payload — 16 bytes LE (validated against mylife / firmware V05.02.03; vicktor + SandraK82
 * test app agree — NOT yet confirmed on our pump, gated behind capture-verify):
 *   percent(u32) || ~percent(u32) || duration(u32, minutes) || ~duration(u32)
 * The bitwise complements are the integrity check, so — unlike the bolus — NO CRC16 is appended.
 *
 * Cancel a running TBR by setting 100% for 0 minutes (see [cancelPayload]).
 */
class TbrCommand(
    private val percent: Int,
    private val durationMinutes: Int
) : YpsoCommand(YpsoCommandCodes.START_STOP_TBR) {

    var tbrStatusCode: Int = 0; private set

    override fun encode(): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(percent).putInt(percent.inv())
            .putInt(durationMinutes).putInt(durationMinutes.inv())
            .array()

    override fun decode(data: ByteArray) {
        if (data.isNotEmpty()) {
            tbrStatusCode = data[0].toInt() and 0xFF
            success = true
        } else {
            success = false
        }
    }

    companion object {
        /** Cancel a running TBR: 100% for 0 minutes. */
        fun cancelPayload(): ByteArray = TbrCommand(100, 0).encode()
    }
}
