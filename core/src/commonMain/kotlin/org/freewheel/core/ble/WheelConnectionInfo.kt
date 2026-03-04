package org.freewheel.core.ble

import org.freewheel.core.domain.WheelType

/**
 * Contains the BLE service and characteristic UUIDs needed to communicate with a wheel.
 * This information is determined during service discovery and wheel type detection.
 */
data class WheelConnectionInfo(
    val wheelType: WheelType,
    val readServiceUuid: String,
    val readCharacteristicUuid: String,
    val writeServiceUuid: String,
    val writeCharacteristicUuid: String,
    val descriptorUuid: String = BleUuids.CLIENT_CHARACTERISTIC_CONFIG
) {
    companion object {
        /**
         * Create connection info for a Kingsong wheel.
         */
        fun forKingsong(): WheelConnectionInfo = WheelConnectionInfo(
            wheelType = WheelType.KINGSONG,
            readServiceUuid = BleUuids.Kingsong.SERVICE,
            readCharacteristicUuid = BleUuids.Kingsong.READ_CHARACTERISTIC,
            writeServiceUuid = BleUuids.Kingsong.SERVICE,
            writeCharacteristicUuid = BleUuids.Kingsong.WRITE_CHARACTERISTIC
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
        fun forInMotionV2(): WheelConnectionInfo = WheelConnectionInfo(
            wheelType = WheelType.INMOTION_V2,
            readServiceUuid = BleUuids.InMotionV2.SERVICE,
            readCharacteristicUuid = BleUuids.InMotionV2.READ_CHARACTERISTIC,
            writeServiceUuid = BleUuids.InMotionV2.SERVICE,
            writeCharacteristicUuid = BleUuids.InMotionV2.WRITE_CHARACTERISTIC
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
            WheelType.INMOTION_V2 -> forInMotionV2()
            WheelType.NINEBOT -> forNinebot()
            WheelType.NINEBOT_Z -> forNinebotZ()
            WheelType.Unknown -> null
        }
    }
}
