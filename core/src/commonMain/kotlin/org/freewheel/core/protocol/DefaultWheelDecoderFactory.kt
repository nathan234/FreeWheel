package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelType

/**
 * Default factory for creating wheel protocol decoders.
 */
class DefaultWheelDecoderFactory : WheelDecoderFactory {

    override fun createDecoder(wheelType: WheelType): WheelDecoder? {
        return when (wheelType) {
            WheelType.GOTWAY, WheelType.GOTWAY_VIRTUAL -> GotwayDecoder()
            WheelType.VETERAN -> VeteranDecoder()
            WheelType.LEAPERKIM -> LeaperkimCanDecoder()
            WheelType.KINGSONG -> KingsongDecoder()
            WheelType.NINEBOT -> NinebotDecoder()
            WheelType.NINEBOT_Z -> NinebotZDecoder()
            WheelType.INMOTION -> InMotionDecoder()
            WheelType.INMOTION_V2 -> InMotionV2Decoder()
            else -> null
        }
    }

    override fun supportedTypes(): List<WheelType> {
        return listOf(
            WheelType.GOTWAY,
            WheelType.GOTWAY_VIRTUAL,
            WheelType.VETERAN,
            WheelType.LEAPERKIM,
            WheelType.KINGSONG,
            WheelType.NINEBOT,
            WheelType.NINEBOT_Z,
            WheelType.INMOTION,
            WheelType.INMOTION_V2
        )
    }
}

/**
 * Extension function to get a decoder for a wheel type with automatic caching.
 */
class CachingWheelDecoderFactory(
    private val delegate: WheelDecoderFactory = DefaultWheelDecoderFactory()
) : WheelDecoderFactory {

    private val cache = mutableMapOf<WheelType, WheelDecoder>()

    override fun createDecoder(wheelType: WheelType): WheelDecoder? {
        return cache.getOrPut(wheelType) {
            delegate.createDecoder(wheelType) ?: return null
        }
    }

    override fun supportedTypes(): List<WheelType> = delegate.supportedTypes()

    /**
     * Clear the cache and reset all decoders.
     */
    fun clearCache() {
        cache.values.forEach { it.reset() }
        cache.clear()
    }

    /**
     * Get a cached decoder without creating a new one.
     */
    fun getDecoderOrNull(wheelType: WheelType): WheelDecoder? = cache[wheelType]
}
