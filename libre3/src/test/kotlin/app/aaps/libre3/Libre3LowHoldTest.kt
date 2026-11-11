package app.aaps.libre3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The 2026-11-12 incident: BG fell 48 → … → 40 → 41 then the sensor dropped below its 2.2 mmol/L
 * floor and streamed out-of-range sentinels. Without a hold the loop went blind, the last zero
 * temp expired, and the pump resumed scheduled basal into a 2.2 mmol/L hypo. These lock in that a
 * crash-into-the-floor keeps the loop suspended, while a sensor fault at normal BG still drops.
 */
class Libre3LowHoldTest {

    private val min = 60_000L

    @Test
    fun `crash into the floor holds a severe-low each new minute`() {
        val h = Libre3LowHold()
        var t = 0L
        for (mgdl in listOf(48, 46, 44, 42, 40, 41)) { h.onValid(mgdl, t); t += min }
        // first sentinel immediately after a recent low -> hold
        assertThat(h.onOutOfRange(t, key = 100)).isEqualTo(40)
        assertThat(h.holding).isTrue()
        // the sensor's repeat notification in the same minute -> no double insert
        assertThat(h.onOutOfRange(t, key = 100)).isNull()
        // next minute -> hold again
        t += min
        assertThat(h.onOutOfRange(t, key = 101)).isEqualTo(40)
    }

    @Test
    fun `a sensor fault at normal BG is dropped, never fabricated as a low`() {
        val h = Libre3LowHold()
        h.onValid(180, 0L)
        assertThat(h.onOutOfRange(1 * min, key = 1)).isNull()
        assertThat(h.holding).isFalse()
    }

    @Test
    fun `a stale low trajectory is not trusted to start a hold`() {
        val h = Libre3LowHold()
        h.onValid(41, 0L)
        // drop-out 15 min later: too long after the low to be the same crash -> drop
        assertThat(h.onOutOfRange(15 * min, key = 1)).isNull()
    }

    @Test
    fun `the hold persists past the entry-recency window up to the cap`() {
        val h = Libre3LowHold()
        h.onValid(41, 0L)
        var key = 1L
        assertThat(h.onOutOfRange(1 * min, key++)).isEqualTo(40)    // entry
        assertThat(h.onOutOfRange(12 * min, key++)).isEqualTo(40)   // past the 10-min entry recency, still held
        assertThat(h.onOutOfRange(25 * min, key++)).isEqualTo(40)   // 24 min in, within the cap
        assertThat(h.onOutOfRange(27 * min, key++)).isNull()        // 26 min in, past the cap -> stop
        assertThat(h.holding).isFalse()
    }

    @Test
    fun `recovery to a normal reading clears the hold`() {
        val h = Libre3LowHold()
        h.onValid(41, 0L)
        assertThat(h.onOutOfRange(1 * min, key = 1)).isEqualTo(40)
        h.onValid(120, 2 * min)                                     // sensor recovers to a clearly-normal value
        assertThat(h.holding).isFalse()
        assertThat(h.onOutOfRange(3 * min, key = 2)).isNull()       // a later fault is not a low now
    }

    @Test
    fun `no hold before any valid reading has been seen`() {
        val h = Libre3LowHold()
        assertThat(h.onOutOfRange(1 * min, key = 1)).isNull()
    }
}
