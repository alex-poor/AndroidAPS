package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * START_STOP_TBR (index 29) — set or cancel a Temporary Basal Rate. Written to CHAR_TBR_START_STOP
 * (…fcbee38b…).
 *
 * Request payload — 16 bytes LE — CONFIRMED ON THE REAL PUMP 2026-07-01 (0% for 15min accepted; pump
 * reported activeTbrPercent=0; firmware V05.02.03):
 *   percent(u32) || ~percent(u32) || duration(u32, minutes) || ~duration(u32)
 * The bitwise complements are the integrity check, so — unlike the bolus — NO CRC16 is appended.
 *
 * DURATION MUST BE IN 15-MINUTE STEPS (15, 30, …). A 3-minute duration was REJECTED with app-status
 * 0x82 (130) — a command-PARAMETER error, distinct from the 0x8A counter/structure error. (The on-wire
 * CamAPS TBR framing confirms 16 bytes; SandraK82's 5-byte format is wrong.)
 *
 * Cancel a running TBR via 100% for 0 minutes (see [cancelPayload]) — NOTE: a 0-minute duration is
 * UNVERIFIED and may also require a 15-min-step value; confirm before relying on cancel.
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
