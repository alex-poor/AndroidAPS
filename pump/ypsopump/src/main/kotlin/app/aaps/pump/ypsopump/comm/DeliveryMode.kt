package app.aaps.pump.ypsopump.comm

/**
 * Pump delivery mode = byte 0 of the decrypted SYSTEM_STATUS body.
 * Values from the YpsoPump firmware (via vicktor/ypsomed-pump).
 */
object DeliveryMode {
    const val STOPPED = 0
    const val BASAL = 1
    const val TBR = 2
    const val BOLUS_FAST = 3
    const val BOLUS_EXTENDED = 4
    const val BOLUS_AND_BASAL = 5
    const val PRIMING = 6
    const val PAUSED = 7

    fun name(mode: Int): String = when (mode) {
        STOPPED -> "Stopped"
        BASAL -> "Basal"
        TBR -> "TBR Active"
        BOLUS_FAST -> "Fast Bolus"
        BOLUS_EXTENDED -> "Extended Bolus"
        BOLUS_AND_BASAL -> "Bolus + Basal"
        PRIMING -> "Priming"
        PAUSED -> "Paused"
        else -> "Unknown($mode)"
    }

    /** The pump is not delivering basal in these modes. */
    fun isSuspended(mode: Int): Boolean = mode == STOPPED || mode == PAUSED
}
