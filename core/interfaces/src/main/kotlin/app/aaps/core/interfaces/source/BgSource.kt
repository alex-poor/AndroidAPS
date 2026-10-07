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

    /**
     * This source's authoritative sensor lifecycle (warming up / active / expired), or **null** if it
     * cannot report one. When non-null the Overview drives the sensor pill, the warm-up hero line and
     * the loop pill from it directly, instead of reconstructing them from the latest SENSOR_CHANGE
     * event and the generic warm-up preference. See [SensorLifecycle] for why that fallback misreads a
     * freshly applied sensor as "Expired" / "No recent reading".
     */
    val sensorLifecycle: SensorLifecycle?
        get() = null
}