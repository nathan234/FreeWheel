package org.freewheel.core.protocol

/**
 * Shared Kingsong frame builders for test use.
 *
 * Frame format (20 bytes):
 * - Bytes 0-1:   Header (AA 55)
 * - Bytes 2-15:  Data payload (varies by frame type)
 * - Byte 16:     Frame type (0xA9=live, 0xBB=name, 0xB3=serial, 0xF1=BMS, etc.)
 * - Byte 17:     0x14 (constant), or pNum for BMS frames
 * - Bytes 18-19: Footer (5A 5A)
 *
 * Byte order: KS uses a reversed-pairs encoding. ByteUtils.getInt2R swaps each
 * pair of bytes then reads big-endian, which is equivalent to little-endian
 * reading of the original bytes. Frame builders write values in LE
 * at the payload positions so that getInt2R returns the intended value.
 */

/**
 * Build a generic 20-byte Kingsong frame.
 *
 * @param frameType Frame type byte at position 16
 * @param data 14-byte payload (copied into positions 2-15)
 * @param byte17 Value at position 17 (0x14 for normal frames, pNum for BMS)
 */
internal fun buildKsFrame(
    frameType: Int,
    data: ByteArray = ByteArray(14),
    byte17: Int = 0x14
): ByteArray {
    val packet = ByteArray(20)
    packet[0] = 0xAA.toByte()
    packet[1] = 0x55
    data.copyInto(packet, 2, 0, minOf(data.size, 14))
    packet[16] = frameType.toByte()
    packet[17] = byte17.toByte()
    packet[18] = 0x5A
    packet[19] = 0x5A
    return packet
}

/**
 * Build a live data (0xA9) frame.
 *
 * Payload layout (within the 14-byte data region, offsets relative to frame byte 2):
 * - [0-1] LE: voltage (raw, e.g. 6505 = 65.05V)
 * - [2-3] LE: speed (raw, decoder stores directly in WheelState.speed)
 * - [4-7] LE-pairs: totalDistance (4-byte, read via getInt4R)
 * - [8-9] LE: current (read manually as data[10]+(data[11]<<8) in frame coords)
 * - [10-11] LE: temperature (raw, e.g. 3600 = 36.00C)
 * - [12]: mode value (stored when byte 15 in frame = 0xE0)
 * - [13]: mode indicator (0xE0 for standard)
 */
internal fun buildKsLivePacket(
    voltage: Int,
    speed: Int = 0,
    current: Int = 0,
    temperature: Int = 3600,
    mode: Int = 0
): ByteArray {
    val data = ByteArray(14)
    // Voltage LE at data[0-1]
    data[0] = (voltage and 0xFF).toByte()
    data[1] = ((voltage shr 8) and 0xFF).toByte()
    // Speed LE at data[2-3]
    data[2] = (speed and 0xFF).toByte()
    data[3] = ((speed shr 8) and 0xFF).toByte()
    // totalDistance at data[4-7]: leave 0
    // current LE at data[8-9] (frame bytes 10-11, read as data[10]+(data[11]<<8))
    data[8] = (current and 0xFF).toByte()
    data[9] = ((current shr 8) and 0xFF).toByte()
    // temperature LE at data[10-11]
    data[10] = (temperature and 0xFF).toByte()
    data[11] = ((temperature shr 8) and 0xFF).toByte()
    // mode at data[12], mode indicator at data[13]
    data[12] = mode.toByte()
    data[13] = 0xE0.toByte()
    return buildKsFrame(0xA9, data)
}

/**
 * Build a name/type (0xBB) frame.
 * Name string bytes go into positions 2-15 (null-terminated).
 */
internal fun buildKsNamePacket(name: String): ByteArray {
    val packet = ByteArray(20)
    packet[0] = 0xAA.toByte()
    packet[1] = 0x55
    val nameBytes = name.encodeToByteArray()
    for (i in nameBytes.indices) {
        if (i + 2 < 16) packet[i + 2] = nameBytes[i]
    }
    packet[16] = 0xBB.toByte()
    packet[17] = 0x14
    packet[18] = 0x5A
    packet[19] = 0x5A
    return packet
}

/**
 * Build a serial number (0xB3) frame.
 * Serial string bytes go into positions 2-15.
 */
internal fun buildKsSerialPacket(serial: String): ByteArray {
    val data = ByteArray(14)
    val serialBytes = serial.encodeToByteArray()
    for (i in serialBytes.indices) {
        if (i < 14) data[i] = serialBytes[i]
    }
    return buildKsFrame(0xB3, data)
}

/**
 * Build a BMS info (0xF1, pNum=0x00) frame with voltage, current, capacity.
 *
 * Payload layout (LE at data offsets):
 * - [0-1]: voltage (divided by 100 in decoder)
 * - [2-3]: current (divided by 100 in decoder)
 * - [4-5]: remaining capacity (multiplied by 10 in decoder)
 * - [6-7]: factory capacity (multiplied by 10 in decoder)
 * - [8-9]: full cycles
 */
