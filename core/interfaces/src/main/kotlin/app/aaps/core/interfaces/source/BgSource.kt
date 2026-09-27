package app.aaps.core.interfaces.source

interface BgSource {

    /**
     *  Does bg source support advanced filtering ? Currently Dexcom native mode only
     *
     *  @return true if supported
     */
    fun advancedFilteringSupported(): Boolean = false

    /**
     *  Sensor battery level in %
     *
     *  -1 if not supported
     */
    val sensorBatteryLevel: Int
        get() = -1

    /**
     * A manually-entered BG (finger-prick), [glucoseMgdl] in mg/dL at [timeMs]. A source that can use
     * it — e.g. the native Libre 3 source correcting its lagging factory-calibrated stream on a rise —
     * consumes it and returns a message (blank = consumed but nothing to say). A source that has no
     * use for a manual BG returns **null**, and the caller falls back to its default (an xDrip
     * calibration broadcast). Recording the value as a BG-check / therapy event is the caller's job,
     * not this hook's.
     */
    fun onManualBg(timeMs: Long, glucoseMgdl: Int): String? = null
}