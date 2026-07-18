package org.freewheel.core.ble

import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.service.WheelTransportProfile

/**
 * Contains the BLE service and characteristic UUIDs needed to communicate with a wheel.
 * This information is determined during service discovery and wheel type detection.
 *
 * Also carries the wheel-family [transportProfile] — write mode, pacing, MTU,
 * warmup, and keepalive choices. Commit 2 of the Kingsong BLE parity plan
 * routes the profile through [WheelConnectionManager.configureForWheel] into
 * the platform layer. Commit 3 promotes classic Kingsong onto
 * [WheelTransportProfile.KingsongClassic]; every other wheel still uses
 * [WheelTransportProfile.Default], which is byte-equivalent to pre-Commit-2
 * behavior.
 */
data class WheelConnectionInfo(
    val wheelType: WheelType,
    val readServiceUuid: String,
    val readCharacteristicUuid: String,
    val writeServiceUuid: String,
    val writeCharacteristicUuid: String,
    val descriptorUuid: String = BleUuids.CLIENT_CHARACTERISTIC_CONFIG,
    val transportProfile: WheelTransportProfile = WheelTransportProfile.Default,
) {
    companion object {
        /**
         * Create connection info for a classic Kingsong wheel (KS-S16 / S18 /
         * S20 / S22 — every wheel that uses the `FFE0` service). Carries
         * [WheelTransportProfile.KingsongClassic] so post-connect traffic
         * follows the official app's pacing/warmup/heartbeat behavior.
         * KS-E1/E3 ("KSE") wheels live on the `AD00` service surface and
         * have their own factory + profile — [forKingsongKse].
         */
        fun forKingsong(): WheelConnectionInfo = WheelConnectionInfo(
            wheelType = WheelType.KINGSONG,
            readServiceUuid = BleUuids.Kingsong.SERVICE,
            readCharacteristicUuid = BleUuids.Kingsong.READ_CHARACTERISTIC,
            writeServiceUuid = BleUuids.Kingsong.SERVICE,
            writeCharacteristicUuid = BleUuids.Kingsong.WRITE_CHARACTERISTIC,
            transportProfile = WheelTransportProfile.KingsongClassic,
        )

        /**
         * Create connection info for a KSE wheel (KS-E1 / KS-E3). Same
         * [WheelType.KINGSONG] family (and the same [KingsongDecoder] under
         * the hood) but distinct transport surface: `AD00` service with
         * `AD01` write + `AD02` notify/read, paired with the conservative
         * [WheelTransportProfile.KingsongKse] (no warmup, no heartbeat, no
         * MTU bump) until real-hardware captures justify divergence.
         *
         * Distinguishing KSE from classic Kingsong at runtime relies on the
         * service-discovery path: [WheelTypeDetector] inspects the
         * advertised AD00 topology and the device-name prefixes
         * (KS-E1 / KS-E3 / KSE), and routes to this factory accordingly.
         * [forType] keeps returning classic Kingsong because saved-profile
         * hints can't currently distinguish classic vs KSE — the picker /
         * SAVED_PROFILE story is left for a later commit.
         */
        fun forKingsongKse(): WheelConnectionInfo = WheelConnectionInfo(
            wheelType = WheelType.KINGSONG,
            readServiceUuid = BleUuids.KingsongKse.SERVICE,
            readCharacteristicUuid = BleUuids.KingsongKse.READ_CHARACTERISTIC,
            writeServiceUuid = BleUuids.KingsongKse.SERVICE,
            writeCharacteristicUuid = BleUuids.KingsongKse.WRITE_CHARACTERISTIC,
            descriptorUuid = BleUuids.KingsongKse.DESCRIPTOR,
            transportProfile = WheelTransportProfile.KingsongKse,
        )

        /**
         * Create connection info for a Gotway/Begode wheel.
         */
        fun forGotway(): WheelConnectionInfo = WheelConnectionInfo(
            wheelType = WheelType.GOTWAY,
            readServiceUuid = BleUuids.Gotway.SERVICE,
            readCharacteristicUuid = BleUuids.Gotway.READ_CHARACTERISTIC,
            writeServiceUuid = BleUuids.Gotway.SERVICE,
            writeCharacteristicUuid = BleUuids.Gotway.WRITE_CHARACTERISTIC
        )

        /**
         * Create connection info for a Veteran wheel.
         */
        fun forVeteran(): WheelConnectionInfo = WheelConnectionInfo(
            wheelType = WheelType.VETERAN,
            readServiceUuid = BleUuids.Gotway.SERVICE,
            readCharacteristicUuid = BleUuids.Gotway.READ_CHARACTERISTIC,
            writeServiceUuid = BleUuids.Gotway.SERVICE,
            writeCharacteristicUuid = BleUuids.Gotway.WRITE_CHARACTERISTIC
        )

        /**
         * Create connection info for an InMotion V1 wheel.
         */
        fun forInMotion(): WheelConnectionInfo = WheelConnectionInfo(
            wheelType = WheelType.INMOTION,
            readServiceUuid = BleUuids.InMotion.READ_SERVICE,
            readCharacteristicUuid = BleUuids.InMotion.READ_CHARACTERISTIC,
            writeServiceUuid = BleUuids.InMotion.WRITE_SERVICE,
            writeCharacteristicUuid = BleUuids.InMotion.WRITE_CHARACTERISTIC
        )

        /**
         * Create connection info for an InMotion V2 wheel.
         */
        fun forLorin(): WheelConnectionInfo = WheelConnectionInfo(
            wheelType = WheelType.LORIN,
            readServiceUuid = BleUuids.Lorin.SERVICE,
            readCharacteristicUuid = BleUuids.Lorin.READ_CHARACTERISTIC,
            writeServiceUuid = BleUuids.Lorin.SERVICE,
            writeCharacteristicUuid = BleUuids.Lorin.WRITE_CHARACTERISTIC
        )

        /**
         * Create connection info for a Ninebot wheel.
         */
        fun forNinebot(): WheelConnectionInfo = WheelConnectionInfo(
            wheelType = WheelType.NINEBOT,
            readServiceUuid = BleUuids.Ninebot.SERVICE,
            readCharacteristicUuid = BleUuids.Ninebot.READ_CHARACTERISTIC,
            writeServiceUuid = BleUuids.Ninebot.SERVICE,
            writeCharacteristicUuid = BleUuids.Ninebot.WRITE_CHARACTERISTIC
        )

        /**
         * Create connection info for a Ninebot Z wheel.
         */
        fun forNinebotZ(): WheelConnectionInfo = WheelConnectionInfo(
            wheelType = WheelType.NINEBOT_Z,
            readServiceUuid = BleUuids.NinebotZ.SERVICE,
            readCharacteristicUuid = BleUuids.NinebotZ.READ_CHARACTERISTIC,
            writeServiceUuid = BleUuids.NinebotZ.SERVICE,
            writeCharacteristicUuid = BleUuids.NinebotZ.WRITE_CHARACTERISTIC
        )

        /**
         * Create connection info for a wheel type.
         */
        /**
         * Create connection info for a Leaperkim CAN wheel.
         */
        fun forLeaperkim(): WheelConnectionInfo = WheelConnectionInfo(
            wheelType = WheelType.LEAPERKIM,
            readServiceUuid = BleUuids.Gotway.SERVICE,
            readCharacteristicUuid = BleUuids.Gotway.READ_CHARACTERISTIC,
            writeServiceUuid = BleUuids.Gotway.SERVICE,
            writeCharacteristicUuid = BleUuids.Gotway.WRITE_CHARACTERISTIC
        )

        fun forType(wheelType: WheelType): WheelConnectionInfo? = when (wheelType) {
            WheelType.KINGSONG -> forKingsong()
            WheelType.GOTWAY -> forGotway()
            WheelType.GOTWAY_VIRTUAL -> forGotway()
            WheelType.VETERAN -> forVeteran()
            WheelType.LEAPERKIM -> forLeaperkim()
            WheelType.INMOTION -> forInMotion()
            WheelType.LORIN -> forLorin()
            WheelType.NINEBOT -> forNinebot()
            WheelType.NINEBOT_Z -> forNinebotZ()
            WheelType.Unknown -> null
        }
    }
}
