package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for DefaultWheelDecoderFactory and CachingWheelDecoderFactory.
 */
class DefaultWheelDecoderFactoryTest {

    @Test
    fun `factory creates Gotway decoder`() {
        val factory = DefaultWheelDecoderFactory()
        val decoder = factory.createDecoder(WheelType.GOTWAY)
        assertNotNull(decoder)
        assertTrue(decoder is GotwayDecoder)
        assertEquals(WheelType.GOTWAY, decoder.wheelType)
    }

    @Test
    fun `factory creates Gotway virtual decoder`() {
        val factory = DefaultWheelDecoderFactory()
        val decoder = factory.createDecoder(WheelType.GOTWAY_VIRTUAL)
        assertNotNull(decoder)
        assertTrue(decoder is GotwayDecoder)
    }

    @Test
    fun `factory creates Veteran decoder`() {
        val factory = DefaultWheelDecoderFactory()
        val decoder = factory.createDecoder(WheelType.VETERAN)
        assertNotNull(decoder)
        assertTrue(decoder is VeteranDecoder)
        assertEquals(WheelType.VETERAN, decoder.wheelType)
    }

    @Test
    fun `factory creates Kingsong decoder`() {
        val factory = DefaultWheelDecoderFactory()
        val decoder = factory.createDecoder(WheelType.KINGSONG)
        assertNotNull(decoder)
        assertTrue(decoder is KingsongDecoder)
        assertEquals(WheelType.KINGSONG, decoder.wheelType)
    }

    @Test
    fun `factory creates Ninebot decoder`() {
        val factory = DefaultWheelDecoderFactory()
        val decoder = factory.createDecoder(WheelType.NINEBOT)
        assertNotNull(decoder)
        assertTrue(decoder is NinebotDecoder)
        assertEquals(WheelType.NINEBOT, decoder.wheelType)
    }

    @Test
    fun `factory creates Ninebot Z decoder`() {
        val factory = DefaultWheelDecoderFactory()
        val decoder = factory.createDecoder(WheelType.NINEBOT_Z)
        assertNotNull(decoder)
        assertTrue(decoder is NinebotZDecoder)
        assertEquals(WheelType.NINEBOT_Z, decoder.wheelType)
    }

    @Test
    fun `factory creates InMotion V1 decoder`() {
        val factory = DefaultWheelDecoderFactory()
        val decoder = factory.createDecoder(WheelType.INMOTION)
        assertNotNull(decoder)
        assertTrue(decoder is InMotionDecoder)
        assertEquals(WheelType.INMOTION, decoder.wheelType)
    }

    @Test
    fun `factory creates InMotion V2 decoder`() {
        val factory = DefaultWheelDecoderFactory()
        val decoder = factory.createDecoder(WheelType.INMOTION_V2)
        assertNotNull(decoder)
        assertTrue(decoder is InMotionV2Decoder)
        assertEquals(WheelType.INMOTION_V2, decoder.wheelType)
    }

    @Test
    fun `factory returns null for unknown wheel type`() {
        val factory = DefaultWheelDecoderFactory()
        val decoder = factory.createDecoder(WheelType.Unknown)
        assertNull(decoder)
    }

    @Test
    fun `supportedTypes includes all decoders`() {
        val factory = DefaultWheelDecoderFactory()
        val supported = factory.supportedTypes()

        assertTrue(WheelType.GOTWAY in supported)
        assertTrue(WheelType.GOTWAY_VIRTUAL in supported)
        assertTrue(WheelType.VETERAN in supported)
        assertTrue(WheelType.KINGSONG in supported)
        assertTrue(WheelType.NINEBOT in supported)
        assertTrue(WheelType.NINEBOT_Z in supported)
        assertTrue(WheelType.INMOTION in supported)
        assertTrue(WheelType.INMOTION_V2 in supported)
    }

    @Test
    fun `caching factory returns same decoder instance`() {
        val factory = CachingWheelDecoderFactory()

        val decoder1 = factory.createDecoder(WheelType.GOTWAY)
        val decoder2 = factory.createDecoder(WheelType.GOTWAY)

        assertNotNull(decoder1)
        assertNotNull(decoder2)
        assertTrue(decoder1 === decoder2, "Should return same instance")
    }

    @Test
    fun `caching factory clearCache resets decoders`() {
        val factory = CachingWheelDecoderFactory()

        val decoder1 = factory.createDecoder(WheelType.GOTWAY)
        assertNotNull(decoder1)

        factory.clearCache()

        val decoder2 = factory.createDecoder(WheelType.GOTWAY)
        assertNotNull(decoder2)
        assertTrue(decoder1 !== decoder2, "Should return different instance after clear")
    }

    @Test
    fun `caching factory getDecoderOrNull returns null before creation`() {
        val factory = CachingWheelDecoderFactory()

        val decoder = factory.getDecoderOrNull(WheelType.GOTWAY)
        assertNull(decoder)
    }

    @Test
    fun `caching factory getDecoderOrNull returns decoder after creation`() {
        val factory = CachingWheelDecoderFactory()

        factory.createDecoder(WheelType.GOTWAY)
        val decoder = factory.getDecoderOrNull(WheelType.GOTWAY)

        assertNotNull(decoder)
        assertTrue(decoder is GotwayDecoder)
    }

    @Test
    fun `caching factory supportedTypes delegates to inner factory`() {
        val factory = CachingWheelDecoderFactory()
        val supported = factory.supportedTypes()

        assertTrue(supported.isNotEmpty())
        assertTrue(WheelType.INMOTION_V2 in supported)
    }
}
