package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.utils.ByteUtils
import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ninebot protocol decoder.
 *
 * This class is thread-safe.
 *
 * Supports multiple protocol versions:
 * - Default: Standard Ninebot protocol
 * - S2: Ninebot S2 variant
 * - Mini: Ninebot Mini variant
 *
 * Frame format:
 * - Header: 55 AA
 * - Length byte
 * - CAN message (source, destination, parameter, data)
 * - CRC16 checksum
 *
 * CAN message structure (after decryption):
 * - Byte 0: Source address
 * - Byte 1: Destination address
 * - Byte 2: Command type (Read=0x01, Write=0x03, Get=0x04)
 * - Byte 3: Parameter type (SerialNumber=0x10, Firmware=0x1A, LiveData=0xB0, etc.)
 * - Bytes 4+: Data payload
 *
 * State machine: WAITING_SERIAL → WAITING_VERSION → READY
 *   Init sends serial request; keep-alive advances through states.
 *
 * Uses gamma XOR encryption with a 16-byte key.
 */
class NinebotDecoder(
    private val protoVersion: ProtoVersion = ProtoVersion.DEFAULT
) : WheelDecoder {

    override val wheelType: WheelType = WheelType.NINEBOT
    private val stateLock = Lock()

    private val unpacker = NinebotUnpacker()

    // Connection state machine
    private var connectionState = ConnectionState.WAITING_SERIAL

    // Encryption key (starts as zeros)
    private var gamma = ByteArray(16) { 0 }

    // Accumulated serial number (may come in multiple parts)
    private var serialNumber = ""

    // Firmware version
    private var version = ""

    // Static values accumulated across frames
    private var batt = 0
    private var speed = 0
    private var distance = 0L
    private var temperature = 0
    private var voltage = 0
    private var current = 0
    private var power = 0

    /**
     * Protocol version variants.
     */
    enum class ProtoVersion(val value: Int) {
        DEFAULT(0),
        S2(1),
        MINI(2)
    }

    /**
     * Connection state machine.
     */
    private enum class ConnectionState {
        WAITING_SERIAL,
        WAITING_VERSION,
        READY
    }

    /**
     * CAN message addresses for different protocol versions.
     */
    private enum class Addr(
        private val valueDef: Int,
        private val valueS2: Int,
        private val valueMini: Int
    ) {
        Controller(0x01, 0x01, 0x01),
        KeyGenerator(0x16, 0x16, 0x16),
        App(0x09, 0x11, 0x0A);

        fun getValue(protoVersion: ProtoVersion): Int = when (protoVersion) {
            ProtoVersion.S2 -> valueS2
            ProtoVersion.MINI -> valueMini
            else -> valueDef
        }
    }

    /**
     * CAN message command types.
     */
    private enum class Comm(val value: Int) {
        Read(0x01),
        Write(0x03),
        Get(0x04),
        GetKey(0x5b)
    }

    /**
     * CAN message parameter types.
     */
    private enum class Param(val value: Int) {
        SerialNumber(0x10),
        SerialNumber2(0x13),
        SerialNumber3(0x16),
        Firmware(0x1a),
        Angles(0x61),
        BatteryLevel(0x22),
        ActivationDate(0x69),
        LiveData(0xb0),
        LiveData2(0xb3),
        LiveData3(0xb6),
        LiveData4(0xb9),
        LiveData5(0xbc),
        LiveData6(0xbf);

        companion object {
            fun fromValue(value: Int): Param? = entries.find { it.value == value }
        }
    }

    /**
     * Parsed CAN message.
     */
    private data class CANMessage(
        val len: Int,
        val source: Int,
        val destination: Int,
        val parameter: Int,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CANMessage) return false
            return len == other.len &&
                    source == other.source &&
                    destination == other.destination &&
                    parameter == other.parameter &&
                    data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = len
            result = 31 * result + source
            result = 31 * result + destination
            result = 31 * result + parameter
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    override fun decode(data: ByteArray, currentState: WheelState, config: DecoderConfig): DecodedData? {
        return stateLock.withLock {
            decodeFrames(data, unpacker, currentState) { buffer, state ->
                val msg = verifyAndParse(buffer) ?: return@decodeFrames null
                processMessage(msg, state)
            }
        }
    }

    /**
     * Verify CRC and decrypt/parse the CAN message.
     */
    private fun verifyAndParse(buffer: ByteArray): CANMessage? {
        if (buffer.size < 9) return null // Minimum: header(2) + len(1) + data(4 min) + CRC(2)

        // Extract data portion (skip 55 AA header)
        val dataBuffer = buffer.copyOfRange(2, buffer.size)

        // Decrypt the data
        val decrypted = crypto(dataBuffer)

        // Verify CRC16
        val providedCrc = ((decrypted[decrypted.size - 1].toInt() and 0xFF) shl 8) or
                (decrypted[decrypted.size - 2].toInt() and 0xFF)
        val dataToCheck = decrypted.copyOfRange(0, decrypted.size - 2)
        val calculatedCrc = computeCrc(dataToCheck)

        if (providedCrc != calculatedCrc) {
            return null // CRC mismatch
        }

        // Parse the CAN message structure
        if (decrypted.size < 7) return null

        val len = decrypted[0].toInt() and 0xFF
        val source = decrypted[1].toInt() and 0xFF
        val destination = decrypted[2].toInt() and 0xFF
        val parameter = decrypted[3].toInt() and 0xFF
        val msgData = decrypted.copyOfRange(4, decrypted.size - 2)

        return CANMessage(len, source, destination, parameter, msgData)
    }

    /**
     * Compute CRC16 checksum.
     */
    private fun computeCrc(buffer: ByteArray): Int {
        var check = 0
        for (byte in buffer) {
            check += (byte.toInt() and 0xFF)
        }
        check = check xor 0xFFFF
        check = check and 0xFFFF
        return check
    }

    /**
     * XOR encrypt/decrypt with gamma key.
     * First byte is not encrypted, remaining bytes XOR with gamma.
     */
    private fun crypto(buffer: ByteArray): ByteArray {
        val result = buffer.copyOf()
        for (j in 1 until result.size) {
            result[j] = (result[j].toInt() xor gamma[(j - 1) % 16].toInt()).toByte()
        }
        return result
    }

    /**
     * Process a parsed CAN message and update state.
     */
    private fun processMessage(message: CANMessage, currentState: WheelState): FrameResult? {
        val param = Param.fromValue(message.parameter)

        return when (param) {
            Param.SerialNumber -> {
                serialNumber = message.data.decodeToString()
                connectionState = ConnectionState.WAITING_VERSION

                // Only return status if we have full serial (14 chars)
                if (message.len - 2 == 14) {
                    FrameResult(
                        state = currentState.copy(
                            serialNumber = serialNumber,
                            model = getModelName()
                        ),
                        hasNewData = false
                    )
                } else null
            }

            Param.SerialNumber2 -> {
                // Append to serial number
                serialNumber += message.data.decodeToString()
                null
            }

            Param.SerialNumber3 -> {
                // Final part of serial number
                serialNumber += message.data.decodeToString()
                FrameResult(
                    state = currentState.copy(
                        serialNumber = serialNumber,
                        model = getModelName()
                    ),
                    hasNewData = false
                )
            }

            Param.Firmware -> {
                version = parseVersionNumber(message.data)
                connectionState = ConnectionState.READY
                FrameResult(
                    state = currentState.copy(version = version),
                    hasNewData = false
                )
            }

            Param.LiveData -> {
                if (message.len - 2 == 32) {
                    parseLiveData(message.data, currentState)
                } else null
            }

            Param.LiveData2 -> {
                parseLiveData2(message.data, currentState)
            }

            Param.LiveData3 -> {
                parseLiveData3(message.data, currentState)
            }

            Param.LiveData4 -> {
                parseLiveData4(message.data, currentState)
            }

            Param.LiveData5 -> {
                parseLiveData5(message.data, currentState)
            }

            Param.LiveData6 -> {
                // LiveData6 exists but doesn't produce new data
                null
            }

            else -> null
        }
    }

    /**
     * Parse firmware version based on protocol.
     */
    private fun parseVersionNumber(data: ByteArray): String {
        if (data.size < 2) return ""

        return when (protoVersion) {
            ProtoVersion.S2 -> {
                val major = (data[1].toInt() and 0xFF) shr 4
                val minor = (data[0].toInt() and 0xFF) shr 4
                val patch = data[0].toInt() and 0x0F
                "$major.$minor.$patch"
            }
            ProtoVersion.MINI -> {
                val major = data[1].toInt() and 0x0F
                val minor = (data[0].toInt() and 0xFF) shr 4
                val patch = data[0].toInt() and 0x0F
                "$major.$minor.$patch"
            }
            else -> {
                // Default - format similar to S2
                val major = (data[1].toInt() and 0xFF) shr 4
                val minor = (data[0].toInt() and 0xFF) shr 4
                val patch = data[0].toInt() and 0x0F
                "$major.$minor.$patch"
            }
        }
    }

    /**
     * Parse main live data frame (0xB0).
     * Contains: battery, speed, distance, temperature, voltage, current.
     */
    private fun parseLiveData(data: ByteArray, currentState: WheelState): FrameResult {
        batt = ByteUtils.shortFromBytesLE(data, 8)

        speed = when (protoVersion) {
            ProtoVersion.S2 -> ByteUtils.shortFromBytesLE(data, 28) // Speed up to 320.00 km/h
            else -> abs(ByteUtils.signedShortFromBytesLE(data, 10) / 10) // Speed up to 32.000 km/h
        }

        distance = ByteUtils.intFromBytesLE(data, 14).toLong()
        temperature = ByteUtils.shortFromBytesLE(data, 22)

        voltage = when (protoVersion) {
            ProtoVersion.MINI -> 0 // No voltage for Mini
            else -> ByteUtils.shortFromBytesLE(data, 24)
        }

        current = ByteUtils.signedShortFromBytesLE(data, 26)
        power = ((current / 100.0) * voltage).roundToInt()

        return FrameResult(
            state = currentState.copy(
                speed = speed,
                voltage = voltage,
                current = current,
                power = power,
                totalDistance = distance,
                temperature = temperature * 10, // Convert to 1/100 degrees
                batteryLevel = batt,
                wheelType = WheelType.NINEBOT,
                model = getModelName()
            ),
            hasNewData = true
        )
    }

    /**
     * Parse live data 2 frame (0xB3).
     * Contains: battery level, speed.
     */
    private fun parseLiveData2(data: ByteArray, currentState: WheelState): FrameResult {
        batt = ByteUtils.shortFromBytesLE(data, 2)
        speed = ByteUtils.shortFromBytesLE(data, 4) / 10

        return FrameResult(
            state = currentState.copy(
                speed = speed,
                batteryLevel = batt
            ),
            hasNewData = false
        )
    }

    /**
     * Parse live data 3 frame (0xB6).
     * Contains: distance.
     */
    private fun parseLiveData3(data: ByteArray, currentState: WheelState): FrameResult {
        distance = ByteUtils.intFromBytesLE(data, 2).toLong()

        return FrameResult(
            state = currentState.copy(
                totalDistance = distance
            ),
            hasNewData = false
        )
    }

    /**
     * Parse live data 4 frame (0xB9).
     * Contains: temperature.
     */
    private fun parseLiveData4(data: ByteArray, currentState: WheelState): FrameResult {
        temperature = ByteUtils.shortFromBytesLE(data, 4)

        return FrameResult(
            state = currentState.copy(
                temperature = temperature * 10
            ),
            hasNewData = false
        )
    }

    /**
     * Parse live data 5 frame (0xBC).
     * Contains: voltage, current.
     */
    private fun parseLiveData5(data: ByteArray, currentState: WheelState): FrameResult {
        voltage = ByteUtils.shortFromBytesLE(data, 0)
        current = ByteUtils.signedShortFromBytesLE(data, 2)
        power = ((current / 100.0) * voltage).roundToInt()

        return FrameResult(
            state = currentState.copy(
                voltage = voltage,
                current = current,
                power = power
            ),
            hasNewData = true
        )
    }

    /**
     * Get model name based on protocol version.
     */
    private fun getModelName(): String = when (protoVersion) {
        ProtoVersion.S2 -> "Ninebot S2"
        ProtoVersion.MINI -> "Ninebot Mini"
        else -> "Ninebot"
    }


    override fun isReady(): Boolean {
        return stateLock.withLock {
            serialNumber.isNotEmpty() &&
                    version.isNotEmpty() &&
                    voltage != 0
        }
    }

    override fun reset() {
        stateLock.withLock {
            unpacker.reset()
            connectionState = ConnectionState.WAITING_SERIAL
            gamma = ByteArray(16) { 0 }
            serialNumber = ""
            version = ""
            batt = 0
            speed = 0
            distance = 0
            temperature = 0
            voltage = 0
            current = 0
            power = 0
        }
    }

    override val keepAliveIntervalMs: Long = 125L // 25ms * 5 steps

    override fun getKeepAliveCommand(): WheelCommand? {
        return when (connectionState) {
            ConnectionState.WAITING_SERIAL -> WheelCommand.SendBytes(createSerialNumberRequest())
            ConnectionState.WAITING_VERSION -> WheelCommand.SendBytes(createVersionRequest())
            ConnectionState.READY -> WheelCommand.SendBytes(createLiveDataRequest())
        }
    }

    override fun getInitCommands(): List<WheelCommand> {
        return listOf(
            WheelCommand.SendBytes(createSerialNumberRequest())
        )
    }

    /**
     * Create a request for serial number.
     */
    private fun createSerialNumberRequest(): ByteArray {
        return createMessage(
            source = Addr.App.getValue(protoVersion),
            destination = Addr.Controller.getValue(protoVersion),
            parameter = Param.SerialNumber.value,
            data = byteArrayOf(0x0e)
        )
    }

    /**
     * Create a request for firmware version.
     */
    private fun createVersionRequest(): ByteArray {
        return createMessage(
            source = Addr.App.getValue(protoVersion),
            destination = Addr.Controller.getValue(protoVersion),
            parameter = Param.Firmware.value,
            data = byteArrayOf(0x02)
        )
    }

    /**
     * Create a request for live data.
     */
    private fun createLiveDataRequest(): ByteArray {
        return createMessage(
            source = Addr.App.getValue(protoVersion),
            destination = Addr.Controller.getValue(protoVersion),
            parameter = Param.LiveData.value,
            data = byteArrayOf(0x20)
        )
    }

    /**
     * Create a CAN message with header, encryption, and CRC.
     */
    private fun createMessage(source: Int, destination: Int, parameter: Int, data: ByteArray): ByteArray {
        val len = data.size + 2

        // Build message body: len + source + destination + parameter + data
        val body = ByteArray(4 + data.size)
        body[0] = len.toByte()
        body[1] = source.toByte()
        body[2] = destination.toByte()
        body[3] = parameter.toByte()
        data.copyInto(body, 4)

        // Calculate CRC on the body
        val crc = computeCrc(body)

        // Add CRC (little endian)
        val bodyWithCrc = ByteArray(body.size + 2)
        body.copyInto(bodyWithCrc, 0)
        bodyWithCrc[body.size] = (crc and 0xFF).toByte()
        bodyWithCrc[body.size + 1] = ((crc shr 8) and 0xFF).toByte()

        // Encrypt
        val encrypted = crypto(bodyWithCrc)

        // Add header
        val result = ByteArray(2 + encrypted.size)
        result[0] = 0x55
        result[1] = 0xAA.toByte()
        encrypted.copyInto(result, 2)

        return result
    }

    companion object {
        /**
         * Number of battery cells for Ninebot wheels.
         */
        const val CELLS_FOR_WHEEL = 15
    }
}
