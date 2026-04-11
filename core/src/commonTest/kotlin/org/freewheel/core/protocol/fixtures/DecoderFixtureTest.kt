package org.freewheel.core.protocol.fixtures

import org.freewheel.core.protocol.GotwayDecoder
import org.freewheel.core.protocol.KingsongDecoder
import org.freewheel.core.protocol.VeteranDecoder
import kotlin.test.Test

/**
 * Runs the golden-frame fixtures against their decoders.
 *
 * Each decoder gets a fresh instance per fixture so state never leaks between
 * tests. Add new fixtures to `<Decoder>Fixtures.kt` and wire them up here.
 */
class DecoderFixtureTest {

    // ========== Gotway ==========

    @Test
    fun `gotway 2020 board stationary`() {
        GotwayDecoder().runFixture(GotwayFixtures.board2020Stationary)
    }

    // ========== Veteran ==========

    @Test
    fun `veteran old board stationary`() {
        VeteranDecoder().runFixture(VeteranFixtures.oldBoardStationary)
    }

    // ========== Kingsong ==========

    @Test
    fun `kingsong KS-S18 model + live`() {
        KingsongDecoder().runFixture(KingsongFixtures.ks18ModelAndLive)
    }
}
