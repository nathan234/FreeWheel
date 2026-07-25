//! Byte-parsing helpers mirroring `ByteUtils.kt`.
//!
//! Rounding helpers replicate Kotlin/JVM semantics exactly:
//! `Double.roundToInt()` == `Math.round(double)` == `floor(x + 0.5)`,
//! which differs from Rust's `f64::round()` (half away from zero) on
//! negative ties. Parity with the Kotlin decoder requires the JVM rule.

pub const KM_TO_MILES_MULTIPLIER: f64 = 0.62137119223733;

/// Kotlin `Double.roundToInt()`: round half up (towards +inf), truncate to i32.
pub fn round_to_i32(x: f64) -> i32 {
    (x + 0.5).floor() as i32
}

/// Kotlin `Double.roundToLong()`.
pub fn round_to_i64(x: f64) -> i64 {
    (x + 0.5).floor() as i64
}

/// Kotlin `Float.roundToInt()` — the addition happens in f32, matching JVM `Math.round(float)`.
pub fn round_f32_to_i32(x: f32) -> i32 {
    (x + 0.5f32).floor() as i32
}

/// Unsigned 16-bit big-endian read. Returns 0 when out of bounds (Kotlin parity).
pub fn short_from_bytes_be(arr: &[u8], offset: usize) -> i32 {
    if arr.len() < offset + 2 {
        return 0;
    }
    ((arr[offset] as i32) << 8) | (arr[offset + 1] as i32)
}

/// Signed 16-bit big-endian read (first byte sign-extended). Returns 0 when out of bounds.
pub fn signed_short_from_bytes_be(arr: &[u8], offset: usize) -> i32 {
    if arr.len() < offset + 2 {
        return 0;
    }
    (((arr[offset] as i8) as i32) << 8) | (arr[offset + 1] as i32)
}

/// Signed 32-bit big-endian read, sign-extended to i64. Returns 0 when out of bounds.
pub fn get_int4(arr: &[u8], offset: usize) -> i64 {
    if arr.len() < offset + 4 {
        return 0;
    }
    let value = ((arr[offset] as i32) << 24)
        | ((arr[offset + 1] as i32) << 16)
        | ((arr[offset + 2] as i32) << 8)
        | (arr[offset + 3] as i32);
    value as i64
}

/// "Reversed BE" 32-bit read (Veteran distance encoding): word-swapped big-endian.
/// Returns 0 when out of bounds (Kotlin parity).
pub fn int_from_bytes_rev_be(bytes: &[u8], starting: usize) -> i32 {
    if bytes.len() < starting + 4 {
        return 0;
    }
    ((bytes[starting + 2] as i32) << 24)
        | ((bytes[starting + 3] as i32) << 16)
        | ((bytes[starting] as i32) << 8)
        | (bytes[starting + 1] as i32)
}

/// Unsigned 32-bit big-endian read as i64. Returns 0 when out of bounds.
pub fn int_from_bytes_be(bytes: &[u8], starting: usize) -> i64 {
    if bytes.len() < starting + 4 {
        return 0;
    }
    let value = ((bytes[starting] as i32) << 24)
        | ((bytes[starting + 1] as i32) << 16)
        | ((bytes[starting + 2] as i32) << 8)
        | (bytes[starting + 3] as i32);
    (value as i64) & 0xFFFF_FFFF
}

pub fn bytes_to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02X}")).collect()
}

pub fn hex_to_bytes(hex: &str) -> Vec<u8> {
    let clean: String = hex.chars().filter(|c| !c.is_whitespace()).collect();
    if clean.is_empty() || !clean.len().is_multiple_of(2) {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(clean.len() / 2);
    for i in (0..clean.len()).step_by(2) {
        match u8::from_str_radix(&clean[i..i + 2], 16) {
            Ok(b) => out.push(b),
            Err(_) => return Vec::new(),
        }
    }
    out
}