internal fun buildKsBmsInfoFrame(
    voltage: Int,
    current: Int,
    remCap: Int = 200,
    factoryCap: Int = 300,
    fullCycles: Int = 50
): ByteArray {
    val data = ByteArray(14)
    // voltage LE
    data[0] = (voltage and 0xFF).toByte()
    data[1] = ((voltage shr 8) and 0xFF).toByte()
    // current LE
    data[2] = (current and 0xFF).toByte()
    data[3] = ((current shr 8) and 0xFF).toByte()
    // remCap LE
    data[4] = (remCap and 0xFF).toByte()
    data[5] = ((remCap shr 8) and 0xFF).toByte()
    // factoryCap LE
    data[6] = (factoryCap and 0xFF).toByte()
    data[7] = ((factoryCap shr 8) and 0xFF).toByte()
    // fullCycles LE
    data[8] = (fullCycles and 0xFF).toByte()
    data[9] = ((fullCycles shr 8) and 0xFF).toByte()
    return buildKsFrame(0xF1, data, byte17 = 0x00)
}

/**
 * Build a BMS cell voltage frame (0xF1, pNum=0x02-0x06).
 * Each frame carries up to 7 cell voltages in LE pairs.
 * Cell voltage unit: raw (divided by 1000.0 in decoder, e.g. 4200 = 4.200V).
 */
internal fun buildKsBmsCellFrame(pNum: Int, cellVoltages: List<Int>): ByteArray {
    val data = ByteArray(14)
    for (i in cellVoltages.indices) {
        if (i < 7) {
            val v = cellVoltages[i]
            data[i * 2] = (v and 0xFF).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
    }
    return buildKsFrame(0xF1, data, byte17 = pNum)
}

/**
 * Build a distance/time (0xB9) frame.
 *
 * Payload layout (LE-pairs at data offsets):
 * - [0-3]: distance (4-byte, read via getInt4R)
 * - [6-7]: topSpeed (2-byte, read via getInt2R)
 * - [10]: fanStatus
 * - [11]: chargingStatus
 * - [12-13]: temperature2 (2-byte, read via getInt2R)
 */
internal fun buildKsDistancePacket(
    distance: Long = 0,
    fanStatus: Int = 0,
    chargingStatus: Int = 0,
    temperature2: Int = 0
): ByteArray {
    val data = ByteArray(14)
    // distance LE-pairs at data[0-3]
    // getInt4R reads 4 bytes, reverses pairs, reads BE
    // To get value V: write LE pairs → [lo0, hi0, lo1, hi1]
    // reverseEvery2 → [hi0, lo0, hi1, lo1]
    // readBE → hi0<<24 | lo0<<16 | hi1<<8 | lo1
    // For this to equal V, we need:
    //   hi0 = (V >> 24), lo0 = (V >> 16) & 0xFF
    //   hi1 = (V >> 8) & 0xFF, lo1 = V & 0xFF
    // So the LE-pair layout in data is: [lo0=V>>16, hi0=V>>24, lo1=V&0xFF, hi1=V>>8]
    val v = distance.toInt()
    data[0] = ((v shr 16) and 0xFF).toByte()
    data[1] = ((v shr 24) and 0xFF).toByte()
    data[2] = (v and 0xFF).toByte()
    data[3] = ((v shr 8) and 0xFF).toByte()
    // fanStatus at data[10]
    data[10] = fanStatus.toByte()
    // chargingStatus at data[11]
    data[11] = chargingStatus.toByte()
    // temperature2 LE at data[12-13]
    data[12] = (temperature2 and 0xFF).toByte()
    data[13] = ((temperature2 shr 8) and 0xFF).toByte()
    return buildKsFrame(0xB9, data)
}

/**
 * Build a max speed alerts (0xA4) frame.
 *
 * Payload layout (LE-pairs at data offsets):
 * - [2-3]: alarm1Speed (read via getInt2R at frame offset 4)
 * - [4-5]: alarm2Speed (read via getInt2R at frame offset 6)
 * - [6-7]: alarm3Speed (read via getInt2R at frame offset 8)
 * - [8-9]: maxSpeed (read via getInt2R at frame offset 10)
 */
internal fun buildKsAlertFrame(
    alarm1Speed: Int = 0,
    alarm2Speed: Int = 0,
    alarm3Speed: Int = 0,
    maxSpeed: Int = 0
): ByteArray {
    val data = ByteArray(14)
    // alarm1Speed LE at data[2-3] (frame offset 4-5)
    data[2] = (alarm1Speed and 0xFF).toByte()
    data[3] = ((alarm1Speed shr 8) and 0xFF).toByte()
    // alarm2Speed LE at data[4-5] (frame offset 6-7)
    data[4] = (alarm2Speed and 0xFF).toByte()
    data[5] = ((alarm2Speed shr 8) and 0xFF).toByte()
    // alarm3Speed LE at data[6-7] (frame offset 8-9)
    data[6] = (alarm3Speed and 0xFF).toByte()
    data[7] = ((alarm3Speed shr 8) and 0xFF).toByte()
    // maxSpeed LE at data[8-9] (frame offset 10-11)
    data[8] = (maxSpeed and 0xFF).toByte()
    data[9] = ((maxSpeed shr 8) and 0xFF).toByte()
    return buildKsFrame(0xA4, data)
}
