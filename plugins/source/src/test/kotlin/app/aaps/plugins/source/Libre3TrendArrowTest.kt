package app.aaps.plugins.source

import app.aaps.core.data.model.TrendArrow
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class Libre3TrendArrowTest {

    @Test
    fun `each sensor trend state maps to its own single arrow`() {
        assertThat(libre3TrendArrow(1)).isEqualTo(TrendArrow.SINGLE_DOWN)
        assertThat(libre3TrendArrow(2)).isEqualTo(TrendArrow.FORTY_FIVE_DOWN)
        assertThat(libre3TrendArrow(3)).isEqualTo(TrendArrow.FLAT)
        assertThat(libre3TrendArrow(4)).isEqualTo(TrendArrow.FORTY_FIVE_UP)
        assertThat(libre3TrendArrow(5)).isEqualTo(TrendArrow.SINGLE_UP)
    }

    @Test
    fun `no trend and undefined states show no arrow, never a double one`() {
        listOf(0, 6, 7).forEach { assertThat(libre3TrendArrow(it)).isEqualTo(TrendArrow.NONE) }
    }
}
