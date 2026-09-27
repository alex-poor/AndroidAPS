package app.aaps.plugins.source

import app.aaps.libre3.Libre3History
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class Libre3BackfillTest {

    private val started = 1_790_000_000_000L
    private fun at(lifeCount: Int) = started + lifeCount * 60_000L
    private val now = at(1_000)

    @Test
    fun `history over minutes already covered by live readings is not stored`() {
        // Live readings every minute from 900 to 990, stamped on arrival ~20 s after the sensor minute.
        val live = (900..990).map { at(it) + 20_000L }
        val history = (900..990 step 5).map { Libre3History.Entry(it, 120) }
        assertThat(libre3GapFill(history, started, now, live)).isEmpty()
    }

    @Test
    fun `history inside a real gap is stored`() {
        val live = (900..930).map { at(it) + 20_000L } + (961..990).map { at(it) + 20_000L }
        val history = (900..990 step 5).map { Libre3History.Entry(it, 120) }
        val stored = libre3GapFill(history, started, now, live).map { it.lifeCount }
        // 930 and 960 sit within 2.5 min of a live reading; 935..955 are the gap.
        assertThat(stored).containsExactly(935, 940, 945, 950, 955).inOrder()
    }

    @Test
    fun `future and pre-start entries are dropped`() {
        val history = listOf(Libre3History.Entry(-5, 100), Libre3History.Entry(1_010, 100), Libre3History.Entry(500, 100))
        assertThat(libre3GapFill(history, started, now, emptyList()).map { it.lifeCount }).containsExactly(500)
    }

    @Test
    fun `after a restart history starts just before the newest stored reading, not at sensor start`() {
        assertThat(libre3HistoryFrom(started, at(4_000) + 30_000L)).isEqualTo(3_990)
        assertThat(libre3HistoryFrom(started, null)).isEqualTo(0)
        assertThat(libre3HistoryFrom(started, started - 1)).isEqualTo(0)   // a reading from the previous sensor
    }
}
