package app.aaps.core.compose.components

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * Where the number ends and the unit begins.
 *
 * Worth testing rather than eyeballing, because the same fields carry strings that only look like
 * measurements. Getting this wrong does not merely style something oddly — shrinking half of "6d 4h"
 * would present a duration as a quantity, which is misreading the value, not decorating it.
 */
class MeasurementTextTest {

    @Test
    fun `a number and its unit are separated, with the space kept on the unit`() {
        assertThat(splitMeasurement("1.42 U")).isEqualTo("1.42" to " U")
        assertThat(splitMeasurement("0.45 U/h")).isEqualTo("0.45" to " U/h")
        assertThat(splitMeasurement("0 g")).isEqualTo("0" to " g")
        assertThat(splitMeasurement("88 U")).isEqualTo("88" to " U")
        assertThat(splitMeasurement("7.0 mmol/L")).isEqualTo("7.0" to " mmol/L")
    }

    @Test
    fun `a trailing percent is a unit even though it has no space`() {
        assertThat(splitMeasurement("74%")).isEqualTo("74" to "%")
        assertThat(splitMeasurement("100%")).isEqualTo("100" to "%")
    }

    @Test
    fun `durations are left alone`() {
        // "6d 4h" ends in a token containing a digit, so it is a value, not a value plus a unit.
        listOf("6d 4h", "3d", "12h", "45m").forEach {
            assertWithMessage(it).that(splitMeasurement(it)).isNull()
        }
    }

    @Test
    fun `words and placeholders are left alone`() {
        listOf("Empty", "—", "", "  ", "Expired", "no data").forEach {
            assertWithMessage("'$it'").that(splitMeasurement(it)).isNull()
        }
    }

    @Test
    fun `a trailing space does not produce an empty unit`() {
        assertThat(splitMeasurement("1.42 ")).isNull()
    }

    @Test
    fun `splitting never loses or reorders characters`() {
        // The rendered string has to remain exactly the value it was handed.
        listOf("1.42 U", "0.45 U/h", "0 g", "74%", "6d 4h", "Empty", "7.0 mmol/L").forEach { value ->
            val split = splitMeasurement(value)
            if (split != null) {
                assertWithMessage("'$value' round-trips").that(split.first + split.second).isEqualTo(value)
            }
        }
    }
}
