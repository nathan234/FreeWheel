//! Shared checksum functions (port of `ProtocolChecksums.kt`).

/// CRC32 (polynomial 0xEDB88320) used by the Veteran/Leaperkim protocol.
/// Returned as i64 to mirror the Kotlin `Long` (unsigned 32-bit value).
pub fn crc32(data: &[u8], offset: usize, length: usize) -> i64 {
    let mut crc: u32 = 0xFFFF_FFFF;
    for &byte in &data[offset..offset + length] {
        crc ^= byte as u32;
        for _ in 0..8 {
            crc = if crc & 1 == 1 {
                (crc >> 1) ^ 0xEDB8_8320
            } else {
                crc >> 1
            };
        }
    }
    (crc ^ 0xFFFF_FFFF) as i64
}
