//! Port of `GotwayUnpacker.kt` — reassembles 24-byte Gotway frames from the
//! BLE byte stream, including the two garbage-pattern recovery paths.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct UnpackerStats {
    pub error_resets: i32,
    pub bytes_discarded: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum State {
    Unknown,
    Collecting,
    Done,
}

#[derive(Debug)]
pub struct GotwayUnpacker {
    buffer: Vec<u8>,
    state: State,
    old_c: i32,
    error_resets: i32,
    bytes_discarded: i32,
}

impl Default for GotwayUnpacker {
    fn default() -> Self {
        GotwayUnpacker {
            buffer: Vec::new(),
            state: State::Unknown,
            old_c: -1,
            error_resets: 0,
            bytes_discarded: 0,
        }
    }
}

impl GotwayUnpacker {
    pub fn stats(&self) -> UnpackerStats {
        UnpackerStats {
            error_resets: self.error_resets,
            bytes_discarded: self.bytes_discarded,
        }
    }

    /// Error counters persist across `reset()`, cleared here (Kotlin parity).
    pub fn reset_stats(&mut self) {
        self.error_resets = 0;
        self.bytes_discarded = 0;
    }

    pub fn reset(&mut self) {
        self.buffer.clear();
        self.state = State::Unknown;
        self.old_c = -1;
    }

    pub fn get_buffer(&self) -> Vec<u8> {
        self.buffer.clone()
    }

    /// Add a byte; returns true when a complete valid frame is ready.
    pub fn add_char(&mut self, c: i32) -> bool {
        let byte = (c & 0xFF) as u8;

        match self.state {
            State::Collecting => {
                self.buffer.push(byte);
                let size = self.buffer.len();

                // Footer bytes must be 5A 5A 5A 5A
                if size > 20 && size <= 24 && byte != 0x5A {
                    self.error_resets += 1;
                    self.bytes_discarded += size as i32;
                    self.state = State::Unknown;
                    return false;
                }

                if size == 24 {
                    self.state = State::Done;
                    return true;
                }

                // Garbage pattern: 55 AA 5A 55 AA — restart from the inner header
                if size == 5
                    && self.buffer[0] == 0x55
                    && self.buffer[1] == 0xAA
                    && self.buffer[2] == 0x5A
                    && self.buffer[3] == 0x55
                    && self.buffer[4] == 0xAA
                {
                    self.buffer.clear();
                    self.buffer.push(0x55);
                    self.buffer.push(0xAA);
                }

                // Garbage pattern: 55 AA 5A 5A 55 AA
                if size == 6
                    && self.buffer[0] == 0x55
                    && self.buffer[1] == 0xAA
                    && self.buffer[2] == 0x5A
                    && self.buffer[3] == 0x5A
                    && self.buffer[4] == 0x55
                    && self.buffer[5] == 0xAA
                {
                    self.buffer.clear();
                    self.buffer.push(0x55);
                    self.buffer.push(0xAA);
                }
            }
            State::Unknown | State::Done => {
                // Looking for the 55 AA header
                if byte == 0xAA && self.old_c == 0x55 {
                    self.buffer.clear();
                    self.buffer.push(0x55);
                    self.buffer.push(0xAA);
                    self.state = State::Collecting;
                }
                self.old_c = byte as i32;
            }
        }

        false
    }
}

/// Kani proof harness (run with `cargo kani --harness gotway_unpacker_never_panics`).
#[cfg(kani)]
mod verification {
    use super::*;

    /// The Gotway unpacker never panics on an arbitrary radio byte stream.
    /// 28 bytes covers a full 24-byte frame plus resync bytes, exercising
    /// both garbage-pattern recovery paths.
    #[kani::proof]
    #[kani::unwind(32)]
    fn gotway_unpacker_never_panics() {
        let data: [u8; 28] = kani::any();
        let mut unpacker = GotwayUnpacker::default();
        for &byte in &data {
            if unpacker.add_char(byte as i32) {
                let _ = unpacker.get_buffer();
                unpacker.reset();
            }
        }
    }
}
